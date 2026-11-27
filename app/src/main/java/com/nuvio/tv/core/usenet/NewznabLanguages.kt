package com.nuvio.tv.core.usenet

import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.ui.util.LANGUAGE_NAME_ALIASES
import com.nuvio.tv.ui.util.LANGUAGE_OVERRIDES
import java.util.Locale

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
        put("zh-hans", "zh-CN")
        put("zh-hant", "zh-TW")
    }

    fun parse(attributes: List<String>): Set<String> = buildSet {
        for (attribute in attributes) {
            for (value in attribute.split(',', ';', '/', '|')) {
                val label = value.trim().lowercase(Locale.ROOT).replace('_', '-')
                // Some indexers send labels such as "Portuguese (BR)" or "Arabic (SA)".
                val base = label.substringBefore('(').substringBefore('-').trim()
                val language = aliases[base] ?: continue
                val region = label.substringAfter('(', "").substringBefore(')').trim()
                val code = aliases[label] ?: aliases["$language-$region"] ?: language
                add(code.lowercase(Locale.ROOT))
            }
        }
    }
}
