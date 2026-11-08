package com.music.bitchord.ui.components

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.carplay.CarPlayWebBridge
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarPlaySheet(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val serverEnabled by AppSettings.carPlayServerEnabled.collectAsStateWithLifecycle()
    val serverRunning by CarPlayWebBridge.serverRunning.collectAsStateWithLifecycle()
    val endpoints by CarPlayWebBridge.endpoints.collectAsStateWithLifecycle()
    val localWifiState by CarPlayWebBridge.localWifiState.collectAsStateWithLifecycle()
    val connectedClients by CarPlayWebBridge.connectedClients.collectAsStateWithLifecycle()
    val carPlaySession by CarPlayWebBridge.carPlaySession.collectAsStateWithLifecycle()
    val syncScreen by CarPlayWebBridge.syncScreenNavigation.collectAsStateWithLifecycle()
    val serverPort by CarPlayWebBridge.port.collectAsStateWithLifecycle()

    // 0 = Open BitChord URL QR, 1 = Join Wi-Fi QR (when Local Wi-Fi is active)
    var qrMode by remember { mutableIntStateOf(0) }
    var selectedEndpointIdx by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted || hasLocalWifiPermission(context)) {
            CarPlayWebBridge.startLocalWifiRadio()
        } else {
            Toast.makeText(
                context,
                "ローカルWi-Fi電波を出すには付近のWi-Fiデバイス権限を許可してください",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            CarPlayWebBridge.refreshEndpoints()
            CarPlayWebBridge.pruneStaleClients()
            delay(2_000L)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsCar,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "CarPlay / iPhone 連携",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = "ローカル専用Wi-Fi電波またはテザリングでiPhone 7と同期再生",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { CarPlayWebBridge.refreshEndpoints() }) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "IPを再検出",
                    )
                }
            }

            // Status Banner
            val statusColor = when {
                carPlaySession -> Color(0xFF30D158)
                connectedClients > 0 -> Color(0xFF0A84FF)
                serverRunning -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            }
            val statusText = when {
                carPlaySession -> "iPhone / CarPlay から音声出力中（双方向同期アクティブ）"
                connectedClients > 0 -> "iPhone 接続中 ($connectedClients 台) — 画面・操作同期中"
                serverRunning -> "サーバー稼働中 — iPhoneのSafariで下記URLを開いてください"
                else -> "連携サーバー停止中"
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(statusColor.copy(alpha = 0.14f))
                    .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // QR Code Card (Scan with iPhone Camera)
            val safeEndpointIdx = selectedEndpointIdx.coerceIn(0, (endpoints.size - 1).coerceAtLeast(0))
            val activeEndpoint = endpoints.getOrNull(safeEndpointIdx)
            val targetWebUrl = when {
                localWifiState.active && qrMode == 0 && activeEndpoint != null -> activeEndpoint.url
                localWifiState.active -> localWifiState.url
                activeEndpoint != null -> activeEndpoint.url
                else -> ""
            }
            val wifiQrPayload = if (localWifiState.active && localWifiState.ssid.isNotBlank()) {
                "WIFI:S:${localWifiState.ssid};T:WPA;P:${localWifiState.password};;"
            } else {
                ""
            }
            val effectiveQrContent = if (localWifiState.active && qrMode == 1 && wifiQrPayload.isNotBlank()) {
                wifiQrPayload
            } else {
                targetWebUrl
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "iPhoneのカメラでQRコード読み取り接続",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }

                if (localWifiState.active) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        QrModeTab(
                            title = "① Wi-Fi 接続 QR",
                            selected = qrMode == 1,
                            onClick = { qrMode = 1 },
                            modifier = Modifier.weight(1f),
                        )
                        QrModeTab(
                            title = "② BitChord を開く QR",
                            selected = qrMode == 0,
                            onClick = { qrMode = 0 },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (endpoints.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        endpoints.forEachIndexed { idx, ep ->
                            QrModeTab(
                                title = ep.ip,
                                selected = safeEndpointIdx == idx,
                                onClick = { selectedEndpointIdx = idx },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                if (effectiveQrContent.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        QrCode(
                            content = effectiveQrContent,
                            size = 188.dp,
                            foreground = Color.Black,
                            background = Color.White,
                            quietZone = 10.dp,
                        )
                    }
                    Text(
                        text = if (localWifiState.active && qrMode == 1) {
                            "iPhoneの標準カメラを向けると「ネットワーク『${localWifiState.ssid}』に接続」が出ます"
                        } else {
                            "iPhoneの標準カメラを向けると「Safariで『$targetWebUrl』を開く」が出ます"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = "下の「通常のWi-Fiテザリング設定を開く」でテザリングをオンにするか、「ローカル専用Wi-Fi電波を発信」をオンにするとここにQRコードが表示されます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            // One-tap Normal Tethering (Mobile Hotspot) Shortcut Card (Recommended when using Google Maps)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .clickable { openTetheringSettings(context) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.WifiTethering,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "通常のWi-Fiテザリング設定を開く（Googleマップ併用におすすめ）",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = "Galaxyのモバイルホットスポットをオンにすると、iPhone側の設定不要でGoogleマップもBitChordも同時に使えます（上のQRコードで即接続）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.OpenInNew,
                    contentDescription = "設定を開く",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Local Direct Wi-Fi Radio Card (No Carrier Tethering Needed + Built-in Proxy for Google Maps)
            val radioAccent = if (localWifiState.active) Color(0xFF30D158) else MaterialTheme.colorScheme.primary
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (localWifiState.active) {
                            Color(0xFF30D158).copy(alpha = 0.11f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f)
                        },
                    )
                    .border(
                        width = 1.dp,
                        color = if (localWifiState.active) {
                            Color(0xFF30D158).copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (localWifiState.active || localWifiState.starting) {
                                CarPlayWebBridge.stopLocalWifiRadio()
                            } else if (hasLocalWifiPermission(context)) {
                                CarPlayWebBridge.startLocalWifiRadio()
                            } else {
                                permissionLauncher.launch(requiredLocalWifiPermissions())
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WifiTethering,
                        contentDescription = null,
                        tint = radioAccent,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "ローカル専用 Wi-Fi 電波を発信",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            text = if (localWifiState.starting) {
                                "直接通信用の Wi-Fi 電波を立ち上げています..."
                            } else if (localWifiState.active) {
                                "${localWifiState.modeLabel} — QR読み取り＆内蔵プロキシでGoogleマップも対応"
                            } else {
                                "テザリングを使わずにGalaxyからiPhone専用の直接Wi-Fi電波を出します"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    IosSwitch(
                        checked = localWifiState.active || localWifiState.starting,
                        onCheckedChange = { enable ->
                            if (!enable) {
                                CarPlayWebBridge.stopLocalWifiRadio()
                            } else if (hasLocalWifiPermission(context)) {
                                CarPlayWebBridge.startLocalWifiRadio()
                            } else {
                                permissionLauncher.launch(requiredLocalWifiPermissions())
                            }
                        },
                    )
                }

                if (localWifiState.error != null) {
                    Text(
                        text = localWifiState.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                if (localWifiState.active) {
                    val pacUrl = "http://${localWifiState.ip}:$serverPort/proxy.pac"
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), thickness = 0.5.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CredentialRow(
                            label = "1. ネットワーク名 (SSID) — 上の「① Wi-Fi 接続 QR」でも接続可",
                            value = localWifiState.ssid,
                            onCopy = { copyToClipboard(context, localWifiState.ssid, "SSID") },
                        )
                        CredentialRow(
                            label = "2. Wi-Fi パスワード",
                            value = localWifiState.password,
                            onCopy = { copyToClipboard(context, localWifiState.password, "パスワード") },
                        )
                        CredentialRow(
                            label = "3. iPhoneのSafariで開く専用固定URL — 上の「② BitChord を開く QR」",
                            value = localWifiState.url,
                            highlight = true,
                            onCopy = { copyToClipboard(context, localWifiState.url, "URL") },
                        )
                        CredentialRow(
                            label = "4. ローカルWi-FiでGoogleマップを使う場合（iPhoneのWi-Fi詳細 > HTTPプロキシ > 自動）",
                            value = pacUrl,
                            onCopy = { copyToClipboard(context, pacUrl, "プロキシ自動設定URL") },
                        )
                    }
                }
            }

            // Switches
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(vertical = 4.dp),
            ) {
                SwitchRow(
                    title = "連携Webサーバーを有効にする",
                    subtitle = "アプリ起動時に自動でiPhone接続待ち受けを開始します",
                    checked = serverEnabled,
                    onCheckedChange = { enabled ->
                        AppSettings.setCarPlayServerEnabled(enabled)
                        if (enabled) CarPlayWebBridge.startServer() else CarPlayWebBridge.stopServer()
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                SwitchRow(
                    title = "iPhone / CarPlay から音声を流す",
                    subtitle = "iPhone接続時は自動でオンになります。オンにするとGalaxy本体の音を止め、iPhoneのSafari経由でCarPlayへ音声を流します",
                    checked = carPlaySession,
                    onCheckedChange = { CarPlayWebBridge.setAudioRoutedToClient(it, isUserExplicit = true) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                SwitchRow(
                    title = "プレーヤー・歌詞パネルの開閉も同期",
                    subtitle = "Androidで開いている画面状態（フルプレーヤー・歌詞・キュー）をiPhoneにも反映します",
                    checked = syncScreen,
                    onCheckedChange = { CarPlayWebBridge.setSyncScreenNavigation(it) },
                )
            }

            // Endpoints List
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "検出された接続URL一覧",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "タップでコピー",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (endpoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(16.dp),
                ) {
                    Text(
                        text = "ローカルIPアドレスが見つかりません。\n上の「ローカル専用 Wi-Fi 電波を発信」をオンにするか、テザリングをオンにしてください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                endpoints.forEach { ep ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                            .clickable {
                                copyToClipboard(context, ep.url, "URL")
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = ep.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = ep.url,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "コピー",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Instructions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "CarPlay で Googleマップと併用する方法（2通り）",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "【方法A：通常のWi-Fiテザリング（一番簡単・設定不要）】\n" +
                        "1. 上の「通常のWi-Fiテザリング設定を開く」からGalaxyのモバイルホットスポットをオンにします。\n" +
                        "2. iPhoneをテザリングWi-Fiに繋ぎ、上のQRコードをiPhoneのカメラで読み取るだけでSafariでBitChordが開き、Googleマップもそのまま使えます。\n\n" +
                        "【方法B：ローカル専用Wi-Fi電波でGoogleマップも使う場合】\n" +
                        "1. 「ローカル専用Wi-Fi電波を発信」をオンにし、「① Wi-Fi接続QR」をiPhoneカメラで読み取って接続します。\n" +
                        "2. iPhoneの「設定 > Wi-Fi > DIRECT-BC-BitChord右の(i) > 一番下のHTTPプロキシ > 自動」に `http://192.168.49.1:$serverPort/proxy.pac`（または手動でサーバー `192.168.49.1` / ポート `$serverPort`）を1回設定すると、次回からローカル専用Wi-FiのままGalaxyの5G/4G経由でGoogleマップも通信できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun QrModeTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun openTetheringSettings(context: Context) {
    val candidates = listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$WifiTetherSettingsActivity"),
        Intent("android.settings.TETHER_SETTINGS"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) {
            return
        }
    }
}

@Composable
private fun CredentialRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .clickable(onClick = onCopy)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (highlight) Color(0xFF30D158) else MaterialTheme.colorScheme.onSurface,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = "コピー",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        IosSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun requiredLocalWifiPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasLocalWifiPermission(context: Context): Boolean =
    requiredLocalWifiPermissions().all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

private fun copyToClipboard(context: Context, text: String, label: String = "URL") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("BitChord $label", text))
    Toast.makeText(context, "${label}をコピーしました: $text", Toast.LENGTH_SHORT).show()
}
