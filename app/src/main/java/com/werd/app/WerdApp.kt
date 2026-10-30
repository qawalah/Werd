package com.werd.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.werd.app.data.Repository
import com.werd.app.data.Settings
import com.werd.app.data.WerdDatabase
import com.werd.app.data.WidgetBridge
import com.werd.app.reminder.Notifier
import com.werd.app.reminder.ReminderScheduler
import com.werd.app.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class WerdApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db by lazy { WerdDatabase.create(this) }
    val settings by lazy { Settings(this) }
    val repo by lazy { Repository(this, db.dao(), settings, appScope) }
    val cloud by lazy { com.werd.app.cloud.CloudRepo(this) }
    val voice by lazy { com.werd.app.voice.VoiceStore(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifier.createChannels(this)
        com.werd.app.sources.SourceWorker.createChannel(this)
        com.werd.app.cloud.CloudSyncWorker.createChannel(this)
        if (cloud.store.value.configured && cloud.store.value.inFamily) com.werd.app.cloud.CloudSyncWorker.schedule(this)
        WidgetBridge.refresher = { ctx -> WidgetUpdater.requestUpdate(ctx) }
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osmdroid/tiles")
        }
        appScope.launch { ReminderScheduler.syncAll(this@WerdApp) }
    }

    companion object {
        lateinit var instance: WerdApp
            private set

        fun from(context: Context): WerdApp = context.applicationContext as WerdApp
    }
}

/** Context in the user's chosen app language (for notifications and widgets rendered outside activities). */
fun Context.localized(): Context {
    val lang = WerdApp.from(this).settings.value.language
    if (lang.isEmpty()) return this
    val cfg = Configuration(resources.configuration)
    cfg.setLocale(Locale.forLanguageTag(localeTag(lang)))
    return createConfigurationContext(cfg)
}

/** Arabic UI with Western digits, so counters and formatted text use the same numerals. */
fun localeTag(lang: String): String = if (lang == "ar") "ar-u-nu-latn" else lang

fun Context.isArabicUi(): Boolean = resources.configuration.locales[0].language == "ar"
