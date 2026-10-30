package com.werd.app.ui.family

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.cloud.Family
import com.werd.app.cloud.Member
import com.werd.app.ui.sources.cloudError
import com.werd.app.ui.theme.LocalExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Family: members' progress today, sending awrad to each other, the invite code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(app: WerdApp, onBack: () -> Unit, onMember: (Member) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud by app.cloud.store.flow.collectAsStateWithLifecycle()
    var family by remember { mutableStateOf<Family?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var sendTo by remember { mutableStateOf<Member?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<Member?>(null) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val sentMsg = stringResource(R.string.fam_sent)
    val copiedMsg = stringResource(R.string.fam_code_copied)
    val inviteText = stringResource(R.string.fam_invite_text, cloud.familyName, cloud.inviteCode)
    val errText = cloudError(error)

    fun refresh() {
        if (!cloud.configured || !cloud.inFamily) return
        loading = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { app.cloud.syncProgress(); app.cloud.refreshFamily() } }
                .onSuccess { family = it }.onFailure { error = it }
            loading = false
        }
    }
    LaunchedEffect(cloud.inFamily, cloud.configured) { refresh() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (cloud.inFamily) cloud.familyName else stringResource(R.string.fam_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (cloud.inFamily) {
                        IconButton(onClick = ::refresh, enabled = !loading) { Icon(Icons.Rounded.Refresh, stringResource(R.string.fam_refresh)) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.server_dashboard)) },
                                    leadingIcon = { Icon(Icons.Rounded.Dashboard, null) },
                                    onClick = { menu = false; com.werd.app.cloud.ServerPages.open(context, cloud.currentUrl, "/admin") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.fam_leave), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; confirmLeave = true })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        when {
            !cloud.configured -> Soon(Modifier.padding(pad))
            !cloud.inFamily -> Setup(app, Modifier.padding(pad))
            else -> LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    InviteCard(cloud.inviteCode,
                        onCopy = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("code", cloud.inviteCode))
                            scope.launch { snackbar.showSnackbar(copiedMsg) }
                        },
                        onShare = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, inviteText), null))
                        })
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.fam_share), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.fam_share_d), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(cloud.shareProgress, { v -> scope.launch { runCatching { withContext(Dispatchers.IO) { app.cloud.setSharing(v) } }; refresh() } })
                    }
                }
                if (loading && family == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (error != null) item { Text(errText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val members = family?.members.orEmpty().sortedByDescending { it.isMe }
                val parent = family?.iAmParent == true
                if (family != null) item {
                    Text(stringResource(if (parent) R.string.fam_you_parent else R.string.fam_you_child),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
                items(members, key = { it.userId }) { m ->
                    MemberCard(m, iAmParent = parent, onSend = { sendTo = m },
                        onHistory = { onMember(m) },
                        onRole = { toParent ->
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { app.cloud.setRole(m.userId, toParent) } }.onFailure { error = it }
                                refresh()
                            }
                        },
                        onRemove = { confirmRemove = m })
                }
                if (family != null && members.size <= 1) item {
                    Text(stringResource(R.string.fam_alone), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }

    confirmRemove?.let { m ->
        AlertDialog(onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.fam_remove_q, m.displayName)) },
            text = { Text(stringResource(R.string.fam_remove_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    scope.launch { runCatching { withContext(Dispatchers.IO) { app.cloud.removeMember(m.userId) } }.onFailure { error = it }; refresh() }
                }) { Text(stringResource(R.string.fam_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } })
    }
    sendTo?.let { m ->
        SendWerdDialog(app, m, canAuto = family?.iAmParent == true && !m.isParent && !m.isMe, onDismiss = { sendTo = null }, onSent = {
            sendTo = null; scope.launch { snackbar.showSnackbar(sentMsg.format(m.displayName)) }
        })
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        title = { Text(stringResource(R.string.fam_leave_q)) },
        text = { Text(stringResource(R.string.fam_leave_msg)) },
        confirmButton = {
            TextButton(onClick = {
                confirmLeave = false
                scope.launch { runCatching { withContext(Dispatchers.IO) { app.cloud.leaveFamily() } }.onFailure { error = it }; family = null }
            }) { Text(stringResource(R.string.fam_leave), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun Soon(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Diversity3, null, Modifier.size(56.dp), tint = LocalExtra.current.gold)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.fam_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.fam_soon_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Not in a family yet: create one, or join with a code. */
@Composable
private fun Setup(app: WerdApp, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val cloud by app.cloud.store.flow.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(cloud.displayName) }
    var familyName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var share by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var signIn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val errText = cloudError(error)
    val x = LocalExtra.current
    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch { runCatching { withContext(Dispatchers.IO) { block() } }.onFailure { error = it }; busy = false }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Diversity3, null, Modifier.size(40.dp), tint = x.gold); Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.fam_intro), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.cloud_your_name)) }, supportingText = { Text(stringResource(R.string.fam_name_hint)) })
        if (!cloud.signedIn) {
            OutlinedTextField(password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.acct_password_optional)) }, supportingText = { Text(stringResource(R.string.acct_password_why)) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                isError = password.isNotEmpty() && password.length < 6)
            TextButton(onClick = { signIn = true }) { Text(stringResource(R.string.acct_have_account)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(share, { share = it })
            Text(stringResource(R.string.fam_share_consent), style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { share = !share })
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), border = BorderStroke(1.dp, x.cardBorder)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.fam_create), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(familyName, { familyName = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.fam_family_name)) })
                Button(enabled = !busy && name.isNotBlank() && familyName.isNotBlank() && (password.isEmpty() || password.length >= 6), modifier = Modifier.fillMaxWidth(),
                    onClick = { run { app.cloud.createFamily(familyName, name, share, password) } }) { Text(stringResource(R.string.fam_create)) }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), border = BorderStroke(1.dp, x.cardBorder)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.fam_join), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(code, { code = it.uppercase().take(12) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.fam_code)) })
                OutlinedButton(enabled = !busy && name.isNotBlank() && code.isNotBlank() && (password.isEmpty() || password.length >= 6), modifier = Modifier.fillMaxWidth(),
                    onClick = { run { app.cloud.joinFamily(code, name, share, password) } }) { Text(stringResource(R.string.fam_join)) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(errText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.fam_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (signIn) com.werd.app.ui.settings.SignInDialog(app, onDone = { signIn = false }, onDismiss = { signIn = false })
}

@Composable
private fun InviteCard(code: String, onCopy: () -> Unit, onShare: () -> Unit) {
    val x = LocalExtra.current
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.fam_invite), style = MaterialTheme.typography.labelLarge, color = x.gold)
                Text(code, style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 3.sp), fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.fam_copy)) }
            FilledTonalButton(onClick = onShare) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.book_share)) }
        }
    }
}

@Composable
private fun MemberCard(m: Member, iAmParent: Boolean, onSend: () -> Unit, onHistory: () -> Unit,
                       onRole: (Boolean) -> Unit, onRemove: () -> Unit) {
    val x = LocalExtra.current
    var open by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val p = m.progress
    val today = p != null && p.day == LocalDate.now().toString()
    Surface(onClick = { open = !open }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, x.cardBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = x.gold.copy(alpha = 0.18f), modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(m.displayName.trim().take(1).ifEmpty { "؟" }, style = MaterialTheme.typography.titleLarge, color = x.gold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (m.isMe) stringResource(R.string.fam_me, m.displayName) else m.displayName,
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = CircleShape, color = if (m.isParent) x.gold.copy(alpha = 0.18f) else MaterialTheme.colorScheme.secondaryContainer) {
                            Text(stringResource(if (m.isParent) R.string.fam_role_parent else R.string.fam_role_child),
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    Text(when {
                        !m.sharing || p == null -> stringResource(R.string.fam_not_sharing)
                        !today -> stringResource(R.string.fam_not_today)
                        else -> stringResource(R.string.fam_today, p.done, p.due)
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p != null && m.sharing && p.streak > 0) {
                    Text("🔥 ${p.streak}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(6.dp))
                }
                if (!m.isMe) IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.fam_send)) }
                if (iAmParent || m.isMe) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_options)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.fam_history)) },
                            leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null) }, onClick = { menu = false; onHistory() })
                        if (iAmParent && !m.isMe) {
                            DropdownMenuItem(text = { Text(stringResource(if (m.isParent) R.string.fam_make_child else R.string.fam_make_parent)) },
                                leadingIcon = { Icon(Icons.Rounded.SupervisorAccount, null) }, onClick = { menu = false; onRole(!m.isParent) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.fam_remove), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.PersonRemove, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onRemove() })
                        }
                    }
                }
            }
            if (p != null && m.sharing && today && p.due > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.due },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), trackColor = MaterialTheme.colorScheme.outlineVariant)
            if (open && p != null && m.sharing && today) p.werds.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (w.completed) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(18.dp),
                        tint = if (w.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    Text(w.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${w.done}/${w.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Pick one of my awrad and send it to [to]. */
@Composable
internal fun SendWerdDialog(app: WerdApp, to: Member, canAuto: Boolean, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dash by app.repo.dashboard.collectAsStateWithLifecycle(initialValue = null)
    var picked by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(canAuto) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val errText = cloudError(error)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.fam_send_to, to.displayName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                dash?.werds.orEmpty().forEach { w ->
                    Row(Modifier.fillMaxWidth().clickable { picked = w.werd.id }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(picked == w.werd.id, { picked = w.werd.id })
                        Column(Modifier.weight(1f)) {
                            Text(w.werd.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.lines_n, w.items.size), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(note, { note = it.take(300) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fam_note)) })
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.fam_send_settings), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (canAuto) Row(Modifier.fillMaxWidth().clickable { auto = !auto }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.fam_auto_add), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.fam_auto_add_d), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(auto, { auto = it })
                }
                if (error != null) Text(errText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(enabled = !busy && picked != null, onClick = {
                busy = true; error = null
                scope.launch {
                    val r = runCatching { withContext(Dispatchers.IO) { app.cloud.sendWerd(to.userId, picked!!, note, autoAdd = canAuto && auto) } }
                    busy = false
                    r.onSuccess { onSent() }.onFailure { error = it }
                }
            }) { Text(stringResource(R.string.fam_send)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
