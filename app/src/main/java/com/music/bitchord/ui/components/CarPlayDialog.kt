package com.music.bitchord.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
    val connectedClients by CarPlayWebBridge.connectedClients.collectAsStateWithLifecycle()
    val carPlaySession by CarPlayWebBridge.carPlaySession.collectAsStateWithLifecycle()
    val serverPort by CarPlayWebBridge.port.collectAsStateWithLifecycle()

    var selectedEndpointIdx by remember { mutableIntStateOf(0) }

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
                        text = "CarPlay / iPhone 連携設定",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = "Wi-Fiテザリングまたは同一ネットワークでiPhoneと同期再生",
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
                serverRunning -> "サーバー稼働中 — iPhoneのアプリまたはSafariで接続してください"
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

            // QR Code Card (Scan with iPhone Camera or BitChord App)
            val safeEndpointIdx = selectedEndpointIdx.coerceIn(0, (endpoints.size - 1).coerceAtLeast(0))
            val activeEndpoint = endpoints.getOrNull(safeEndpointIdx)
            val targetWebUrl = activeEndpoint?.url ?: "http://192.168.43.1:$serverPort"
            val effectiveQrContent = targetWebUrl

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

                if (endpoints.size > 1) {
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
                        text = "iPhoneのBitChordアプリのカメラボタン、または標準カメラで読み取ると接続先が自動設定されます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = "下の「通常のWi-Fiテザリング設定を開く」でテザリングをオンにすると、ここに接続用QRコードが表示されます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            // One-tap Normal Tethering (Mobile Hotspot) Shortcut Card
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
                        text = "通常のWi-Fiテザリング設定を開く",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = "Galaxyのモバイルホットスポットをオンにすると、iPhoneからGoogleマップもBitChordも同時に利用できます",
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
                    subtitle = "iPhone接続時は自動でオンになります。オンにするとGalaxy本体の音を止め、iPhoneのBitChord CarPlayアプリ経由で車載オーディオへ音声を流します",
                    checked = carPlaySession,
                    onCheckedChange = { CarPlayWebBridge.setAudioRoutedToClient(it, isUserExplicit = true) },
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
                        text = "ローカルIPアドレスが見つかりません。テザリングをオンにするか、同一Wi-Fiネットワークに接続してください。",
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
                    text = "CarPlay / iPhone 連携の使い方",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "1. 上の「通常のWi-Fiテザリング設定を開く」からGalaxyのモバイルホットスポット（テザリング）をオンにします。\n" +
                        "2. iPhoneをGalaxyのWi-Fiに接続します。\n" +
                        "3. iPhoneのBitChordアプリ（設定 > 接続先 Galaxy）の「カメラでQRコードを読み取り」ボタンを押して上のQRコードをスキャンするか、標準カメラでスキャンして接続します。\n" +
                        "4. 接続されると、Galaxyで再生中の曲、アートワーク、キュー、シークバーがiPhoneおよび車載CarPlayに完全同期します。",
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
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun copyToClipboard(context: Context, text: String, label: String = "URL") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("BitChord $label", text))
    Toast.makeText(context, "${label}をコピーしました: $text", Toast.LENGTH_SHORT).show()
}

private fun openTetheringSettings(context: Context) {
    val tetherSettingsIntent = Intent().apply {
        action = Intent.ACTION_MAIN
        setClassName("com.android.settings", "com.android.settings.TetherSettings")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        context.startActivity(tetherSettingsIntent)
    } catch (_: Exception) {
        runCatching { context.startActivity(fallbackIntent) }
    }
}
