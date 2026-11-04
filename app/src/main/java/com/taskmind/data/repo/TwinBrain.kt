package com.taskmind.data.repo

import com.taskmind.ai.AiResult
import com.taskmind.ai.TraceContext
import com.taskmind.ai.errorText
import com.taskmind.contacts.ContactLookup
import com.taskmind.core.ChatContext
import com.taskmind.core.DateResolver
import com.taskmind.core.DayPlanner
import com.taskmind.core.EmailText
import com.taskmind.core.Importance
import com.taskmind.core.Nudge
import com.taskmind.core.Recall
import com.taskmind.core.Rules
import com.taskmind.core.SourceType
import com.taskmind.core.Standing
import com.taskmind.core.TaskStatus
import com.taskmind.core.TodayPlan
import com.taskmind.core.Twin
import com.taskmind.core.WaitingOn
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import com.taskmind.reminders.MeetingNudges
import com.taskmind.tagging.AutoTagger
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the twin knows and how it answers: builds the context (tasks,
 * calendar, matters, and - for a question - a few recalled messages), asks
 * the model, and plans on the phone when the model is not there.
 *
 * Applying anything is [TwinActions]' job; this only proposes.
 */
class TwinBrain(private val container: AppContainer) {

    enum class Plan { DAY, WEEK }

    data class Built(
        val ctx: Twin.Context,
        val sent: List<Twin.Task>,
        val entities: Map<String, TaskEntity>,
        val keyPeople: Set<String>,
    )

    sealed interface Answer {
        data class Ok(val reply: Twin.Reply, val usedModel: Boolean, val note: String? = null) : Answer
        data class Failed(val message: String) : Answer
    }

    /** One nudge, drafted and ready to send. */
    data class Draft(
        val taskId: String,
        val person: String,
        val text: String,
        val phone: String?,
        val email: String?,
        val subject: String?,
        val channel: String?,
        val usedModel: Boolean,
    )

    /** The line at the top of the twin's screen. */
    data class Overview(
        val meetings: Int,
        val today: Int,
        val todayDone: Int,
        val inbox: Int,
        val owed: Int,
        val chase: List<Nudge.Candidate>,
    )

    // ------------------------------------------------------------- context

    suspend fun build(request: String?, week: Boolean): Built {
        val now = System.currentTimeMillis()
        val settings = container.settingsRepository.current()
        val all = topLevel()
        val projects = container.database.projectDao().all().associate { it.id to it.name }
        val keyPeople = keyPeople(all, settings.aboutMe)
        val tasks = all.map { twinTask(it, projects, keyPeople, now) }
        val sent = Twin.select(tasks, now)

        val meetings = runCatching {
            MeetingNudges.today(container.context, now).map { e ->
                Twin.Meeting(e.begin, e.end, e.title, e.attendees.filter { !it.contains('@') }.distinct().take(6))
            }
        }.getOrDefault(emptyList())
        val upcoming = if (week) {
            runCatching {
                MeetingNudges.between(container.context, DateResolver.nextMidnight(now), now + 7 * DAY)
                    .map { e -> Twin.Meeting(e.begin, e.end, e.title) }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val open = all.filter { it.status == TaskStatus.ACTIVE && !it.inbox }
        val matters = container.database.projectDao().all().mapNotNull { p ->
            val mine = open.filter { it.projectId == p.id }
            if (mine.isEmpty()) null else Twin.MatterLine(p.name, Standing.matter(mine.map(TaskRanking::standing), now))
        }
        val memory = if (request != null && container.twinStore.current().recall) {
            runCatching { recall(request, now) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return Built(
            Twin.Context(
                now = now,
                tasks = sent,
                meetings = meetings,
                upcoming = upcoming,
                matters = matters,
                memory = memory,
                areas = settings.areas,
                aboutMe = settings.aboutMe,
                userNames = settings.ownNames,
            ),
            sent,
            all.associateBy { it.id },
            keyPeople,
        )
    }

    private suspend fun topLevel(): List<TaskEntity> =
        container.taskRepository.observeTasks().first().filter {
            it.parentTaskId == null && (it.status == TaskStatus.ACTIVE || it.status == TaskStatus.COMPLETED)
        }

    private fun keyPeople(all: List<TaskEntity>, aboutMe: String): Set<String> =
        TaskRanking.keyPeople(
            all.filter { it.status == TaskStatus.ACTIVE },
            aboutMe,
            Rules.keyPeople(container.learningStore.rules.value),
        )

    private fun twinTask(t: TaskEntity, projects: Map<String, String>, keyPeople: Set<String>, now: Long): Twin.Task {
        val parsed = AutoTagger.parseLabel(t.sourceType, t.sourceLabel)
        val from = when {
            t.sourceType == SourceType.CALL -> parsed.person?.let { "Call with $it" }
            parsed.person != null -> buildString {
                append(parsed.person)
                parsed.group?.let { append(" in ").append(it) }
                parsed.app?.let { append(" (").append(it).append(')') }
            }
            else -> null
        }
        val state = when {
            t.status == TaskStatus.COMPLETED -> Twin.State.DONE
            t.inbox -> Twin.State.INBOX
            else -> Twin.State.OPEN
        }
        return Twin.Task(
            id = t.id,
            title = t.title,
            state = state,
            dueAt = t.dueAt,
            plannedFor = t.plannedFor,
            area = t.area,
            matter = t.projectId?.let(projects::get),
            from = from,
            evidence = t.evidence,
            waiting = WaitingOn.matches(t.title, t.tags),
            priority = t.priority,
            createdAt = t.createdAt,
            completedAt = t.completedAt,
            points = if (state == Twin.State.OPEN) Importance.score(TaskRanking.input(t), now, keyPeople).points else 0,
        )
    }

    /** Snippets of the user's own messages and mail that the question is about. */
    private suspend fun recall(question: String, now: Long): List<Twin.Memory> {
        val captures = container.database.rawCaptureDao().recentWithText(now - RECALL_DAYS * DAY, RECALL_SCAN)
        val items = captures.mapNotNull { c ->
            val text = c.rawText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when (c.sourceType) {
                SourceType.EMAIL -> {
                    val meta = EmailText.parseContext(c.contextLabel)
                    val subject = meta.subject?.let { ", \"$it\"" }.orEmpty()
                    Recall.Item(c.occurredAt, (if (meta.sent) "You to ${meta.sender}" else meta.sender) + " (email$subject)", EmailText.bodyOf(text))
                }
                SourceType.NOTIFICATION -> {
                    val chat = ChatContext.parseLabel(c.contextLabel)
                    Recall.Item(c.occurredAt, chat.sender + (chat.group?.let { " in $it" } ?: ""), text)
                }
                SourceType.CALL, SourceType.CLIPBOARD -> Recall.Item(c.occurredAt, c.sourceLabel ?: "A call", text)
                else -> null
            }
        }
        return Recall.search(question, items, now).map { Twin.Memory(it.at, it.from, it.text) }
    }

    // ---------------------------------------------------------------- asking

    /** Why the model cannot be asked right now, or null when it can. */
    private suspend fun unavailable(): String? {
        val settings = container.settingsRepository.current()
        if (!settings.cloudConsent) return "Cloud processing is off"
        if (!container.secretStore.hasLlmKey()) return "No AI key is set"
        return null
    }

    suspend fun ask(request: String, plan: Plan? = null): Answer {
        val built = build(request, week = plan == Plan.WEEK)
        val why = unavailable()
        if (why != null) {
            return when (plan) {
                Plan.DAY -> Answer.Ok(localDay(built), usedModel = false, note = "$why, so this plan was made on the phone.")
                Plan.WEEK -> Answer.Ok(localWeek(built), usedModel = false, note = "$why, so this plan was made on the phone.")
                null -> Answer.Failed("$why, so questions cannot be answered right now. Plan my day and Plan my week still work.")
            }
        }
        container.settingsRepository.recordLlmCall(DateResolver.dayKey(built.ctx.now), null)
        val result = container.llmClient.complete(
            config = container.llmConfig(),
            systemPrompt = Twin.SYSTEM,
            userPrompt = Twin.userPrompt(request, built.ctx),
            maxTokens = 1500,
            trace = TraceContext(KIND_TWIN),
        )
        val content = (result as? AiResult.Ok)?.value?.content
        val reply = content?.let { Twin.parse(it, built.sent, built.ctx.now, built.ctx.areas) }
        if (reply != null) return Answer.Ok(reply, usedModel = true, note = memoryNote(built))
        val failure = if (content == null) (result.errorText ?: "The AI did not answer") else "The AI's answer could not be read"
        return when (plan) {
            Plan.DAY -> Answer.Ok(localDay(built), usedModel = false, note = "$failure, so this plan was made on the phone.")
            Plan.WEEK -> Answer.Ok(localWeek(built), usedModel = false, note = "$failure, so this plan was made on the phone.")
            null -> Answer.Failed("$failure. Try asking again.")
        }
    }

    private fun memoryNote(built: Built): String? {
        val n = built.ctx.memory.size
        return if (n == 0) null else "Also looked at $n of your messages for this."
    }

    // ------------------------------------------------------ planning locally

    private fun planned(built: Built): List<TodayPlan.Task> =
        built.entities.values.filter { it.status == TaskStatus.ACTIVE }
            .map { TodayPlan.Task(it.id, it.plannedFor, it.inbox, TaskRanking.input(it)) }

    fun localDay(built: Built): Twin.Reply {
        val now = built.ctx.now
        val today = DateResolver.dayKey(now)
        val picks = DayPlanner.planDay(planned(built), built.ctx.meetings, now, built.keyPeople)
        if (picks.isEmpty()) return Twin.Reply("Nothing open to plan for today. A clear day - or add something.", emptyList(), emptyList())
        val lines = picks.mapNotNull { p ->
            val t = built.entities[p.taskId] ?: return@mapNotNull null
            (p.at?.let { hm(it) + "  " } ?: "") + t.title + (p.why?.let { "  - $it" } ?: "")
        }
        val actions = picks.filter { p ->
            val t = built.entities[p.taskId]
            t != null && (t.plannedFor != today || p.at != null)
        }.map { Twin.Action.Plan(it.taskId, today, it.at) }
        return Twin.Reply("Today, between your meetings:\n" + lines.joinToString("\n"), picks.map { it.taskId }, actions)
    }

    fun localWeek(built: Built): Twin.Reply {
        val now = built.ctx.now
        val byDay = built.ctx.upcoming.mapNotNull { it.start }.groupingBy { DateResolver.dayKey(it) }.eachCount()
        val plan = DayPlanner.planWeek(planned(built), now, built.keyPeople, meetingsByDay = byDay)
        if (plan.isEmpty()) return Twin.Reply("Nothing needs a day this week that is not already planned.", emptyList(), emptyList())
        val lines = plan.groupBy({ it.second }, { it.first }).toSortedMap().map { (day, ids) ->
            dayName(day, now) + ": " + ids.mapNotNull { built.entities[it]?.title }.joinToString("; ")
        }
        return Twin.Reply(
            "A week, a few things a day:\n" + lines.joinToString("\n"),
            plan.map { it.first },
            plan.map { (id, day) -> Twin.Action.Plan(id, day, null) },
        )
    }

    // ------------------------------------------------------------- nudges

    suspend fun overview(): Overview {
        val now = System.currentTimeMillis()
        val all = topLevel()
        val projects = container.database.projectDao().all().associate { it.id to it.name }
        val settings = container.settingsRepository.current()
        val keyPeople = keyPeople(all, settings.aboutMe)
        val tasks = all.map { twinTask(it, projects, keyPeople, now) }
        val today = DateResolver.dayKey(now)
        val meetings = runCatching { MeetingNudges.today(container.context, now).size }.getOrDefault(0)
        return Overview(
            meetings = meetings,
            today = all.count { it.plannedFor == today && it.status == TaskStatus.ACTIVE },
            todayDone = all.count { it.plannedFor == today && it.status == TaskStatus.COMPLETED },
            inbox = all.count { it.inbox && it.status == TaskStatus.ACTIVE },
            owed = tasks.count { it.state == Twin.State.OPEN && it.waiting },
            chase = Nudge.candidates(tasks, container.twinStore.current().nudgedAt, now),
        )
    }

    /** A nudge for one task: drafted by the model when it can be, else from the template. */
    suspend fun draft(taskId: String, proposed: String? = null): Draft? {
        val now = System.currentTimeMillis()
        val task = container.taskRepository.byId(taskId) ?: return null
        val projects = container.database.projectDao().all().associate { it.id to it.name }
        val t = twinTask(task, projects, emptySet(), now)
        val person = Nudge.person(t.from) ?: "there"
        val channel = t.from?.substringAfter(" (", "")?.substringBefore(')')?.takeIf { it.isNotBlank() }
        val candidate = Nudge.Candidate(t.id, person, t.title, t.evidence, t.createdAt, t.dueAt, channel)

        val capture = task.rawCaptureId?.let { container.database.rawCaptureDao().byId(it) }
        val meta = if (task.sourceType == SourceType.EMAIL) EmailText.parseContext(capture?.contextLabel) else null
        val phone = if (meta == null && person != "there") {
            runCatching { ContactLookup.numberFor(container.context, person) }.getOrNull()
        } else {
            null
        }

        var usedModel = false
        val text = proposed ?: run {
            val model = if (unavailable() == null) {
                container.settingsRepository.recordLlmCall(DateResolver.dayKey(now), null)
                val result = container.llmClient.complete(
                    config = container.llmConfig(),
                    systemPrompt = Nudge.SYSTEM,
                    userPrompt = Nudge.userPrompt(candidate, now, container.settingsRepository.current().aboutMe),
                    maxTokens = 300,
                    trace = TraceContext(KIND_NUDGE, sourceLabel = person),
                )
                (result as? AiResult.Ok)?.value?.content?.let(Nudge::parse)
            } else {
                null
            }
            usedModel = model != null
            model ?: Nudge.draft(candidate, now)
        }
        return Draft(
            taskId = task.id,
            person = person,
            text = text,
            phone = phone,
            email = meta?.address,
            subject = meta?.subject?.let { "Re: $it" },
            channel = channel,
            usedModel = usedModel || proposed != null,
        )
    }

    private fun hm(at: Long): String = Instant.ofEpochMilli(at).atZone(DateResolver.IST).format(HM)

    private fun dayName(key: String, now: Long): String = when (key) {
        DateResolver.dayKey(now) -> "Today"
        DateResolver.dayKey(now + DAY) -> "Tomorrow"
        else -> runCatching { java.time.LocalDate.parse(key).format(DAY_NAME) }.getOrDefault(key)
    }

    companion object {
        /** How these calls are labelled in Model calls. */
        const val KIND_TWIN = "twin"
        const val KIND_NUDGE = "nudge"

        private const val DAY = 24L * 60 * 60 * 1000
        private const val RECALL_DAYS = 60L
        private const val RECALL_SCAN = 1500
        private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
        private val DAY_NAME = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }
}
