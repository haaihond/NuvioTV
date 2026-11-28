package com.nuvio.tv.core.usenet

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UsenetLanguageRulesTest {
    private val english = UsenetRelease("Film.2024.1080p", "en", audioLanguages = setOf("en"))
    private val dutch = english.copy(nzbUrl = "nl", audioLanguages = setOf("nl"), subtitleLanguages = setOf("en"))
    private val both = english.copy(nzbUrl = "both", audioLanguages = setOf("en", "nl"))
    private val unknown = english.copy(nzbUrl = "unknown", audioLanguages = emptySet())

    @Test fun `required matches any selected language and excluded tests every reported language`() {
        val releases = listOf(english, dutch, both, unknown)
        val rules = UsenetLanguageRules(required = listOf("nl", "unknown"), excluded = listOf("en"))
        assertEquals(setOf(dutch, both, unknown), NewznabProtocol.arrange(releases,
            UsenetSourceConfiguration(audioLanguageFilters = rules)).toSet())
        val config = UsenetSourceConfiguration(audioLanguageFilters = rules,
            subtitleLanguageFilters = UsenetLanguageRules(required = listOf("en")))
        assertEquals(listOf(dutch), NewznabProtocol.arrange(releases, config))
        assertEquals(emptyList<UsenetRelease>(), NewznabProtocol.arrange(listOf(both), config.copy(
            audioLanguageFilters = rules.copy(excluded = listOf("en", "nl")), subtitleLanguageFilters = null)))
    }

    @Test fun `included subtitles bypass advisory filters but password and limit still apply`() {
        val included = dutch.copy(title = "Film.2024.CAM", size = Long.MAX_VALUE)
        val config = UsenetSourceConfiguration(minResolution = 2160, maxSizeGb = 1, maxAgeDays = 1, maxResults = 1,
            audioLanguageFilters = UsenetLanguageRules(required = listOf("ja")),
            subtitleLanguageFilters = UsenetLanguageRules(included = listOf("en"), excluded = listOf("en")))
        assertEquals(listOf(included), NewznabProtocol.arrange(listOf(english, included,
            included.copy(nzbUrl = "zzz"), included.copy(nzbUrl = "aaa", passworded = true)), config))
    }

    @Test fun `earliest preferred match wins with independent sort switches`() {
        val releases = listOf(english.copy(size = 3), dutch.copy(size = 2), both.copy(size = 1))
        val audio = UsenetLanguageRules(preferred = listOf("nl", "en"))
        val subs = UsenetLanguageRules(preferred = listOf("en", "unknown"))
        val config = UsenetSourceConfiguration(sort = UsenetSort.LARGEST, audioLanguageFilters = audio, subtitleLanguageFilters = subs)
        fun urls(c: UsenetSourceConfiguration) = NewznabProtocol.arrange(releases, c).map { it.nzbUrl }
        assertEquals(listOf("nl", "both", "en"), urls(config))
        assertEquals(listOf("en", "both", "nl"), urls(config.copy(audioLanguageFilters = audio.copy(preferred = listOf("en", "nl")))))
        assertEquals(listOf("nl", "en", "both"), urls(config.copy(audioLanguageFilters = audio.copy(sortEnabled = false))))
        assertEquals(listOf("en", "nl", "both"), urls(config.copy(audioLanguageFilters = audio.copy(sortEnabled = false),
            subtitleLanguageFilters = subs.copy(sortEnabled = false))))
    }

    @Test fun `audio tags subtitle metadata unknown and original are separate`() {
        val tagged = unknown.copy(title = "Film.2024.MULTi.Dual-Audio.Dubbed.JPN.1080p", originalLanguage = "ja")
        assertEquals(setOf("multi", "dual", "dubbed", "ja", "original"), tagged.audioTags)
        assertEquals(setOf("unknown"), tagged.subtitleTags)
        val multiSubs = unknown.copy(title = "Film.2024.MULTI.SUBS", subtitleLanguages = setOf("fr"), originalLanguage = "fr")
        assertEquals(setOf("unknown"), multiSubs.audioTags)
        assertEquals(setOf("fr", "multi", "original"), multiSubs.subtitleTags)
        assertEquals(setOf("unknown"), unknown.copy(title = "The.French.Dispatch.2021.1080p").audioTags)
        assertEquals(setOf("nl"), dutch.copy(title = "Film.2024.English", originalLanguage = "en").audioTags)
        assertEquals(setOf("dual"), NewznabLanguages.parse(listOf("Dual Audio")))
    }

    @Test fun `saved single choices and new lists round trip without reviving cleared preferences`() {
        val old = Json.decodeFromString<UsenetSourceConfiguration>("""{"preferredAudioLanguage":"ja","preferredSubtitleLanguage":"nl"}""")
        assertEquals(listOf("ja"), old.audioRules.preferred)
        assertEquals(listOf("nl"), old.subtitleRules.preferred)
        val edited = old.copy(audioLanguageFilters = UsenetLanguageRules(), subtitleLanguageFilters = UsenetLanguageRules(
            preferred = listOf("en", "nl"), required = listOf("original", "unknown"), sortEnabled = false))
        val decoded = Json.decodeFromString<UsenetSourceConfiguration>(Json.encodeToString(edited))
        assertEquals(emptyList<String>(), decoded.audioRules.preferred)
        assertEquals(edited.subtitleRules, decoded.subtitleRules)
    }
}
