package com.taskmind.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/**
 * The Supabase calls this app makes. Four of them.
 *
 * Written against the REST and auth endpoints directly rather than pulling in
 * the Supabase Kotlin SDK: OkHttp is already a dependency, these are four
 * ordinary HTTP requests, and a new multi-module SDK is a compatibility
 * surface that can only be discovered in CI.
 *
 * Every method returns a result rather than throwing. A sync failure is an
 * ordinary condition - the phone spends a lot of its life on no network - and
 * the caller needs the reason to show on the settings screen, not a stack
 * trace in a log nobody reads.
 */
class SupabaseApi(private val http: OkHttpClient) {

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
        val expiresAt: Long,
    )

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        /** [retryable] separates "come back later" from "this will never work". */
        data class Failed(val message: String, val retryable: Boolean) : Outcome<Nothing>
    }

    // -- auth ---------------------------------------------------------------

    fun signIn(projectUrl: String, anonKey: String, email: String, password: String): Outcome<Session> =
        token(
            projectUrl, anonKey, "password",
            JsonObject(mapOf("email" to JsonPrimitive(email), "password" to JsonPrimitive(password))),
        )

    fun refresh(projectUrl: String, anonKey: String, refreshToken: String): Outcome<Session> =
        token(
            projectUrl, anonKey, "refresh_token",
            JsonObject(mapOf("refresh_token" to JsonPrimitive(refreshToken))),
        )

    private fun token(projectUrl: String, anonKey: String, grant: String, payload: JsonObject): Outcome<Session> {
        val body = json.encodeToString(JsonObject.serializer(), payload)
        val request = Request.Builder()
            .url("$projectUrl/auth/v1/token?grant_type=$grant")
            .addHeader("apikey", anonKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        return call(request) { text ->
            val obj = json.parseToJsonElement(text).jsonObject
            val access = obj["access_token"]?.jsonPrimitive?.content
            val refresh = obj["refresh_token"]?.jsonPrimitive?.content
            if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
                return@call Outcome.Failed("Sign-in response had no token in it.", retryable = false)
            }
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            Outcome.Ok(
                Session(
                    accessToken = access,
                    refreshToken = refresh,
                    userId = obj["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content.orEmpty(),
                    // A minute of headroom: a token that expires mid-request
                    // fails the whole push for no reason.
                    expiresAt = System.currentTimeMillis() + (expiresIn - 60).coerceAtLeast(0L) * 1000,
                ),
            )
        }
    }

    // -- data ---------------------------------------------------------------

    /**
     * Insert-or-update keyed on id.
     *
     * `resolution=merge-duplicates` is what makes this idempotent: re-pushing
     * a row the server already has updates it instead of failing on the
     * primary key. Sync retries constantly, so anything less than idempotent
     * would mean a dropped connection leaves a permanent error behind.
     */
    fun upsert(
        projectUrl: String,
        anonKey: String,
        accessToken: String,
        table: String,
        rows: JsonArray,
    ): Outcome<Unit> {
        if (rows.isEmpty()) return Outcome.Ok(Unit)
        val request = Request.Builder()
            .url("$projectUrl/rest/v1/$table?on_conflict=id")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(json.encodeToString(JsonArray.serializer(), rows).toRequestBody(JSON))
            .build()
        return call(request) { Outcome.Ok(Unit) }
    }

    /** Removes rows the phone has deleted. Ids absent on the server are a no-op. */
    fun deleteIds(
        projectUrl: String,
        anonKey: String,
        accessToken: String,
        table: String,
        ids: List<String>,
    ): Outcome<Unit> {
        if (ids.isEmpty()) return Outcome.Ok(Unit)
        val list = ids.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
        val request = Request.Builder()
            .url("$projectUrl/rest/v1/$table?id=in.($list)")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Prefer", "return=minimal")
            .delete()
            .build()
        return call(request) { Outcome.Ok(Unit) }
    }

    /**
     * Reads rows back. The only place this app asks the server a question
     * rather than telling it something.
     *
     * [filter] is raw PostgREST query syntax (`web_updated_at=gt.2026-01-01T00:00:00Z`).
     * Values in it must already be encoded - the caller knows which parts are
     * operators and which are data, and this does not.
     */
    fun select(
        projectUrl: String,
        anonKey: String,
        accessToken: String,
        table: String,
        filter: String,
        limit: Int,
    ): Outcome<JsonArray> {
        val request = Request.Builder()
            .url("$projectUrl/rest/v1/$table?select=*&$filter&limit=$limit")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return call(request) { text ->
            val parsed = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: return@call Outcome.Failed("Server did not return a list of rows.", retryable = false)
            Outcome.Ok(parsed)
        }
    }

    /**
     * Removes every row EXCEPT the ids given - how the pending-review mirror
     * is kept honest.
     *
     * An empty list means "nothing should be here", so it clears the table.
     * That is the correct reading, and the one the caller relies on when the
     * last pending item is approved on the phone.
     */
    fun deleteNotIn(
        projectUrl: String,
        anonKey: String,
        accessToken: String,
        table: String,
        keep: List<String>,
    ): Outcome<Unit> {
        val filter = if (keep.isEmpty()) {
            // PostgREST refuses an unfiltered delete, by design. `not.is.null`
            // on the primary key matches every row and states the intent.
            "id=not.is.null"
        } else {
            "id=not.in.(" + keep.joinToString(",") { URLEncoder.encode(it, "UTF-8") } + ")"
        }
        val request = Request.Builder()
            .url("$projectUrl/rest/v1/$table?$filter")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Prefer", "return=minimal")
            .delete()
            .build()
        return call(request) { Outcome.Ok(Unit) }
    }

    // -- plumbing -----------------------------------------------------------

    private fun <T> call(request: Request, onSuccess: (String) -> Outcome<T>): Outcome<T> = try {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> onSuccess(text)

                // 401 on a data call means the access token aged out; the
                // caller refreshes and comes back. On an auth call it means
                // the credentials are wrong, which retrying cannot fix - the
                // caller knows which it made.
                response.code == 401 -> Outcome.Failed(explain(401, text), retryable = true)

                response.code == 429 || response.code >= 500 ->
                    Outcome.Failed(explain(response.code, text), retryable = true)

                else -> Outcome.Failed(explain(response.code, text), retryable = false)
            }
        }
    } catch (e: IOException) {
        // No network, DNS failure, timeout. Always worth another go.
        Outcome.Failed(e.message ?: "Could not reach the server.", retryable = true)
    } catch (e: Throwable) {
        Outcome.Failed(e.message ?: "Unexpected sync error.", retryable = false)
    }

    /**
     * Supabase puts the useful part in a JSON body; the HTTP code alone sends
     * you looking in the wrong place. A missing table and a bad key are both
     * "it does not work" until you read the message.
     */
    private fun explain(code: Int, body: String): String {
        val detail = runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
            obj["message"]?.jsonPrimitive?.content
                ?: obj["error_description"]?.jsonPrimitive?.content
                ?: obj["msg"]?.jsonPrimitive?.content
                ?: obj["hint"]?.jsonPrimitive?.content
        }.getOrNull()
        val hint = when (code) {
            401 -> "Not signed in, or the key is wrong."
            404 -> "No such table - has schema.sql been run?"
            409 -> "Conflicting row."
            else -> null
        }
        return listOfNotNull("HTTP $code", detail, hint).joinToString(" - ")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
    }
}
