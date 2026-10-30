package com.werd.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.werd.app.WerdApp
import com.werd.app.reminder.Notifier
import com.werd.app.ui.theme.WerdTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Things other parts of the app ask the navigation host to open. */
sealed interface NavRequest {
    data class OpenWerd(val id: Long) : NavRequest
    data class Import(val text: String) : NavRequest
    data class ImportMedia(val uri: Uri, val mime: String) : NavRequest
    data class OpenSource(val id: Long) : NavRequest
    /** A shared book file (.werdbook) to add to Sources. */
    data class ImportBook(val text: String) : NavRequest
}

/** Volume-key counting in the reader. */
object KeyBus {
    @Volatile var readerActive = false
    val events = MutableSharedFlow<Int>(extraBufferCapacity = 16)
}

class MainActivity : AppCompatActivity() {
    val navRequests = MutableStateFlow<NavRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = WerdApp.from(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val s by app.settings.flow.collectAsStateWithLifecycle()
            WerdTheme(s.theme, runCatching { com.werd.app.ui.theme.Palette.valueOf(s.palette) }.getOrDefault(com.werd.app.ui.theme.Palette.STANDARD)) {
                WerdNavHost(this, app)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val source = intent.getLongExtra(com.werd.app.sources.SourceWorker.EXTRA_SOURCE, -1)
        if (source > 0) { navRequests.value = NavRequest.OpenSource(source); return }
        val werd = intent.getLongExtra(Notifier.EXTRA_WERD, -1)
        if (werd > 0) {
            navRequests.value = NavRequest.OpenWerd(werd)
            Notifier.cancel(this, werd)
            return
        }
        val type = intent.type.orEmpty()
        val isMedia = type.startsWith("image/") || type == "application/pdf"
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                when {
                    isMedia && stream != null -> navRequests.value = NavRequest.ImportMedia(stream, type)
                    stream != null -> readUri(stream)?.let { textRequest(it) }
                    else -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { textRequest(it) }
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                val t = type.ifEmpty { contentResolver.getType(uri).orEmpty() }
                if (t.startsWith("image/") || t == "application/pdf") navRequests.value = NavRequest.ImportMedia(uri, t)
                else readUri(uri)?.let { textRequest(it) }
            }
        }
    }

    private fun textRequest(text: String) {
        navRequests.value = if (com.werd.app.sources.BookFile.looksLikeBook(text)) NavRequest.ImportBook(text) else NavRequest.Import(text)
    }

    fun readUri(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(com.werd.app.localeTag(tag)),
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = WerdApp.from(this)
        if (KeyBus.readerActive && app.settings.value.volumeKeys &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                KeyBus.events.tryEmit(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
