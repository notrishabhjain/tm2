package com.taskmind.profiles

import com.taskmind.tagging.AutoTagger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRulesTest {

    private fun person(name: String) = AutoTagger.Tag(AutoTagger.Kind.PERSON, name)
    private fun group(name: String) = AutoTagger.Tag(AutoTagger.Kind.GROUP, name)
    private fun app(name: String) = AutoTagger.Tag(AutoTagger.Kind.APP, name)
    private fun topic(name: String) = AutoTagger.Tag(AutoTagger.Kind.TOPIC, name)

    private val book = ProfileRules.Book(
        personal = setOf("mummy", "family group"),
        work = setOf("sharma ji", "cpc infra"),
    )

    // -- classification ------------------------------------------------------

    @Test
    fun `a classified person decides the profile`() {
        assertEquals(Profile.WORK, ProfileRules.of(listOf(person("Sharma Ji")), book))
        assertEquals(Profile.PERSONAL, ProfileRules.of(listOf(person("Mummy")), book))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(Profile.WORK, ProfileRules.of(listOf(person("SHARMA JI")), book))
    }

    @Test
    fun `an unclassified person has no profile`() {
        assertNull(ProfileRules.of(listOf(person("Someone New")), book))
    }

    @Test
    fun `an empty book classifies nothing`() {
        assertNull(ProfileRules.of(listOf(person("Sharma Ji")), ProfileRules.Book()))
    }

    @Test
    fun `the group wins over the person`() {
        // A work contact messaging in the family group is personal.
        val tags = listOf(person("Sharma Ji"), group("Family Group"))
        assertEquals(Profile.PERSONAL, ProfileRules.of(tags, book))
    }

    @Test
    fun `an unclassified group falls through to the person`() {
        val tags = listOf(person("Sharma Ji"), group("Some Random Group"))
        assertEquals(Profile.WORK, ProfileRules.of(tags, book))
    }

    // -- what can be classified ---------------------------------------------

    @Test
    fun `only people and groups are offered for classification`() {
        val tags = listOf(person("Amit"), group("CPC Infra"), app("WhatsApp"), topic("payment"))
        assertEquals(listOf("Amit", "CPC Infra"), ProfileRules.subjects(tags))
    }

    // -- filtering -----------------------------------------------------------

    @Test
    fun `no profile selected shows everything`() {
        assertTrue(ProfileRules.matches(listOf(person("Sharma Ji")), book, null))
        assertTrue(ProfileRules.matches(listOf(person("Mummy")), book, null))
    }

    @Test
    fun `a work view hides personal tasks`() {
        assertFalse(ProfileRules.matches(listOf(person("Mummy")), book, Profile.WORK))
        assertTrue(ProfileRules.matches(listOf(person("Sharma Ji")), book, Profile.WORK))
    }

    @Test
    fun `an unclassified task appears in both profiles`() {
        // The important one: never hide a real commitment because a contact
        // was not sorted.
        val tags = listOf(person("Someone New"))
        assertTrue(ProfileRules.matches(tags, book, Profile.WORK))
        assertTrue(ProfileRules.matches(tags, book, Profile.PERSONAL))
    }

    @Test
    fun `a task with no person or group at all appears everywhere`() {
        val tags = listOf(app("WhatsApp"), topic("payment"))
        assertTrue(ProfileRules.matches(tags, book, Profile.WORK))
        assertTrue(ProfileRules.matches(tags, book, Profile.PERSONAL))
    }

    // -- the book ------------------------------------------------------------

    @Test
    fun `profileOf trims and lowercases`() {
        assertEquals(Profile.WORK, book.profileOf("  Sharma Ji  "))
    }

    @Test
    fun `a blank subject is not classified`() {
        assertNull(book.profileOf("   "))
    }

    @Test
    fun `a stored profile name that no longer exists falls back`() {
        assertNull(Profile.byName("COLLEAGUE"))
        assertEquals(Profile.WORK, Profile.byName("WORK"))
    }
}
