package com.werd.app.ui.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.werd.app.R
import com.werd.app.ui.theme.LocalExtra

/**
 * "Read with AI" or "Read on this phone" — used when adding a book and when scanning into a werd.
 * Without a key the AI option explains where to get one instead of being selectable.
 */
@Composable
fun ReadModePicker(useAi: Boolean, aiReady: Boolean, onChange: (Boolean) -> Unit, onSetupAi: (() -> Unit)? = null,
                   compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeCard(Icons.Rounded.AutoAwesome, stringResource(R.string.mode_ai),
            if (aiReady) stringResource(R.string.mode_ai_d) else stringResource(R.string.mode_ai_needs_key),
            selected = useAi && aiReady, enabled = aiReady, compact = compact, onClick = { onChange(true) },
            action = if (!aiReady && onSetupAi != null) ({ TextButton(onClick = onSetupAi) { Text(stringResource(R.string.ai_setup)) } }) else null)
        ModeCard(Icons.Rounded.PhoneAndroid, stringResource(R.string.mode_offline), stringResource(R.string.mode_offline_d),
            selected = !useAi || !aiReady, enabled = true, compact = compact, onClick = { onChange(false) })
    }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, desc: String, selected: Boolean, enabled: Boolean, compact: Boolean,
                     onClick: () -> Unit, action: (@Composable () -> Unit)? = null) {
    val x = LocalExtra.current
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        color = if (selected) cs.primaryContainer.copy(alpha = 0.45f) else cs.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else x.cardBorder),
        modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton; this.selected = selected }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (enabled) x.gold else cs.outline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) cs.onSurface else cs.onSurfaceVariant)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (action != null) action() else RadioButton(selected, null, enabled = enabled)
        }
    }
}

/** Checking a page reading: one pass, two passes (without, then with tashkeel), or a second pass only for doubtful pages. */
@Composable
fun ScanModePicker(mode: Int, onChange: (Int) -> Unit, reader: String? = null, onReader: ((String) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.scan_check), style = MaterialTheme.typography.labelLarge)
        listOf(
            com.werd.app.sources.TwoPass.SINGLE to (R.string.scan_single to R.string.scan_single_d),
            com.werd.app.sources.TwoPass.DOUBTFUL to (R.string.scan_doubtful to R.string.scan_doubtful_d),
            com.werd.app.sources.TwoPass.TWO to (R.string.scan_two to R.string.scan_two_d),
        ).forEach { (m, labels) ->
            ModeCard(if (m == com.werd.app.sources.TwoPass.SINGLE) Icons.Rounded.PhoneAndroid else Icons.Rounded.AutoAwesome,
                stringResource(labels.first), stringResource(labels.second), selected = mode == m, enabled = true, compact = true,
                onClick = { onChange(m) })
        }
        // the second reader: any reader, not only the built-in one
        if (mode != com.werd.app.sources.TwoPass.SINGLE && reader != null && onReader != null) CheckReaderPicker(reader, onReader)
    }
}
