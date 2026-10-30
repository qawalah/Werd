package com.werd.app.ui.sources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.data.flagged
import com.werd.app.sources.Helpers
import com.werd.app.sources.Readers
import com.werd.app.sources.SourceWorker
import com.werd.app.sources.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The readers this phone can use now (the server's are asked for once). */
@Composable
fun rememberReaderChoices(): List<Pair<String, String>> {
    val context = LocalContext.current
    var list by remember { mutableStateOf(listOf(Readers.BUILTIN to Readers.label(context, Readers.BUILTIN))) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { runCatching { Readers.choices(context) }.getOrDefault(list) } }
    return list
}

/** The second reader of a two-pass scan: any reader on the phone, the server or the user's AI. */
@Composable
fun CheckReaderPicker(value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val choices = rememberReaderChoices()
    var open by remember { mutableStateOf(false) }
    val current = value.ifBlank { Readers.BUILTIN }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.scan_second_reader), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(choices.firstOrNull { it.first == current }?.second ?: Readers.label(context, current), modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(open, { open = false }) {
                choices.forEach { (spec, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onChange(if (spec == Readers.BUILTIN) "" else spec); open = false },
                        leadingIcon = { if (spec == current) Icon(Icons.Rounded.Check, null) })
                }
            }
        }
        Text(stringResource(R.string.scan_second_reader_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "Proofread with…" on a book already read: other readers read its pages again (optionally each item's part of the page
 * at a higher resolution) and the helpers add suggestions. The text is never replaced; only words to check are marked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProofreadWithDialog(app: WerdApp, src: Source, items: List<SourceItem>, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val choices = rememberReaderChoices()
    val hasPages = Sources.hasPages(src) && src.kind != Sources.KIND_TEXT
    val first = remember(src.id) { Readers.firstOf(context, src) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    // by default a reader other than the one that read the book
    LaunchedEffect(choices) { if (picked.isEmpty()) choices.firstOrNull { it.first != first }?.let { picked = setOf(it.first) } }
    var scope2 by remember { mutableStateOf(if (items.any { it.flagged }) "flagged" else "unchecked") }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf(src.pageCount.coerceAtLeast(1).toString()) }
    var hiRes by remember { mutableStateOf(false) }
    var quran by remember { mutableStateOf(true) }
    var spelling by remember { mutableStateOf(true) }
    val modelSpec = remember { Helpers.ContextModel.pick(context) }
    var useModel by remember { mutableStateOf(false) }
    val inScope = items.filter {
        when (scope2) { "all" -> true; "flagged" -> it.flagged; "pages" -> it.page in (from.toIntOrNull() ?: 1)..(to.toIntOrNull() ?: src.pageCount); else -> !it.checked }
    }
    val pages = inScope.map { it.page }.distinct().size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pw_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.pw_lead), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasPages) {
                    Text(stringResource(R.string.pw_readers), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        choices.forEach { (spec, label) ->
                            FilterChip(spec in picked, { picked = if (spec in picked) picked - spec else picked + spec },
                                label = { Text(label + if (spec == first) " · " + stringResource(R.string.pw_first) else "") })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(hiRes, { hiRes = it }); Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.pw_hires))
                            Text(stringResource(R.string.pw_hires_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(stringResource(R.string.pw_which), style = MaterialTheme.typography.labelLarge)
                listOf("unchecked" to R.string.pw_unchecked, "flagged" to R.string.pw_flagged, "all" to R.string.pw_all, "pages" to R.string.pw_pages).forEach { (k, l) ->
                    if (k == "flagged" && items.none { it.flagged }) return@forEach
                    if (k == "pages" && !hasPages) return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(scope2 == k, { scope2 = k }); Text(stringResource(l))
                    }
                }
                if (scope2 == "pages") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.rescan_from)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(to, { to = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.rescan_to)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Text(stringResource(R.string.pw_helpers), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(quran, { quran = it }); Text(stringResource(R.string.pw_quran)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(spelling, { spelling = it }); Text(stringResource(R.string.pw_spelling)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(useModel, { useModel = it }, enabled = modelSpec.isNotBlank())
                    Column {
                        Text(stringResource(R.string.pw_model))
                        Text(if (modelSpec.isBlank()) stringResource(R.string.pw_model_none) else stringResource(R.string.pw_model_d, Readers.label(context, modelSpec)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.pw_estimate, inScope.size, pages * picked.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(enabled = inScope.isNotEmpty() && (picked.isNotEmpty() || quran || spelling || useModel), onClick = {
                scope.launch {
                    SourceWorker.proofread(context, src.id, if (hasPages) picked.toList() else emptyList(), scope2,
                        from.toIntOrNull() ?: 1, to.toIntOrNull() ?: src.pageCount, hiRes && hasPages, quran, spelling, if (useModel) modelSpec else "")
                    onStarted()
                }
            }) { Text(stringResource(R.string.pw_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
