package com.example.priordepth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(onBack: () -> Unit) {
    var renderMode by remember { mutableStateOf(RenderMode.MESH) }
    var showExportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("3D Preview", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = { showExportDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Export")
                        Spacer(Modifier.width(8.dp))
                        Text("EXPORT")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
        ) {
            // [Background Layer] 3D Viewer Placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF181818)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "3D Viewer: ${renderMode.name} MODE",
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // Bottom Action Bar
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RenderMode.values().forEach { mode ->
                    val isSelected = renderMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { renderMode = mode }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = mode.name,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // Extra tools
                VerticalDivider(modifier = Modifier.height(24.dp), color = Color.Gray)
                Text("CROP", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
                Text("MEASURE", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
            }
        }

        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Export Model") },
                text = {
                    Column {
                        Text("Formats:")
                        Spacer(Modifier.height(8.dp))
                        listOf(".obj", ".ply", ".usdz").forEach { format ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = format == ".ply", onClick = {})
                                Text(format)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showExportDialog = false }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

enum class RenderMode { RGB, DEPTH, MESH, PT_CLOUD }
