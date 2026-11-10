package com.music.bitchord.download

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
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
import com.music.bitchord.data.settings.AutoDownloadQuality
import com.music.bitchord.data.settings.AutoDownloadTarget
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Automatically downloads the listener's most frequently played and liked songs
 * in the background when connected to Wi-Fi.
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
    private const val KEY_AUTO_DOWNLOADED_IDS = "auto_downloaded_ids"

    private const val PERIOD_HOURS = 12L
    private const val FLEX_HOURS = 4L
    private const val CATCH_UP_MILLIS = 4L * 60 * 60 * 1000 // 4 hours

    private val syncMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _autoDownloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val autoDownloadedIds: StateFlow<Set<String>> = _autoDownloadedIds.asStateFlow()

    private val queuedAutoDownloadIds = ConcurrentHashMap.newKeySet<String>()

    private var appContext: Context? = null

    fun init(app: Application) {
        appContext = app

        // Restore saved auto-downloaded IDs and prune ones no longer existing on disk
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet(KEY_AUTO_DOWNLOADED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val validSet = savedSet.filter { id -> Downloads.verifiedSavedUri(id) != null }.toSet()
        _autoDownloadedIds.value = validSet
        if (validSet.size != savedSet.size) {
            prefs.edit().putStringSet(KEY_AUTO_DOWNLOADED_IDS, validSet).apply()
        }

        scope.launch {
            combine(
                AppSettings.autoDownloadFavorites,
                AppSettings.meteredConnection,
                AppSettings.autoDownloadOnlyWhenCharging,
                AppSettings.autoDownloadWifiOnly,
            ) { on, metered, charging, wifiOnly ->
                AutoDownloadCondition(on, metered, charging, wifiOnly)
            }
                .distinctUntilChanged()
                .collect { cond ->
                    if (cond.on) {
                        schedule(app)
                        if ((!cond.wifiOnly || cond.metered == false) &&
                            (!cond.onlyCharging || isDeviceCharging(app))
                        ) {
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
                    (!AppSettings.autoDownloadWifiOnly.value || AppSettings.meteredConnection.value == false) &&
                    (!AppSettings.autoDownloadOnlyWhenCharging.value || isDeviceCharging(app)) &&
                    isCatchUpDue(app)
                ) {
                    syncNow(app)
                }
            }
        })
    }

    private data class AutoDownloadCondition(
        val on: Boolean,
        val metered: Boolean?,
        val onlyCharging: Boolean,
        val wifiOnly: Boolean,
    )

    fun isAutoDownloaded(videoId: String?): Boolean {
        if (videoId.isNullOrBlank()) return false
        return _autoDownloadedIds.value.contains(videoId) && Downloads.verifiedSavedUri(videoId) != null
    }

    fun isQueuedForAutoDownload(videoId: String): Boolean = queuedAutoDownloadIds.contains(videoId)

    fun effectiveDownloadQualityFor(videoId: String): DownloadQuality {
        if (queuedAutoDownloadIds.contains(videoId)) {
            return when (AppSettings.autoDownloadQuality.value) {
                AutoDownloadQuality.MATCH_DOWNLOAD -> AppSettings.downloadQuality.value
                AutoDownloadQuality.LOSSLESS -> DownloadQuality.LOSSLESS
                AutoDownloadQuality.HIGH -> DownloadQuality.HIGH
                AutoDownloadQuality.STANDARD -> DownloadQuality.STANDARD
            }
        }
        return AppSettings.downloadQuality.value
    }

    fun onDownloadCompleted(ids: Iterable<String>) {
        var addedAny = false
        val toPersist = _autoDownloadedIds.value.toMutableSet()
        for (id in ids) {
            if (queuedAutoDownloadIds.remove(id)) {
                toPersist.add(id)
                addedAny = true
            }
        }
        if (addedAny) {
            _autoDownloadedIds.value = toPersist
            appContext?.let { ctx ->
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(KEY_AUTO_DOWNLOADED_IDS, toPersist)
                    .apply()
            }
        }
    }

    fun onDownloadDeleted(videoId: String) {
        if (_autoDownloadedIds.value.contains(videoId)) {
            val updated = _autoDownloadedIds.value - videoId
            _autoDownloadedIds.value = updated
            appContext?.let { ctx ->
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(KEY_AUTO_DOWNLOADED_IDS, updated)
                    .apply()
            }
        }
    }

    fun clearAutoDownloads(context: Context) {
        scope.launch {
            val ids = _autoDownloadedIds.value.toList()
            for (id in ids) {
                runCatching { Downloads.delete(context, id) }
            }
            _autoDownloadedIds.value = emptySet()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_AUTO_DOWNLOADED_IDS, emptySet())
                .apply()
        }
    }

    fun totalAutoDownloadedBytes(context: Context): Long {
        var total = 0L
        val ids = _autoDownloadedIds.value
        for (id in ids) {
            val uriStr = Downloads.verifiedSavedUri(id) ?: continue
            total += uriSizeBytes(context, Uri.parse(uriStr))
        }
        return total
    }

    private fun uriSizeBytes(context: Context, uri: Uri): Long = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).length() } ?: 0L
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }
    }.getOrDefault(0L)

    fun isDeviceCharging(context: Context): Boolean = runCatching {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

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

    private fun constraints(): Constraints = Constraints.Builder().apply {
        if (AppSettings.autoDownloadWifiOnly.value) {
            setRequiredNetworkType(NetworkType.UNMETERED)
        } else {
            setRequiredNetworkType(NetworkType.CONNECTED)
        }
        if (AppSettings.autoDownloadOnlyWhenCharging.value) {
            setRequiresCharging(true)
        }
        setRequiresBatteryNotLow(true)
        setRequiresStorageNotLow(true)
    }.build()

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
        if (AppSettings.autoDownloadWifiOnly.value && AppSettings.meteredConnection.value != false) return@withLock false
        if (AppSettings.autoDownloadOnlyWhenCharging.value && !isDeviceCharging(context)) return@withLock false
        if (!AppSettings.downloadsAllowedNow) return@withLock false

        val maxSongs = AppSettings.autoDownloadMaxSongs.value
        val maxStorageMb = AppSettings.autoDownloadMaxStorageMb.value
        val maxStorageBytes = if (maxStorageMb > 0) maxStorageMb.toLong() * 1024L * 1024L else Long.MAX_VALUE

        val target = AppSettings.autoDownloadTarget.value
        val candidates = LinkedHashMap<String, Song>()

        // 1. Liked songs from OfflinePlaylists (if target includes liked)
        if (target == AutoDownloadTarget.BOTH || target == AutoDownloadTarget.LIKED_ONLY) {
            runCatching {
                OfflinePlaylists.playlists.value.firstOrNull { it.isLiked }?.songs.orEmpty()
            }.getOrNull()?.forEach { song ->
                if (song.videoId.isNotBlank() && !isLocalUriOnly(song.videoId)) {
                    candidates[song.videoId] = song
                }
            }
        }

        // 2. Frequently listened songs (if target includes top played)
        if (target == AutoDownloadTarget.BOTH || target == AutoDownloadTarget.TOP_PLAYED_ONLY) {
            runCatching {
                ListeningStats.summary(ReplayPeriod.THIS_MONTH).songs
            }.getOrNull()?.forEach { ranked ->
                val song = ranked.song
                if (song.videoId.isNotBlank() && !candidates.containsKey(song.videoId) && !isLocalUriOnly(song.videoId)) {
                    candidates[song.videoId] = song
                }
            }

            // All-time top played songs if still under maxSongs (or if unlimited)
            if (maxSongs <= 0 || candidates.size < maxSongs) {
                runCatching {
                    ListeningStats.summary(ReplayPeriod.ALL_TIME).songs
                }.getOrNull()?.forEach { ranked ->
                    val song = ranked.song
                    if (song.videoId.isNotBlank() && !candidates.containsKey(song.videoId) && !isLocalUriOnly(song.videoId)) {
                        candidates[song.videoId] = song
                    }
                }
            }
        }

        val targetList = if (maxSongs > 0) candidates.values.take(maxSongs) else candidates.values.toList()
        val targetIdSet = targetList.map { it.videoId }.toSet()

        // --- SMART ROTATION & PRUNING ---
        // Automatically prune old auto-downloaded songs that are no longer frequently played
        // (i.e. fell off the active target favorites/top-played ranking).
        val currentAutoSet = _autoDownloadedIds.value.toSet()
        val obsoleteIds = currentAutoSet - targetIdSet
        if (obsoleteIds.isNotEmpty()) {
            Log.i(TAG, "Pruning ${obsoleteIds.size} obsolete auto-downloads no longer in top listening rotation")
            for (oldId in obsoleteIds) {
                runCatching { Downloads.delete(context, oldId) }
            }
            val remainingValid = currentAutoSet - obsoleteIds
            _autoDownloadedIds.value = remainingValid
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_AUTO_DOWNLOADED_IDS, remainingValid)
                .apply()
        }

        var currentBytes = totalAutoDownloadedBytes(context)

        // Storage ceiling enforcement: If existing auto-downloads exceed storage limit, prune from least prioritized
        if (maxStorageMb > 0 && currentBytes > maxStorageBytes) {
            val reversedTargets = targetList.reversed()
            for (lowPriSong in reversedTargets) {
                if (currentBytes <= maxStorageBytes) break
                val id = lowPriSong.videoId
                if (_autoDownloadedIds.value.contains(id)) {
                    val uriStr = Downloads.verifiedSavedUri(id)
                    val sBytes = if (uriStr != null) uriSizeBytes(context, Uri.parse(uriStr)) else 0L
                    runCatching { Downloads.delete(context, id) }
                    currentBytes = (currentBytes - sBytes).coerceAtLeast(0L)
                }
            }
        }

        val needed = targetList.filter { song ->
            Downloads.verifiedSavedUri(song.videoId) == null &&
            !Downloads.active.value.containsKey(song.videoId)
        }

        if (needed.isEmpty()) {
            recordSyncSuccess(context)
            return@withLock true
        }

        Log.i(TAG, "Starting auto-download for ${needed.size} songs on Wi-Fi (target: ${target.name})")
        val approxSongBytes = 8L * 1024 * 1024 // ~8 MB estimate
        for (song in needed) {
            if (AppSettings.autoDownloadWifiOnly.value && AppSettings.meteredConnection.value != false) {
                Log.i(TAG, "Network condition changed; pausing auto-download")
                break
            }
            if (AppSettings.autoDownloadOnlyWhenCharging.value && !isDeviceCharging(context)) {
                Log.i(TAG, "Device disconnected from charger; pausing auto-download")
                break
            }
            if (!AppSettings.downloadsAllowedNow) break

            if (maxStorageMb > 0 && (currentBytes + approxSongBytes > maxStorageBytes)) {
                Log.i(TAG, "Storage ceiling (${maxStorageMb}MB) reached; stopping auto-download")
                break
            }

            queuedAutoDownloadIds.add(song.videoId)
            Downloads.enqueue(context, song, from = "AutoDownload")
            currentBytes += approxSongBytes
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
        if (AppSettings.autoDownloadWifiOnly.value && AppSettings.meteredConnection.value != false) return Result.success()
        if (AppSettings.autoDownloadOnlyWhenCharging.value && !AutoDownloadManager.isDeviceCharging(applicationContext)) {
            return Result.retry()
        }
        val success = AutoDownloadManager.performSync(applicationContext)
        return if (success) Result.success() else Result.retry()
    }
}
