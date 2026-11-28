package com.nuvio.tv.core.usenet

import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.ui.util.LANGUAGE_NAME_ALIASES
import com.nuvio.tv.ui.util.LANGUAGE_OVERRIDES
import java.util.Locale
import kotlinx.serialization.Serializable

/** Map indexer track labels to the same language codes offered by the settings picker. */
internal object NewznabLanguages {
    private val aliases = buildMap {
        putAll(LANGUAGE_NAME_ALIASES)
        putAll(LANGUAGE_OVERRIDES)
        for (language in AVAILABLE_SUBTITLE_LANGUAGES) {
            put(language.code.lowercase(Locale.ROOT), language.code)
            val name = language.name.lowercase(Locale.ROOT)
            put(name, language.code)
            putIfAbsent(name.substringBefore(" ("), language.code.substringBefore('-'))
        }
        put("multi", "multi")
        put("multi audio", "multi")
        put("multi subs", "multi")
        put("dual", "dual")
        put("dual audio", "dual")
        put("dubbed", "dubbed")
        put("original", "original")
        put("zh-hans", "zh-CN")
        put("zh-hant", "zh-TW")
    }

    private fun tag(pattern: String) = Regex("(?i)(?<![\\p{L}\\p{N}])(?:$pattern)(?![\\p{L}\\p{N}])")
    private val multiSubs = tag("multi[ ._-]*sub(?:title)?s?")
    private val audioTags = mapOf(
        "dual" to tag("dual(?:[ ._-]*(?:audio|aac2?|ac3))?"),
        "multi" to tag("multi(?:[ ._-]*audio)?"),
        "dubbed" to tag("dub(?:s|bed|bing)?")
    )
    private val yearOrEpisode = tag("(?:19|20)[0-9]{2}|s[0-9]{1,2}(?:e[0-9]{1,3})?")
    private val resolution = tag("[0-9]{3,4}[pi]|4k")
    private val words = Regex("[\\p{L}]+")

    fun releaseTags(release: UsenetRelease, subtitles: Boolean): Set<String> = buildSet {
        val reported = if (subtitles) release.subtitleLanguages else release.audioLanguages
        addAll(reported)
        val title = yearOrEpisode.find(release.title)?.let { release.title.substring(it.range.last + 1) } ?: release.title
        if (subtitles) {
            if (multiSubs.containsMatchIn(title)) add("multi")
        } else {
            val audioTitle = multiSubs.replace(title, " ")
            for ((code, pattern) in audioTags) if (pattern.containsMatchIn(audioTitle)) add(code)
            if (reported.isEmpty()) {
                // Short language codes in a movie's own title are often just ordinary words.
                val start = if (title != release.title) 0 else resolution.find(audioTitle)?.range?.last ?: audioTitle.length
                for (word in words.findAll(audioTitle)) {
                    val token = word.value.lowercase(Locale.ROOT)
                    if ((word.range.first >= start || token.length >= 3) && token !in audioTags && token != "original") {
                        aliases[token]?.let { add(it.lowercase(Locale.ROOT)) }
                    }
                }
            }
        }
        val original = release.originalLanguage
        if (original != null && any { languageMatches(it, original) }) add("original")
        if (isEmpty()) add("unknown")
    }

    fun parse(attributes: List<String>): Set<String> = buildSet {
        for (attribute in attributes) {
            for (value in attribute.split(',', ';', '/', '|')) {
                val label = value.trim().lowercase(Locale.ROOT).replace('_', '-')
                // Some indexers send labels such as "Portuguese (BR)" or "Arabic (SA)".
                val base = label.substringBefore('(').substringBefore('-').trim()
                val language = aliases[label] ?: aliases[base] ?: continue
                val region = label.substringAfter('(', "").substringBefore(')').trim()
                val code = aliases[label] ?: aliases["$language-$region"] ?: language
                add(code.lowercase(Locale.ROOT))
            }
        }
    }
}

private fun languageMatches(actual: String, selected: String): Boolean =
    actual.equals(selected, ignoreCase = true) || actual.startsWith("$selected-", ignoreCase = true)

@Serializable
data class UsenetLanguageRules(
    val required: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val included: List<String> = emptyList(),
    val preferred: List<String> = emptyList(),
    val sortEnabled: Boolean = true
) {
    val filters get() = required.isNotEmpty() || excluded.isNotEmpty()
    val sorts get() = sortEnabled && preferred.isNotEmpty()
    val needsOriginal get() = "original" in required || "original" in excluded ||
        "original" in included || (sorts && "original" in preferred)

    fun includes(tags: Set<String>) = included.any { choice -> tags.any { languageMatches(it, choice) } }

    fun allows(tags: Set<String>) =
        (required.isEmpty() || required.any { choice -> tags.any { languageMatches(it, choice) } }) &&
            (excluded.isEmpty() || tags.any { tag -> excluded.none { languageMatches(tag, it) } })

    fun rank(tags: Set<String>): Int = preferred.indexOfFirst { choice -> tags.any { languageMatches(it, choice) } }
        .let { if (it < 0) preferred.size else it }
}
