package com.yinu.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.yinu.app.reminders.ReminderScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

@HiltAndroidApp
class YinuApplication : Application() {
    @Inject lateinit var reminders: ReminderScheduler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        reminders.watch(scope)
        scope.launch {
            File(noBackupFilesDir, "vosk-model-small-cn-0.22").deleteRecursively()
            File(noBackupFilesDir, "vosk-model-small-cn-0.22-installing").deleteRecursively()
            val cutoff = System.currentTimeMillis() - 86_400_000
            cacheDir.listFiles()?.filter {
                it.isFile && it.name.startsWith("voice-") && it.extension == "m4a" && it.lastModified() < cutoff
            }?.forEach { it.delete() }
            File(cacheDir, "long_exports").listFiles()?.filter {
                it.name.matches(Regex("[a-f0-9-]{36}")) && it.lastModified() < cutoff
            }?.forEach { directory -> directory.listFiles()?.forEach { it.delete() }; directory.delete() }
        }
    }
}
