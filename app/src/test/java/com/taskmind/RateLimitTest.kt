package com.taskmind

import com.taskmind.core.RateLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body here is the one the device actually received, at the moment the
 * account had spent all 250 of its daily requests on a model that could not
 * answer.
 */
class RateLimitTest {

    private val groqDaily =
        "Rate limit reached for model `groq/compound` in organization `org_01m11aqb4ye5evc4j2nwqwayeq` " +
            "service tier `on_demand` on requests per day (RPD): Limit 250, Used 250, Requested 1. " +
            "Please try again in 5m45.6s. Need more tokens? Upgrade to Dev Tier today at " +
            "https://console.groq.com/settings/billing"

    @Test
    fun `reads minutes and seconds out of the provider's prose`() {
        assertEquals(345_600L, RateLimit.fromBody(groqDaily))
    }

    @Test
    fun `reads hours, minutes and seconds together`() {
        assertEquals((1 * 3600 + 2 * 60 + 3) * 1000L, RateLimit.fromBody("please try again in 1h2m3s"))
    }

    @Test
    fun `reads a bare seconds duration`() {
        assertEquals(30_000L, RateLimit.fromBody("try again in 30s"))
    }

    @Test
    fun `sub-second waits still round to something non-zero`() {
        assertEquals(500L, RateLimit.fromBody("try again in 0.5s"))
    }

    @Test
    fun `ignores prose with no duration in it`() {
        assertNull(RateLimit.fromBody("try again in a little while"))
        assertNull(RateLimit.fromBody("rate limited"))
        assertNull(RateLimit.fromBody(null))
    }

    @Test
    fun `Retry-After is seconds`() {
        assertEquals(120_000L, RateLimit.fromHeader("120"))
        assertNull(RateLimit.fromHeader(""))
        assertNull(RateLimit.fromHeader(null))
        // An HTTP-date Retry-After is legal but not worth parsing; falling
        // through to the body or the default is the safe answer.
        assertNull(RateLimit.fromHeader("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `the header wins over the body`() {
        assertEquals(60_000L, RateLimit.cooldownMillis("60", groqDaily))
    }

    @Test
    fun `falls back to the body, then to a default`() {
        assertEquals(345_600L, RateLimit.cooldownMillis(null, groqDaily))
        assertEquals(RateLimit.DEFAULT_COOLDOWN_MILLIS, RateLimit.cooldownMillis(null, "slow down"))
        assertEquals(RateLimit.DEFAULT_COOLDOWN_MILLIS, RateLimit.cooldownMillis(null, null))
    }

    @Test
    fun `an absurd wait is capped`() {
        assertEquals(
            RateLimit.MAX_COOLDOWN_MILLIS,
            RateLimit.cooldownMillis("999999", null),
        )
    }

    @Test
    fun `a cooldown is never shorter than a second`() {
        assertTrue(RateLimit.cooldownMillis("0", null) >= 1_000)
    }

    @Test
    fun `a daily limit is recognised as daily`() {
        assertTrue(RateLimit.isDailyLimit(groqDaily))
        assertTrue(RateLimit.isDailyLimit("limit on tokens per day (TPD)"))
        assertFalse(RateLimit.isDailyLimit("Rate limit reached on requests per minute (RPM)"))
        assertFalse(RateLimit.isDailyLimit(null))
    }
}
