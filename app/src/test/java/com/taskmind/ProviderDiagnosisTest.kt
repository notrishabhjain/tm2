package com.taskmind

import com.taskmind.core.ProviderDiagnosis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cases come from a real failure on the device: the app was configured
 * with `groq/compound` and got back a 403 naming
 * `meta-llama/llama-4-scout-17b-16e-instruct`, a model the user had never
 * selected and could not find in their console.
 */
class ProviderDiagnosisTest {

    private val groqCompoundError =
        "The model `meta-llama/llama-4-scout-17b-16e-instruct` is blocked at the project level. " +
            "Please have a project admin enable this model in the project settings."

    @Test
    fun `names the model the provider actually complained about`() {
        assertEquals(
            "meta-llama/llama-4-scout-17b-16e-instruct",
            ProviderDiagnosis.modelNamedIn(groqCompoundError),
        )
    }

    @Test
    fun `ignores quoted words that are not model names`() {
        assertNull(ProviderDiagnosis.modelNamedIn("The request was `bad` and we refused it"))
    }

    @Test
    fun `recognises routing models`() {
        assertTrue(ProviderDiagnosis.isRouterModel("groq/compound"))
        assertTrue(ProviderDiagnosis.isRouterModel("openrouter/auto"))
        assertFalse(ProviderDiagnosis.isRouterModel("llama-3.3-70b-versatile"))
        assertFalse(ProviderDiagnosis.isRouterModel("gpt-4o-mini"))
    }

    @Test
    fun `explains the router case in terms of both models`() {
        val diagnosis = ProviderDiagnosis.diagnose("groq/compound", 403, groqCompoundError)
        assertNotNull(diagnosis)
        // The explanation is useless unless it connects the two names.
        assertTrue(diagnosis!!.contains("groq/compound"))
        assertTrue(diagnosis.contains("meta-llama/llama-4-scout-17b-16e-instruct"))
        assertTrue(diagnosis.contains("routing model"))
    }

    @Test
    fun `a blocked model the user did choose reads differently`() {
        val diagnosis = ProviderDiagnosis.diagnose(
            "llama-3.3-70b-versatile",
            403,
            "The model `llama-3.3-70b-versatile` is blocked at the project level.",
        )
        assertNotNull(diagnosis)
        assertTrue(diagnosis!!.contains("blocked for this project"))
        assertFalse("must not claim a routing problem", diagnosis.contains("routing model"))
    }

    @Test
    fun `a bad key is called a bad key`() {
        val diagnosis = ProviderDiagnosis.diagnose("gpt-4o-mini", 401, "Incorrect API key provided")
        assertTrue(diagnosis!!.contains("API key was rejected"))
    }

    @Test
    fun `an unknown model points at the model list and the base URL`() {
        val diagnosis = ProviderDiagnosis.diagnose("does-not-exist", 404, "not found")
        assertTrue(diagnosis!!.contains("does not exist"))
        assertTrue(diagnosis.contains("base URL"))
    }

    @Test
    fun `a retired model is reported as retired`() {
        val diagnosis = ProviderDiagnosis.diagnose(
            "llama-3.1-70b-versatile",
            400,
            "The model `llama-3.1-70b-versatile` has been decommissioned",
        )
        assertTrue(diagnosis!!.contains("retired"))
    }

    @Test
    fun `rate limiting is not treated as misconfiguration`() {
        val diagnosis = ProviderDiagnosis.diagnose("gpt-4o-mini", 429, "Rate limit reached")
        assertTrue(diagnosis!!.contains("Rate limited"))
        assertTrue(diagnosis.contains("key and the model are fine"))
    }

    @Test
    fun `an ordinary failure with nothing useful to add says nothing`() {
        assertNull(ProviderDiagnosis.diagnose("gpt-4o-mini", 402, "payment required"))
    }

    @Test
    fun `the settings warning fires only for routers`() {
        assertNotNull(ProviderDiagnosis.settingsWarningFor("groq/compound"))
        assertNull(ProviderDiagnosis.settingsWarningFor("llama-3.3-70b-versatile"))
    }
}
