package com.taskmind.email

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.emailDataStore: DataStore<Preferences> by preferencesDataStore(name = "taskmind_email")

/**
 * The mailbox TaskMind reads, and how far it has read it.
 *
 * The app password lives in [com.taskmind.data.settings.SecretStore], not
 * here. What is here is harmless to lose: the server, the address, and for
 * each folder the last message read, so a run starts where the last one ended.
 */
class EmailStore(private val context: Context) {

    data class Provider(
        val id: String,
        val label: String,
        val host: String,
        val port: Int,
        /** What to do to get a password that works. */
        val help: String,
        /** Where the app password is made, when the provider has one place for it. */
        val passwordUrl: String?,
    )

    data class State(
        val providerId: String = "gmail",
        val address: String = "",
        val host: String = "",
        val port: Int = 993,
        val connected: Boolean = false,
        /** Also read the sent folder, for the promises the user makes. */
        val readSent: Boolean = true,
        /** Skip mail the user is not in To or Cc of. */
        val onlyToMe: Boolean = true,
        val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
        /** Other addresses that are the user (aliases, a work address forwarded here). */
        val extraAddresses: String = "",
        val lastCheckAt: Long = 0L,
        val lastResult: String = "",
        val lastError: String = "",
        /** The last sign-in was refused; automatic checks wait for a new password or a manual check. */
        val authFailed: Boolean = false,
        val readTotal: Long = 0L,
        val capturedTotal: Long = 0L,
        /** Folder name to (UIDVALIDITY, last UID read). */
        val marks: Map<String, Pair<Long, Long>> = emptyMap(),
    ) {
        /** Every address that is the user. */
        val mine: Set<String>
            get() = (listOf(address) + extraAddresses.split(',', ' ', ';', '\n'))
                .map { it.trim() }
                .filter { it.contains('@') }
                .toSet()

        val provider: Provider get() = PROVIDERS.firstOrNull { it.id == providerId } ?: PROVIDERS.last()
    }

    private object K {
        val providerId = stringPreferencesKey("provider")
        val address = stringPreferencesKey("address")
        val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port")
        val connected = booleanPreferencesKey("connected")
        val readSent = booleanPreferencesKey("read_sent")
        val onlyToMe = booleanPreferencesKey("only_to_me")
        val interval = intPreferencesKey("interval_hours")
        val extra = stringPreferencesKey("extra_addresses")
        val lastCheckAt = longPreferencesKey("last_check_at")
        val lastResult = stringPreferencesKey("last_result")
        val lastError = stringPreferencesKey("last_error")
        val authFailed = booleanPreferencesKey("auth_failed")
        val readTotal = longPreferencesKey("read_total")
        val capturedTotal = longPreferencesKey("captured_total")
        val marks = stringSetPreferencesKey("marks")
    }

    val state: Flow<State> = context.emailDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            State(
                providerId = p[K.providerId] ?: "gmail",
                address = p[K.address].orEmpty(),
                host = p[K.host].orEmpty(),
                port = p[K.port] ?: 993,
                connected = p[K.connected] ?: false,
                readSent = p[K.readSent] ?: true,
                onlyToMe = p[K.onlyToMe] ?: true,
                intervalHours = p[K.interval] ?: DEFAULT_INTERVAL_HOURS,
                extraAddresses = p[K.extra].orEmpty(),
                lastCheckAt = p[K.lastCheckAt] ?: 0L,
                lastResult = p[K.lastResult].orEmpty(),
                lastError = p[K.lastError].orEmpty(),
                authFailed = p[K.authFailed] ?: false,
                readTotal = p[K.readTotal] ?: 0L,
                capturedTotal = p[K.capturedTotal] ?: 0L,
                marks = (p[K.marks] ?: emptySet()).mapNotNull(::parseMark).toMap(),
            )
        }

    suspend fun current(): State = state.first()

    /** A working sign-in: remember it and start reading. */
    suspend fun connect(providerId: String, address: String, host: String, port: Int) {
        context.emailDataStore.edit {
            val changed = it[K.address] != address.trim() || it[K.host] != host.trim()
            it[K.providerId] = providerId
            it[K.address] = address.trim()
            it[K.host] = host.trim()
            it[K.port] = port
            it[K.connected] = true
            it[K.authFailed] = false
            it[K.lastError] = ""
            // A different mailbox starts from the newest mail, not from where
            // the old one had got to.
            if (changed) it.remove(K.marks)
        }
    }

    suspend fun disconnect() {
        context.emailDataStore.edit {
            it[K.connected] = false
            it.remove(K.marks)
            it[K.lastResult] = ""
            it[K.lastError] = ""
            it[K.authFailed] = false
        }
    }

    suspend fun setReadSent(value: Boolean) = context.emailDataStore.edit { it[K.readSent] = value }
    suspend fun setOnlyToMe(value: Boolean) = context.emailDataStore.edit { it[K.onlyToMe] = value }
    suspend fun setInterval(hours: Int) = context.emailDataStore.edit { it[K.interval] = hours }
    suspend fun setExtraAddresses(value: String) = context.emailDataStore.edit { it[K.extra] = value.trim() }

    suspend fun setMark(folder: String, uidValidity: Long, lastUid: Long) {
        context.emailDataStore.edit { p ->
            val others = (p[K.marks] ?: emptySet()).filterNot { parseMark(it)?.first == folder }
            p[K.marks] = (others + formatMark(folder, uidValidity, lastUid)).toSet()
        }
    }

    suspend fun recordRun(at: Long, result: String, read: Int, captured: Int) {
        context.emailDataStore.edit {
            it[K.lastCheckAt] = at
            it[K.lastResult] = result
            it[K.lastError] = ""
            it[K.authFailed] = false
            it[K.readTotal] = (it[K.readTotal] ?: 0L) + read
            it[K.capturedTotal] = (it[K.capturedTotal] ?: 0L) + captured
        }
    }

    suspend fun recordFailure(at: Long, message: String, auth: Boolean) {
        context.emailDataStore.edit {
            it[K.lastCheckAt] = at
            it[K.lastError] = message
            if (auth) it[K.authFailed] = true
        }
    }

    private fun formatMark(folder: String, validity: Long, uid: Long) = "$folder\t$validity\t$uid"

    private fun parseMark(raw: String): Pair<String, Pair<Long, Long>>? {
        val parts = raw.split('\t')
        if (parts.size != 3) return null
        val validity = parts[1].toLongOrNull() ?: return null
        val uid = parts[2].toLongOrNull() ?: return null
        return parts[0] to (validity to uid)
    }

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 3

        /** How often to look, in hours. */
        val INTERVALS = listOf(1, 3, 6, 24)

        val PROVIDERS = listOf(
            Provider(
                "gmail", "Gmail or Google Workspace", "imap.gmail.com", 993,
                "Turn on 2-Step Verification, then create an app password (Google calls it " +
                    "\"App passwords\") and paste the 16 letters here. Your Google password will not work.",
                "https://myaccount.google.com/apppasswords",
            ),
            Provider(
                "yahoo", "Yahoo Mail", "imap.mail.yahoo.com", 993,
                "Create an app password under Account security, then paste it here.",
                "https://login.yahoo.com/account/security",
            ),
            Provider(
                "icloud", "iCloud Mail", "imap.mail.me.com", 993,
                "Create an app-specific password in your Apple Account's Sign-In and Security page.",
                "https://account.apple.com/account/manage",
            ),
            Provider(
                "zoho", "Zoho Mail", "imap.zoho.in", 993,
                "For an account outside India change the server to imap.zoho.com. Create an " +
                    "application-specific password in Zoho's security settings.",
                "https://accounts.zoho.in/home#security/app_password",
            ),
            Provider(
                "fastmail", "Fastmail", "imap.fastmail.com", 993,
                "Create an app password under Settings, Privacy & Security.",
                null,
            ),
            Provider(
                "other", "Another provider (IMAP)", "", 993,
                "Your provider's IMAP server (it usually starts with imap.) and an app password. " +
                    "Outlook and Microsoft 365 need Microsoft sign-in, which this does not do: for those, " +
                    "keep the Outlook app's notifications on, or forward work mail to a mailbox above.",
                null,
            ),
        )
    }
}
