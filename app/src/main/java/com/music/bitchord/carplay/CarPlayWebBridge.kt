package com.music.bitchord.carplay

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.playback.cast.CastSink
import com.music.bitchord.playback.cast.RemoteSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Coordinates bidirectional synchronization, in-app direct local Wi-Fi radio,
 * and CarPlay audio routing between the Android BitChord host app and the iPhone Safari Web Companion.
 */
object CarPlayWebBridge {

    private const val TAG = "CarPlayWebBridge"
    const val DEFAULT_PORT = 8765
    const val DIRECT_WIFI_SSID = "DIRECT-BC-BitChord"
    const val DIRECT_WIFI_PASS = "bitchord8765"
    const val DIRECT_WIFI_DEFAULT_IP = "192.168.49.1"

    data class NetworkEndpoint(
        val interfaceName: String,
        val label: String,
        val ip: String,
        val url: String,
        val priority: Int,
    )

    data class LocalWifiState(
        val active: Boolean = false,
        val starting: Boolean = false,
        val modeLabel: String = "",
        val ssid: String = "",
        val password: String = "",
        val ip: String = "",
        val url: String = "",
        val error: String? = null,
    )

    data class QueueEntry(
        val index: Int,
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val thumbnailUrl: String?,
        val durationMs: Long,
    )

    data class AndroidScreenState(
        val selectedTab: Int = 0,
        val showNowPlaying: Boolean = false,
        val lyricsOpen: Boolean = false,
        val queueOpen: Boolean = false,
        val detailBrowseId: String? = null,
        val detailTitle: String? = null,
    )

    interface UiController {
        fun onPlaySong(song: Song, shelfTitle: String?)
        fun onPlayShelfItem(item: ShelfItem, shelfTitle: String)
        fun onOpenDetail(browseId: String, title: String, subtitle: String, thumbnailUrl: String?)
        fun onPlayDetailSong(songs: List<Song>, index: Int, pageTitle: String, browseId: String)
        fun onPlayQueueIndex(index: Int)
        fun onTogglePlay()
        fun onSeekTo(positionMs: Long)
        fun onNext()
        fun onPrevious()
        fun onToggleShuffle()
        fun onCycleRepeat()
        fun onToggleLike(song: Song)
        fun onSelectTab(tabIndex: Int)
        fun onSetNowPlayingOpen(open: Boolean)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _endpoints = MutableStateFlow<List<NetworkEndpoint>>(emptyList())
    val endpoints: StateFlow<List<NetworkEndpoint>> = _endpoints.asStateFlow()

    private val _localWifiState = MutableStateFlow(LocalWifiState())
    val localWifiState: StateFlow<LocalWifiState> = _localWifiState.asStateFlow()

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    private val _carPlaySession = MutableStateFlow(false)
    val carPlaySession: StateFlow<Boolean> = _carPlaySession.asStateFlow()

    private val _syncScreenNavigation = MutableStateFlow(true)
    val syncScreenNavigation: StateFlow<Boolean> = _syncScreenNavigation.asStateFlow()

    // Live snapshots from MainActivity / MainViewModel / PlaybackService
    @Volatile
    var uiController: UiController? = null

    @Volatile
    var homeShelvesSnapshot: List<HomeShelf> = emptyList()

    @Volatile
    var libraryShelvesSnapshot: List<HomeShelf> = emptyList()

    @Volatile
    var currentLyricsSnapshot: List<LyricLine>? = null

    @Volatile
    var currentLyricsSourceSnapshot: String? = null

    @Volatile
    var currentDetailSnapshot: DetailPage? = null

    @Volatile
    var listenerNameSnapshot: String? = null

    @Volatile
    var listenerAvatarUrlSnapshot: String? = null

    @Volatile
    var androidScreenState: AndroidScreenState = AndroidScreenState()

    @Volatile
    var currentSongSnapshot: Song? = null

    @Volatile
    var currentLikeStatusSnapshot: LikeStatus = LikeStatus.INDIFFERENT

    @Volatile
    var currentShuffleSnapshot: Boolean = false

    @Volatile
    var currentRepeatModeSnapshot: Int = Player.REPEAT_MODE_OFF

    @Volatile
    var localPlayerProvider: (() -> ExoPlayer?)? = null

    @Volatile
    internal var speaker: CarPlayWebSpeaker? = null
        private set

    private val clientHeartbeats = ConcurrentHashMap<String, Long>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var appContext: Context? = null

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var localHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    @Volatile
    private var userExplicitlyDisabledAudio: Boolean = false

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshEndpoints()
        if (AppSettings.carPlayServerEnabled.value) {
            startServer()
        }
    }

    fun startServer() {
        refreshEndpoints()
        CarPlayWebServer.start(DEFAULT_PORT) { actualPort, running ->
            _port.value = actualPort
            _serverRunning.value = running
            val curWifi = _localWifiState.value
            if (curWifi.active && curWifi.ip.isNotBlank()) {
                _localWifiState.value = curWifi.copy(url = "http://${curWifi.ip}:$actualPort")
            }
            if (running) {
                registerMdns(actualPort)
            } else {
                unregisterMdns()
            }
            refreshEndpoints()
        }
    }

    fun stopServer() {
        unregisterMdns()
        speaker?.let { sp ->
            mainHandler.post { sp.stop(resumeHere = false) }
        }
        stopLocalWifiRadio()
        CarPlayWebServer.stop()
        _serverRunning.value = false
        _connectedClients.value = 0
        clientHeartbeats.clear()
        userExplicitlyDisabledAudio = false
        releaseLocks()
    }

    private fun registerMdns(port: Int) {
        val ctx = appContext ?: return
        unregisterMdns()
        SimpleMdnsResponder.start(port)
        try {
            val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = nsd
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "bitchord"
                serviceType = "_http._tcp."
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                    Log.i(TAG, "mDNS registered as http://bitchord.local:$port")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS registration failed: errorCode=$errorCode")
                }
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    Log.i(TAG, "mDNS unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS unregistration failed: errorCode=$errorCode")
                }
            }
            nsdRegistrationListener = listener
            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) {
            Log.w(TAG, "mDNS registration error: ${e.message}")
        }
    }

    private fun unregisterMdns() {
        SimpleMdnsResponder.stop()
        try {
            val listener = nsdRegistrationListener ?: return
            nsdManager?.unregisterService(listener)
        } catch (_: Throwable) {
        }
        nsdRegistrationListener = null
        nsdManager = null
    }

    /**
     * Starts an in-app Local Direct Wi-Fi radio wave (no carrier tethering / mobile hotspot data sharing).
     *
     * Primary method: Wi-Fi Direct Autonomous Group Owner (`WifiP2pManager.createGroup`) with fixed SSID
     * `DIRECT-BC-BitChord` and fixed password `bitchord8765` on `192.168.49.1`. This keeps Galaxy S25's
     * normal Wi-Fi AND 5G/4G mobile data active simultaneously, and allows the iPhone 7 to auto-reconnect
     * without re-entering a new password every time.
     *
     * Fallback method: `WifiManager.startLocalOnlyHotspot` if Wi-Fi Direct group creation is unavailable.
     */
    @SuppressLint("MissingPermission")
    fun startLocalWifiRadio() {
        val ctx = appContext ?: return
        if (!_serverRunning.value) {
            startServer()
        }
        acquireLocks()
        _localWifiState.value = LocalWifiState(
            active = false,
            starting = true,
            modeLabel = "ローカル専用 Wi-Fi 電波を起動中...",
        )

        mainHandler.post {
            try {
                val mgr = p2pManager ?: (ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager)?.also {
                    p2pManager = it
                }
                val ch = p2pChannel ?: mgr?.initialize(ctx, Looper.getMainLooper(), null)?.also {
                    p2pChannel = it
                }
                if (mgr == null || ch == null) {
                    startLocalOnlyHotspotFallback(ctx)
                    return@post
                }

                mgr.requestGroupInfo(ch) { existingGroup ->
                    if (existingGroup != null && existingGroup.isGroupOwner && !existingGroup.networkName.isNullOrBlank()) {
                        onP2pGroupReady(existingGroup)
                    } else if (existingGroup != null) {
                        mgr.removeGroup(
                            ch,
                            object : WifiP2pManager.ActionListener {
                                override fun onSuccess() = createNewP2pGroup(ctx, mgr, ch)
                                override fun onFailure(reason: Int) = createNewP2pGroup(ctx, mgr, ch)
                            },
                        )
                    } else {
                        createNewP2pGroup(ctx, mgr, ch)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Wi-Fi Direct start error: ${t.message}")
                startLocalOnlyHotspotFallback(ctx)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createNewP2pGroup(
        ctx: Context,
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(DIRECT_WIFI_SSID)
                    .setPassphrase(DIRECT_WIFI_PASS)
                    .enablePersistentMode(true)
                    .build()
                mgr.createGroup(
                    ch,
                    config,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            pollP2pGroupDetails(mgr, ch, attemptsLeft = 12)
                        }

                        override fun onFailure(reason: Int) {
                            Log.w(TAG, "createGroup(config) failed with reason=$reason, trying default createGroup")
                            createDefaultP2pGroup(ctx, mgr, ch)
                        }
                    },
                )
                return
            } catch (t: Throwable) {
                Log.w(TAG, "WifiP2pConfig.Builder failed: ${t.message}")
            }
        }
        createDefaultP2pGroup(ctx, mgr, ch)
    }

    @SuppressLint("MissingPermission")
    private fun createDefaultP2pGroup(
        ctx: Context,
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ) {
        mgr.createGroup(
            ch,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    pollP2pGroupDetails(mgr, ch, attemptsLeft = 12)
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "createGroup() failed with reason=$reason, falling back to LocalOnlyHotspot")
                    startLocalOnlyHotspotFallback(ctx)
                }
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun pollP2pGroupDetails(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        attemptsLeft: Int,
    ) {
        mainHandler.postDelayed(
            {
                try {
                    mgr.requestGroupInfo(ch) { group ->
                        if (group != null && !group.networkName.isNullOrBlank()) {
                            onP2pGroupReady(group)
                        } else if (attemptsLeft > 1) {
                            pollP2pGroupDetails(mgr, ch, attemptsLeft - 1)
                        } else {
                            // Even if requestGroupInfo hasn't populated yet, use configured defaults
                            val ip = findP2pInterfaceIp(null) ?: DIRECT_WIFI_DEFAULT_IP
                            val currentPort = _port.value
                            _localWifiState.value = LocalWifiState(
                                active = true,
                                starting = false,
                                modeLabel = "ローカル専用 Wi-Fi Direct 電波 (直接通信)",
                                ssid = DIRECT_WIFI_SSID,
                                password = DIRECT_WIFI_PASS,
                                ip = ip,
                                url = "http://$ip:$currentPort",
                            )
                            refreshEndpoints()
                        }
                    }
                } catch (_: Throwable) {
                }
            },
            250L,
        )
    }

    private fun onP2pGroupReady(group: WifiP2pGroup) {
        val ssid = group.networkName?.takeIf { it.isNotBlank() } ?: DIRECT_WIFI_SSID
        val pass = group.passphrase?.takeIf { it.isNotBlank() } ?: DIRECT_WIFI_PASS
        val iface = group.`interface`
        val ip = findP2pInterfaceIp(iface) ?: DIRECT_WIFI_DEFAULT_IP
        val currentPort = _port.value
        _localWifiState.value = LocalWifiState(
            active = true,
            starting = false,
            modeLabel = "ローカル専用 Wi-Fi Direct 電波 (直接通信)",
            ssid = ssid,
            password = pass,
            ip = ip,
            url = "http://$ip:$currentPort",
        )
        refreshEndpoints()
    }

    @SuppressLint("MissingPermission")
    private fun startLocalOnlyHotspotFallback(ctx: Context) {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm == null) {
                _localWifiState.value = LocalWifiState(
                    error = "Wi-Fi サービスを取得できませんでした",
                )
                return
            }
            localHotspotReservation?.close()
            localHotspotReservation = null
            wm.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        localHotspotReservation = reservation
                        var ssid = ""
                        var pass = ""
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val conf = reservation?.softApConfiguration
                            ssid = conf?.ssid.orEmpty()
                            pass = conf?.passphrase.orEmpty()
                        } else {
                            @Suppress("DEPRECATION")
                            val conf = reservation?.wifiConfiguration
                            @Suppress("DEPRECATION")
                            ssid = conf?.SSID?.trim('"').orEmpty()
                            @Suppress("DEPRECATION")
                            pass = conf?.preSharedKey?.trim('"').orEmpty()
                        }
                        mainHandler.postDelayed(
                            {
                                refreshEndpoints()
                                val apEp = _endpoints.value.firstOrNull { ep ->
                                    ep.interfaceName.startsWith("swlan") ||
                                        ep.interfaceName.startsWith("ap") ||
                                        ep.interfaceName.startsWith("wlan1")
                                } ?: _endpoints.value.firstOrNull()
                                val ip = apEp?.ip ?: "192.168.43.1"
                                val currentPort = _port.value
                                _localWifiState.value = LocalWifiState(
                                    active = true,
                                    starting = false,
                                    modeLabel = "ローカル専用ホットスポット (LocalOnlyHotspot)",
                                    ssid = ssid,
                                    password = pass,
                                    ip = ip,
                                    url = "http://$ip:$currentPort",
                                )
                            },
                            500L,
                        )
                    }

                    override fun onStopped() {
                        localHotspotReservation = null
                        _localWifiState.value = LocalWifiState()
                        refreshEndpoints()
                    }

                    override fun onFailed(reason: Int) {
                        localHotspotReservation = null
                        _localWifiState.value = LocalWifiState(
                            error = "ローカル専用 Wi-Fi 電波の起動に失敗しました (code=$reason)。Wi-Fi がオンになっているか確認してください。",
                        )
                    }
                },
                mainHandler,
            )
        } catch (t: Throwable) {
            _localWifiState.value = LocalWifiState(
                error = "権限またはシステム制限により起動できませんでした: ${t.message}",
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun stopLocalWifiRadio() {
        mainHandler.post {
            try {
                localHotspotReservation?.close()
            } catch (_: Throwable) {
            }
            localHotspotReservation = null

            val mgr = p2pManager
            val ch = p2pChannel
            if (mgr != null && ch != null) {
                try {
                    mgr.removeGroup(
                        ch,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                refreshEndpoints()
                            }

                            override fun onFailure(reason: Int) {
                                refreshEndpoints()
                            }
                        },
                    )
                } catch (_: Throwable) {
                }
            }
            _localWifiState.value = LocalWifiState()
            refreshEndpoints()
        }
    }

    private fun findP2pInterfaceIp(preferredIface: String?): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val matchesPreferred = !preferredIface.isNullOrBlank() && name.equals(preferredIface, ignoreCase = true)
                if (matchesPreferred || name.startsWith("p2p")) {
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            addr.hostAddress?.let { return it }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    fun setSyncScreenNavigation(enabled: Boolean) {
        _syncScreenNavigation.value = enabled
    }

    fun setAudioRoutedToClient(enabled: Boolean, isUserExplicit: Boolean = false) {
        if (isUserExplicit) {
            userExplicitlyDisabledAudio = !enabled
        }
        mainHandler.post {
            val sp = speaker ?: return@post
            if (enabled) {
                sp.start()
            } else {
                sp.stop(resumeHere = true)
            }
        }
    }

    fun recordClientHeartbeat(clientId: String) {
        val now = SystemClock.elapsedRealtime()
        clientHeartbeats[clientId] = now
        pruneStaleClients(now)
    }

    fun pruneStaleClients(now: Long = SystemClock.elapsedRealtime()) {
        val iter = clientHeartbeats.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value > 15_000L) {
                iter.remove()
            }
        }
        val count = clientHeartbeats.size
        val previousCount = _connectedClients.value
        if (previousCount != count) {
            _connectedClients.value = count
        }
        if (count > 0) {
            acquireLocks()
            // iPhoneが接続されたら「iPhone/CarPlayから音を流す」をデフォルトで自動オンにする
            if (!_carPlaySession.value && !userExplicitlyDisabledAudio) {
                setAudioRoutedToClient(true, isUserExplicit = false)
            }
        } else {
            userExplicitlyDisabledAudio = false
            if (!_carPlaySession.value && !_localWifiState.value.active) {
                releaseLocks()
            }
        }
    }

    internal fun setCarPlaySession(active: Boolean) {
        _carPlaySession.value = active
        if (active) {
            acquireLocks()
        } else if (_connectedClients.value == 0 && !_localWifiState.value.active) {
            releaseLocks()
        }
    }

    private fun acquireLocks() {
        val ctx = appContext ?: return
        try {
            if (wakeLock == null) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitChord:CarPlayWebLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L)
            }
            if (wifiLock == null) {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BitChord:CarPlayWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
        }
    }

    fun refreshEndpoints() {
        val currentPort = _port.value
        val result = mutableListOf<NetworkEndpoint>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy") || name.startsWith("tun")) {
                    continue
                }
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val (label, priority) = classifyInterface(name, ip)
                        result.add(
                            NetworkEndpoint(
                                interfaceName = nif.name,
                                label = label,
                                ip = ip,
                                url = "http://$ip:$currentPort",
                                priority = priority,
                            ),
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val curWifi = _localWifiState.value
        if (curWifi.active && curWifi.ip.isNotBlank()) {
            val expectedUrl = "http://${curWifi.ip}:$currentPort"
            if (curWifi.url != expectedUrl) {
                _localWifiState.value = curWifi.copy(url = expectedUrl)
            }
            if (result.none { it.ip == curWifi.ip }) {
                result.add(
                    NetworkEndpoint(
                        interfaceName = "p2p0",
                        label = "アプリ内ローカル専用 Wi-Fi 電波 (直接通信)",
                        ip = curWifi.ip,
                        url = expectedUrl,
                        priority = 0,
                    ),
                )
            }
        }
        // iPhone/iOS Safari向けに、mDNS Bonjour解決可能な環境向けホスト名を登録 (優先度は実IPを優先)
        if (result.isNotEmpty() || _serverRunning.value) {
            result.add(
                NetworkEndpoint(
                    interfaceName = "mdns",
                    label = "固定ホスト名 (bitchord.local / Bonjour対応環境用)",
                    ip = "bitchord.local",
                    url = "http://bitchord.local:$currentPort",
                    priority = 2,
                ),
            )
        }
        _endpoints.value = result.sortedWith(compareBy<NetworkEndpoint> { it.priority }.thenBy { it.ip })
    }

    private fun classifyInterface(name: String, ip: String): Pair<String, Int> = when {
        name.startsWith("p2p") || ip.startsWith("192.168.49.") ->
            "アプリ内ローカル専用 Wi-Fi 電波 (直接通信)" to 0
        name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") ||
            name.startsWith("wlan1") || ip.startsWith("192.168.43.") || ip.startsWith("10.207.") ->
            "Wi-Fi テザリング / ホットスポット" to 0
        ip.startsWith("172.20.10.") ->
            "iPhone インターネット共有 (テザリング)" to 0
        name.startsWith("bt-pan") || name.startsWith("bnep") || ip.startsWith("192.168.44.") ->
            "Bluetooth テザリング (PAN)" to 1
        name.startsWith("rndis") || name.startsWith("usb") ->
            "USB テザリング" to 1
        name.startsWith("wlan") ->
            "Wi-Fi ネットワーク" to 1
        else ->
            "ローカルネットワーク ($name)" to 3
    }

    fun createSpeaker(
        context: Context,
        scope: CoroutineScope,
        localPlayer: () -> ExoPlayer?,
        onChanged: () -> Unit,
    ): RemoteSpeaker {
        localPlayerProvider = localPlayer
        val chainedOnChanged = {
            updatePlaybackSnapshot()
            onChanged()
        }
        return CarPlayWebSpeaker(context.applicationContext, localPlayer, chainedOnChanged).also {
            speaker = it
        }
    }

    fun releaseSpeaker() {
        speaker?.stop(resumeHere = false)
        speaker = null
    }

    /**
     * Resolves the authoritative track duration in milliseconds, preventing iOS Safari's
     * fragmented-MP4 double-duration (`moov` + `sidx` = 2x) from inflating the timeline.
     */
    internal fun resolveAuthoritativeDurationMs(
        mediaId: String?,
        clientDurationMs: Long = 0L,
        curEntryDurationMs: Long = 0L,
        currentSong: Song? = currentSongSnapshot,
        player: ExoPlayer? = localPlayerProvider?.invoke(),
    ): Long {
        if (!mediaId.isNullOrBlank()) {
            val mp4HeaderDur = CarPlayWebServer.getResolvedDurationMs(mediaId)
            if (mp4HeaderDur != null && mp4HeaderDur > 0L) {
                return mp4HeaderDur
            }
        }
        if (curEntryDurationMs > 0L) {
            return curEntryDurationMs
        }
        if (currentSong != null && (mediaId == null || currentSong.videoId == mediaId)) {
            val songDur = currentSong.durationMillis()
            if (songDur > 0L) return songDur
        }
        if (player != null) {
            if (!mediaId.isNullOrBlank()) {
                for (i in 0 until player.mediaItemCount) {
                    val item = player.getMediaItemAt(i)
                    if (item.mediaId == mediaId) {
                        val metaDur = item.mediaMetadata.durationMs ?: 0L
                        if (metaDur > 0L) return metaDur
                    }
                }
            }
            val playerDur = player.duration
            if (playerDur != C.TIME_UNSET && playerDur > 0L) {
                return playerDur
            }
        }
        return clientDurationMs.coerceAtLeast(0L)
    }

    @Volatile
    private var cachedPlaybackSnapshot: PlaybackSnapshot = PlaybackSnapshot()

    fun updatePlaybackSnapshot() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cachedPlaybackSnapshot = buildPlaybackSnapshotOnMain()
        } else {
            mainHandler.post {
                cachedPlaybackSnapshot = buildPlaybackSnapshotOnMain()
            }
        }
    }

    /**
     * Reads a thread-safe snapshot of the current queue and playback state.
     * Uses volatile caching so API threads are NEVER blocked and never time out with empty data!
     */
    fun getPlaybackSnapshot(callback: (PlaybackSnapshot) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cachedPlaybackSnapshot = buildPlaybackSnapshotOnMain()
            callback(cachedPlaybackSnapshot)
        } else {
            val cached = cachedPlaybackSnapshot
            // Asynchronously refresh the cached snapshot on the main thread for the next poll
            mainHandler.post {
                cachedPlaybackSnapshot = buildPlaybackSnapshotOnMain()
            }
            if (cached.mediaId != null || cached.queue.isNotEmpty()) {
                callback(cached)
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                var result: PlaybackSnapshot? = null
                mainHandler.post {
                    try {
                        result = buildPlaybackSnapshotOnMain()
                        result?.let { cachedPlaybackSnapshot = it }
                    } finally {
                        latch.countDown()
                    }
                }
                try {
                    latch.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                }
                callback(result ?: cachedPlaybackSnapshot)
            }
        }
    }

    data class PlaybackSnapshot(
        val activeOnIphone: Boolean = false,
        val mediaId: String? = null,
        val title: String = "",
        val artist: String = "",
        val album: String? = null,
        val thumbnailUrl: String? = null,
        val isPlaying: Boolean = false,
        val playWhenReady: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val commandSeq: Long = 0L,
        val seekSeq: Long = 0L,
        val wantedSeekMs: Long = 0L,
        val shuffle: Boolean = false,
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        val currentIndex: Int = 0,
        val queue: List<QueueEntry> = emptyList(),
    )

    private fun buildPlaybackSnapshotOnMain(): PlaybackSnapshot {
        val player = localPlayerProvider?.invoke()
        val sp = speaker
        val activeOnIphone = sp?.active == true
        val currentSong = currentSongSnapshot
        val currentItem = player?.currentMediaItem

        val mediaId = if (activeOnIphone) {
            sp.wantedMediaId ?: currentItem?.mediaId ?: currentSong?.videoId
        } else {
            currentItem?.mediaId ?: currentSong?.videoId
        }

        val queueList = mutableListOf<QueueEntry>()
        if (player != null && player.mediaItemCount > 0) {
            val count = player.mediaItemCount
            for (i in 0 until count) {
                val item = player.getMediaItemAt(i)
                val meta = item.mediaMetadata
                val itemDur = (meta.durationMs ?: 0L).coerceAtLeast(0L)
                    .takeIf { it > 0L }
                    ?: CarPlayWebServer.getResolvedDurationMs(item.mediaId)
                    ?: 0L
                queueList.add(
                    QueueEntry(
                        index = i,
                        videoId = item.mediaId,
                        title = meta.title?.toString().orEmpty(),
                        artist = meta.artist?.toString().orEmpty(),
                        album = meta.albumTitle?.toString(),
                        thumbnailUrl = meta.artworkUri?.toString(),
                        durationMs = itemDur,
                    ),
                )
            }
        } else if (currentSong != null) {
            val songDur = currentSong.durationMillis().takeIf { it > 0L }
                ?: CarPlayWebServer.getResolvedDurationMs(currentSong.videoId)
                ?: 0L
            queueList.add(
                QueueEntry(
                    index = 0,
                    videoId = currentSong.videoId,
                    title = currentSong.title,
                    artist = currentSong.artist,
                    album = currentSong.albumName,
                    thumbnailUrl = currentSong.thumbnailUrl,
                    durationMs = songDur,
                ),
            )
        }

        val currentIdx = player?.currentMediaItemIndex?.takeIf { it >= 0 } ?: 0
        val curEntry = queueList.getOrNull(currentIdx)

        val rawPosMs = if (activeOnIphone) {
            sp.positionMs.coerceAtLeast(0L)
        } else {
            player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        }

        val durMs = resolveAuthoritativeDurationMs(
            mediaId = mediaId,
            clientDurationMs = if (activeOnIphone && sp.durationMs != C.TIME_UNSET) sp.durationMs else 0L,
            curEntryDurationMs = curEntry?.durationMs ?: 0L,
            currentSong = currentSong,
            player = player,
        )
        val posMs = if (durMs > 0L) rawPosMs.coerceAtMost(durMs) else rawPosMs

        val isPlaying = if (activeOnIphone) {
            sp.isPlaying
        } else {
            player?.isPlaying == true
        }

        val playWhenReady = if (activeOnIphone) {
            sp.playWhenReady
        } else {
            player?.playWhenReady == true
        }

        val buffering = if (activeOnIphone) {
            sp.playbackState == Player.STATE_BUFFERING
        } else {
            player?.playbackState == Player.STATE_BUFFERING
        }

        return PlaybackSnapshot(
            activeOnIphone = activeOnIphone,
            mediaId = mediaId,
            title = currentSong?.title ?: curEntry?.title.orEmpty(),
            artist = currentSong?.artist ?: curEntry?.artist.orEmpty(),
            album = currentSong?.albumName ?: curEntry?.album,
            thumbnailUrl = currentSong?.thumbnailUrl ?: curEntry?.thumbnailUrl,
            isPlaying = isPlaying,
            playWhenReady = playWhenReady,
            buffering = buffering,
            positionMs = posMs,
            durationMs = durMs,
            commandSeq = sp?.commandSeq ?: 0L,
            seekSeq = sp?.seekSeq ?: 0L,
            wantedSeekMs = sp?.wantedSeekMs ?: posMs,
            shuffle = currentShuffleSnapshot,
            repeatMode = player?.repeatMode ?: currentRepeatModeSnapshot,
            currentIndex = currentIdx,
            queue = queueList,
        )
    }
}

/**
 * [RemoteSpeaker] implementation for the iPhone Safari / Apple CarPlay Web Player.
 *
 * When active, Android's local [ExoPlayer] holds the queue while staying paused/stopped,
 * and the iPhone's HTML5 `<audio>` element streams the track and drives the playback clock.
 */
internal class CarPlayWebSpeaker(
    private val app: Context,
    private val localPlayer: () -> ExoPlayer?,
    private val onChanged: () -> Unit,
) : RemoteSpeaker {

    override var sink: CastSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override var active: Boolean = false
        private set

    var wantsAutoActivate: Boolean = false
        private set

    var wantedMediaId: String? = null
        private set

    var commandSeq: Long = 1L
        private set

    var seekSeq: Long = 1L
        private set

    var wantedSeekMs: Long = 0L
        private set

    private class ClientStatus(
        val mediaId: String,
        val isPlaying: Boolean,
        val buffering: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val volume: Int,
        val at: Long,
    )

    private var status: ClientStatus? = null
    private var wantsPlay: Boolean = false
    private var loading: Boolean = false
    private var finished: Boolean = false
    private var requestedPositionMs: Long = 0L
    private var requestedAt: Long = 0L
    private var autoEndedForMediaId: String? = null
    private var lastSinkNotifyAt: Long = 0L

    private val loadingTimeoutRunnable = Runnable {
        if (loading) {
            loading = false
            sink?.onRemotePlaybackChanged()
            onChanged()
            CarPlayWebBridge.updatePlaybackSnapshot()
        }
    }
    // Guard against loading getting permanently stuck if client report drops or delays
    private val isLoadingEffective: Boolean
        get() = loading && (SystemClock.elapsedRealtime() - requestedAt < 2500L)

    override val playWhenReady: Boolean get() = wantsPlay

    override val isPlaying: Boolean
        get() {
            if (!active || !wantsPlay || finished) return false
            if (isLoadingEffective) return false
            val s = status
            val now = SystemClock.elapsedRealtime()
            // If client reported within 4 seconds, respect its playing status
            if (s != null && now - s.at < 4000L) {
                return s.isPlaying
            }
            // Fallback during Wi-Fi report jitter/gap: keep clock alive if host wants play
            return true
        }

    override val playbackState: Int
        get() {
            val now = SystemClock.elapsedRealtime()
            val isBufferingRecent = status?.buffering == true && (now - (status?.at ?: 0L) < 3000L)
            return when {
                isLoadingEffective -> Player.STATE_BUFFERING
                finished -> Player.STATE_ENDED
                isBufferingRecent -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            }
        }

    override val positionMs: Long
        get() {
            val s = status
            val now = SystemClock.elapsedRealtime()
            if (!active) return requestedPositionMs.coerceAtLeast(0L)
            if (isLoadingEffective || (now - requestedAt < SEEK_GRACE_MS && requestedPositionMs >= 0L)) {
                if (wantsPlay && !finished && requestedAt > 0L) {
                    val elapsed = (now - requestedAt).coerceAtLeast(0L)
                    val extrapolated = requestedPositionMs + elapsed
                    val dur = durationMs
                    return if (dur != C.TIME_UNSET && dur > 0L) extrapolated.coerceAtMost(dur) else extrapolated
                }
                return requestedPositionMs.coerceAtLeast(0L)
            }
            if (s == null) {
                if (wantsPlay && !finished) {
                    val elapsed = (now - requestedAt).coerceAtLeast(0L)
                    val extrapolated = requestedPositionMs + elapsed
                    val dur = durationMs
                    return if (dur != C.TIME_UNSET && dur > 0L) extrapolated.coerceAtMost(dur) else extrapolated
                }
                return requestedPositionMs.coerceAtLeast(0L)
            }
            if (!wantsPlay) return s.positionMs.coerceAtLeast(0L)
            // Extrapolate smoothly if client is playing or if client report is temporarily stalled
            val isMoving = (s.isPlaying || (wantsPlay && !finished)) && (now - s.at < 4000L)
            if (!isMoving) return s.positionMs.coerceAtLeast(0L)
            val elapsed = (now - s.at).coerceAtLeast(0L)
            val extrapolated = s.positionMs + elapsed
            val dur = durationMs
            return if (dur != C.TIME_UNSET && dur > 0L) extrapolated.coerceAtMost(dur) else extrapolated
        }

    override val durationMs: Long
        get() {
            val mediaId = status?.mediaId ?: wantedMediaId
            val authDur = CarPlayWebBridge.resolveAuthoritativeDurationMs(
                mediaId = mediaId,
                clientDurationMs = status?.durationMs ?: 0L,
            )
            return if (authDur > 0L) authDur else C.TIME_UNSET
        }

    override val deviceVolume: Int
        get() = status?.volume ?: 100

    fun start() {
        wantsAutoActivate = true
        if (active) return
        val player = localPlayer()
        if (player == null || player.mediaItemCount == 0) {
            CarPlayWebBridge.setCarPlaySession(true)
            return
        }
        active = true
        CarPlayWebBridge.setCarPlaySession(true)
        val wasPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        val pos = player.currentPosition.coerceAtLeast(0L)
        val currentId = player.currentMediaItem?.mediaId
        // Mute/stop local phone playback so sound only comes from iPhone / CarPlay
        player.pause()
        player.stop()

        wantedMediaId = currentId
        autoEndedForMediaId = null
        wantsPlay = if (wasPlaying) true else true
        loading = false
        finished = false
        requestedPositionMs = pos
        wantedSeekMs = pos
        requestedAt = SystemClock.elapsedRealtime()
        commandSeq++
        seekSeq++

        if (currentId != null) {
            CarPlayWebServer.warmUpTrack(currentId)
            warmUpNextTrack(player)
        }

        sink?.onRemoteRouteChanged()
        sink?.onRemotePlaybackChanged()
        onChanged()
    }

    fun stop(resumeHere: Boolean) {
        mainHandler.removeCallbacks(loadingTimeoutRunnable)
        wantsAutoActivate = false
        CarPlayWebBridge.setCarPlaySession(false)
        if (!active) return
        val wasPlaying = wantsPlay
        val pos = positionMs.coerceAtLeast(0L)
        val mediaId = status?.mediaId ?: wantedMediaId
        active = false
        status = null
        loading = false
        finished = false
        autoEndedForMediaId = null
        commandSeq++

        localPlayer()?.let { player ->
            val idx = mediaId?.let { indexOf(player, it) }
            if (idx != null) {
                player.seekTo(idx, pos)
            } else {
                player.seekTo(pos)
            }
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                player.prepare()
            }
            if (resumeHere && wasPlaying) {
                player.play()
            }
        }
        wantsPlay = false
        sink?.onRemoteRouteChanged()
        sink?.onRemotePlaybackChanged()
        onChanged()
    }

    override fun play() {
        if (!active) {
            if (wantsAutoActivate) start()
            return
        }
        wantsPlay = true
        finished = false
        requestedAt = SystemClock.elapsedRealtime()
        commandSeq++
        sink?.onRemotePlaybackChanged()
        onChanged()
        CarPlayWebBridge.updatePlaybackSnapshot()
    }

    override fun pause() {
        if (!active) return
        wantsPlay = false
        status = status?.let {
            ClientStatus(it.mediaId, false, false, it.positionMs, it.durationMs, it.volume, SystemClock.elapsedRealtime())
        }
        if (status != null) {
            requestedPositionMs = positionMs
            requestedAt = 0L
        }
        commandSeq++
        sink?.onRemotePlaybackChanged()
        onChanged()
        CarPlayWebBridge.updatePlaybackSnapshot()
    }

    override fun seekTo(positionMs: Long) {
        if (!active) return
        val authDur = durationMs
        val target = if (authDur != C.TIME_UNSET && authDur > 0L) {
            positionMs.coerceIn(0L, authDur)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        autoEndedForMediaId = null
        finished = false
        requestedPositionMs = target
        wantedSeekMs = target
        requestedAt = SystemClock.elapsedRealtime()
        seekSeq++
        commandSeq++
        status = status?.let {
            ClientStatus(
                it.mediaId,
                it.isPlaying,
                it.buffering,
                target,
                it.durationMs,
                it.volume,
                SystemClock.elapsedRealtime(),
            )
        }
        sink?.onRemotePlaybackChanged()
        onChanged()
        CarPlayWebBridge.updatePlaybackSnapshot()
    }

    override fun setVolume(fraction: Float) {
        // iPhone / CarPlay volume is managed on the car head unit or iPhone
    }

    override fun onLocalTransition(item: MediaItem?, reason: Int) {
        if (!active && wantsAutoActivate && item != null) {
            start()
            return
        }
        if (!active || item == null) return
        val newId = item.mediaId
        if (newId == wantedMediaId && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            localPlayer()?.let(::warmUpNextTrack)
            return
        }
        wantedMediaId = newId
        autoEndedForMediaId = null
        wantsPlay = true
        loading = true
        mainHandler.removeCallbacks(loadingTimeoutRunnable)
        mainHandler.postDelayed(loadingTimeoutRunnable, 2500L)
        finished = false
        requestedPositionMs = 0L
        wantedSeekMs = 0L
        requestedAt = SystemClock.elapsedRealtime()
        commandSeq++
        seekSeq++

        CarPlayWebServer.warmUpTrack(newId)
        localPlayer()?.let(::warmUpNextTrack)

        sink?.onRemotePlaybackChanged()
        onChanged()
    }

    override fun onLocalQueueChanged() {
        if (!active) return
        localPlayer()?.let(::warmUpNextTrack)
        commandSeq++
        onChanged()
    }

    override fun onLocalRepeatChanged(repeatMode: Int) {
        if (!active) return
        commandSeq++
        onChanged()
    }

    override fun onLocalStartedPlaying(player: Player) {
        if (!active) {
            if (wantsAutoActivate) {
                start()
            }
            return
        }
        player.pause()
        play()
    }

    fun onClientReport(
        mediaId: String,
        playing: Boolean,
        buffering: Boolean,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
        clientSeekSeq: Long = -1L,
    ) {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        val cleanMediaId = mediaId.substringBefore("?").trim()
        val targetId = wantedMediaId?.substringBefore("?")?.trim()
        val isTargetMatch = cleanMediaId.isNotBlank() && (
            targetId == null ||
            cleanMediaId == targetId ||
            cleanMediaId.contains(targetId) ||
            targetId.contains(cleanMediaId) ||
            (now - requestedAt > 1800L && playing)
        )

        if (isTargetMatch) {
            mainHandler.removeCallbacks(loadingTimeoutRunnable)
            val prev = status
            val wasLoading = loading
            loading = false
            if (wantedMediaId.isNullOrBlank()) {
                wantedMediaId = cleanMediaId
            }
            val isPendingSeek = if (clientSeekSeq >= 0L) {
                clientSeekSeq < seekSeq
            } else {
                now - requestedAt < SEEK_GRACE_MS
            }
            val authDurMs = CarPlayWebBridge.resolveAuthoritativeDurationMs(
                mediaId = cleanMediaId,
                clientDurationMs = durationMs,
            )
            val rawEffectivePos = if (isPendingSeek) {
                if (wantsPlay && !finished && requestedAt > 0L) {
                    val elapsed = (now - requestedAt).coerceAtLeast(0L)
                    val extrapolated = requestedPositionMs + elapsed
                    if (authDurMs > 0L) extrapolated.coerceAtMost(authDurMs) else extrapolated
                } else {
                    requestedPositionMs
                }
            } else {
                requestedAt = 0L
                positionMs.coerceAtLeast(0L)
            }
            val effectivePos = if (authDurMs > 0L) {
                rawEffectivePos.coerceAtMost(authDurMs)
            } else {
                rawEffectivePos
            }
            if (rawEffectivePos < 2_000L && autoEndedForMediaId == cleanMediaId) {
                autoEndedForMediaId = null
            }

            // Smooth out Wi-Fi HTTP report jitter during steady playback so Android's LyricClock
            // receives a monotonic 1.0x playhead without 400ms network-jitter jumps.
            val currentExtrap = if (prev != null && prev.mediaId == cleanMediaId && prev.isPlaying) {
                val elapsed = (now - prev.at).coerceAtLeast(0L)
                prev.positionMs + elapsed
            } else {
                effectivePos
            }
            val drift = effectivePos - currentExtrap
            val anchoredPos = if (
                prev != null &&
                prev.mediaId == cleanMediaId &&
                prev.isPlaying &&
                playing &&
                !buffering &&
                !isPendingSeek &&
                abs(drift) <= 600L
            ) {
                (currentExtrap + (drift * 0.25).toLong()).coerceAtLeast(0L)
            } else {
                effectivePos
            }

            // If host has explicitly paused, reject late/stale playing=true reports from client
            val effectivePlaying = if (!wantsPlay) false else playing

            status = ClientStatus(
                mediaId = cleanMediaId,
                isPlaying = effectivePlaying,
                buffering = buffering,
                positionMs = if (authDurMs > 0L) anchoredPos.coerceAtMost(authDurMs) else anchoredPos,
                durationMs = authDurMs,
                volume = 100,
                at = now,
            )
            val reachedAuthoritativeEnd = !isPendingSeek &&
                effectivePlaying &&
                authDurMs > 5_000L &&
                rawEffectivePos >= authDurMs - 550L
            if (ended || reachedAuthoritativeEnd) {
                if (autoEndedForMediaId != cleanMediaId) {
                    autoEndedForMediaId = cleanMediaId
                    onClientTrackEnded()
                }
            }

            val periodicSyncNeeded = now - lastSinkNotifyAt > 450L
            val stateChanged = prev == null ||
                wasLoading ||
                prev.mediaId != cleanMediaId ||
                prev.isPlaying != effectivePlaying ||
                prev.buffering != buffering ||
                prev.durationMs != authDurMs ||
                ended ||
                reachedAuthoritativeEnd ||
                abs(drift) > 650L ||
                periodicSyncNeeded

            if (stateChanged) {
                lastSinkNotifyAt = now
                sink?.onRemotePlaybackChanged()
                onChanged()
                CarPlayWebBridge.updatePlaybackSnapshot()
            }
        } else if (cleanMediaId.isNotBlank() && playing && loading) {
            mainHandler.removeCallbacks(loadingTimeoutRunnable)
            loading = false
            sink?.onRemotePlaybackChanged()
            onChanged()
            CarPlayWebBridge.updatePlaybackSnapshot()
        }
    }

    fun onClientDirectTransport(play: Boolean, currentPositionMs: Long?) {
        if (!active) {
            start()
        }
        wantsPlay = play
        if (!play) {
            status = status?.let {
                ClientStatus(it.mediaId, false, false, currentPositionMs ?: it.positionMs, it.durationMs, it.volume, SystemClock.elapsedRealtime())
            }
            requestedAt = 0L
        } else {
            requestedAt = SystemClock.elapsedRealtime()
        }
        if (currentPositionMs != null && currentPositionMs >= 0L) {
            requestedPositionMs = currentPositionMs
            wantedSeekMs = currentPositionMs
        }
        commandSeq++
        sink?.onRemotePlaybackChanged()
        onChanged()
        CarPlayWebBridge.updatePlaybackSnapshot()
    }

    private fun onClientTrackEnded() {
        val player = localPlayer() ?: return
        when {
            player.repeatMode == Player.REPEAT_MODE_ONE -> {
                autoEndedForMediaId = null
                wantsPlay = true
                finished = false
                requestedPositionMs = 0L
                wantedSeekMs = 0L
                requestedAt = SystemClock.elapsedRealtime()
                seekSeq++
                commandSeq++
            }
            player.hasNextMediaItem() -> {
                wantsPlay = true
                player.seekToNextMediaItem()
            }
            player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 0 -> {
                autoEndedForMediaId = null
                wantsPlay = true
                player.seekTo(0, 0L)
            }
            else -> {
                wantsPlay = false
                finished = true
            }
        }
    }

    private fun warmUpNextTrack(player: Player) {
        val cur = player.currentMediaItemIndex
        if (cur in 0 until player.mediaItemCount - 1) {
            val nextId = player.getMediaItemAt(cur + 1).mediaId
            if (nextId.isNotBlank()) {
                CarPlayWebServer.warmUpTrack(nextId)
            }
        }
    }

    private fun indexOf(player: Player, mediaId: String): Int? {
        val from = player.currentMediaItemIndex.coerceAtLeast(0)
        return (from until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
            ?: (0 until from).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
    }

    private companion object {
        const val SEEK_GRACE_MS = 2_500L
    }
}

/**
 * Lightweight, embedded mDNS/DNS-SD responder that listens on UDP 5353
 * across softap/swlan/p2p/wlan network interfaces, allowing iPhones in Apple CarPlay mode
 * to resolve `http://bitchord.local:8765` directly even through Android carrier tethering.
 */
private object SimpleMdnsResponder {
    private const val TAG = "SimpleMdnsResponder"
    private var job: Job? = null
    private var socket: MulticastSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(port: Int) {
        stop()
        job = scope.launch {
            try {
                val group = InetAddress.getByName("224.0.0.251")
                val s = MulticastSocket(5353).apply {
                    reuseAddress = true
                    timeToLive = 255
                }
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { nif ->
                    try {
                        if (nif.isUp && !nif.isLoopback) {
                            s.joinGroup(InetSocketAddress(group, 5353), nif)
                        }
                    } catch (_: Throwable) {
                    }
                }
                socket = s
                val buf = ByteArray(1500)
                while (!s.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(packet)
                    } catch (_: Throwable) {
                        break
                    }
                    val len = packet.length
                    if (len < 12) continue
                    val queryStr = String(buf, 0, len, StandardCharsets.ISO_8859_1).lowercase()
                    if (queryStr.contains("bitchord") && queryStr.contains("local")) {
                        val localIp = findBestLocalIp() ?: continue
                        val response = buildDnsResponse(localIp)
                        try {
                            s.send(DatagramPacket(response, response.size, packet.address, packet.port))
                        } catch (_: Throwable) {
                        }
                        try {
                            s.send(DatagramPacket(response, response.size, group, 5353))
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "SimpleMdnsResponder stopped: ${t.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
    }

    private fun findBestLocalIp(): Inet4Address? {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            if (name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") || name.startsWith("p2p")) {
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr
                }
            }
        }
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr
            }
        }
        return null
    }

    private fun buildDnsResponse(ip: Inet4Address): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(0); baos.write(0) // Transaction ID
        baos.write(0x84); baos.write(0x00) // Flags: Standard authoritative response
        baos.write(0); baos.write(0) // Questions: 0
        baos.write(0); baos.write(1) // Answers: 1
        baos.write(0); baos.write(0) // Authority: 0
        baos.write(0); baos.write(0) // Additional: 0
        // Name: \x08bitchord\x05local\x00
        val nameBytes = byteArrayOf(
            8, 'b'.code.toByte(), 'i'.code.toByte(), 't'.code.toByte(), 'c'.code.toByte(),
            'h'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'd'.code.toByte(),
            5, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
            0,
        )
        baos.write(nameBytes)
        baos.write(0); baos.write(1) // Type: A
        baos.write(0x80.toInt()); baos.write(1) // Class: IN (cache-flush)
        baos.write(0); baos.write(0); baos.write(0); baos.write(120) // TTL: 120s
        baos.write(0); baos.write(4) // RDLENGTH: 4
        baos.write(ip.address) // RDATA: 4-byte IPv4
        return baos.toByteArray()
    }
}
