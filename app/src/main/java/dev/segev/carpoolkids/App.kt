package dev.segev.carpoolkids

import android.app.Application
import dev.segev.carpoolkids.utilities.AuthManager
import dev.segev.carpoolkids.utilities.FirestoreManager
import dev.segev.carpoolkids.utilities.ImageLoader
import dev.segev.carpoolkids.utilities.SignalManager
import dev.segev.carpoolkids.utilities.StorageManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SignalManager.init(this)
        ImageLoader.init(this)
        AuthManager.init(this)
        FirestoreManager.init(this)
        StorageManager.init(this)
    }
}
