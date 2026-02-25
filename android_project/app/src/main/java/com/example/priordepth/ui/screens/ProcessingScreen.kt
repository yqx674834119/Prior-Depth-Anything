package com.example.priordepth.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ProcessingScreen(onProcessingComplete: () -> Unit, onCancel: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }
    var stage by remember { mutableStateOf("Syncing Data...") }

    // Fake processing simulation
    LaunchedEffect(Unit) {
        while (progress < 1f) {
            delay(100)
            progress += 0.02f
            if (progress > 0.3f && progress < 0.7f) {
                stage = "Stage 2: Sparse to Dense Interpolation"
            } else if (progress >= 0.7f) {
                stage = "Stage 3: Mesh Generation"
            }
        }
        onProcessingComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Advanced loading animation (simulated point cloud funnel)
        Box(
            modifier = Modifier.size(300.dp),
            contentAlignment = Alignment.Center
        ) {
            ProcessingAnimation(progress = progress)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Processing... ${(progress * 100).toInt()}%",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stage,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth(0.5f).height(50.dp)
        ) {
            Text("CANCEL")
        }
    }
}

@Composable
fun ProcessingAnimation(progress: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = ""
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.width / 3

        drawCircle(
            color = Color(0xFF00E5FF),
            radius = radius,
            center = center,
            style = Stroke(width = 4.dp.toPx())
        )

        // Draw connecting points spinning around
        for (i in 0 until 12) {
            val angle = Math.toRadians((rotation + i * 30).toDouble())
            val x = center.x + radius * cos(angle).toFloat() * progress
            val y = center.y + radius * sin(angle).toFloat() * progress
            drawCircle(
                color = Color.White.copy(alpha = 0.6f),
                radius = 4.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}
