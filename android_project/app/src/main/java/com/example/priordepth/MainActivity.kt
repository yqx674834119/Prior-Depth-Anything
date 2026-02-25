package com.example.priordepth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.priordepth.ui.screens.HomeScreen
import com.example.priordepth.ui.screens.PreviewScreen
import com.example.priordepth.ui.screens.ProcessingScreen
import com.example.priordepth.ui.screens.ScanningScreen
import com.example.priordepth.ui.theme.PriorDepthTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.priordepth.logic.UdpViewModel

import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PriorDepthTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val udpViewModel: UdpViewModel = viewModel()
    
    NavHost(navController = navController, startDestination = "home") {
        
        composable("home") {
            HomeScreen(
                viewModel = udpViewModel,
                onNewScanClick = {
                    navController.navigate("scan")
                }
            )
        }
        
        composable("scan") {
            ScanningScreen(
                onCaptureComplete = {
                    navController.navigate("processing") {
                        popUpTo("scan") { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable("processing") {
            ProcessingScreen(
                onProcessingComplete = {
                    navController.navigate("preview") {
                        popUpTo("processing") { inclusive = true }
                    }
                },
                onCancel = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            )
        }
        
        composable("preview") {
            PreviewScreen(onBack = {
                navController.navigate("home") {
                    popUpTo("home") { inclusive = true }
                }
            })
        }
    }
}
