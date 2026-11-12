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
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AutoDownloadQuality
import com.music.bitchord.data.settings.AutoDownloadTarget
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.spotify.LocalPlaylistStore
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private const val KEY_QUEUED_IDS = "queued_auto_download_ids"

    private const val PERIOD_HOURS = 12L
    private const val FLEX_HOURS = 4L
    private const val CATCH_UP_MILLIS = 4L * 60 * 60 * 1000 // 4 hours

    private val syncMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _autoDownloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val autoDownloadedIds: StateFlow<Set<String>> = _autoDownloadedIds.asStateFlow()

    private val queuedAutoDownloadIds = ConcurrentHashMap.newKeySet<String>()
    private val lastTargetIds = ConcurrentHashMap.newKeySet<String>()

    private var appContext: Context? = null

    private const val KEY_LAST_TARGET_IDS = "last_target_ids"

    fun init(app: Application) {
        appContext = app

        // Restore saved auto-downloaded IDs and prune ones no longer existing on disk
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet(KEY_AUTO_DOWNLOADED_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val queuedSet = prefs.getStringSet(KEY_QUEUED_IDS, emptySet()) ?: emptySet()
        val targetSet = prefs.getStringSet(KEY_LAST_TARGET_IDS, emptySet()) ?: emptySet()
        queuedAutoDownloadIds.addAll(queuedSet)
        lastTargetIds.addAll(targetSet)

        // Incorporate any songs with isAutoDownloaded == true from persisted Downloads metadata
        val idsToPromote = mutableSetOf<String>()
        Downloads.savedMetadata.value.values.forEach { meta ->
            val isAuto = meta.isAutoDownloaded || lastTargetIds.contains(meta.videoId) || savedSet.contains(meta.videoId)
            if (isAuto && Downloads.verifiedSavedUri(meta.videoId) != null) {
                savedSet.add(meta.videoId)
                if (!meta.isAutoDownloaded) idsToPromote.add(meta.videoId)
            }
        }
        if (idsToPromote.isNotEmpty()) {
            Downloads.markAutoDownloadedMetadata(idsToPromote)
        }

        val validSet = savedSet.filter { id -> Downloads.verifiedSavedUri(id) != null }.toSet()
        _autoDownloadedIds.value = validSet
        if (validSet.size != savedSet.size) {
            prefs.edit().putStringSet(KEY_AUTO_DOWNLOADED_IDS, validSet).apply()
        }

        scope.launch {
            Downloads.savedMetadata.collect { metaMap ->
                val newAutoIds = mutableSetOf<String>()
                metaMap.values.forEach { meta ->
                    if (meta.isAutoDownloaded && Downloads.verifiedSavedUri(meta.videoId) != null) {
                        newAutoIds.add(meta.videoId)
                    }
                }
                if (newAutoIds.isNotEmpty()) {
                    val current = _autoDownloadedIds.value
                    if (!current.containsAll(newAutoIds)) {
                        _autoDownloadedIds.value = current + newAutoIds
                    }
                }
            }
        }

        scope.launch {
            combine(
                AppSettings.autoDownloadFavorites,
                AppSettings.meteredConnection,
                AppSettings.autoDownloadOnlyWhenCharging,
                AppSettings.autoDownloadWifiOnly,
                com.music.bitchord.carplay.CarPlayWebBridge.serverRunning,
            ) { on, metered, charging, wifiOnly, carPlayRunning ->
                AutoDownloadCondition(on, metered, charging, wifiOnly, carPlayRunning)
            }
                .distinctUntilChanged()
                .collect { cond ->
                    if (cond.on) {
                        val hotspotOnCellular = AppSettings.isHotspotOnCellular(app)
                        if (hotspotOnCellular) {
                            cancelPendingAutoDownloads()
                        } else if ((!cond.wifiOnly || cond.metered == false) &&
                            (!cond.onlyCharging || isDeviceCharging(app))
                        ) {
                            schedule(app)
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
                    !com.music.bitchord.carplay.CarPlayWebBridge.serverRunning.value &&
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
        val carPlayRunning: Boolean,
    )

    fun isHotspotActive(context: Context): Boolean {
        return AppSettings.isHotspotActive(context)
    }

    fun cancelPendingAutoDownloads() {
        val autoDownloadIds = DownloadSession.state.value.items
            .filter { it.from == "AutoDownload" || queuedAutoDownloadIds.contains(it.videoId) }
            .map { it.videoId }
            .toSet()
        if (autoDownloadIds.isNotEmpty()) {
            Log.i(TAG, "Cancelling ${autoDownloadIds.size} pending/running auto-downloads due to hotspot/CarPlay activity")
            for (id in autoDownloadIds) {
                Downloads.cancel(id)
            }
        }
    }

    fun isTargetSong(videoId: String): Boolean =
        videoId.isNotBlank() && (lastTargetIds.contains(videoId) || queuedAutoDownloadIds.contains(videoId) || _autoDownloadedIds.value.contains(videoId))

    fun isAutoDownloaded(videoId: String?): Boolean {
        if (videoId.isNullOrBlank()) return false
        if (_autoDownloadedIds.value.contains(videoId) && Downloads.verifiedSavedUri(videoId) != null) return true
        if (lastTargetIds.contains(videoId) && Downloads.verifiedSavedUri(videoId) != null) {
            markAutoDownloaded(videoId)
            return true
        }
        val meta = Downloads.savedMetadata.value[videoId]
        if (meta?.isAutoDownloaded == true && Downloads.verifiedSavedUri(videoId) != null) {
            markAutoDownloaded(videoId)
            return true
        }
        val uri = Downloads.saved.value[videoId]
        if (uri != null) {
            val isAliasAuto = Downloads.savedMetadata.value.values.any {
                it.uri == uri && (it.isAutoDownloaded || _autoDownloadedIds.value.contains(it.videoId) || lastTargetIds.contains(it.videoId))
            }
            if (isAliasAuto && Downloads.verifiedSavedUri(videoId) != null) {
                markAutoDownloaded(videoId)
                return true
            }
        }
        return false
    }

    fun allAutoDownloadedIds(): Set<String> {
        val result = _autoDownloadedIds.value.toMutableSet()
        val metaMap = Downloads.savedMetadata.value
        val savedMap = Downloads.saved.value

        for ((vid, meta) in metaMap) {
            if (meta.isAutoDownloaded && Downloads.verifiedSavedUri(vid) != null) {
                result.add(vid)
            }
        }

        for (vid in lastTargetIds) {
            if (Downloads.verifiedSavedUri(vid) != null) {
                result.add(vid)
            }
        }

        val autoUris = HashSet<String>()
        for (vid in result) {
            savedMap[vid]?.let { autoUris.add(it) }
        }
        if (autoUris.isNotEmpty()) {
            for ((vid, uri) in savedMap) {
                if (uri in autoUris && Downloads.verifiedSavedUri(vid) != null) {
                    result.add(vid)
                }
            }
        }
        return result
    }

    fun markAutoDownloaded(videoId: String) {
        if (videoId.isBlank()) return
        val current = _autoDownloadedIds.value
        if (!current.contains(videoId)) {
            val updated = current + videoId
            _autoDownloadedIds.value = updated
            appContext?.let { ctx ->
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(KEY_AUTO_DOWNLOADED_IDS, updated)
                    .apply()
            }
        }
    }

    fun markQueued(videoId: String) {
        if (videoId.isNotBlank() && queuedAutoDownloadIds.add(videoId)) {
            persistQueuedIds()
        }
    }

    private fun persistQueuedIds() {
        appContext?.let { ctx ->
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_QUEUED_IDS, queuedAutoDownloadIds.toSet())
                .apply()
        }
    }

    fun isQueuedForAutoDownload(videoId: String): Boolean =
        queuedAutoDownloadIds.contains(videoId) ||
        lastTargetIds.contains(videoId) ||
        _autoDownloadedIds.value.contains(videoId) ||
        Downloads.savedMetadata.value[videoId]?.isAutoDownloaded == true

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
        var removedQueued = false
        val toPersist = _autoDownloadedIds.value.toMutableSet()
        val isAnyQueued = ids.any { queuedAutoDownloadIds.contains(it) }
        val isAnyMeta = ids.any { Downloads.savedMetadata.value[it]?.isAutoDownloaded == true }
        val isAnyTarget = ids.any { lastTargetIds.contains(it) }
        val shouldMarkAuto = isAnyQueued || isAnyMeta || isAnyTarget

        for (id in ids) {
            val wasQueued = queuedAutoDownloadIds.remove(id)
            if (wasQueued) removedQueued = true
            if (shouldMarkAuto) {
                toPersist.add(id)
                addedAny = true
            }
        }
        if (removedQueued) {
            persistQueuedIds()
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
            ExistingWorkPolicy.REPLACE,
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
        if (AppSettings.isHotspotOnCellular(context)) {
            Log.i(TAG, "Hotspot on cellular active; skipping auto-download to preserve mobile hotspot data")
            cancelPendingAutoDownloads()
            return@withLock false
        }
        if (AppSettings.autoDownloadWifiOnly.value && AppSettings.meteredConnection.value != false) return@withLock false
        if (AppSettings.autoDownloadOnlyWhenCharging.value && !isDeviceCharging(context)) return@withLock false
        if (!AppSettings.downloadsAllowedNow) return@withLock false

        val maxSongs = AppSettings.autoDownloadMaxSongs.value
        val maxStorageMb = AppSettings.autoDownloadMaxStorageMb.value
        val maxStorageBytes = if (maxStorageMb > 0) maxStorageMb.toLong() * 1024L * 1024L else Long.MAX_VALUE

        val target = AppSettings.autoDownloadTarget.value
        val candidates = LinkedHashMap<String, Song>()

        fun addCandidate(song: Song) {
            val id = song.videoId.trim()
            if (id.isNotBlank() && !isLocalUriOnly(id) && !candidates.containsKey(id)) {
                candidates[id] = song
            }
        }

        fun hasReachedCapacity(): Boolean = maxSongs > 0 && candidates.size >= maxSongs

        // 1. Liked songs (if target includes liked: BOTH or LIKED_ONLY)
        if (target == AutoDownloadTarget.BOTH || target == AutoDownloadTarget.LIKED_ONLY) {
            // A. Online Liked Songs from YouTube Music (VLLM / LM) with pagination
            runCatching {
                withTimeoutOrNull(25_000L) {
                    var page = YtMusicRepository.browseSongs(YtMusicRepository.LIKED_MUSIC).getOrNull()
                        ?: YtMusicRepository.browseSongs("LM").getOrNull()
                    var pagesRead = 0
                    while (page != null && pagesRead < 50) { // Up to ~5000 songs
                        page.songs.forEach { addCandidate(it) }
                        if (hasReachedCapacity()) break
                        val token = page.continuation ?: break
                        pagesRead++
                        page = YtMusicRepository.moreSongs(token).getOrNull()
                    }
                }
            }

            // B. Offline Liked Songs from OfflinePlaylists
            runCatching {
                OfflinePlaylists.playlists.value.firstOrNull { it.isLiked }?.songs.orEmpty()
            }.getOrNull()?.forEach { addCandidate(it) }
        }

        // 2. Frequently listened songs (if target includes top played: BOTH or TOP_PLAYED_ONLY)
        if (!hasReachedCapacity() && (target == AutoDownloadTarget.BOTH || target == AutoDownloadTarget.TOP_PLAYED_ONLY)) {
            // This month's top played songs
            runCatching {
                ListeningStats.summary(ReplayPeriod.THIS_MONTH).songs
            }.getOrNull()?.forEach { ranked ->
                addCandidate(ranked.song)
            }

            // All-time top played songs
            if (!hasReachedCapacity()) {
                runCatching {
                    ListeningStats.summary(ReplayPeriod.ALL_TIME).songs
                }.getOrNull()?.forEach { ranked ->
                    addCandidate(ranked.song)
                }
            }
        }

        // 3. User Playlists & History (if still under capacity)
        if (!hasReachedCapacity()) {
            // YouTube Music account listening history
            runCatching {
                withTimeoutOrNull(10_000L) {
                    YtMusicRepository.history().getOrNull().orEmpty()
                }
            }.getOrNull()?.forEach { addCandidate(it) }
        }

        if (!hasReachedCapacity()) {
            // BitChord Local Playlists (imported/created)
            runCatching {
                LocalPlaylistStore.playlists.value.flatMap { it.songs }
            }.getOrNull()?.forEach { addCandidate(it) }

            // All other Offline Playlists
            runCatching {
                OfflinePlaylists.playlists.value.filterNot { it.isLiked }.flatMap { it.songs }
            }.getOrNull()?.forEach { addCandidate(it) }
        }

        if (!hasReachedCapacity()) {
            // YouTube Music Library Playlists
            runCatching {
                withTimeoutOrNull(20_000L) {
                    val libShelves = YtMusicRepository.libraryPlaylists().getOrNull().orEmpty()
                    for (shelf in libShelves) {
                        if (hasReachedCapacity()) break
                        val bId = shelf.browseId ?: continue
                        val pSongs = YtMusicRepository.browseSongs(bId).getOrNull()?.songs.orEmpty()
                        pSongs.forEach { addCandidate(it) }
                    }
                }
            }
        }

        // 4. Smart Taste Expansion (if still under capacity):
        // When the user's liked and played library has fewer tracks than maxSongs,
        // intelligently pull recommended tracks based on user taste so the download quota is fulfilled!
        if (!hasReachedCapacity() && target != AutoDownloadTarget.LIKED_ONLY) {
            // A. Quick Picks / Recommended from Home
            runCatching {
                withTimeoutOrNull(10_000L) {
                    YtMusicRepository.quickPicks(excludeSongIds = candidates.keys).getOrNull().orEmpty()
                }
            }.getOrNull()?.forEach { addCandidate(it) }

            // B. Radio stations seeded by the user's top played songs
            if (!hasReachedCapacity()) {
                val seedTracks = candidates.values.take(20) // Up to 20 top songs as seeds
                for (seed in seedTracks) {
                    if (hasReachedCapacity()) break
                    runCatching {
                        withTimeoutOrNull(10_000L) {
                            YtMusicRepository.radio(seed.videoId).getOrNull().orEmpty()
                        }
                    }.getOrNull()?.forEach { addCandidate(it) }
                }
            }
        }

        val targetList = if (maxSongs > 0) candidates.values.take(maxSongs) else candidates.values.toList()
        val targetIdSet = targetList.map { it.videoId }.toSet()
        if (targetIdSet.isNotEmpty()) {
            lastTargetIds.clear()
            lastTargetIds.addAll(targetIdSet)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_LAST_TARGET_IDS, targetIdSet)
                .apply()
        }

        // --- SMART ROTATION & PRUNING ---
        // Automatically prune old auto-downloaded songs that are no longer in active target rotation.
        // Safety guard: only prune if targetList is non-empty to avoid wiping out offline files on transient error.
        val currentAutoSet = _autoDownloadedIds.value.toSet()
        val excessCount = if (maxSongs > 0) (currentAutoSet.size - maxSongs).coerceAtLeast(0) else 0
        val obsoleteCandidates = (currentAutoSet - targetIdSet).toList()
        val obsoleteIds = if (excessCount > 0) obsoleteCandidates.take(excessCount).toSet() else emptySet()
        if (targetList.isNotEmpty() && obsoleteIds.isNotEmpty()) {
            Log.i(TAG, "Pruning ${obsoleteIds.size} excess obsolete auto-downloads to fit within maxSongs ($maxSongs)")
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

        // Self-heal: Ensure any candidates already saved on disk are tracked in _autoDownloadedIds
        for (song in targetList) {
            val id = song.videoId
            if (Downloads.verifiedSavedUri(id) != null) {
                markAutoDownloaded(id)
            }
        }

        val needed = targetList.filter { song ->
            Downloads.verifiedSavedUri(song.videoId) == null &&
            Downloads.active.value[song.videoId].let { it !is DownloadState.Queued && it !is DownloadState.Running }
        }

        if (needed.isEmpty()) {
            recordSyncSuccess(context)
            return@withLock true
        }

        Log.i(TAG, "Starting auto-download for ${needed.size} songs on Wi-Fi (target: ${target.name}, total candidates: ${candidates.size})")
        val approxSongBytes = 8L * 1024 * 1024 // ~8 MB estimate
        for (song in needed) {
            if (AppSettings.isHotspotOnCellular(context)) {
                Log.i(TAG, "Hotspot on cellular active; cancelling and pausing auto-download")
                cancelPendingAutoDownloads()
                break
            }
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

            markQueued(song.videoId)
            Downloads.enqueue(context, song, from = "AutoDownload")
            currentBytes += approxSongBytes
            delay(30L)
        }

        recordSyncSuccess(context)
        return@withLock true
    }

    private fun isLocalUriOnly(videoId: String): Boolean {
        return videoId.isBlank() || videoId.startsWith("content://") || videoId.startsWith("file://") || videoId.startsWith("/")
    }
}

class AutoDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!AppSettings.autoDownloadFavorites.value) return Result.success()
        if (AppSettings.isHotspotOnCellular(applicationContext)) {
            AutoDownloadManager.cancelPendingAutoDownloads()
            return Result.success()
        }
        if (AppSettings.autoDownloadWifiOnly.value && AppSettings.meteredConnection.value != false) return Result.success()
        if (AppSettings.autoDownloadOnlyWhenCharging.value && !AutoDownloadManager.isDeviceCharging(applicationContext)) {
            return Result.retry()
        }
        val success = AutoDownloadManager.performSync(applicationContext)
        return if (success) Result.success() else Result.retry()
    }
}
