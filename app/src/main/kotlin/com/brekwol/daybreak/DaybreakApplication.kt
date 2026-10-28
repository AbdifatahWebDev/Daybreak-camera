package com.brekwol.daybreak

import android.app.Application
import android.content.Context
import com.brekwol.daybreak.data.PhotoRepository
import com.brekwol.daybreak.data.SaveQueue
import com.brekwol.daybreak.data.SettingsRepository
import com.brekwol.daybreak.data.ThumbnailLoader
import com.brekwol.daybreak.engine.DaybreakEngine
import com.brekwol.daybreak.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DaybreakApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.updates.onAppStart()
    }
}

/** Plain constructor wiring; no DI framework in v0.1. */
class AppContainer(context: Context) {
    /** Lives as long as the process, so saves finish after the screen changes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = DaybreakEngine()
    val settings = SettingsRepository(context)
    val photos = PhotoRepository(context)
    val thumbnails = ThumbnailLoader(context, engine, photos)
    val saveQueue = SaveQueue(appScope, engine, photos, settings)
    val updates = UpdateManager(context, settings, appScope)
}

val Context.container: AppContainer
    get() = (applicationContext as DaybreakApplication).container
