package dev.ahmedmohamed.hayaitts.app

import android.app.Application
import dev.ahmedmohamed.hayaitts.app.crash.CrashHandler
import dev.ahmedmohamed.hayaitts.app.di.appModule
import dev.ahmedmohamed.hayaitts.data.download.DownloadNotifications
import dev.ahmedmohamed.hayaitts.data.update.UpdateCheckWorker
import dev.ahmedmohamed.hayaitts.tts.HayaiTtsNudge
import dev.ahmedmohamed.hayaitts.tts.engine.ReadingDefaults
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import dev.ahmedmohamed.hayaitts.data.routing.LanguageSync

class HayaiTtsApplication : Application() {
    private fun isMainProcess(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P || getProcessName() == packageName

    override fun onCreate() {
        super.onCreate()
        // Install the uncaught-exception handler before anything else so
        // crashes during Koin startup, channel registration or worker
        // scheduling are caught and surfaced to the user instead of the
        // OS "app keeps stopping" dialog.
        CrashHandler.install(this)
        startKoin {
            androidContext(this@HayaiTtsApplication)
            modules(appModule)
        }
        // The voice download worker needs the channel before it can call
        // setForeground(); creating it on a cold start (when the worker may
        // run before any other component) avoids racing the system watchdog.
        DownloadNotifications.ensureChannel(this)
        // Phase 8b: ensure the TTS nudge channel exists so HayaiTtsService can
        // post the "speaking" notification immediately on first synthesis.
        HayaiTtsNudge.ensureChannel(this)
        // Phase 8c: schedule the 12h update poll. Idempotent via KEEP policy.
        UpdateCheckWorker.schedule(this)
        // Fork: warm the speech engines, then keep "languages shown" and the
        // Reading priority lists one set (also seeds them on first run). Main
        // process only: the :crash process must not write the same DataStore.
        if (isMainProcess()) {
            ReadingDefaults.warmInBackground(this)
            GlobalContext.get().get<LanguageSync>().start()
        }
    }
}
