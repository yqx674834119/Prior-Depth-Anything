package com.example.priordepth.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.priordepth.R
import com.example.priordepth.logic.UdpViewModel

// Design System Colors
private val BgColor = Color(0xFF121212)
private val SurfaceColor = Color(0xFF1E1E1E)
private val CyanAccent = Color(0xFF00E5FF)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0B0B0)
private val IconGrey = Color(0xFF888888)

@Composable
fun HomeScreen(
    viewModel: UdpViewModel,
    onNewScanClick: () -> Unit = {}
) {
    val connStatus by viewModel.connectionStatus.collectAsState()
    val isConnected = connStatus.startsWith("Connected")

    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            HomeHeader()
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 140.dp) // Space for custom dock and glow
            ) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    if (isConnected) {
                        ConnectedHardwareCard()
                    } else {
                        DisconnectedHardwareCard(statusText = connStatus, onConnectClick = { viewModel.startConnection() })
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = "Recent Projects",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 16.dp)
                    )
                }
                
                items(5) { index ->
                    ProjectItem(
                        name = if (index == 0) "Project Alpha" else "Office Scan",
                        date = if (index == 0) "15m ago • 1.3M pts" else "Yesterday • 850k pts",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            }
        }
        
        // Custom Bottom Dock overlaying Scaffold content
        Box(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
        ) {
            // Huge glow effect sitting behind everything, unclipped by navigation padding
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .align(Alignment.BottomCenter)
                    .offset(y = (-30).dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, CyanAccent.copy(alpha = 0.3f), Color.Transparent)
                        )
                    )
            )
            
            CustomBottomDock(
                modifier = Modifier.align(Alignment.BottomCenter),
                onScanClick = onNewScanClick
            )
        }
    }
}

@Composable
fun HomeHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_menu_burger),
            contentDescription = "Menu",
            tint = TextPrimary,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = "Home",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Box {
            Icon(
                painter = painterResource(id = R.drawable.ic_notification_bell),
                contentDescription = "Notifications",
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color.Red, CircleShape)
                    .align(Alignment.TopEnd)
                    .border(2.dp, BgColor, CircleShape)
                    .offset(x = 2.dp, y = (-2).dp)
            )
        }
    }
}

@Composable
fun ConnectedHardwareCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .border(1.5.dp, CyanAccent.copy(alpha=0.6f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Hardware Status",
                    color = CyanAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color(0xFF4CAF50), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Connected",
                        color = Color(0xFF4CAF50),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Divider line matching the original mockup exactly
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)))

            // Sensor Info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nav_scan),
                    contentDescription = "Sensor",
                    tint = CyanAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Sensor: ToF-Pro-X1 (USB-C)",
                    color = TextPrimary,
                    fontSize = 16.sp
                )
            }

            // Battery Info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 24.dp, height = 14.dp)
                        .border(1.5.dp, CyanAccent, RoundedCornerShape(3.dp))
                        .padding(2.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(0.85f).background(CyanAccent))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Battery: 85%",
                    color = TextPrimary,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun DisconnectedHardwareCard(statusText: String, onConnectClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .border(1.dp, CyanAccent.copy(alpha=0.3f), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_device_connect_hero),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(140.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Disconnected",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Device: ToF-Pro-X1 (USB-C)",
                color = TextSecondary,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(elevation = 20.dp, shape = RoundedCornerShape(28.dp), ambientColor = CyanAccent, spotColor = CyanAccent)
                    .clip(RoundedCornerShape(28.dp))
                    .background(CyanAccent)
                    .clickable { onConnectClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "CONNECT",
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun ProjectItem(name: String, date: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(115.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = name,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = date,
                    color = TextSecondary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "...",
                    color = TextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Thumbnail image matching the design (rounded square on the right)
            Image(
                painter = painterResource(id = R.drawable.img_device_connect_hero), // Placeholder
                contentDescription = "Thumbnail",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgColor), // Fallback
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun CustomBottomDock(modifier: Modifier = Modifier, onScanClick: () -> Unit) {
    var selectedItem by remember { mutableStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxWidth()
    ) {

        // Lower Dock Bar (SurfaceColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(SurfaceColor)
                .navigationBarsPadding()
                .height(65.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockNavItem(icon = R.drawable.ic_nav_home, isSelected = selectedItem == 0) { selectedItem = 0 }
            DockNavItem(icon = R.drawable.ic_nav_chart, isSelected = selectedItem == 1) { selectedItem = 1 }
            
            // Empty space for the protruding button
            Spacer(modifier = Modifier.width(80.dp))
            
            DockNavItem(icon = R.drawable.ic_nav_profile, isSelected = selectedItem == 3) { selectedItem = 3 }
            DockNavItem(icon = R.drawable.ic_nav_settings, isSelected = selectedItem == 4) { selectedItem = 4 }
        }

        // Protruding Bulge Background matches the SurfaceColor
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-20).dp)
                .size(86.dp)
                .background(SurfaceColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Inner glowing Cyan Button
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = CyanAccent,
                        spotColor = CyanAccent
                    )
                    .background(CyanAccent, CircleShape)
                    .clickable { onScanClick() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nav_cam),
                        contentDescription = "New Scan",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "NEW SCAN",
                        color = Color.Black,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

@Composable
fun DockNavItem(icon: Int, isSelected: Boolean, onClick: () -> Unit) {
    val color = if (isSelected) CyanAccent else IconGrey
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 16.dp)
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(width = 16.dp, height = 2.dp)
                    .background(CyanAccent, RoundedCornerShape(1.dp))
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}
