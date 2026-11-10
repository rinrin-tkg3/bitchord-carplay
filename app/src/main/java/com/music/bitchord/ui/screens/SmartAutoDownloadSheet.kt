package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AutoDownloadQuality
import com.music.bitchord.data.settings.AutoDownloadTarget
import com.music.bitchord.download.AutoDownloadManager
import com.music.bitchord.ui.components.IosSwitch
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartAutoDownloadSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by AppSettings.autoDownloadFavorites.collectAsStateWithLifecycle()
    val target by AppSettings.autoDownloadTarget.collectAsStateWithLifecycle()
    val quality by AppSettings.autoDownloadQuality.collectAsStateWithLifecycle()
    val maxSongs by AppSettings.autoDownloadMaxSongs.collectAsStateWithLifecycle()
    val maxStorageMb by AppSettings.autoDownloadMaxStorageMb.collectAsStateWithLifecycle()
    val onlyCharging by AppSettings.autoDownloadOnlyWhenCharging.collectAsStateWithLifecycle()
    val wifiOnly by AppSettings.autoDownloadWifiOnly.collectAsStateWithLifecycle()

    val autoDownloadedIds by AutoDownloadManager.autoDownloadedIds.collectAsStateWithLifecycle()
    val totalBytes = remember(autoDownloadedIds) { AutoDownloadManager.totalAutoDownloadedBytes(context) }
    val totalMb = totalBytes / (1024 * 1024)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "スマート自動ダウンロード",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "よく聴く曲を自動保存し、通信量0で再生",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "閉じる",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Main Switch Card
                item {
                    SheetCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "自動ダウンロードを有効化",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Wi-Fi接続時にお気に入りやよく聴く曲を自動的にダウンロードしてローカル保存します",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            IosSwitch(
                                checked = enabled,
                                onCheckedChange = AppSettings::setAutoDownloadFavorites,
                            )
                        }
                    }
                }

                if (enabled) {
                    // Target Selection Card
                    item {
                        SheetSectionHeader(title = "ダウンロード対象")
                        SheetCard {
                            AutoDownloadTarget.entries.forEachIndexed { index, itemTarget ->
                                if (index > 0) SheetDivider()
                                OptionRow(
                                    title = itemTarget.label,
                                    subtitle = when (itemTarget) {
                                        AutoDownloadTarget.BOTH -> "お気に入り曲と今月再生回数の多い曲の両方を保存"
                                        AutoDownloadTarget.LIKED_ONLY -> "ライブラリでお気に入りに登録した楽曲のみ"
                                        AutoDownloadTarget.TOP_PLAYED_ONLY -> "再生履歴から自動判定された上位曲のみ"
                                    },
                                    selected = target == itemTarget,
                                    onClick = { AppSettings.setAutoDownloadTarget(itemTarget) },
                                )
                            }
                        }
                    }

                    // Audio Quality Card
                    item {
                        SheetSectionHeader(title = "ダウンロード音質")
                        SheetCard {
                            AutoDownloadQuality.entries.forEachIndexed { index, itemQuality ->
                                if (index > 0) SheetDivider()
                                OptionRow(
                                    title = itemQuality.label,
                                    subtitle = itemQuality.detail,
                                    selected = quality == itemQuality,
                                    onClick = { AppSettings.setAutoDownloadQuality(itemQuality) },
                                )
                            }
                        }
                    }

                    // Max Songs Limit Card (Enhanced with Slider & Quick Preset Chips)
                    item {
                        SheetSectionHeader(title = "最大曲数制限")
                        SheetCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = if (maxSongs == 0) "無制限" else "$maxSongs 曲",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    val approxEst = if (maxSongs == 0) "空き容量次第" else if (maxSongs >= 1000) "約${String.format(java.util.Locale.US, "%.1f", maxSongs * 8.0 / 1024)} GB" else "約${(maxSongs * 8.0).toInt()} MB"
                                    Text(
                                        text = "目安: $approxEst",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                // Slider: 20 to 2000 songs
                                var sliderPos by remember(maxSongs) {
                                    mutableFloatStateOf(if (maxSongs <= 0) 2000f else maxSongs.toFloat().coerceIn(20f, 2000f))
                                }
                                Slider(
                                    value = sliderPos,
                                    onValueChange = {
                                        sliderPos = it
                                        val rounded = ((it / 10f).roundToInt() * 10).coerceIn(20, 2000)
                                        AppSettings.setAutoDownloadMaxSongs(rounded)
                                    },
                                    valueRange = 20f..2000f,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Spacer(Modifier.height(12.dp))

                                Text(
                                    text = "クイック選択",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val presets = listOf(30, 50, 100, 200, 300, 500, 1000, 2000, 0)
                                    presets.forEach { count ->
                                        val isSelected = maxSongs == count
                                        val label = if (count == 0) "無制限" else "$count 曲"
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.clickable {
                                                AppSettings.setAutoDownloadMaxSongs(count)
                                            }
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Max Storage Limit Card
                    item {
                        SheetSectionHeader(title = "最大ストレージ容量制限")
                        SheetCard {
                            val storageOptions = listOf(
                                500 to "500 MB",
                                1000 to "1 GB",
                                2000 to "2 GB (標準おすすめ)",
                                5000 to "5 GB",
                                10000 to "10 GB",
                                20000 to "20 GB",
                                50000 to "50 GB",
                                0 to "無制限",
                            )
                            storageOptions.forEachIndexed { index, (mb, label) ->
                                if (index > 0) SheetDivider()
                                OptionRow(
                                    title = label,
                                    subtitle = if (mb == 0) "端末の空き容量がある限り保存" else "上限に達すると最近聴いていない曲を自動整理",
                                    selected = maxStorageMb == mb,
                                    onClick = { AppSettings.setAutoDownloadMaxStorageMb(mb) },
                                )
                            }
                        }
                    }

                    // Conditions Card
                    item {
                        SheetSectionHeader(title = "実行条件")
                        SheetCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.Wifi,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "Wi-Fi接続時のみ実行",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "モバイルデータ通信での消費を完全にブロック",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IosSwitch(
                                    checked = wifiOnly,
                                    onCheckedChange = AppSettings::setAutoDownloadWifiOnly,
                                )
                            }
                            SheetDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.BatteryChargingFull,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "充電中のみ実行",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "端末のバッテリー消費を防止",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IosSwitch(
                                    checked = onlyCharging,
                                    onCheckedChange = AppSettings::setAutoDownloadOnlyWhenCharging,
                                )
                            }
                        }
                    }

                    // Current Storage & Manual Actions Card
                    item {
                        SheetSectionHeader(title = "現在の保存状況")
                        SheetCard {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Rounded.Bolt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "自動ダウンロード済み",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Text(
                                        text = "${autoDownloadedIds.size} 曲 (約 ${totalMb} MB)",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                Spacer(Modifier.height(16.dp))

                                Button(
                                    onClick = {
                                        AutoDownloadManager.syncNow(context)
                                        Toast.makeText(context, "スマート自動ダウンロードを開始しました", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("今すぐ自動ダウンロードを実行")
                                }

                                if (autoDownloadedIds.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            AutoDownloadManager.clearAutoDownloads(context)
                                            Toast.makeText(context, "自動ダウンロードした曲をすべて削除しました", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("自動ダウンロード曲を一括削除")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Rounded.Check,
                contentDescription = "選択中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
