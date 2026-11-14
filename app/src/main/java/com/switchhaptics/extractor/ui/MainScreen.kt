package com.switchhaptics.extractor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.switchhaptics.extractor.extractor.ExtractedBnvibItem
import com.switchhaptics.extractor.haptics.BnvibParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    onPickKeys: () -> Unit,
    onPickXci: () -> Unit,
    onStartExtract: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSelectItem: (ExtractedBnvibItem?) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Switch BNVIB 提取器 (阶段一)") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // File picker row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onPickKeys,
                    enabled = !uiState.isExtracting,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Key, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = uiState.keysDisplayName ?: "选择 prod.keys",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onPickXci,
                    enabled = !uiState.isExtracting,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = uiState.xciDisplayName ?: "选择 .xci / .nsp",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onStartExtract,
                enabled = uiState.canStartExtraction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (uiState.isExtracting) "正在提取..." else "开始扫描并提取 .bnvib")
            }

            // Progress & status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.errorText != null) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = uiState.errorText ?: uiState.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.errorText != null) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (uiState.isExtracting) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { uiState.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Search bar once items exist
            if (uiState.items.isNotEmpty()) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchChange,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("按文件名或 RomFS 路径筛选 (${uiState.filteredItems.size}/${uiState.items.size})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Extracted BNVIB list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.filteredItems, key = { it.id }) { item ->
                    BnvibItemCard(item = item, onClick = { onSelectItem(item) })
                }
            }
        }
    }

    uiState.selectedItem?.let { item ->
        BnvibDetailDialog(
            item = item,
            onDismiss = { onSelectItem(null) }
        )
    }
}

@Composable
private fun BnvibItemCard(
    item: ExtractedBnvibItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Vibration,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${item.sizeBytes} B",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = item.sourcePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (item.info.isValid) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SuggestionChip(
                        onClick = onClick,
                        label = { Text(item.info.type.label, style = MaterialTheme.typography.labelSmall) }
                    )
                    SuggestionChip(
                        onClick = onClick,
                        label = { Text("${item.info.durationMs} ms", style = MaterialTheme.typography.labelSmall) }
                    )
                    SuggestionChip(
                        onClick = onClick,
                        label = { Text("${item.info.sampleRateMs}ms/帧", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BnvibDetailDialog(
    item: ExtractedBnvibItem,
    onDismiss: () -> Unit
) {
    val info = item.info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = item.fileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("来源路径: ${item.sourcePath}", style = MaterialTheme.typography.bodySmall)
                Text("本地缓存: ${item.savedFile.absolutePath}", style = MaterialTheme.typography.bodySmall)
                if (info.isValid) {
                    Text("振动类型: ${info.type.label}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "采样率: ${info.sampleRateMs} ms/帧 | 总帧数: ${info.sampleCount} | 总时长: ${info.durationMs} ms",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (info.loopStart != null && info.loopEnd != null) {
                        Text(
                            "循环区间: ${info.loopStart} -> ${info.loopEnd}" +
                                (info.loopWait?.let { " (等待 $it)" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("振幅包络预览（蓝: 低频 / 橙: 高频）", style = MaterialTheme.typography.labelMedium)
                    AmplitudePreviewCanvas(samples = info.samples)
                } else {
                    Text("文件头解析失败（Magic=0x${info.magic.toString(16)}）", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun AmplitudePreviewCanvas(samples: List<BnvibParser.BnvibSample>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
    ) {
        if (samples.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val step = (samples.size / 120).coerceAtLeast(1)
        val drawnCount = ((samples.size + step - 1) / step).coerceAtLeast(1)
        val dx = w / drawnCount.toFloat()

        var prevLow: Offset? = null
        var prevHigh: Offset? = null
        var idx = 0

        for (i in samples.indices step step) {
            val s = samples[i]
            val x = idx * dx
            val yLow = h - s.ampLow.coerceIn(0f, 1f) * h
            val yHigh = h - s.ampHigh.coerceIn(0f, 1f) * h

            val curLow = Offset(x, yLow)
            val curHigh = Offset(x, yHigh)

            prevLow?.let { drawLine(Color(0xFF1E88E5), it, curLow, strokeWidth = 3f) }
            prevHigh?.let { drawLine(Color(0xFFFB8C00), it, curHigh, strokeWidth = 2.5f) }

            prevLow = curLow
            prevHigh = curHigh
            idx++
        }
    }
}
