package com.werd.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.werd.app.cloud.WerdApi
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountDialogTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<WerdApp>()

    @Test fun accountCardAndPasswordDialogSettle() {
        val fake = FakeWerdServer().start()
        try {
            System.setProperty("http.keepAlive", "false"); WerdApi.retryBaseMs = 10
            runBlocking { app.cloud.store.update { it.copy(serverOverride = fake.url) }; app.cloud.ensureAccount("أبو حمزة") }
            compose.setContent { com.werd.app.ui.theme.WerdTheme(com.werd.app.data.ThemeMode.LIGHT) { com.werd.app.ui.settings.AccountCard(app) } }
            compose.waitForIdle()
            println("CARD IDLE")
            compose.onAllNodesWithText(app.getString(R.string.acct_add_password))[0].performClick()
            compose.waitForIdle()
            println("DIALOG IDLE")
        } finally { fake.stop() }
    }
}
