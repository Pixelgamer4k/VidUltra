package com.pixelgamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val permissionState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    )
    
    val cameraState by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionState.allPermissionsGranted) {
                // SurfaceView handles lifecycle
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionState.allPermissionsGranted) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                viewModel.onSurfaceReady(holder.surface)
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                viewModel.onSurfaceDestroyed()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Premium UI Overlay
            PremiumControls(
                isRecording = cameraState is Camera2Api.CameraState.Recording,
                onRecord = { if (it) viewModel.stopRecording() else viewModel.startRecording() },
                onIsoChange = { viewModel.setIso(it.toInt()) },
                onShutterChange = { viewModel.setExposure(it.toLong()) },
                onFocusChange = { viewModel.setFocus(it) },
                onAuto = { viewModel.setAuto() }
            )
            
            // Error Overlay
            if (cameraState is Camera2Api.CameraState.Error) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.8f)), contentAlignment = Alignment.Center) {
                    Text("Error: ${(cameraState as Camera2Api.CameraState.Error).msg}", color = Color.Red)
                }
            }
        } else {
            // Permission Request
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Please grant permissions to use the camera.", color = Color.White)
                LaunchedEffect(Unit) { permissionState.launchMultiplePermissionRequest() }
            }
        }
    }
}

@Composable
fun PremiumControls(
    isRecording: Boolean,
    onRecord: (Boolean) -> Unit,
    onIsoChange: (Float) -> Unit,
    onShutterChange: (Float) -> Unit,
    onFocusChange: (Float) -> Unit,
    onAuto: () -> Unit
) {
    var activeControl by remember { mutableStateOf<String?>(null) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent)))
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("VID ULTRA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Badge("4K")
                Badge("30 FPS")
                Badge("HEVC")
            }
        }

        // Right Sidebar (Manual Controls)
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Slider Panel (Animated)
            AnimatedVisibility(
                visible = activeControl != null,
                enter = fadeIn() + slideInHorizontally { it / 2 },
                exit = fadeOut() + slideOutHorizontally { it / 2 }
            ) {
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(300.dp)
                        .padding(end = 16.dp)
                        .background(Color.Black.copy(0.5f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (activeControl) {
                        "ISO" -> ControlSlider(value = 100f, range = 100f..3200f, onValueChange = onIsoChange, label = "ISO")
                        "S" -> ControlSlider(value = 10000000f, range = 1000000f..100000000f, onValueChange = onShutterChange, label = "Shutter (ns)")
                        "F" -> ControlSlider(value = 0f, range = 0f..10f, onValueChange = onFocusChange, label = "Focus")
                    }
                }
            }

            // Control Buttons Strip
            Column(
                modifier = Modifier
                    .width(60.dp)
                    .background(Color.Black.copy(0.4f), RoundedCornerShape(30.dp))
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ControlKnob("ISO", activeControl == "ISO") { activeControl = if (activeControl == "ISO") null else "ISO" }
                ControlKnob("S", activeControl == "S") { activeControl = if (activeControl == "S") null else "S" }
                ControlKnob("F", activeControl == "F") { activeControl = if (activeControl == "F") null else "F" }
                ControlKnob("A", false) { activeControl = null; onAuto() }
            }
        }

        // Bottom Bar (Shutter)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.9f)))),
            contentAlignment = Alignment.Center
        ) {
            // Shutter Button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color.Red else Color.White)
                    .clickable { onRecord(isRecording) }
            )
        }
    }
}

@Composable
fun ControlKnob(text: String, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (isActive) Color(0xFFBB86FC) else Color.White.copy(0.2f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun Badge(text: String) {
    Box(
        modifier = Modifier
            .background(Color.White.copy(0.2f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ControlSlider(value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit, label: String) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it; onValueChange(it) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFBB86FC),
                activeTrackColor = Color(0xFFBB86FC)
            )
        )
    }
}
