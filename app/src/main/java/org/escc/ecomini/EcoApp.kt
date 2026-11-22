package org.escc.ecomini

import android.app.Application
import android.content.ComponentCallbacks2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.escc.ecomini.engine.Engine
import org.escc.ecomini.files.Downloads
import org.escc.ecomini.files.Store

class EcoApp : Application() {
    lateinit var store: Store
        private set
    lateinit var engine: Engine
        private set
    lateinit var downloads: Downloads
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        // A GPU load that never finished means the driver crashed the app: use the CPU from now on.
        if (store.gpuAttempt) {
            store.gpuAttempt = false
            store.gpuBroken = true
        }
        engine = Engine(this, store)
        downloads = Downloads(this, store)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Free the model only when memory is genuinely short, not merely because the UI was hidden.
        val low = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (low) scope.launch { engine.release() }
    }
}
