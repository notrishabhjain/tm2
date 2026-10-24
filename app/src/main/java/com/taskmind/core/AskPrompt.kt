package com.taskmind.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * "What do I owe Rahul?" - a question answered from the task list.
 *
 * The model sees only what the web mirror already carries: each task's title,
 * due date, status, who it came from and the short evidence quote. Never the
 * full message or transcript. Tasks are numbered rather than sent with their
 * ids, so the answer can point back at them cheaply and the ids never leave
 * the phone.
 *
 * Pure: building the prompt and reading the answer are tested without a
 * provider.
 */
object AskPrompt {

    data class Task(
        val id: String,
        val title: String,
        val open: Boolean,
        val dueAt: Long?,
        val from: String?,
        val evidence: String?,
        val createdAt: Long,
    )

    data class Answer(val text: String, val taskIds: List<String>)

    /** Enough for a busy month; past this the oldest finished tasks drop off. */
    const val MAX_TASKS = 200
    private const val MAX_EVIDENCE = 160

    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ENGLISH)

    val SYSTEM = """
        You answer questions about the user's to-do list. The list is below, one task per line,
        each with a number in square brackets. Answer ONLY from the list: if it does not say,
        say you cannot tell from the tasks. Never invent a task, a date or a person.

        Be short and concrete: a sentence or a few bullet lines, naming the tasks. The user
        writes in English, Hindi or Hinglish; answer in the language of the question.

        Reply with ONE JSON object and nothing else:
        {"answer": "<your answer, plain text, may contain line breaks>", "tasks": [<numbers of the tasks your answer is about>]}
    """.trimIndent()

    /**
     * The tasks worth sending: every open one, then the most recently created
     * finished ones, up to [MAX_TASKS].
     */
    fun select(tasks: List<Task>): List<Task> {
        val open = tasks.filter { it.open }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
        val done = tasks.filter { !it.open }.sortedByDescending { it.createdAt }
        return (open + done).take(MAX_TASKS)
    }

    fun userPrompt(question: String, tasks: List<Task>, now: Long): String = buildString {
        append("Now: ").append(format(now)).append(" (IST)\n\n")
        append("TASKS:\n")
        tasks.forEachIndexed { i, t ->
            append('[').append(i + 1).append("] ")
            append(if (t.open) "OPEN" else "DONE").append(" | ")
            append(t.title.replace('\n', ' '))
            t.dueAt?.let { append(" | due ").append(format(it)) }
            t.from?.takeIf { it.isNotBlank() }?.let { append(" | from ").append(it) }
            t.evidence?.takeIf { it.isNotBlank() }?.let {
                append(" | said: \"").append(it.replace('\n', ' ').take(MAX_EVIDENCE)).append('"')
            }
            append('\n')
        }
        append("\nQUESTION: ").append(question.trim())
    }

    /** Reads the model's reply; [sent] is the list in the order it was numbered. */
    fun parse(raw: String, sent: List<Task>): Answer? {
        val obj = LlmJson.parseObject(raw) ?: return null
        val text = (obj["answer"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (text.isEmpty()) return null
        val ids = runCatching {
            obj["tasks"]?.jsonArray?.mapNotNull { el ->
                el.jsonPrimitive.content.trim().trim('[', ']').toIntOrNull()
            }
        }.getOrNull().orEmpty()
            .filter { it in 1..sent.size }
            .distinct()
            .map { sent[it - 1].id }
        return Answer(text, ids)
    }

    private fun format(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(DateResolver.IST).format(DATE)
}
