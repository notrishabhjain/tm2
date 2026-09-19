package com.taskmind.relate

import com.taskmind.relate.TaskRelations.Relatable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relating rules, checked against the traffic this app actually sees:
 * Hindi, English and both mixed into one sentence.
 */
class TaskRelationsTest {

    private val day = 24L * 60 * 60 * 1000
    private val monday = 1_726_000_000_000L

    private fun task(
        id: String,
        title: String,
        due: Long? = null,
        person: String? = null,
        group: String? = null,
        topics: List<String> = emptyList(),
        project: String? = null,
    ) = Relatable(id, title, due, project, person, group, topics)

    // -- wording -------------------------------------------------------------

    @Test
    fun `identical titles overlap completely`() {
        assertEquals(1.0, TaskRelations.overlap("Send the invoice", "Send the invoice"), 0.001)
    }

    @Test
    fun `stop words do not create overlap`() {
        // Only "invoice" is meaningful in either, and only one of them has it.
        assertEquals(0.0, TaskRelations.overlap("please can you", "kindly do this"), 0.001)
    }

    @Test
    fun `hinglish and english reach the same word`() {
        val shared = TaskRelations.overlap("Send invoice to Sharma", "Invoice bhejna hai Sharma ko")
        assertTrue("expected some overlap, got $shared", shared > 0.2)
    }

    @Test
    fun `plurals collapse onto the singular`() {
        assertEquals(setOf("payment"), TaskRelations.words("payments"))
    }

    @Test
    fun `double s is left alone`() {
        assertTrue("address" in TaskRelations.words("address"))
    }

    @Test
    fun `devanagari survives tokenising`() {
        val words = TaskRelations.words("पैसे भेजना")
        assertTrue("got $words", words.contains("पैसे"))
        assertTrue("got $words", words.contains("भेजना"))
    }

    @Test
    fun `empty titles do not count as identical`() {
        assertEquals(0.0, TaskRelations.overlap("ok", "haan"), 0.001)
    }

    // -- scoring -------------------------------------------------------------

    @Test
    fun `the same person is enough on its own`() {
        val a = task("a", "Send invoice", person = "Sharma Ji")
        val b = task("b", "Book the hall", person = "Sharma Ji")
        assertTrue(TaskRelations.score(a, b) >= TaskRelations.RELATED)
    }

    @Test
    fun `different people with nothing in common are not related`() {
        val a = task("a", "Send invoice", person = "Sharma Ji")
        val b = task("b", "Book the hall", person = "Amit")
        assertTrue(TaskRelations.score(a, b) < TaskRelations.RELATED)
    }

    @Test
    fun `a shared group and a shared topic together are enough`() {
        val a = task("a", "Pay the vendor", group = "Project Alpha", topics = listOf("payment"))
        val b = task("b", "Clear advance", group = "Project Alpha", topics = listOf("payment"))
        assertTrue(TaskRelations.score(a, b) >= TaskRelations.RELATED)
    }

    @Test
    fun `an unknown person does not group everyone together`() {
        val a = task("a", "Send invoice", person = null)
        val b = task("b", "Book the hall", person = null)
        assertTrue(TaskRelations.score(a, b) < TaskRelations.RELATED)
    }

    @Test
    fun `a task is not related to itself`() {
        val a = task("a", "Send invoice", person = "Sharma Ji")
        assertEquals(0.0, TaskRelations.score(a, a), 0.001)
    }

    @Test
    fun `score never exceeds one`() {
        val a = task("a", "Pay invoice", monday, "Sharma Ji", "Alpha", listOf("payment"), "p1")
        val b = task("b", "Pay invoice", monday, "Sharma Ji", "Alpha", listOf("payment"), "p1")
        assertTrue(TaskRelations.score(a, b) <= 1.0)
    }

    // -- bundling ------------------------------------------------------------

    @Test
    fun `tasks from one person become one bundle named after them`() {
        val bundles = TaskRelations.bundles(
            listOf(
                task("a", "Send invoice", person = "Sharma Ji"),
                task("b", "Book the hall", person = "Sharma Ji"),
                task("c", "Buy milk"),
            ),
        )
        assertEquals(1, bundles.size)
        assertEquals("Sharma Ji", bundles[0].label)
        assertEquals(listOf("a", "b"), bundles[0].ids)
    }

    @Test
    fun `a lone task is not a bundle`() {
        val bundles = TaskRelations.bundles(listOf(task("a", "Buy milk"), task("b", "Call plumber")))
        assertTrue(bundles.isEmpty())
    }

    @Test
    fun `an empty list bundles nothing`() {
        assertTrue(TaskRelations.bundles(emptyList()).isEmpty())
    }

    @Test
    fun `bundles are ordered largest first`() {
        val bundles = TaskRelations.bundles(
            listOf(
                task("a", "One", person = "Amit"),
                task("b", "Two", person = "Amit"),
                task("c", "Three", person = "Sharma Ji"),
                task("d", "Four", person = "Sharma Ji"),
                task("e", "Five", person = "Sharma Ji"),
            ),
        )
        assertEquals(2, bundles.size)
        assertEquals(3, bundles[0].size)
        assertEquals("Sharma Ji", bundles[0].label)
    }

    @Test
    fun `a mixed bundle falls back to the topic it shares`() {
        val bundles = TaskRelations.bundles(
            listOf(
                task("a", "Clear the advance", monday, "Amit", topics = listOf("payment"), project = "p1"),
                task("b", "Settle the bill", monday, "Ravi", topics = listOf("payment"), project = "p1"),
            ),
        )
        assertEquals(1, bundles.size)
        assertEquals("Payment", bundles[0].label)
        assertEquals("Same topic", bundles[0].reason)
    }

    @Test
    fun `every bundle explains itself`() {
        val bundles = TaskRelations.bundles(
            listOf(
                task("a", "Send invoice", person = "Sharma Ji"),
                task("b", "Book the hall", person = "Sharma Ji"),
            ),
        )
        assertTrue(bundles.all { it.reason.isNotBlank() && it.label.isNotBlank() })
    }

    @Test
    fun `bundleFor finds the group a task sits in`() {
        val tasks = listOf(
            task("a", "Send invoice", person = "Sharma Ji"),
            task("b", "Book the hall", person = "Sharma Ji"),
            task("c", "Buy milk"),
        )
        assertNotNull(TaskRelations.bundleFor("a", tasks))
        assertNull(TaskRelations.bundleFor("c", tasks))
    }

    @Test
    fun `linking is transitive`() {
        // a-b share a person, b-c share a group; a and c share nothing.
        val bundles = TaskRelations.bundles(
            listOf(
                task("a", "Alpha one", person = "Amit"),
                task("b", "Beta two", person = "Amit", group = "Site", topics = listOf("payment")),
                task("c", "Gamma three", group = "Site", topics = listOf("payment")),
            ),
        )
        assertEquals(1, bundles.size)
        assertEquals(3, bundles[0].size)
    }

    // -- duplicates ----------------------------------------------------------

    @Test
    fun `the same task captured twice is flagged`() {
        val dupes = TaskRelations.duplicates(
            listOf(
                task("a", "Send the invoice to Sharma", monday),
                task("b", "Send invoice to Sharma", monday),
            ),
        )
        assertEquals(1, dupes.size)
        assertEquals("a", dupes[0].keepId)
        assertEquals("b", dupes[0].otherId)
    }

    @Test
    fun `similar wording on different days is two tasks`() {
        val dupes = TaskRelations.duplicates(
            listOf(
                task("a", "Send the invoice to Sharma", monday),
                task("b", "Send invoice to Sharma", monday + 3 * day),
            ),
        )
        assertTrue(dupes.isEmpty())
    }

    @Test
    fun `one missing date does not stop a duplicate being found`() {
        val dupes = TaskRelations.duplicates(
            listOf(
                task("a", "Send the invoice to Sharma", monday),
                task("b", "Send invoice to Sharma", null),
            ),
        )
        assertEquals(1, dupes.size)
    }

    @Test
    fun `merely related tasks are not duplicates`() {
        val dupes = TaskRelations.duplicates(
            listOf(
                task("a", "Send invoice", person = "Sharma Ji"),
                task("b", "Book the hall", person = "Sharma Ji"),
            ),
        )
        assertTrue(dupes.isEmpty())
    }

    @Test
    fun `duplicate detection does not pair a task with itself`() {
        assertTrue(TaskRelations.duplicates(listOf(task("a", "Send invoice"))).isEmpty())
    }

    @Test
    fun `a bundle key is stable whatever order the tasks arrive in`() {
        val one = TaskRelations.bundles(
            listOf(task("a", "One", person = "Amit"), task("b", "Two", person = "Amit")),
        )
        val other = TaskRelations.bundles(
            listOf(task("b", "Two", person = "Amit"), task("a", "One", person = "Amit")),
        )
        assertEquals(one[0].key, other[0].key)
    }

    @Test
    fun `a blank person is not a shared person`() {
        val a = task("a", "Alpha", person = "")
        val b = task("b", "Beta", person = "")
        assertFalse(TaskRelations.score(a, b) >= TaskRelations.RELATED)
    }
}
