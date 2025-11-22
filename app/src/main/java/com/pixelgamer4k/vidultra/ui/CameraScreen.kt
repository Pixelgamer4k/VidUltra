package com.pixelgamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.pixelgamer4k.vidultra.core.Camera2Api

// Colors
val Gold = Color(0xFFFFD700)
val DarkPanel = Color(0xCC000000)
val RedRec = Color(0xFFD32F2F)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val permissionState = rememberMultiplePermissionsState(
        listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
    )
    val cameraState by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionState.allPermissionsGranted) { }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionState.allPermissionsGranted) {
            // Full Screen Preview
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = viewModel.onSurfaceReady(holder.surface)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) = viewModel.onSurfaceDestroyed()
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Supreme UI Overlay
            SupremeOverlay(
                isRecording = cameraState is Camera2Api.CameraState.Recording,
                onRecord = { if (it) viewModel.stopRecording() else viewModel.startRecording() }
            )
        } else {
            LaunchedEffect(Unit) { permissionState.launchMultiplePermissionRequest() }
        }
    }
}

@Composable
fun SupremeOverlay(isRecording: Boolean, onRecord: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            // Avoid punch hole / status bars
            .windowInsetsPadding(WindowInsets.safeDrawing) 
    ) {
        
        // --- LEFT SIDE: Settings & Histogram ---
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(140.dp)
                .padding(top = 16.dp), // Extra padding for corner cutouts
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Histogram
            Box(
                modifier = Modifier
                    .size(140.dp, 80.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .border(1.dp, Gold, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
            ) {
                HistogramGraph()
            }
            
            // Settings Stack
            SettingItem(label = "BITRATE", value = "100 Mbps", color = Gold)
            SettingItem(label = "CODEC", value = "HEVC", color = Gold)
            SettingItem(label = "DEPTH", value = "8-bit", color = Color.Green)
            SettingItem(label = "LOG", value = "OFF", color = Color.White)
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Format Indicator
            Text("4K 30FPS", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        // --- RIGHT SIDE: Controls ---
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                
                // Zoom Slider (Mock)
                Column(
                    modifier = Modifier
                        .width(50.dp)
                        .height(200.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(25.dp))
                        .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(25.dp))
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("+", color = Color.White)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .border(1.dp, Gold, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("1.0", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("-", color = Color.White)
                }

                // Shutter Button
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .border(3.dp, Gold, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) RedRec else RedRec.copy(alpha = 0.8f))
                        .clickable { onRecord(isRecording) }
                )
            }
        }

        // --- TOP RIGHT: Icons ---
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Gallery Button
            CircleIcon("G", onClick = {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    type = "video/*"
                }
                context.startActivity(intent)
            })
            CircleIcon("R") // Refresh
            CircleIcon("S") // Settings
        }

        // --- BOTTOM RIGHT: Pro Button ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Gold, RoundedCornerShape(8.dp))
                .clickable { /* Open Pro Menu */ }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("PRO", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SettingItem(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun CircleIcon(text: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            .border(1.dp, Color.White.copy(0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HistogramGraph() {
    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        val path = Path()
        path.moveTo(0f, size.height)
        // Mock curve
        path.cubicTo(
            size.width * 0.2f, size.height,
            size.width * 0.4f, size.height * 0.2f,
            size.width * 0.6f, size.height * 0.5f
        )
        path.cubicTo(
            size.width * 0.8f, size.height * 0.8f,
            size.width, size.height,
            size.width, size.height
        )
        path.lineTo(0f, size.height)
        path.close()
        
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(Gold, Gold.copy(alpha = 0.1f))
            )
        )
        drawPath(
            path = path,
            color = Gold,
            style = Stroke(width = 2f)
        )
    }
}
