package dev.lumastream.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogConfigurationTest {
    @Test fun oldSettingsKeepCatalogCredentialsAndLanguageWithIndependentPlaybackDefaults() {
        val configuration = decodeCatalogConfiguration("""{"token":"private-token","providerManifestUrl":"https://example.org/private/manifest.json","catalogLanguage":"ja-JP"}""")
        assertEquals("private-token", configuration.token)
        assertEquals("https://example.org/private/manifest.json", configuration.providerManifestUrl)
        assertEquals("ja-JP", configuration.catalogLanguage)
        assertEquals("en", configuration.subtitleLanguage)
        assertEquals("original", configuration.audioLanguage)
        assertEquals("", configuration.openSubtitlesApiKey)
    }

    @Test fun allChoicesAndClearedCredentialsSurviveSerializationWithoutLoggingSecrets() {
        val selected = CatalogConfiguration(token = "tmdb-secret", subtitleLanguage = "de", audioLanguage = "ja", openSubtitlesApiKey = "subtitle-secret")
        assertEquals(selected, decodeCatalogConfiguration(encodeCatalogConfiguration(selected)))
        val cleared = selected.copy(token = "", openSubtitlesApiKey = "")
        assertEquals(cleared, decodeCatalogConfiguration(encodeCatalogConfiguration(cleared)))
        assertFalse(selected.toString().contains("tmdb-secret"))
        assertFalse(selected.toString().contains("subtitle-secret"))
        assertFalse(selected.toString().contains(selected.providerManifestUrl))
    }

    @Test fun invalidPreferencesAndKeysAreRejectedWithoutEchoingCredentials() {
        assertThrows(IllegalArgumentException::class.java) { CatalogConfiguration(subtitleLanguage = "any").validated() }
        assertThrows(IllegalArgumentException::class.java) { CatalogConfiguration(audioLanguage = "auto").validated() }
        val error = assertThrows(IllegalArgumentException::class.java) { CatalogConfiguration(openSubtitlesApiKey = "secret\nheader").validated() }
        assertFalse(error.message.orEmpty().contains("secret"))
        assertEquals("k".repeat(512), CatalogConfiguration(openSubtitlesApiKey = "k".repeat(512)).validated().openSubtitlesApiKey)
        assertThrows(IllegalArgumentException::class.java) { CatalogConfiguration(openSubtitlesApiKey = "k".repeat(513)).validated() }
        assertThrows(IllegalArgumentException::class.java) { CatalogConfiguration(openSubtitlesApiKey = "ключ").validated() }
    }
}
