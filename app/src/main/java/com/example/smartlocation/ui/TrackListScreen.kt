package com.example.smartlocation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.smartlocation.LocationService
import com.example.smartlocation.LocationService.Companion.trackUpdates
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TrackListScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // 從 LocationService 讀取軌跡
    var currentTrack by remember { mutableStateOf(LocationService.Companion.getCurrentTrack()) }
    var completedTracks by remember { mutableStateOf(LocationService.Companion.getCompletedTracks()) }
    
    // 觀察軌跡更新
    LaunchedEffect(key1 = Unit) {
        lifecycleOwner.lifecycleScope.launch {
            trackUpdates.collectLatest { track ->
                currentTrack = track
                completedTracks = LocationService.Companion.getCompletedTracks()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 標題列
        Text(
            text = "我的軌跡",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp)
        )

        // 當前軌跡統計
        if (currentTrack != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "📍 目前軌跡",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "點位數: ${currentTrack!!.points.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "距離: ${String.format("%.2f", currentTrack!!.totalDistance / 1000)} 公里",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "持續時間: ${formatDuration(currentTrack!!.durationSeconds())}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "平均速度: ${String.format("%.1f", currentTrack!!.avgSpeed() * 3.6)} km/h",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 已完成軌跡列表
        if (completedTracks.isEmpty() && currentTrack == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "尚無軌跡記錄\n按下方按鈕開始記錄",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(completedTracks) { track ->
                    TrackCard(track = track)
                }
            }
        }

        // 底部按鈕
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { /* TODO: 開始記錄 */ },
                modifier = Modifier.weight(1f)
            ) {
                Text("開始記錄")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { /* TODO: 匯出 GPX */ },
                modifier = Modifier.weight(1f)
            ) {
                Text("匯出 GPX")
            }
        }
    }
}

@Composable
fun TrackCard(track: LocationService.TrackSegment) {
    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "軌跡 ${dateFormat.format(Date(track.startTime))}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "開始: ${dateFormat.format(Date(track.startTime))}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (track.endTime != null) {
                Text(
                    text = "結束: ${dateFormat.format(Date(track.endTime!!))}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "距離: ${String.format("%.2f", track.totalDistance / 1000)} 公里",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "持續: ${formatDuration(track.durationSeconds())}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "平均速度: ${String.format("%.1f", track.avgSpeed() * 3.6)} km/h",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "點位數: ${track.points.size}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, secs)
        minutes > 0 -> String.format("%02d:%02d", minutes, secs)
        else -> "${secs}秒"
    }
}
