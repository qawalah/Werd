package com.werd.app.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.werd.app.R

/** The family server's own pages, opened in the browser: its start page and the admin dashboard. */
object ServerPages {
    fun open(context: Context, base: String, path: String = "/admin"): Boolean {
        if (base.isBlank()) return false
        val url = base.trimEnd('/') + path
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
    }

    /** A launcher shortcut (long-press the Werd icon) to the server dashboard, while a server is set up. */
    fun updateShortcut(context: Context, base: String) {
        runCatching {
            if (base.isBlank()) { ShortcutManagerCompat.removeDynamicShortcuts(context, listOf("server_admin")); return }
            val s = ShortcutInfoCompat.Builder(context, "server_admin")
                .setShortLabel(context.getString(R.string.server_dashboard))
                .setLongLabel(context.getString(R.string.server_dashboard))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_admin))
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(base.trimEnd('/') + "/admin")))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, s)
        }
    }
}
