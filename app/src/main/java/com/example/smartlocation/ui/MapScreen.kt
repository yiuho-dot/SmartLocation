package com.example.smartlocation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.smartlocation.LocationService
import com.example.smartlocation.LocationService.Companion.locationUpdates
import com.example.smartlocation.LocationService.Companion.satelliteUpdates
import com.example.smartlocation.LocationService.Companion.trackUpdates
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun MapScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // 當前位置
    var currentLocation by remember { mutableStateOf(LatLng(22.3193, 114.1694)) }
    var currentAccuracy by remember { mutableStateOf(0.0f) }
    var currentSpeed by remember { mutableStateOf(0.0f) }
    var currentAltitude by remember { mutableStateOf(0.0) }
    var hasLocation by remember { mutableStateOf(false) }
    
    // 軌跡點位
    var trackPoints by remember { mutableStateOf(listOf<LatLng>()) }
    var totalDistance by remember { mutableStateOf(0.0f) }
    var trackDuration by remember { mutableStateOf(0L) }
    
    // 衛星訊號
    var satellites by remember { mutableStateOf(listOf<com.example.smartlocation.SatelliteInfo>()) }
    var satelliteCount by remember { mutableStateOf(0) }
    var usedSatelliteCount by remember { mutableStateOf(0) }
    
    // 相機狀態
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(currentLocation, 15f)
    }
    
    // 觀察位置更新
    LaunchedEffect(key1 = Unit) {
        lifecycleOwner.lifecycleScope.launch {
            locationUpdates.collectLatest { location ->
                val newLatLng = LatLng(location.latitude, location.longitude)
                currentLocation = newLatLng
                currentAccuracy = location.accuracy
                currentSpeed = location.speed
                currentAltitude = location.altitude
                hasLocation = true
                
                cameraPositionState.animate(
                    update = CameraUpdateFactory.newLatLng(newLatLng),
                    durationMs = 1000
                )
            }
        }
    }
    
    // 觀察軌跡更新
    LaunchedEffect(key1 = Unit) {
        lifecycleOwner.lifecycleScope.launch {
            trackUpdates.collectLatest { track ->
                trackPoints = track.points.map { LatLng(it.latitude, it.longitude) }
                totalDistance = track.totalDistance
                trackDuration = track.durationSeconds()
            }
        }
    }
    
    // 觀察衛星訊號更新
    LaunchedEffect(key1 = Unit) {
        lifecycleOwner.lifecycleScope.launch {
            satelliteUpdates.collectLatest { sats ->
                satellites = sats
                satelliteCount = sats.size
                usedSatelliteCount = sats.count { it.usedInFix }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = true,
                isTrafficEnabled = false
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = true,
                myLocationButtonEnabled = true
            )
        ) {
            // 顯示軌跡路線
            if (trackPoints.size > 1) {
                Polyline(
                    points = trackPoints,
                    color = Color(0xFF1976D2), // 藍色
                    width = 8f,
                    geodesic = true
                )
            }
            
            // 標示當前位置
            if (hasLocation) {
                Marker(
                    state = MarkerState(position = currentLocation),
                    title = "當前位置",
                    snippet = "緯度: ${String.format("%.6f", currentLocation.latitude)}, 經度: ${String.format("%.6f", currentLocation.longitude)}"
                )
                
                Circle(
                    center = currentLocation,
                    radius = 50.0,
                    fillColor = Color(0x440000FF),
                    strokeColor = Color(0xFF0000FF),
                    strokeWidth = 2f
                )
            }
        }

        // 上方資訊列
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 主要位置資訊
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (hasLocation) "目前位置" else "等待定位...",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "緯度: ${String.format("%.6f", currentLocation.latitude)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "經度: ${String.format("%.6f", currentLocation.longitude)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (hasLocation) {
                        Text(
                            text = "精度: ±${String.format("%.1f", currentAccuracy)}m",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (currentSpeed > 0) {
                            Text(
                                text = "速度: ${String.format("%.1f", currentSpeed * 3.6)} km/h",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = "海拔: ${String.format("%.1f", currentAltitude)}m",
                            style = MaterialTheme.typography.bodySmall
                        )
                        
                        // 軌跡統計
                        if (trackPoints.size > 1) {
                            Text(
                                text = "📍 軌跡統計:",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF1976D2)
                            )
                            Text(
                                text = "   距離: ${String.format("%.2f", totalDistance / 1000)} km",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "   時間: ${formatDuration(trackDuration)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "   點位: ${trackPoints.size} 個",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        
                        Text(
                            text = "✅ 位置已更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }
            
            // 衛星訊號資訊
            if (satelliteCount > 0) {
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
                            text = "🛰️ 衛星訊號",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        // 衛星總數
                        Text(
                            text = "總共: $satelliteCount 顆 | 已使用: $usedSatelliteCount 顆",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        // 訊號強度指示器
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            satellites.take(12).forEach { sat ->
                                val snr = sat.snr.toInt().coerceIn(0, 50)
                                val color = when {
                                    snr > 40 -> Color(0xFF4CAF50) // 綠色：強
                                    snr > 25 -> Color(0xFFFFEB3B) // 黃色：中
                                    else -> Color(0xFFFF5722) // 紅色：弱
                                }
                                
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "${sat.prn}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(8.dp)
                                            .height((snr * 2).dp)
                                            .background(color)
                                    )
                                    Text(
                                        text = "${sat.snr.toInt()}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        
                        // 定位品質評估
                        val quality = when {
                            usedSatelliteCount >= 8 && satellites.any { it.snr > 40 } -> "優秀"
                            usedSatelliteCount >= 5 -> "良好"
                            usedSatelliteCount >= 4 -> "一般"
                            else -> "不佳"
                        }
                        val qualityColor = when (quality) {
                            "優秀" -> Color(0xFF4CAF50)
                            "良好" -> Color(0xFFFFEB3B)
                            else -> Color(0xFFFF5722)
                        }
                        
                        Text(
                            text = "定位品質: $quality",
                            style = MaterialTheme.typography.bodyMedium,
                            color = qualityColor
                        )
                    }
                }
            }
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
