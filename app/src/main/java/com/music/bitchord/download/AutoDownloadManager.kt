package com.music.bitchord.download

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Automatically downloads the listener's most frequently played and liked songs
 * in the background when connected to unmetered Wi-Fi.
 *
 * All downloaded tracks are subsequently played directly from local storage
 * across the app (Home, Search, Playlists, Queue, and CarPlay Companion),
 * consuming 0 bytes of mobile data.
 */
object AutoDownloadManager {

    private const val TAG = "AutoDownloadManager"
    private const val PERIODIC_WORK = "auto_download_top_songs"
    private const val ONE_OFF_WORK = "auto_download_top_songs_now"
    private const val PREFS = "bitchord_auto_download"
    private const val KEY_LAST_SYNC = "last_sync_time"

    private const val PERIOD_HOURS = 12L
    private const val FLEX_HOURS = 4L
    private const val CATCH_UP_MILLIS = 4L * 60 * 60 * 1000 // 4 hours

    private val syncMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(app: Application) {
        scope.launch {
            combine(
                AppSettings.autoDownloadFavorites,
                AppSettings.meteredConnection,
            ) { on, metered -> on to metered }
                .distinctUntilChanged()
                .collect { (on, metered) ->
                    if (on) {
                        schedule(app)
                        if (metered == false) {
                            // Connected to unmetered Wi-Fi: check if sync is due
                            syncNow(app)
                        }
                    } else {
                        cancel(app)
                    }
                }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (AppSettings.autoDownloadFavorites.value &&
                    AppSettings.meteredConnection.value == false &&
                    isCatchUpDue(app)
                ) {
                    syncNow(app)
                }
            }
        })
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoDownloadWorker>()
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_OFF_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoDownloadWorker>(
            PERIOD_HOURS,
            TimeUnit.HOURS,
            FLEX_HOURS,
            TimeUnit.HOURS,
        ).setConstraints(constraints()).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun cancel(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(PERIODIC_WORK)
        work.cancelUniqueWork(ONE_OFF_WORK)
    }

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    private fun isCatchUpDue(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SYNC, 0L)
        return System.currentTimeMillis() - last >= CATCH_UP_MILLIS
    }

    private fun recordSyncSuccess(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .apply()
    }

    suspend fun performSync(context: Context): Boolean = syncMutex.withLock {
        if (!AppSettings.autoDownloadFavorites.value) return@withLock false
        if (AppSettings.meteredConnection.value != false) return@withLock false
        if (!AppSettings.downloadsAllowedNow) return@withLock false

        val maxSongs = AppSettings.autoDownloadMaxSongs.value
        val candidates = LinkedHashMap<String, Song>()

        // 1. Liked songs from OfflinePlaylists
        runCatching {
            OfflinePlaylists.playlists.value.firstOrNull { it.isLiked }?.songs.orEmpty()
        }.getOrNull()?.forEach { song ->
            if (song.videoId.isNotBlank() && !isLocalUriOnly(song.videoId)) {
                candidates[song.videoId] = song
            }
        }

        // 2. Frequently listened songs this month
        runCatching {
            ListeningStats.summary(ReplayPeriod.THIS_MONTH).songs
        }.getOrNull()?.forEach { ranked ->
            val song = ranked.song
            if (song.videoId.isNotBlank() && !candidates.containsKey(song.videoId) && !isLocalUriOnly(song.videoId)) {
                candidates[song.videoId] = song
            }
        }

        // 3. All-time top played songs if we still have room
        if (candidates.size < maxSongs) {
            runCatching {
                ListeningStats.summary(ReplayPeriod.ALL_TIME).songs
            }.getOrNull()?.forEach { ranked ->
                val song = ranked.song
                if (song.videoId.isNotBlank() && !candidates.containsKey(song.videoId) && !isLocalUriOnly(song.videoId)) {
                    candidates[song.videoId] = song
                }
            }
        }

        val targetList = candidates.values.take(maxSongs)
        val needed = targetList.filter { song ->
            Downloads.verifiedSavedUri(song.videoId) == null &&
            !Downloads.active.value.containsKey(song.videoId)
        }

        if (needed.isEmpty()) {
            recordSyncSuccess(context)
            return@withLock true
        }

        Log.i(TAG, "Starting auto-download for ${needed.size} frequently listened songs on unmetered Wi-Fi")
        for (song in needed) {
            // Check network condition before queuing each song
            if (AppSettings.meteredConnection.value != false || !AppSettings.downloadsAllowedNow) {
                Log.i(TAG, "Network condition changed; pausing auto-download")
                break
            }
            Downloads.enqueue(context, song, from = "AutoDownload")
            delay(400L)
        }

        recordSyncSuccess(context)
        return@withLock true
    }

    private fun isLocalUriOnly(videoId: String): Boolean {
        return videoId.startsWith("content://") || videoId.startsWith("file://") || videoId.startsWith("/")
    }
}

class AutoDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!AppSettings.autoDownloadFavorites.value) return Result.success()
        if (AppSettings.meteredConnection.value != false) return Result.success()
        val success = AutoDownloadManager.performSync(applicationContext)
        return if (success) Result.success() else Result.retry()
    }
}
