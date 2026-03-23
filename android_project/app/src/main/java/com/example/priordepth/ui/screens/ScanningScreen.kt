package com.example.priordepth.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.priordepth.R
import com.example.priordepth.data.DeviceInterface
import com.example.priordepth.data.TofRepository
import kotlinx.coroutines.isActive

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
    var latestBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    val context = LocalContext.current
    val tofRepository = remember { TofRepository() }
    val depthData by tofRepository.depthData.collectAsState()

    // Use center pixel (e.g. index 36) for the distance readout
    val centerDepthMm = depthData[36]
    val distance = if (centerDepthMm > 0) centerDepthMm / 1000f else 0f

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
    
    DisposableEffect(Unit) {
        tofRepository.connect()
        onDispose {
            tofRepository.disconnect()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Live Camera Background (From ESP32 WiFi Stream) & ToF Overlay
        if (hasCameraPermission) {
                // Container for true 4:3 alignment (640x480)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .align(Alignment.Center)
                ) {
                    // We stream the 640x480 natively now fetching isolated JPEGs perfectly
                    NativeCameraStream(
                        url = DeviceInterface.cameraUrl,
                        onBitmapFrame = { bmp -> latestBitmap = bmp }
                    )
                    
                    // 8x8 ToF Data Visualization Overlay
                    if (mode == ScanMode.FUSION) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cols = 8
                            val rows = 8
                            val cellWidth = size.width / cols
                            val cellHeight = size.height / rows
                            
                            for (i in 0 until 64) {
                                val row = i / cols
                                val col = i % cols
                                val mm = depthData[i]
                                
                                if (mm <= 0) continue // Skip invalid
                                
                                // Map depth (mm) to Color (0m -> 5m max)
                                val maxMm = 5000f
                                val ratio = (mm / maxMm).coerceIn(0f, 1f)
                                
                                // Simple Thermal Mapping (Red -> Yellow -> Green -> Blue)
                                val r = ((1f - ratio) * 255).toInt()
                                val b = (ratio * 255).toInt()
                                val g = (if (ratio < 0.5f) ratio * 2 * 255 else (1f - ratio) * 2 * 255).toInt()
                                
                                // Set alpha to 150 (about 60% opacity) to see through to camera
                                drawRect(
                                    color = Color(r, g, b, 150),
                                    topLeft = Offset(col * cellWidth, row * cellHeight),
                                    size = Size(cellWidth, cellHeight)
                                )
                            }
                        }
                    }
                }
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
                onCaptureClick = { 
                    com.example.priordepth.logic.SharedData.capturedRgbBitmap = latestBitmap
                    com.example.priordepth.logic.SharedData.capturedDepthMm = depthData.clone()
                    onCaptureComplete()
                },
                onCancelClick = onBack,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
fun NativeCameraStream(url: String, onBitmapFrame: (android.graphics.Bitmap) -> Unit, modifier: Modifier = Modifier) {
    var errorMsg by remember { mutableStateOf("Connecting to ESP32...") }
    var currentBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(url) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            while (isActive) {
                var connection: java.net.HttpURLConnection? = null
                try {
                    connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.requestMethod = "GET"
                    connection.connect()
                    
                    if (connection.responseCode == 200) {
                        val bytes = connection.inputStream.readBytes()
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                currentBitmap = bmp
                                errorMsg = "Connected"
                                onBitmapFrame(bmp)
                            }
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            errorMsg = "HTTP Error: ${connection.responseCode}"
                        }
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        errorMsg = "Network Error: ${e.message}"
                    }
                } finally {
                    connection?.disconnect()
                }
                // Don't smash the ESP32, give it 33ms breathing room (~30fps max)
                kotlinx.coroutines.delay(33) 
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (currentBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = currentBitmap!!.asImageBitmap(),
                contentDescription = "Live Camera Feed",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        } else {
            // Debug Text
            androidx.compose.material3.Text(
                text = "📡 JPEG POLLING DEBUG INFO\nStatus: $errorMsg\nTarget: $url",
                color = Color.Yellow,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha=0.7f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(8.dp)
            )
        }
    }
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
