package com.taskmind.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tasks are somebody else's move. */
class WaitingOnTest {

    @Test fun `the prompt's own waiting-on example is recognised`() {
        assertTrue(WaitingOn.matches("Check Abhishek confirmed the FARPS request"))
    }

    @Test fun `the call pipeline's tag is enough on its own`() {
        assertTrue(WaitingOn.matches("Signed copy from Priya", listOf("waiting-on")))
    }

    @Test fun `follow-ups, chases and reminders to others are waiting on`() {
        listOf(
            "Follow up with Rahul on the quote",
            "Follow-up on the vendor invoice",
            "Chase Priya for the signed agreement",
            "Check if the vendor sent the PO",
            "Check whether legal approved the contract",
            "Check the vendor has sent the PO",
            "Make sure Rahul sends the deck",
            "Remind Sharma ji to send the bill",
            "Wait for the client's approval",
        ).forEach { assertTrue(it, WaitingOn.matches(it)) }
    }

    @Test fun `things the user does themselves are not`() {
        listOf(
            "Send the signed file to Rahul",
            "Pay 25,000 to Sharma Ji",
            "Check the dashboard numbers before the review",
            "Call Abhishek about the FARPS request",
            "Confirm the venue for Friday",
            "Reminder: submit timesheet",
        ).forEach { assertFalse(it, WaitingOn.matches(it)) }
    }
}
