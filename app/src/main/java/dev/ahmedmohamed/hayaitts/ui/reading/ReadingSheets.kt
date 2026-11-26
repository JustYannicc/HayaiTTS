@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ahmedmohamed.hayaitts.ui.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ahmedmohamed.hayaitts.R
import dev.ahmedmohamed.hayaitts.domain.model.LanguageProfile
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute
import dev.ahmedmohamed.hayaitts.domain.repo.RouteVoice
import dev.ahmedmohamed.hayaitts.ui.components.displayName

/** Searchable list of languages some installed or online voice can read. */
@Composable
internal fun LanguagePickerSheet(languages: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val named = remember(languages) { languages.map { it to displayName(it) }.sortedBy { it.second.lowercase() } }
    val q = query.trim()
    val shown = if (q.isEmpty()) named else named.filter { (tag, name) -> name.contains(q, true) || tag.contains(q, true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            SheetTitle(stringResource(R.string.reading_pick_language_title))
            if (named.size > SEARCH_THRESHOLD) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = null) }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.browse_filter_language_search)) },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (named.isEmpty()) SheetEmpty(stringResource(R.string.reading_pick_language_empty))
            LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
                items(shown, key = { it.first }) { (tag, name) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text(tag) },
                        colors = sheetRowColors(),
                        modifier = Modifier.clickable { onPick(tag) },
                    )
                }
            }
        }
    }
}

/**
 * Engine first (only when Edge is usable), then that engine's voices for the
 * language's base language. On device leads with "Best installed voice
 * (auto)" so new installs are picked up without editing the list.
 */
@Composable
internal fun VoicePickerSheet(
    state: ReadingViewModel.UiState,
    profile: LanguageProfile,
    onPick: (VoiceRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    val edgeUsable = state.onlineAvailable && state.onlineEnabled
    var engine by remember { mutableStateOf(VoiceRoute.ENGINE_LOCAL) }
    val voices = state.voicesFor(profile.language, engine)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            SheetTitle(stringResource(R.string.reading_pick_voice_title, displayName(profile.language)))
            if (edgeUsable) {
                val engines = listOf(VoiceRoute.ENGINE_LOCAL, VoiceRoute.ENGINE_EDGE)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    engines.forEachIndexed { i, e ->
                        SegmentedButton(
                            selected = engine == e,
                            onClick = { engine = e },
                            shape = SegmentedButtonDefaults.itemShape(i, engines.size),
                            label = { Text(engineLabel(e)) },
                        )
                    }
                }
            }
            val rows = buildList {
                if (engine == VoiceRoute.ENGINE_LOCAL) add(null)
                addAll(voices)
            }
            if (voices.isEmpty() && engine == VoiceRoute.ENGINE_EDGE) SheetEmpty(stringResource(R.string.reading_pick_voice_empty_edge))
            LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
                items(rows, key = { it?.route?.toString() ?: "auto" }) { voice ->
                    VoiceOption(voice = voice, taken = (voice?.route ?: VoiceRoute.LocalAuto) in profile.routes, onPick = onPick)
                }
            }
            if (voices.isEmpty() && engine == VoiceRoute.ENGINE_LOCAL) SheetEmpty(stringResource(R.string.reading_pick_voice_empty_local))
        }
    }
}

/** A voice row; `null` is the local auto route. Already-listed voices show a check and can't be added twice. */
@Composable
private fun VoiceOption(voice: RouteVoice?, taken: Boolean, onPick: (VoiceRoute) -> Unit) {
    val route = voice?.route ?: VoiceRoute.LocalAuto
    ListItem(
        leadingContent = if (voice == null) {
            { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) }
        } else null,
        headlineContent = { Text(voice?.displayName ?: stringResource(R.string.reading_route_auto_option)) },
        supportingContent = {
            Text(
                when {
                    taken -> stringResource(R.string.reading_voice_in_list)
                    voice == null -> stringResource(R.string.reading_route_auto_hint)
                    else -> voice.languages.joinToString()
                },
            )
        },
        trailingContent = if (taken) {
            { Icon(Icons.Outlined.Check, contentDescription = null) }
        } else null,
        colors = sheetRowColors(),
        modifier = (if (taken) Modifier.alpha(0.6f) else Modifier.clickable { onPick(route) }),
    )
}

/** ListItem paints `surface` by default, which shows as a box on the sheet's container colour. */
@Composable
private fun sheetRowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun SheetEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
    )
}

private const val SEARCH_THRESHOLD = 8
