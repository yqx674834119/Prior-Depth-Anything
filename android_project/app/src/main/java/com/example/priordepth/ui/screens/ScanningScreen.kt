package com.example.priordepth.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.priordepth.R

// Future-Tech Colors
private val ThermalBlue = Color(0xFF0038FF)
private val ThermalCyan = Color(0xFF00E5FF)
private val ThermalGreen = Color(0xFF00FF73)
private val ThermalYellow = Color(0xFFFFE600)
private val ThermalRed = Color(0xFFFF2A00)

private val BrightCyan = Color(0xFF00E5FF)
private val BrightGreen = Color(0xFF81FF00)
private val AlertRed = Color(0xFFFF453A)
private val PanelDark = Color(0xFF1E1E1E)
private val GlassDark = Color(0x99000000)

@Composable
fun ScanningScreen(
    onCaptureComplete: () -> Unit,
    onBack: () -> Unit
) {
    var mode by remember { mutableStateOf(ScanMode.FUSION) }
    // Simulated depth in meters
    val distance by remember { mutableStateOf(1.5f) }

    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Live Camera Background (if permission granted)
        if (hasCameraPermission) {
            CameraPreview()
        } else {
            // Fallback while waiting for permission or if denied
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text("Camera permission required", color = Color.White)
            }
        }

        // 2. Main HUD Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Center Readout (Scaled Down)
            CenterDistanceReadout(
                distance = distance,
                modifier = Modifier.align(Alignment.Center).offset(y = (-40).dp)
            )

            // Right Depth Scale
            RightDepthScale(
                distance = distance,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
            )

            // Top Status Bar (Cinematic HUD Strip)
            ScanningStatusHeader(
                fps = 30, // Simulated default
                pointCount = 800,
                batteryLevel = 85,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Bottom Core Controls
            BottomControls(
                mode = mode,
                onModeChange = { mode = it },
                onCaptureClick = onCaptureComplete,
                onCancelClick = onBack,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
fun CameraPreview() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.scaleType = PreviewView.ScaleType.FILL_CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { previewView ->
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                
                // Select back camera as a default
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    // Unbind use cases before rebinding
                    cameraProvider.unbindAll()

                    // Bind use cases to camera
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview
                    )
                } catch(exc: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

@Composable
fun ScanningStatusHeader(fps: Int, pointCount: Int, batteryLevel: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // FPS Group
            HudDataGroup(label = "FPS", value = fps.toString())
            
            // Separator
            Text("|", color = Color(0x66FFFFFF), fontSize = 16.sp, fontFamily = FontFamily.Monospace)
            
            // Points Group
            HudDataGroup(label = "PTS", value = if (pointCount > 1000) "${pointCount/1000}k" else pointCount.toString())
            
            // Separator
            Text("|", color = Color(0x66FFFFFF), fontSize = 16.sp, fontFamily = FontFamily.Monospace)
            
            // Battery Group
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "BATT",
                        color = Color(0xFFAAAAAA),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "$batteryLevel%",
                        color = if (batteryLevel > 20) Color(0xFF00FF00) else AlertRed,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    painter = painterResource(id = R.drawable.ic_battery_full), 
                    contentDescription = "Battery",
                    tint = if (batteryLevel > 20) Color(0xFF00FF00) else AlertRed,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun HudDataGroup(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color(0xFFAAAAAA),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
        Text(
            text = value,
            color = Color(0xFF00FF00),
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun CenterDistanceReadout(distance: Float, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = "${distance}m",
            color = Color.White,
            fontSize = 36.sp,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = "- OPTIMAL -",
            color = BrightGreen,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun RightDepthScale(distance: Float, modifier: Modifier = Modifier) {
    val maxDist = 5.0f
    val barWidth = 12.dp
    val scaleHeight = 400.dp
    
    Row(modifier = modifier.height(scaleHeight), verticalAlignment = Alignment.CenterVertically) {
        // Labels
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            val labels = listOf("5.0m", "4.0m", "3.0m", "2.0m", "1.0m", "0.0m")
            labels.forEach { label ->
                Text(
                    text = label,
                    color = Color.White.copy(alpha=0.8f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.offset(y = (-7).dp) // Adjust label center to tick line
                )
            }
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Bar AND Ticks
        Box(modifier = Modifier.width(30.dp).fillMaxHeight()) {
            // Track Background
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            )
            
            // Track Fill
            val fillRatio = (distance / maxDist).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight(fillRatio)
                    .align(Alignment.BottomStart)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(BrightGreen, BrightCyan)
                        )
                    )
                    .shadow(elevation = 8.dp, ambientColor = BrightCyan, spotColor = BrightCyan)
            )
            
            // Ticks
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stepCount = 50 // every 0.1m
                val height = size.height
                val startX = barWidth.toPx()
                
                for (i in 0..stepCount) {
                    val y = height - (i / stepCount.toFloat()) * height
                    val isMajor = i % 10 == 0
                    val isHalf = i % 5 == 0 && !isMajor
                    
                    val tickLength = when {
                        isMajor -> 14.dp.toPx()
                        isHalf -> 8.dp.toPx()
                        else -> 4.dp.toPx()
                    }
                    
                    val strokeW = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
                    val alpha = if (isMajor) 1f else if (isHalf) 0.6f else 0.3f
                    
                    drawLine(
                        color = Color.White.copy(alpha = alpha),
                        start = Offset(startX, y),
                        end = Offset(startX + tickLength, y),
                        strokeWidth = strokeW
                    )
                }
            }
            
            // Indicator Handle
            val yPos = (1f - fillRatio)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-4).dp, y = (scaleHeight * yPos) - 2.dp)
                    .width(barWidth + 8.dp)
                    .height(4.dp)
                    .background(Color.White, RoundedCornerShape(2.dp))
                    .shadow(elevation = 10.dp, ambientColor = Color.White, spotColor = Color.White)
            )
        }
    }
}

@Composable
fun BottomControls(
    mode: ScanMode,
    onModeChange: (ScanMode) -> Unit,
    onCaptureClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // MODE Toggle Pill
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(GlassDark)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "MODE: FUSION",
                color = Color.White.copy(alpha=0.8f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(end = 16.dp)
            )
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PanelDark)
                    .border(1.dp, Color.White.copy(alpha=0.2f), RoundedCornerShape(12.dp))
                    .clickable { 
                        onModeChange(if (mode == ScanMode.FUSION) ScanMode.DEPTH else ScanMode.FUSION)
                    }
            ) {
                // Glow Knob
                Box(
                    modifier = Modifier
                        .align(if (mode == ScanMode.FUSION) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(2.dp)
                        .size(20.dp)
                        .shadow(elevation = 12.dp, shape = CircleShape, ambientColor = BrightCyan, spotColor = BrightCyan)
                        .background(BrightCyan, CircleShape)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(40.dp))
        
        // Primary Interaction Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel / Stop Node
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .border(3.dp, BrightCyan.copy(alpha = 0.5f), CircleShape)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .clickable { onCancelClick() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(AlertRed, RoundedCornerShape(4.dp))
                )
            }
            
            // Hero Shutter Orb
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .background(Color.Transparent, CircleShape)
                    .clickable { onCaptureClick() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(elevation = 20.dp, shape = CircleShape, ambientColor = BrightCyan, spotColor = BrightCyan)
                        .background(BrightCyan, CircleShape)
                )
            }
            
            // Last Scan Thumbnail
            Image(
                painter = painterResource(id = R.drawable.img_device_connect_hero), // using existing resource
                contentDescription = "Thumbnail",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PanelDark)
                    .border(1.dp, Color.White.copy(alpha=0.3f), RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop
            )
        }
    }
}

enum class ScanMode { RAW, DEPTH, FUSION }
