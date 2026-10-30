package com.werd.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.InsertChart
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.werd.app.R
import com.werd.app.WerdApp
import com.werd.app.ui.editor.EditorScreen
import com.werd.app.ui.home.HomeScreen
import com.werd.app.ui.reader.ReaderScreen
import com.werd.app.ui.reminders.ReminderEditorScreen
import com.werd.app.ui.reminders.RemindersScreen
import com.werd.app.ui.settings.SettingsScreen
import com.werd.app.ui.stats.StatsScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Routes {
    const val HOME = "home"
    const val STATS = "stats"
    const val REMINDERS = "reminders"
    const val SETTINGS = "settings"
    const val SOURCES = "sources"
    fun source(id: Long) = "source/$id"
    fun proofread(id: Long, page: Int = 0) = "proof/$id/$page"
    fun reader(id: Long) = "reader/$id"
    fun editor(id: Long) = "editor/$id"
    fun reminder(werdId: Long, id: Long, groupId: Long = 0) = "reminder/$werdId/$groupId/$id"
}

/** Holds text handed over from share / file import until the editor picks it up. */
object PendingImport {
    var text: String? = null
    /** A shared/opened photo or PDF to run text recognition on: (uri, mime type). */
    var media: Pair<android.net.Uri, String>? = null
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

@Composable
fun WerdNavHost(activity: MainActivity, app: WerdApp) {
    val nav = rememberNavController()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val request by activity.navRequests.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val arabic = currentIsArabic()

    LaunchedEffect(request) {
        when (val r = request) {
            is NavRequest.OpenWerd -> nav.navigate(Routes.reader(r.id)) { launchSingleTop = true }
            is NavRequest.Import -> { PendingImport.text = r.text; nav.navigate(Routes.editor(0)) }
            is NavRequest.ImportMedia -> { PendingImport.media = r.uri to r.mime; nav.navigate(Routes.editor(0)) }
            is NavRequest.OpenSource -> nav.navigate(Routes.source(r.id)) { launchSingleTop = true }
            is NavRequest.ImportBook -> {
                val id = runCatching {
                    withContext(Dispatchers.IO) { com.werd.app.sources.BookFile.import(app.db.dao(), com.werd.app.sources.BookFile.parse(r.text)) }
                }.getOrNull()
                if (id != null) withContext(Dispatchers.Main.immediate) { nav.navigate(Routes.source(id)) { launchSingleTop = true } }
            }
            null -> Unit
        }
        if (request != null) activity.navRequests.value = null
    }

    if (!settings.onboarded) {
        OnboardingDialog(
            onPick = { tag ->
                scope.launch {
                    app.repo.seedIfNeeded(arabic = tag == "ar" || (tag.isEmpty() && arabic))
                    app.settings.update { it.copy(onboarded = true, language = tag) }
                    activity.setLanguage(tag)
                }
            },
        )
    }

    val tabs = listOf(
        Tab(Routes.HOME, R.string.tab_home, Icons.Rounded.Home),
        Tab(Routes.SOURCES, R.string.tab_sources, Icons.AutoMirrored.Rounded.LibraryBooks),
        Tab(Routes.STATS, R.string.tab_stats, Icons.Rounded.InsertChart),
        Tab(Routes.REMINDERS, R.string.tab_reminders, Icons.Rounded.NotificationsActive),
        Tab(Routes.SETTINGS, R.string.tab_settings, Icons.Rounded.Settings),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { nav.navigateTab(t.route) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(stringResource(t.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(bottom = if (showBar) pad.calculateBottomPadding() else 0.dp)) {
            composable(Routes.HOME) {
                HomeScreen(
                    app = app,
                    onOpen = { nav.navigate(Routes.reader(it)) },
                    onEdit = { nav.navigate(Routes.editor(it)) },
                    onNew = { nav.navigate(Routes.editor(0)) },
                    onGroupReminder = { gid -> nav.navigate(Routes.reminder(0, 0, gid)) },
                )
            }
            composable(Routes.SOURCES) {
                com.werd.app.ui.sources.SourcesScreen(app,
                    onOpen = { nav.navigate(Routes.source(it)) },
                    onSettings = { nav.navigateTab(Routes.SETTINGS) },
                    onOpenWerd = { nav.navigate(Routes.reader(it)) })
            }
            composable("source/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                com.werd.app.ui.sources.SourceDetailScreen(app, e.arguments?.getLong("id") ?: 0,
                    onBack = { nav.popBackStack() },
                    onOpenWerd = { nav.navigate(Routes.reader(it)) },
                    onProofread = { id, page -> nav.navigate(Routes.proofread(id, page)) })
            }
            composable("proof/{id}/{page}", arguments = listOf(navArgument("id") { type = NavType.LongType },
                navArgument("page") { type = NavType.IntType })) { e ->
                com.werd.app.ui.sources.ProofreadScreen(app, e.arguments?.getLong("id") ?: 0, e.arguments?.getInt("page") ?: 0,
                    onBack = { nav.popBackStack() },
                    onOpenWerd = { nav.navigate(Routes.reader(it)) })
            }
            composable(Routes.STATS) { StatsScreen(app, onOpen = { nav.navigate(Routes.reader(it)) }, onFamily = { nav.navigate("family") }) }
            composable("family") {
                com.werd.app.ui.family.FamilyScreen(app, onBack = { nav.popBackStack() },
                    onMember = { m -> nav.navigate("member/${android.net.Uri.encode(m.userId)}?name=${android.net.Uri.encode(m.displayName)}") })
            }
            composable("member/{id}?name={name}", arguments = listOf(navArgument("id") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" })) { e ->
                com.werd.app.ui.family.MemberScreen(app, e.arguments?.getString("id").orEmpty(), e.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() })
            }
            composable(Routes.REMINDERS) {
                RemindersScreen(app,
                    onEdit = { w, r -> nav.navigate(Routes.reminder(w, r)) },
                    onEditGroup = { g, r -> nav.navigate(Routes.reminder(0, r, g)) })
            }
            composable(Routes.SETTINGS) { SettingsScreen(app, activity) }
            composable("reader/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments?.getLong("id") ?: 0
                ReaderScreen(app, id,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate(Routes.editor(id)) },
                    onReminders = { nav.navigate(Routes.reminder(id, 0)) })
            }
            composable("editor/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments?.getLong("id") ?: 0
                EditorScreen(app, activity, id,
                    onBack = { nav.popBackStack() },
                    onSaved = { newId ->
                        nav.popBackStack()
                        if (id == 0L) nav.navigate(Routes.reader(newId))
                    },
                    onReminder = { w, r -> nav.navigate(Routes.reminder(w, r)) },
                    onGroupReminder = { g, r -> nav.navigate(Routes.reminder(0, r, g)) })
            }
            composable(
                "reminder/{werdId}/{groupId}/{id}",
                arguments = listOf(navArgument("werdId") { type = NavType.LongType }, navArgument("groupId") { type = NavType.LongType },
                    navArgument("id") { type = NavType.LongType }),
            ) { e ->
                ReminderEditorScreen(app,
                    werdId = e.arguments?.getLong("werdId") ?: 0,
                    groupId = e.arguments?.getLong("groupId") ?: 0,
                    reminderId = e.arguments?.getLong("id") ?: 0,
                    onBack = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun currentIsArabic(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar"

@Composable
private fun OnboardingDialog(onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        shape = RoundedCornerShape(28.dp),
        title = {
            Column(Modifier.fillMaxWidth()) {
                Text("ورد · Werd", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("اختر لغة التطبيق · Choose app language", style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onPick("ar") }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("العربية") }
                OutlinedButton(onClick = { onPick("en") }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("English") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick("") }) { Text("System · النظام") }
        },
    )
}
