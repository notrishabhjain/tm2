package com.taskmind.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One access token for everything that talks to Supabase (v1.36).
 *
 * Supabase rotates the refresh token on every use. Two callers refreshing at
 * once - the sync and the Twin's web bridge - would each spend the same
 * refresh token, and the second would sign the phone out. Both go through
 * this lock.
 */
object SyncAuth {
    val lock = Mutex()

    suspend fun token(api: SupabaseApi, secrets: SyncSecrets, projectUrl: String): String? = lock.withLock {
        val held = secrets.accessToken
        if (held.isNotBlank() && System.currentTimeMillis() < secrets.accessTokenExpiresAt) return@withLock held
        when (val out = api.refresh(projectUrl, secrets.anonKey, secrets.refreshToken)) {
            is SupabaseApi.Outcome.Ok -> {
                secrets.accessToken = out.value.accessToken
                secrets.accessTokenExpiresAt = out.value.expiresAt
                secrets.refreshToken = out.value.refreshToken
                out.value.accessToken
            }
            is SupabaseApi.Outcome.Failed -> null
        }
    }
}
