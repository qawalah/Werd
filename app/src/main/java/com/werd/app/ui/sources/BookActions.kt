package com.werd.app.ui.sources

import androidx.compose.material.icons.rounded.ArrowDropDown
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.ai.AiClient
import com.werd.app.cloud.CloudException
import com.werd.app.data.Source
import com.werd.app.data.SourceItem
import com.werd.app.sources.BookFile
import com.werd.app.sources.SourceWorker
import com.werd.app.sources.Sources
import com.werd.app.ui.components.CountStepper
import com.werd.app.ui.theme.LocalExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Writes the book to the cache and opens the share sheet (WhatsApp, Telegram, Drive…). */
fun shareBook(context: Context, source: Source, items: List<SourceItem>) {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val f = File(dir, BookFile.fileName(source.title))
    f.writeText(BookFile.toJson(source, items).toString(1))
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).setType(BookFile.MIME).putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, source.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, source.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Human text for a failed server call. */
@Composable
fun cloudError(e: Throwable?): String = when ((e as? CloudException)?.code) {
    "name_taken" -> stringResource(R.string.acct_err_taken)
    "wrong_password" -> stringResource(R.string.acct_err_wrong)
    "weak_password" -> stringResource(R.string.acct_err_weak)
    "current_password" -> stringResource(R.string.acct_err_current)
    "no_password" -> stringResource(R.string.acct_err_no_password)
    else -> cloudKindError(e)
}

@Composable
private fun cloudKindError(e: Throwable?): String = when ((e as? CloudException)?.kind) {
    null -> stringResource(R.string.cloud_err_server)
    CloudException.Kind.NOT_CONFIGURED -> stringResource(R.string.cloud_soon)
    CloudException.Kind.NETWORK -> stringResource(R.string.cloud_err_network)
    CloudException.Kind.NOT_FOUND -> stringResource(R.string.cloud_err_not_found)
    CloudException.Kind.FORBIDDEN -> stringResource(R.string.cloud_err_parent_only)
    CloudException.Kind.CONFLICT -> if ((e as CloudException).body?.optString("code") == "last_parent") stringResource(R.string.cloud_err_last_parent)
        else stringResource(R.string.cloud_err_conflict)
    CloudException.Kind.RATE_LIMIT -> stringResource(R.string.cloud_err_busy)
    CloudException.Kind.TOO_LARGE -> stringResource(R.string.cloud_err_too_large)
    else -> stringResource(R.string.cloud_err_server)
}

/**
 * Re-read pages [startFrom]..[startTo] — with a stronger model, the other provider, or on the phone.
 * What was found there before is replaced; proofread ticks survive where the text is unchanged.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RescanDialog(app: WerdApp, source: Source, startFrom: Int, startTo: Int, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val pages = source.pageCount.coerceAtLeast(1)
    var from by remember { mutableIntStateOf(startFrom.coerceIn(1, pages)) }
    var to by remember { mutableIntStateOf(startTo.coerceIn(1, pages)) }
    fun keyOf(p: String) = if (p == AiClient.CLAUDE) settings.aiKeyClaude else if (p == AiClient.GEMINI) settings.aiKeyGemini else "x"
    var provider by remember {
        mutableStateOf(when {
            keyOf(settings.aiProvider).isNotBlank() -> settings.aiProvider
            settings.aiKeyGemini.isNotBlank() -> AiClient.GEMINI
            settings.aiKeyClaude.isNotBlank() -> AiClient.CLAUDE
            else -> Sources.OFFLINE
        })
    }
    var model by remember(provider) {
        mutableStateOf(when (provider) { AiClient.CLAUDE -> settings.aiModelClaude; AiClient.GEMINI -> settings.aiModelGemini; else -> "" })
    }
    var models by remember(provider) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(provider) {
        if (provider != Sources.OFFLINE && keyOf(provider).isNotBlank())
            models = runCatching { AiClient.create(provider, keyOf(provider), "").listModels() }.getOrDefault(emptyList()).take(8)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rescan_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rescan_from), Modifier.weight(1f))
                    CountStepper(from, { from = it.coerceIn(1, pages); if (to < from) to = from })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rescan_to), Modifier.weight(1f))
                    CountStepper(to, { to = it.coerceIn(from, pages) })
                }
                Text(stringResource(R.string.mode_q), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AiClient.GEMINI to "Gemini", AiClient.CLAUDE to "Claude").forEach { (p, label) ->
                        FilterChip(provider == p, { provider = p }, enabled = keyOf(p).isNotBlank(), label = { Text(label) },
                            leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp)) })
                    }
                    FilterChip(provider == Sources.OFFLINE, { provider = Sources.OFFLINE }, label = { Text(stringResource(R.string.mode_offline)) },
                        leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(16.dp)) })
                }
                if (provider != Sources.OFFLINE) {
                    OutlinedTextField(model, { model = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ai_model)) },
                        placeholder = { Text(if (provider == AiClient.CLAUDE) AiClient.DEFAULT_CLAUDE_MODEL else AiClient.DEFAULT_GEMINI_MODEL) })
                    if (models.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        models.forEach { m -> SuggestionChip({ model = m }, label = { Text(m, style = MaterialTheme.typography.labelSmall) }) }
                    }
                    Text(stringResource(R.string.rescan_model_hint), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.gold)
                }
                Text(stringResource(R.string.rescan_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch { SourceWorker.rescan(context, source.id, from, to, provider, model); onStarted() }
            }) { Text(stringResource(R.string.rescan_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Share a fully proofread book with everyone through the library. */
@Composable
fun PublishDialog(app: WerdApp, source: Source, items: List<SourceItem>, onDismiss: () -> Unit, onProofread: () -> Unit,
                  onPublished: () -> Unit) {
    val scope = rememberCoroutineScope()
    val left = items.count { !it.checked }
    var author by remember { mutableStateOf(source.author) }
    var name by remember { mutableStateOf(app.cloud.store.value.displayName) }
    val inFamily = app.cloud.store.value.inFamily
    var familyOnly by remember { mutableStateOf(false) }
    var madhhab by remember { mutableStateOf("") }
    var madhhabMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val errText = cloudError(error)
    if (left > 0) {
        AlertDialog(onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.lib_publish)) },
            text = { Text(stringResource(R.string.lib_publish_needs_proof, left, items.size)) },
            confirmButton = { Button(onClick = { onDismiss(); onProofread() }) { Text(stringResource(R.string.proof_title)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.lib_publish)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.lib_publish_body, items.size), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(author, { author = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.lib_author)) })
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.cloud_your_name)) })
                // madhhab, so readers can filter the library
                Box {
                    OutlinedTextField(madhhabLabel(madhhab), {}, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.madhhab)) },
                        trailingIcon = { IconButton(onClick = { madhhabMenu = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.ArrowDropDown, null) } })
                    // the whole field opens the list
                    Box(Modifier.matchParentSize().clickable { madhhabMenu = true })
                    DropdownMenu(madhhabMenu, { madhhabMenu = false }) {
                        (listOf("") + com.werd.app.cloud.MADHHABS).forEach { m ->
                            DropdownMenuItem(text = { Text(madhhabLabel(m)) }, onClick = { madhhab = m; madhhabMenu = false })
                        }
                    }
                }
                // who sees it
                Text(stringResource(R.string.lib_share_with), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().clickable { familyOnly = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!familyOnly, { familyOnly = false })
                    Column { Text(stringResource(R.string.lib_vis_public)); Text(stringResource(R.string.lib_vis_public_d),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = inFamily) { familyOnly = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(familyOnly, { familyOnly = true }, enabled = inFamily)
                    Column { Text(stringResource(R.string.lib_vis_family)); Text(stringResource(if (inFamily) R.string.lib_vis_family_d else R.string.lib_vis_family_none),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(stringResource(R.string.lib_publish_privacy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != null) Text(errText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    val r = runCatching {
                        withContext(Dispatchers.IO) {
                            if (name.trim() != app.cloud.store.value.displayName) {
                                if (app.cloud.store.value.signedIn) app.cloud.setDisplayName(name) else app.cloud.ensureAccount(name)
                            }
                            app.cloud.publish(source.id, author, if (familyOnly) "family" else "public", madhhab)
                        }
                    }
                    busy = false
                    r.onSuccess { onPublished() }.onFailure { error = it }
                }
            }) { Text(stringResource(R.string.lib_publish_go)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
