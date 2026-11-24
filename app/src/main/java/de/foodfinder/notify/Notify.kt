package de.foodfinder.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.foodfinder.MainActivity
import de.foodfinder.R
import de.foodfinder.data.DealOrigin
import de.foodfinder.data.Mode
import de.foodfinder.data.Prefs
import de.foodfinder.data.Repository
import de.foodfinder.data.Service
import de.foodfinder.data.eur
import de.foodfinder.mail.MailScanner
import de.foodfinder.mail.Signals
import java.util.concurrent.TimeUnit

object Notifier {
    const val CH_DEALS = "deals"
    const val CH_BAGS = "bags"
    const val CH_MEMBER = "membership"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DEALS, "Neue Top-Angebote", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_BAGS, "Too Good To Go Tüten", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_MEMBER, "Mitgliedschaften", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun post(ctx: Context, channel: String, id: Int, title: String, text: String, url: String? = null) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = if (url != null) Intent(Intent.ACTION_VIEW, Uri.parse(url)) else Intent(ctx, MainActivity::class.java)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_deal)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    fun listenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(ctx, DealListenerService::class.java).flattenToString())
    }
}

/** Reads push notifications of the delivery apps and keeps the deals they announce. */
class DealListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val service = Service.forPackage(sbn.packageName) ?: return
        if (sbn.packageName == packageName) return
        val ex = sbn.notification.extras
        val title = ex.getCharSequence("android.title")?.toString() ?: ""
        val text = listOfNotNull(ex.getCharSequence("android.bigText"), ex.getCharSequence("android.text")).joinToString(" ")
        if (title.isBlank() && text.isBlank()) return
        val prefs = Prefs(applicationContext)
        Signals.applyMembership(prefs, service, title, text, sbn.postTime, "App-Push")
        Signals.voucherFrom(service, title, text, sbn.postTime, DealOrigin.PUSH)?.let { Signals.addVoucher(prefs, it) }
    }
}

class DealWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = Repository(applicationContext)
        val p = repo.prefs
        // 1) mails
        if (p.imapConfigured && System.currentTimeMillis() - p.imapLastScan > 50 * 60_000) runCatching { MailScanner(p).scan() }
        // 2) membership expiry reminder
        p.membership.woltPlusEnds?.let { end ->
            val left = end - System.currentTimeMillis()
            val key = "woltplus-$end"
            if (p.membership.woltPlus && left in 0..3 * 24 * 3600_000L && key !in p.notified) {
                Notifier.post(applicationContext, Notifier.CH_MEMBER, 9001, "Wolt+ läuft bald aus",
                    "Deine Wolt+-Mitgliedschaft endet in ${left / 3600_000 / 24 + 1} Tag(en). Danach rechnet FoodFinder ohne Gratis-Lieferung.")
                p.notified = p.notified + (key to System.currentTimeMillis())
            } else if (p.membership.woltPlus && left < 0) {
                p.membership = p.membership.copy(woltPlus = false, woltNote = "Automatisch beendet (Ablaufdatum erreicht)")
            }
        }
        if (!p.notifyEnabled) return Result.success()
        val at = repo.where(useGps = false) ?: return Result.success()
        val now = System.currentTimeMillis()
        val seen = p.notified.filterValues { now - it < 24 * 3600_000L }.toMutableMap()
        // 3) delivery deals
        runCatching {
            val res = repo.load(Mode.DELIVERY, at, p.filters.services)
            val ranked = repo.rank(res.venues, p.persons, p.filters, "")
            ranked.take(5).filter { it.estimate.savings >= p.notifyMinSaving && it.venue.uid !in seen }.take(2).forEachIndexed { i, r ->
                Notifier.post(applicationContext, Notifier.CH_DEALS, 1000 + i,
                    "${r.venue.name} · spart ca. ${eur(r.estimate.savings)}",
                    "${r.venue.service.label}: ${r.estimate.appliedDeals.firstOrNull()?.label ?: "Angebot"} · für ${p.persons}: ca. ${eur(r.estimate.total)}",
                    r.venue.url)
                seen[r.venue.uid] = now
            }
        }
        // 4) TGTG bags
        if (p.notifyTgtg && repo.tgtgAuth.loggedIn) runCatching {
            val res = repo.load(Mode.PICKUP, at, setOf(Service.TGTG))
            res.venues.filter { (it.itemsAvailable ?: 0) > 0 && it.uid !in seen && (it.distanceM ?: 0) < 3000 }.take(3).forEachIndexed { i, v ->
                Notifier.post(applicationContext, Notifier.CH_BAGS, 2000 + i, "Tüte verfügbar: ${v.name}",
                    "${v.itemsAvailable}× für ${v.bagPrice?.let(::eur) ?: "?"} statt ${v.bagValue?.let(::eur) ?: "?"} · Abholung ${v.pickupWindow ?: ""}", v.url)
                seen[v.uid] = now
            }
        }
        p.notified = seen
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context, minutes: Int) {
            val req = PeriodicWorkRequestBuilder<DealWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("deals", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
