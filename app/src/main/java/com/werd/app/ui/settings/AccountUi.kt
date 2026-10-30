package com.werd.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.ui.sources.cloudError
import com.werd.app.ui.theme.LocalExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's account on the family server: who it is signed in as, a password so the same person can come back to
 * the account from another phone (instead of becoming a new member with the same name), signing in, and signing out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountCard(app: WerdApp) {
    val c by app.cloud.store.flow.collectAsStateWithLifecycle()
    if (!c.configured) return
    var dialog by remember { mutableStateOf<String?>(null) }   // signin | create | password | signout | others
    LaunchedEffect(c.token) { if (c.signedIn) withContext(Dispatchers.IO) { runCatching { app.cloud.refreshAccount() } } }
    val x = LocalExtra.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.acct_title), style = MaterialTheme.typography.labelLarge)
                    Text(if (c.signedIn) stringResource(R.string.acct_signed_in_as, c.displayName.ifBlank { "—" }) else stringResource(R.string.acct_not_signed_in),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (c.signedIn && !c.hasPassword)
                Text(stringResource(R.string.acct_no_password_d), style = MaterialTheme.typography.bodySmall, color = x.gold)
            else if (c.signedIn)
                Text(stringResource(R.string.acct_password_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else
                Text(stringResource(R.string.acct_signin_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (c.signedIn) {
                    FilledTonalButton(onClick = { dialog = "password" }) {
                        Icon(Icons.Rounded.Key, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (c.hasPassword) R.string.acct_change_password else R.string.acct_add_password))
                    }
                    OutlinedButton(onClick = { dialog = "signin" }) { Text(stringResource(R.string.acct_switch)) }
                    if (c.hasPassword) {
                        TextButton(onClick = { dialog = "others" }) { Text(stringResource(R.string.acct_signout_others)) }
                        TextButton(onClick = { dialog = "signout" }) { Text(stringResource(R.string.acct_signout)) }
                    }
                } else {
                    FilledTonalButton(onClick = { dialog = "signin" }) { Text(stringResource(R.string.acct_signin)) }
                    OutlinedButton(onClick = { dialog = "create" }) { Text(stringResource(R.string.acct_create)) }
                }
            }
        }
    }
    when (dialog) {
        "signin" -> SignInDialog(app, onDone = { reset -> dialog = if (reset) "password" else null }, onDismiss = { dialog = null })
        "create" -> CreateAccountDialog(app, onDismiss = { dialog = null })
        "password" -> PasswordDialog(app, onDismiss = { dialog = null })
        "others", "signout" -> ConfirmDialog(app, all = dialog == "others", onDismiss = { dialog = null })
    }
}

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } })
}

/** Runs a server call with a busy bar and the server's error in words. */
private class Busy {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
}

@Composable
private fun rememberRunner(): Pair<Busy, (suspend () -> Unit, () -> Unit) -> Unit> {
    val b = remember { Busy() }
    val scope = rememberCoroutineScope()
    return b to { block, done ->
        b.busy = true; b.error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { done() }.onFailure { b.error = it }
            b.busy = false
        }
    }
}

@Composable
private fun Status(b: Busy) {
    if (b.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    b.error?.let { Text(cloudError(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** Sign in with a name and password, or with a one-time code from the server's owner ("forgot password"). */
@Composable
fun SignInDialog(app: WerdApp, onDone: (needsNewPassword: Boolean) -> Unit, onDismiss: () -> Unit) {
    val c by app.cloud.store.flow.collectAsStateWithLifecycle()
    var byCode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(c.displayName) }
    var pw by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val (b, run) = rememberRunner()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.acct_signin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!byCode, { byCode = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.acct_by_password)) }
                    SegmentedButton(byCode, { byCode = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.acct_forgot)) }
                }
                if (!byCode) {
                    OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cloud_your_name)) })
                    PasswordField(pw, stringResource(R.string.acct_password)) { pw = it }
                } else {
                    Text(stringResource(R.string.acct_code_d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(code, { code = it.uppercase().take(10) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.acct_code)) }, placeholder = { Text("K7Q-4MX") })
                }
                if (c.signedIn) Text(stringResource(R.string.acct_switch_d, c.displayName), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Status(b)
            }
        },
        confirmButton = {
            TextButton(enabled = !b.busy && (if (byCode) code.length >= 6 else name.isNotBlank() && pw.isNotEmpty()), onClick = {
                var reset = false
                run({ reset = app.cloud.signIn(name, pw, if (byCode) code else "").canReset }, { onDone(reset) })
            }) { Text(stringResource(R.string.acct_signin)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A new account whose name is reserved on the server, with a password. */
@Composable
private fun CreateAccountDialog(app: WerdApp, onDismiss: () -> Unit) {
    val c by app.cloud.store.flow.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(c.displayName) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val (b, run) = rememberRunner()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.acct_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.cloud_your_name)) }, supportingText = { Text(stringResource(R.string.acct_name_unique)) })
                PasswordField(pw, stringResource(R.string.acct_password)) { pw = it }
                PasswordField(pw2, stringResource(R.string.acct_password_again)) { pw2 = it }
                if (pw2.isNotEmpty() && pw != pw2) Text(stringResource(R.string.acct_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (pw.isNotEmpty() && pw.length < 6) Text(stringResource(R.string.acct_err_weak), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Status(b)
            }
        },
        confirmButton = {
            TextButton(enabled = !b.busy && name.isNotBlank() && pw.length >= 6 && pw == pw2,
                onClick = { run({ app.cloud.createAccount(name, pw) }, onDismiss) }) { Text(stringResource(R.string.acct_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Add a password (the name becomes the sign-in name) or change it. */
@Composable
private fun PasswordDialog(app: WerdApp, onDismiss: () -> Unit) {
    val c by app.cloud.store.flow.collectAsStateWithLifecycle()
    var canReset by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { canReset = runCatching { app.cloud.refreshAccount()?.canReset == true }.getOrDefault(false) } }
    val needCurrent = c.hasPassword && !canReset
    var cur by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val (b, run) = rememberRunner()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (c.hasPassword && !canReset) R.string.acct_change_password else R.string.acct_add_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.acct_add_password_d, c.displayName), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (needCurrent) PasswordField(cur, stringResource(R.string.acct_password_current)) { cur = it }
                PasswordField(pw, stringResource(R.string.acct_password_new)) { pw = it }
                PasswordField(pw2, stringResource(R.string.acct_password_again)) { pw2 = it }
                if (pw2.isNotEmpty() && pw != pw2) Text(stringResource(R.string.acct_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Status(b)
            }
        },
        confirmButton = {
            TextButton(enabled = !b.busy && pw.length >= 6 && pw == pw2 && (!needCurrent || cur.isNotEmpty()),
                onClick = { run({ app.cloud.setPassword(pw, cur) }, onDismiss) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(app: WerdApp, all: Boolean, onDismiss: () -> Unit) {
    val (b, run) = rememberRunner()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (all) R.string.acct_signout_others else R.string.acct_signout)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(if (all) R.string.acct_signout_others_d else R.string.acct_signout_d))
                Status(b)
            }
        },
        confirmButton = {
            TextButton(enabled = !b.busy, onClick = { run({ if (all) app.cloud.signOutOthers() else app.cloud.signOut() }, onDismiss) }) {
                Text(stringResource(if (all) R.string.acct_signout_others else R.string.acct_signout))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
