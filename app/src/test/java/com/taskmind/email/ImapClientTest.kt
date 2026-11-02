package com.taskmind.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * The client against a scripted server on localhost: real sockets, real
 * literals, so the parsing is tested as it runs, not as it reads.
 */
class ImapClientTest {

    private val mail = "From: Rahul <rahul@acme.com>\r\nTo: me@gmail.com\r\nSubject: PO\r\n\r\nCan you sign the PO today?\r\n"

    /** Answers each command by its verb; records what the client sent. */
    private fun server(password: String = "secret", sent: MutableList<String>): Pair<ServerSocket, Thread> {
        val socket = ServerSocket(0)
        val t = thread(isDaemon = true) {
            socket.accept().use { conn ->
                val reader = BufferedReader(InputStreamReader(conn.getInputStream(), Charsets.ISO_8859_1))
                val out = conn.getOutputStream()
                fun send(text: String) { out.write(text.toByteArray(Charsets.ISO_8859_1)); out.flush() }
                send("* OK [CAPABILITY IMAP4rev1] ready\r\n")
                while (true) {
                    val line = reader.readLine() ?: break
                    sent += line
                    val tag = line.substringBefore(' ')
                    val command = line.substringAfter(' ')
                    when {
                        command.startsWith("LOGIN") ->
                            if (command.contains("\"$password\"")) send("$tag OK logged in\r\n")
                            else send("$tag NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)\r\n")
                        command.startsWith("LIST") -> {
                            send("* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n")
                            send("* LIST (\\HasNoChildren \\Sent) \"/\" \"[Gmail]/Sent Mail\"\r\n")
                            send("* LIST (\\HasNoChildren) \"/\" {8}\r\nTaskMind\r\n")
                            send("$tag OK done\r\n")
                        }
                        command.startsWith("EXAMINE") -> {
                            send("* 3 EXISTS\r\n* OK [UIDVALIDITY 777] ok\r\n* OK [UIDNEXT 8] ok\r\n")
                            send("$tag OK [READ-ONLY] examined\r\n")
                        }
                        command.startsWith("UID SEARCH UID") -> send("* SEARCH 7\r\n$tag OK\r\n")
                        command.startsWith("UID SEARCH") -> send("* SEARCH 5 7\r\n$tag OK\r\n")
                        command.startsWith("UID FETCH") -> {
                            val bytes = mail.toByteArray(Charsets.ISO_8859_1)
                            send("* 2 FETCH (UID 5 INTERNALDATE \" 2-Nov-2026 10:15:30 +0530\" BODY[]<0> {${bytes.size}}\r\n")
                            out.write(bytes)
                            send(")\r\n")
                            send("* 3 FETCH (FLAGS (\\Seen) UID 7 BODY[]<0> \"\")\r\n")
                            send("$tag OK fetched\r\n")
                        }
                        command.startsWith("LOGOUT") -> {
                            send("* BYE\r\n$tag OK bye\r\n")
                            break
                        }
                        else -> send("$tag BAD unknown\r\n")
                    }
                }
            }
        }
        return socket to t
    }

    @Test
    fun `reads folders and new mail without changing anything`() {
        val sent = mutableListOf<String>()
        val (socket, t) = server(sent = sent)
        ImapClient("127.0.0.1", socket.localPort, tls = false, timeoutMillis = 5_000).use { client ->
            client.connect()
            client.login("me@gmail.com", "secret")
            val folders = client.list()
            assertEquals(listOf("INBOX", "[Gmail]/Sent Mail", "TaskMind"), folders.map { it.name })
            assertEquals("[Gmail]/Sent Mail", ImapClient.sentFolder(folders)?.name)
            assertEquals("TaskMind", ImapClient.labelFolder(folders)?.name)

            val box = client.examine("INBOX")
            assertEquals(777L, box.uidValidity)
            assertEquals(8L, box.uidNext)
            assertEquals(listOf(5L, 7L), client.uidsSince(LocalDate.of(2026, 11, 1)))
            assertEquals(emptyList<Long>(), client.uidsAfter(7))

            val fetched = client.fetch(listOf(5, 7), 65536)
            assertEquals(listOf(5L, 7L), fetched.map { it.uid })
            assertEquals(mail, String(fetched[0].raw, Charsets.ISO_8859_1))
            assertEquals(1793594730000L, fetched[0].internalDate)
            assertEquals(0, fetched[1].raw.size)
            client.logout()
        }
        t.join(2_000)
        socket.close()
        // Read-only: folders are EXAMINEd and bodies PEEKed, never SELECT or STORE.
        assertTrue(sent.any { it.contains("EXAMINE \"INBOX\"") })
        assertTrue(sent.any { it.contains("BODY.PEEK[]<0.65536>") })
        assertTrue(sent.none { it.contains(" SELECT ") || it.contains(" STORE ") })
        assertTrue(sent.any { it.contains("SINCE 1-Nov-2026") })
    }

    @Test
    fun `a refused password says so in the server's words`() {
        val sent = mutableListOf<String>()
        val (socket, t) = server(password = "other", sent = sent)
        ImapClient("127.0.0.1", socket.localPort, tls = false, timeoutMillis = 5_000).use { client ->
            client.connect()
            try {
                client.login("me@gmail.com", "secret")
                fail("expected the login to be refused")
            } catch (e: ImapClient.ImapException) {
                assertTrue(e.auth)
                assertEquals("Invalid credentials (Failure)", e.message)
            }
        }
        socket.close()
        t.join(1_000)
    }

    @Test
    fun `quoting escapes what IMAP needs escaped`() {
        assertEquals("\"a\\\"b\\\\c\"", ImapClient.quote("a\"b\\c"))
        assertEquals("a\"b\\c", ImapClient.unquote("a\\\"b\\\\c"))
    }
}
