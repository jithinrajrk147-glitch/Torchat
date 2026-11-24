package com.blackstrings

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic background sync every 15 minutes (WiFi / Unmetered preferred).
 * Wakes Tor for a 30-second window, flushes PENDING Outbox messages, fetches from Mailbox, and sleeps.
 */
class WorkerSync(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val outbox = OutboxManager(applicationContext)
        val crypto = CryptoManager(applicationContext)
        val mailbox = MailboxManager(applicationContext, outbox)
        val tor = TorManager(applicationContext, crypto, outbox, mailbox)

        tor.wakeForSyncAndSleep(30_000L)
        delay(31_000L)
        tor.shutdown()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "blackstrings_tor_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<WorkerSync>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
