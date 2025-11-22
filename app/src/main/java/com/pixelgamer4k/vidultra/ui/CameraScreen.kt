package com.pixelgamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
                onRecord = { if (it) viewModel.stopRecording() else viewModel.startRecording() },
                onIsoChange = { viewModel.setIso(it.toInt()) },
                onShutterChange = { viewModel.setExposure(it.toLong()) },
                onFocusChange = { viewModel.setFocus(it) }
            )
        } else {
            LaunchedEffect(Unit) { permissionState.launchMultiplePermissionRequest() }
        }
    }
}

@Composable
fun SupremeOverlay(
    isRecording: Boolean,
    onRecord: (Boolean) -> Unit,
    onIsoChange: (Float) -> Unit,
    onShutterChange: (Float) -> Unit,
    onFocusChange: (Float) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var activeControl by remember { mutableStateOf<String?>(null) }
    
    // Animation for recording transparency (only for non-essential UI)
    val uiAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isRecording) 0.3f else 1f,
        label = "uiAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        
        // --- LEFT SIDE: Settings & Histogram ---
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(140.dp)
                .padding(top = 16.dp)
                .alpha(uiAlpha), // Fade during recording
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
        }

        // --- RIGHT SIDE: Shutter Button ---
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
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

        // --- TOP RIGHT: Icons ---
        Row(
            modifier = Modifier.align(Alignment.TopEnd).alpha(uiAlpha),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircleIcon("G", onClick = {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    type = "video/*"
                }
                context.startActivity(intent)
            })
            CircleIcon("R")
            CircleIcon("S")
        }

        // --- BOTTOM: Manual Controls (ALWAYS VISIBLE) ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Slider popup (appears above controls with animation)
                AnimatedVisibility(
                    visible = activeControl != null,
                    enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) + 
                            androidx.compose.animation.slideInVertically(
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                )
                            ) { it / 2 } +
                            androidx.compose.animation.scaleIn(
                                initialScale = 0.8f,
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                )
                            ),
                    exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(200)) + 
                           androidx.compose.animation.slideOutVertically { it / 2 } +
                           androidx.compose.animation.scaleOut(targetScale = 0.8f)
                ) {
                    val range = when (activeControl) {
                        "ISO" -> 100f..3200f
                        "S" -> 100000f..33333333f
                        "F" -> 0f..10f
                        else -> 0f..1f
                    }
                    
                    var sliderValue by remember(activeControl) { mutableFloatStateOf(range.start) }
                    
                    // Frosted glass slider panel
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 40.dp, vertical = 8.dp)
                            .fillMaxWidth(0.8f)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                            .border(2.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            when (activeControl) {
                                "ISO" -> "ISO: ${sliderValue.toInt()}"
                                "S" -> "Shutter: 1/${(1000000000 / sliderValue).toInt()}s"
                                "F" -> "Focus: ${"%.1f".format(sliderValue)}"
                                else -> ""
                            },
                            color = Gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = sliderValue,
                            onValueChange = { 
                                sliderValue = it
                                when (activeControl) {
                                    "ISO" -> onIsoChange(it)
                                    "S" -> onShutterChange(it)
                                    "F" -> onFocusChange(it)
                                }
                            },
                            valueRange = range,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = Gold,
                                activeTrackColor = Gold,
                                inactiveTrackColor = Color.White.copy(0.3f)
                            )
                        )
                    }
                }
                
                // Frosted glass control bar (no gradient shadow)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(32.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                        .padding(vertical = 16.dp, horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlToggle("ISO", activeControl == "ISO") { 
                        activeControl = if (activeControl == "ISO") null else "ISO" 
                    }
                    ControlToggle("S", activeControl == "S") { 
                        activeControl = if (activeControl == "S") null else "S" 
                    }
                    ControlToggle("F", activeControl == "F") { 
                        activeControl = if (activeControl == "F") null else "F" 
                    }
                    
                    Spacer(modifier = Modifier.width(32.dp))
                    
                    // Format/Mode indicator
                    Box(
                        modifier = Modifier
                            .background(Gold, RoundedCornerShape(12.dp))
                            .border(1.dp, Color.Black.copy(0.2f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text("4K 30FPS", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ControlToggle(text: String, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(if (isActive) Gold else Color.Black.copy(alpha = 0.6f), CircleShape)
            .border(1.dp, if (isActive) Gold else Color.White.copy(0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, 
            color = if (isActive) Color.Black else Color.White, 
            fontSize = 14.sp, 
            fontWeight = FontWeight.Bold
        )
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
