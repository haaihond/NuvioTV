package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.usenet.UsenetLanguageRules
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.components.NuvioDialog

private enum class LanguageField(val title: Int, val hint: Int) {
    REQUIRED(R.string.usenet_languages_required, R.string.usenet_languages_required_hint),
    EXCLUDED(R.string.usenet_languages_excluded, R.string.usenet_languages_excluded_hint),
    INCLUDED(R.string.usenet_languages_included, R.string.usenet_languages_included_hint),
    PREFERRED(R.string.usenet_languages_preferred, R.string.usenet_languages_preferred_hint);

    fun values(rules: UsenetLanguageRules) = when (this) {
        REQUIRED -> rules.required
        EXCLUDED -> rules.excluded
        INCLUDED -> rules.included
        PREFERRED -> rules.preferred
    }

    fun update(rules: UsenetLanguageRules, values: List<String>) = when (this) {
        REQUIRED -> rules.copy(required = values)
        EXCLUDED -> rules.copy(excluded = values)
        INCLUDED -> rules.copy(included = values)
        PREFERRED -> rules.copy(preferred = values)
    }
}

@Composable
internal fun UsenetLanguageDialog(
    audio: Boolean,
    rules: UsenetLanguageRules,
    onUpdate: (UsenetLanguageRules) -> Unit,
    onDismiss: () -> Unit
) {
    val title = stringResource(if (audio) R.string.usenet_audio_languages else R.string.usenet_subtitle_languages)
    val options = buildList {
        add(SettingsPickerOption("dual", stringResource(R.string.usenet_language_dual)))
        add(SettingsPickerOption("dubbed", stringResource(R.string.usenet_language_dubbed)))
        add(SettingsPickerOption("multi", stringResource(R.string.usenet_language_multi)))
        add(SettingsPickerOption("original", stringResource(R.string.usenet_language_original)))
        add(SettingsPickerOption("unknown", stringResource(R.string.type_unknown)))
        addAll(AVAILABLE_SUBTITLE_LANGUAGES.map { SettingsPickerOption(it.code, it.displayName) }.sortedBy { it.title })
    }
    val names = options.associate { it.value to it.title }
    var editing by remember { mutableStateOf<LanguageField?>(null) }
    var ordering by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    val field = editing
    val language = moving
    when {
        field != null -> SettingsMultiChoiceDialog(
            title = stringResource(field.title), subtitle = stringResource(field.hint), options = options,
            selectedValues = field.values(rules), preserveSelectionOrder = field == LanguageField.PREFERRED,
            onValuesSelected = { onUpdate(field.update(rules, it)); editing = null },
            onDismiss = { editing = null }
        )
        language != null -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.usenet_language_position, names[language] ?: language),
            options = rules.preferred.mapIndexed { index, code ->
                SettingsPickerOption(index, "${index + 1}. ${names[code] ?: code}")
            } + SettingsPickerOption(-1, stringResource(R.string.cw_action_remove)),
            selectedValue = rules.preferred.indexOf(language),
            onOptionSelected = { position ->
                val reordered = rules.preferred.toMutableList()
                reordered.remove(language)
                if (position >= 0) reordered.add(position, language)
                onUpdate(rules.copy(preferred = reordered))
                moving = null
                ordering = reordered.isNotEmpty()
            },
            onDismiss = { moving = null }
        )
        ordering -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.usenet_language_order),
            options = rules.preferred.mapIndexed { index, code -> SettingsPickerOption(code, "${index + 1}. ${names[code] ?: code}") },
            selectedValue = rules.preferred.firstOrNull().orEmpty(),
            onOptionSelected = { moving = it }, onDismiss = { ordering = false }
        )
        else -> NuvioDialog(title = title, onDismiss = onDismiss) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for (entry in LanguageField.entries) {
                    val values = entry.values(rules)
                    SettingsActionRow(title = stringResource(entry.title), subtitle = stringResource(entry.hint),
                        value = values.joinToString(", ") { names[it] ?: it }.ifEmpty { stringResource(R.string.action_none) },
                        onClick = { editing = entry })
                }
                SettingsToggleRow(title = stringResource(R.string.usenet_language_sort_enabled), subtitle = null,
                    checked = rules.sortEnabled, onToggle = { onUpdate(rules.copy(sortEnabled = !rules.sortEnabled)) })
                SettingsActionRow(title = stringResource(R.string.usenet_language_order), subtitle = null,
                    enabled = rules.preferred.isNotEmpty(), onClick = { ordering = true })
            }
        }
    }
}
