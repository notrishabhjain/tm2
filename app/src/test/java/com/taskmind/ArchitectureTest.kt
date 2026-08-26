package com.taskmind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 5 asks for the single-funnel rule to be enforced STRUCTURALLY, because
 * failure mode 1 - the call path writing tasks to a different table - looked
 * fine in every individual code review it survived.
 *
 * Kotlin has no package-private, and Room does not deal well with `internal`
 * DAO functions, so the enforcement is this test: it reads the source tree and
 * fails the build if a second path to the task table appears.
 */
class ArchitectureTest {

    private val mainSources: List<File> by lazy {
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun sourceRoot(): File {
        // The local pre-flight harness compiles these sources from a different
        // working directory; it points the test at the tree explicitly.
        System.getProperty("taskmind.sourceRoot")?.let { override ->
            val dir = File(override)
            if (dir.isDirectory) return dir
        }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                File(dir, "src/main/java/com/taskmind"),
                File(dir, "app/src/main/java/com/taskmind"),
            )) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "cannot locate the main source tree from ${System.getProperty("user.dir")}",
        )
    }

    private fun filesReferencing(token: String): List<String> =
        mainSources.filter { stripComments(it.readText()).contains(token) }.map { it.name }.sorted()

    /**
     * These tests scan for anti-patterns by name, and the code comments name
     * those same anti-patterns in order to warn about them. Comments are
     * stripped first so that documenting a trap does not count as falling into
     * it.
     */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")

    @Test
    fun `the source tree is actually being scanned`() {
        assertTrue("expected to find app sources, found ${mainSources.size}", mainSources.size > 20)
    }

    /**
     * Only the funnel may call the task sink. Everything else must go through
     * IntakeFunnel.submit().
     */
    @Test
    fun `only the intake funnel inserts tasks`() {
        val callers = filesReferencing("insertIfAbsent(")
        assertEquals(
            "a second path to the task table has appeared - see spec 5 and failure mode 1",
            listOf("IntakeFunnel.kt", "IntakeModels.kt", "RoomIntakePorts.kt"),
            callers,
        )
    }

    /**
     * The DAO-level ignore-insert is the last mile. It may exist in exactly two
     * places: the DAO that declares it and the sink adapter that calls it.
     */
    @Test
    fun `the task insert DAO method has exactly one caller`() {
        val callers = filesReferencing("insertIgnoringDuplicates")
        assertEquals(
            "TaskDao.insertIgnoringDuplicates must only ever be called by RoomIntakePorts",
            listOf("RoomIntakePorts.kt", "TaskDao.kt"),
            callers,
        )
    }

    /**
     * Spec 17.4 / failure mode 6: corruption recovery must never delete user
     * data, so the destructive migration escape hatch must not exist anywhere.
     */
    @Test
    fun `no destructive room migrations`() {
        val offenders = filesReferencing("fallbackToDestructiveMigration")
        assertEquals(
            "destructive migration deletes the user's tasks - see failure mode 6",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Spec 17.1 / failure mode 2: Android 15 calls the two-argument onTimeout
     * for dataSync services. Implementing only the one-argument overload means
     * the handler never fires and the app is killed on the daily budget.
     */
    @Test
    fun `foreground services implement both onTimeout overloads`() {
        val service = mainSources.firstOrNull { it.name == "WorkerService.kt" }
            ?: throw AssertionError("WorkerService.kt not found")
        val text = service.readText()
        assertTrue(
            "WorkerService must implement onTimeout(startId) - failure mode 2",
            text.contains("override fun onTimeout(startId: Int)"),
        )
        assertTrue(
            "WorkerService must implement onTimeout(startId, fgsType) - failure mode 2",
            text.contains("override fun onTimeout(startId: Int, fgsType: Int)"),
        )
    }

    /**
     * Spec 11.2 / failure mode 4: `duration >= :min` is FALSE for NULL in SQL,
     * so every call with an unknown duration was silently skipped.
     */
    @Test
    fun `numeric filters handle NULL explicitly`() {
        val offenders = mutableListOf<String>()
        val bareComparison = Regex("\\b(duration|durationSeconds)\\s*>=\\s*:")
        for (file in mainSources) {
            val text = stripComments(file.readText())
            for (line in text.lines()) {
                if (bareComparison.containsMatchIn(line) && !line.contains("IS NULL")) {
                    offenders.add("${file.name}: ${line.trim()}")
                }
            }
        }
        assertEquals(
            "a numeric filter without an explicit NULL branch - see failure mode 4",
            emptyList<String>(),
            offenders,
        )
    }
}
