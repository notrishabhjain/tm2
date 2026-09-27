package com.taskmind.core

import com.taskmind.core.LlmJson.bool
import com.taskmind.core.LlmJson.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompts are the app's precision story, and they are prose - so nothing
 * else in the build can tell you when an edit has quietly undone one.
 *
 * These tests do not judge whether the wording is any good; no test can. They
 * pin the two things that are load-bearing and easy to break by accident:
 *
 *  - the ORDER of the argument. The recall bias ("when unsure, say true and
 *    lower the confidence") is the most forcefully worded instruction in the
 *    file, and for a long time it sat above the question of whose task it is.
 *    A model asked to judge "Rahul, send the file" with no idea whether Rahul
 *    is the user followed the louder instruction and said yes. Whose-task-is-it
 *    has to come first, and has to be exempted from that generosity by name.
 *
 *  - the EXAMPLES. Few-shot examples teach output format as much as judgement,
 *    so one with a stray quote or a missing field teaches the model to produce
 *    exactly that. They are hand-written with escaped quotes inside escaped
 *    strings, which is precisely where a typo hides.
 */
class PromptsTest {

    private val notification = Prompts.DEFAULT_NOTIFICATION_SYSTEM
    private val verify = Prompts.DEFAULT_VERIFY_SYSTEM

    // -- the order of the argument -------------------------------------------

    @Test
    fun `whose task it is comes before the instruction to be generous`() {
        val doer = notification.indexOf("WHOSE TASK IS IT")
        val recall = notification.indexOf("LOWER THE CONFIDENCE")
        assertTrue("the doer question is missing", doer >= 0)
        assertTrue("the recall instruction is missing", recall >= 0)
        assertTrue(
            "the recall bias must not be stated before the doer question",
            doer < recall,
        )
    }

    @Test
    fun `the recall bias is explicitly exempted from the doer question`() {
        assertTrue(
            "nothing tells the model that 'when unsure say true' stops at whose task it is",
            notification.contains("DOES NOT REACH BACK") ||
                notification.contains("does not reach back"),
        )
    }

    @Test
    fun `a third party named as the doer is a hard false, not a low confidence`() {
        // Matched without the leading "is", because the prompt is hard-wrapped
        // at 90 columns and the phrase straddles a line break. Asserting on a
        // span that a re-wrap can split is a test that fails for the wrong
        // reason - which is exactly what this one did first time.
        assertTrue(notification.contains("not an uncertain task"))
        assertTrue(notification.contains("isTask=false"))
    }

    @Test
    fun `the recipient-versus-doer distinction survives`() {
        // "Rahul ko bhej do" (send it TO Rahul) is the user's task; "Rahul,
        // bhej do" (Rahul, send it) is not. A rule cannot tell these apart and
        // the model can, but only if it is told.
        assertTrue(notification.contains("\"ko\""))
        assertTrue(notification.contains("RECEIVES"))
    }

    @Test
    fun `the verify pass is told to drop somebody else's task`() {
        assertTrue(verify.contains("SOMEBODY ELSE"))
        assertTrue(verify.contains("\"ko\""))
    }

    // -- the examples --------------------------------------------------------

    /** Every line that looks like a worked example, parsed. */
    private fun examples(): List<Pair<String, kotlinx.serialization.json.JsonObject>> =
        notification.lines()
            .filter { it.trimStart().startsWith("{\"reasoning\"") }
            .map { line ->
                val parsed = LlmJson.parseObject(line)
                assertNotNull("example is not valid JSON: ${line.take(80)}", parsed)
                line to parsed!!
            }

    @Test
    fun `every worked example is valid JSON`() {
        assertTrue("the prompt has lost its examples", examples().size >= 8)
    }

    @Test
    fun `every example carries the full response shape`() {
        val required = listOf(
            "reasoning", "isTask", "evidence", "title",
            "priority", "dueDate", "notes", "confidence",
        )
        examples().forEach { (line, obj) ->
            required.forEach { key ->
                assertTrue("example missing \"$key\": ${line.take(70)}", obj.containsKey(key))
            }
        }
    }

    @Test
    fun `an example that is not a task quotes nothing`() {
        examples().forEach { (line, obj) ->
            if (obj.bool("isTask") == false) {
                assertNull("a non-task example has evidence: ${line.take(70)}", obj.str("evidence"))
                assertNull("a non-task example has a title: ${line.take(70)}", obj.str("title"))
            }
        }
    }

    @Test
    fun `an example that is a task quotes and titles`() {
        examples().forEach { (line, obj) ->
            if (obj.bool("isTask") == true) {
                assertNotNull("a task example has no evidence: ${line.take(70)}", obj.str("evidence"))
                assertNotNull("a task example has no title: ${line.take(70)}", obj.str("title"))
            }
        }
    }

    @Test
    fun `at least one example shows a third party being asked`() {
        val refusals = examples().count { (_, obj) ->
            obj.bool("isTask") == false && obj.str("reasoning")?.contains("not the user") == true
        }
        assertTrue("the case this prompt exists to stop is not demonstrated", refusals >= 1)
    }

    // -- the user-role messages ----------------------------------------------

    @Test
    fun `the names line is sent when names are known`() {
        val user = Prompts.notificationUser(
            occurredAtMillis = 1_751_875_200_000L,
            appLabel = "WhatsApp",
            senderKey = "Kashish",
            groupName = "CPC Infra",
            messageText = "please share the deck",
            userNames = listOf("Rishabh", "RJ"),
        )
        assertTrue(user.contains("You are known as: Rishabh, RJ"))
        assertTrue(user.contains("group \"CPC Infra\""))
    }

    @Test
    fun `the names line is omitted entirely when there are none`() {
        // "You are known as: " with nothing after it is worse than silence -
        // the model will try to use it.
        val user = Prompts.notificationUser(
            occurredAtMillis = 1_751_875_200_000L,
            appLabel = "WhatsApp",
            senderKey = "Kashish",
            groupName = null,
            messageText = "please share the deck",
        )
        assertTrue(user, !user.contains("known as"))
    }

    @Test
    fun `blank names do not produce an empty list`() {
        val user = Prompts.notificationUser(
            occurredAtMillis = 1_751_875_200_000L,
            appLabel = "WhatsApp",
            senderKey = "Kashish",
            groupName = null,
            messageText = "please share the deck",
            userNames = listOf("  ", ""),
        )
        assertTrue(user, !user.contains("known as"))
    }

    @Test
    fun `the verifier is told the names too`() {
        val user = Prompts.verifyUser("source text", listOf("title=Do a thing"), listOf("Rishabh"))
        assertTrue(user.contains("The user is known as: Rishabh"))
        assertTrue(user.contains("SOURCE TEXT:"))
        assertEquals(true, user.contains("0. title=Do a thing"))
    }

    @Test
    fun `the verifier omits the names line when there are none`() {
        val user = Prompts.verifyUser("source text", listOf("title=Do a thing"))
        assertTrue(user, !user.contains("known as"))
    }
}
