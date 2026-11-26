package dev.ahmedmohamed.hayaitts.ui.reading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmedmohamed.hayaitts.R
import dev.ahmedmohamed.hayaitts.domain.model.VoiceRoute
import dev.ahmedmohamed.hayaitts.ui.components.displayName

/** Language name plus its raw tag, with reorder/remove tucked in an overflow menu. */
@Composable
internal fun LanguageHeader(
    language: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(displayName(language), style = MaterialTheme.typography.titleMedium)
            Text(language, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.reading_language_options))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reading_move_up)) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                    enabled = canMoveUp,
                    onClick = { menuOpen = false; onMove(-1) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reading_move_down)) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                    enabled = canMoveDown,
                    onClick = { menuOpen = false; onMove(1) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reading_remove_language)) },
                    leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

/**
 * One route in a language's priority list. The leading number is the try
 * order, which is the whole point of the list. Edge rows fade when Edge
 * can't be used, so the list shows what will actually be tried.
 */
@Composable
internal fun RouteRow(
    position: Int,
    route: VoiceRoute,
    voiceName: String?,
    onlineAvailable: Boolean,
    onlineEnabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val isEdge = route.engine == VoiceRoute.ENGINE_EDGE
    val isAuto = route.engine == VoiceRoute.ENGINE_LOCAL && route.voiceId == VoiceRoute.AUTO
    val status = when {
        isEdge && !onlineAvailable -> stringResource(R.string.reading_route_edge_unavailable)
        isEdge && !onlineEnabled -> stringResource(R.string.reading_route_edge_off)
        !isEdge && !isAuto && voiceName == null -> stringResource(R.string.reading_route_missing)
        else -> null
    }
    val skipped = isEdge && (!onlineAvailable || !onlineEnabled)
    ListItem(
        leadingContent = {
            Box(
                modifier = Modifier.size(28.dp).alpha(if (skipped) 0.5f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(28.dp),
                ) {}
                Text("${position + 1}", style = MaterialTheme.typography.labelLarge)
            }
        },
        headlineContent = {
            Text(
                text = if (isAuto) stringResource(R.string.reading_route_auto) else voiceName ?: route.voiceId,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(if (skipped) 0.5f else 1f),
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                EngineBadge(route.engine)
                if (status != null) {
                    Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.reading_move_up))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.reading_move_down))
                }
                IconButton(onClick = onRemove, enabled = canRemove) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.reading_remove_voice))
                }
            }
        },
    )
}

@Composable
internal fun EngineBadge(engine: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = engineLabel(engine),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun engineLabel(engine: String): String =
    if (engine == VoiceRoute.ENGINE_EDGE) stringResource(R.string.reading_engine_edge) else stringResource(R.string.reading_engine_local)

/** Add voice + Test, then the last test's outcome. */
@Composable
internal fun LanguageActions(
    test: ReadingViewModel.TestState?,
    onAddVoice: () -> Unit,
    onTest: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAddVoice) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.reading_add_voice))
            }
            Spacer(Modifier.weight(1f))
            val running = test is ReadingViewModel.TestState.Running
            FilledTonalButton(onClick = onTest, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (running) R.string.reading_testing else R.string.reading_test))
            }
        }
        val result = when (test) {
            is ReadingViewModel.TestState.Spoke ->
                stringResource(R.string.reading_test_spoke, test.voiceName, engineLabel(test.route.engine)) to false
            is ReadingViewModel.TestState.Failed -> stringResource(R.string.reading_test_failed, test.reason) to true
            else -> null
        }
        if (result != null) {
            Text(
                text = result.first,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            )
        }
    }
}
