package dev.lumastream.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePreferencesTest {
    @Test fun englishAcceptsIsoAndRegionalTagsButNeverUnknownOrAnotherLanguage() {
        for (language in listOf("en", "eng", "EN-us", "eng_US")) assertTrue(matchesSubtitleLanguage(language, "en"))
        for (language in listOf(null, "", "und", "zxx", "de", "fra", "English")) assertFalse(matchesSubtitleLanguage(language, "en"))
        assertFalse(matchesSubtitleLanguage(null, "und"))
    }

    @Test fun originalAudioUsesMetadataWithoutInventingEnglishAndExplicitChoiceWins() {
        assertEquals("ja", preferredAudioLanguage("original", "jpn"))
        assertNull(preferredAudioLanguage("original", null))
        assertNull(preferredAudioLanguage("original", "und"))
        assertEquals("en", preferredAudioLanguage("en", "ja"))
    }

    @Test fun bibliographicAndTerminologicCodesMatchSameLanguage() {
        for (language in listOf("ger", "deu", "de-DE")) assertTrue(matchesSubtitleLanguage(language, "de"))
        for (language in listOf("fre", "fra")) assertTrue(matchesSubtitleLanguage(language, "fr"))
        for (language in listOf("chi", "zho")) assertTrue(matchesSubtitleLanguage(language, "zh"))
    }
}
