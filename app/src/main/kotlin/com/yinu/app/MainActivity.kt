package com.yinu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.yinu.app.records.RecordsViewModel
import com.yinu.app.sync.CloudSync
import com.yinu.app.sync.LocalCloudSync
import javax.inject.Inject
import kotlinx.coroutines.delay
import com.yinu.designsystem.YinuTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val records: RecordsViewModel by viewModels()
    @Inject internal lateinit var cloud: CloudSync

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, 0xFFF7F7F7.toInt()),
        )
        val appearance = AppearancePreferences(this)
        val systemBars = WindowCompat.getInsetsController(window, window.decorView)
        cloud.schedule()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    cloud.sync()
                    delay(30_000)
                }
            }
        }
        setContent {
            val savedOptions by appearance.options.collectAsStateWithLifecycle(initialValue = null)
            val options = savedOptions ?: return@setContent
            val darkTheme = when (options.mode) {
                AppearanceMode.System -> isSystemInDarkTheme()
                AppearanceMode.Light -> false
                AppearanceMode.Dark -> true
            }
            YinuTheme(darkTheme, lightweightBlur = options.lightweightBlur, monet = options.monet) {
                CompositionLocalProvider(LocalCloudSync provides cloud) {
                YinuApp(
                    records = records,
                    appearance = options,
                    onThemeChange = { saveAppearance { appearance.setMode(it) } },
                    onBlurChange = { saveAppearance { appearance.setLightweightBlur(it) } },
                    onMonetChange = { saveAppearance { appearance.setMonet(it) } },
                    onMenuLabelsChange = { saveAppearance { appearance.setMenuLabels(it) } },
                    darkTheme = darkTheme,
                    onSystemBarsDark = {
                        systemBars.isAppearanceLightStatusBars = !it
                        systemBars.isAppearanceLightNavigationBars = !it
                    },
                    onExit = { if (!moveTaskToBack(false)) finish() },
                )
                }
            }
        }
    }

    private fun saveAppearance(change: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                change()
            } catch (_: IOException) {
                records.notify("无法保存外观设置")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        records.refreshReminders()
    }

    private fun receiveIntent(intent: Intent) {
        try {
            when (intent.getStringExtra(QuickActionExtra)) {
                "text" -> records.prepareQuickAction("text")
                "image" -> records.prepareQuickAction("image")
            }
            intent.getStringExtra(LocateRecordExtra)?.takeIf { it.isNotBlank() && it.length <= 128 }
                ?.let(records::requestLocation)
        } catch (_: RuntimeException) {
            records.notify("无法读取快捷入口，请重新打开")
        }
        receiveShare(intent)
    }

    private fun receiveShare(intent: Intent) {
        if (!IncomingShare.accepts(intent.action)) return
        val share = try {
            IncomingShare.from(intent, "$packageName.attachments")
        } catch (_: RuntimeException) {
            null
        }
        if (share == null) records.notify("无法读取分享内容，请从来源应用重新分享")
        else if (intent.action == Intent.ACTION_VIEW) records.saveIncomingShare(share)
        else records.stageIncomingShare(share)
    }
}
