package de.foodfinder

import android.app.Application
import de.foodfinder.data.Prefs
import de.foodfinder.notify.DealWorker
import de.foodfinder.notify.Notifier

class FoodFinderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        DealWorker.schedule(this, Prefs(this).notifyIntervalMin)
    }
}
