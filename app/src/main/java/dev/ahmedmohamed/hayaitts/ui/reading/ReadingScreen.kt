@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ahmedmohamed.hayaitts.ui.reading

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ahmedmohamed.hayaitts.R
import dev.ahmedmohamed.hayaitts.ui.components.HayaiScreenChrome
import dev.ahmedmohamed.hayaitts.ui.settings.SectionHeader
import dev.ahmedmohamed.hayaitts.ui.theme.HayaiTtsTheme
import org.koin.androidx.compose.koinViewModel

/** Settings → Reading. Its own activity so both Settings entry points (tab and system TTS cog) reach it. */
class ReadingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HayaiTtsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReadingScreen(onBack = { finish() })
                }
            }
        }
    }
}

/**
 * The languages the user reads and, for each, the voices to try in order.
 * One block per language: a header, its numbered routes, then Add voice /
 * Test. Online controls sit on top because they change every block below.
 */
@Composable
fun ReadingScreen(onBack: () -> Unit, viewModel: ReadingViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var addingLanguage by remember { mutableStateOf(false) }
    var addingVoiceFor by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    HayaiScreenChrome(
        title = stringResource(R.string.reading_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
    ) { topInset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topInset, bottom = 24.dp),
        ) {
            if (state.onlineAvailable) {
                item("online_header") { SectionHeader(stringResource(R.string.reading_section_online)) }
                item("online_switch") {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.reading_online_toggle)) },
                        supportingContent = { Text(stringResource(R.string.reading_online_privacy)) },
                        trailingContent = {
                            Switch(checked = state.onlineEnabled, onCheckedChange = viewModel::setOnlineEnabled)
                        },
                        modifier = Modifier.clickable { viewModel.setOnlineEnabled(!state.onlineEnabled) },
                    )
                }
                if (state.onlineEnabled && state.onlineBenched) item("online_health") {
                    ListItem(
                        leadingContent = {
                            Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        headlineContent = {
                            Text(stringResource(R.string.reading_edge_benched), color = MaterialTheme.colorScheme.error)
                        },
                        trailingContent = {
                            TextButton(onClick = viewModel::retryOnline) { Text(stringResource(R.string.reading_retry)) }
                        },
                    )
                }
            }

            item("languages_header") { SectionHeader(stringResource(R.string.reading_section_languages)) }
            item("languages_hint") {
                Text(
                    text = stringResource(R.string.reading_languages_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
            }
            if (state.profiles.isEmpty()) item("languages_empty") {
                ListItem(headlineContent = { Text(stringResource(R.string.reading_empty)) })
            }

            state.profiles.forEachIndexed { position, profile ->
                val language = profile.language
                if (position > 0) item("divider_$language") {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                item("lang_$language") {
                    LanguageHeader(
                        language = language,
                        canMoveUp = position > 0,
                        canMoveDown = position < state.profiles.lastIndex,
                        onMove = { viewModel.moveLanguage(language, it) },
                        onRemove = { viewModel.removeLanguage(language) },
                    )
                }
                itemsIndexed(profile.routes, key = { i, r -> "route_${language}_${i}_${r.engine}_${r.voiceId}" }) { i, route ->
                    RouteRow(
                        position = i,
                        route = route,
                        voiceName = state.voiceName(route),
                        onlineAvailable = state.onlineAvailable,
                        onlineEnabled = state.onlineEnabled,
                        canMoveUp = i > 0,
                        canMoveDown = i < profile.routes.lastIndex,
                        canRemove = profile.routes.size > 1,
                        onMove = { viewModel.moveRoute(language, i, it) },
                        onRemove = { viewModel.removeRoute(language, i) },
                    )
                }
                item("actions_$language") {
                    LanguageActions(
                        test = state.tests[language],
                        onAddVoice = {
                            viewModel.refresh()
                            addingVoiceFor = language
                        },
                        onTest = { viewModel.test(language) },
                    )
                }
            }

            item("add_language") {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.reading_add_language)) },
                    colors = ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.padding(top = 8.dp).clickable {
                        viewModel.refresh()
                        addingLanguage = true
                    },
                )
            }
        }
    }

    if (addingLanguage) {
        LanguagePickerSheet(
            languages = state.addableLanguages,
            onPick = {
                viewModel.addLanguage(it)
                addingLanguage = false
            },
            onDismiss = { addingLanguage = false },
        )
    }

    addingVoiceFor?.let { language ->
        val profile = state.profiles.firstOrNull { it.language == language }
        if (profile == null) {
            addingVoiceFor = null
        } else {
            VoicePickerSheet(
                state = state,
                profile = profile,
                onPick = {
                    viewModel.addRoute(language, it)
                    addingVoiceFor = null
                },
                onDismiss = { addingVoiceFor = null },
            )
        }
    }
}
