package com.example.smartwalkapp.ui.navigation

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.smartwalkapp.ui.components.SmartWalkBottomBar
import com.example.smartwalkapp.ui.explore.ExploreScreen
import com.example.smartwalkapp.ui.explore.ExploreViewModel
import com.example.smartwalkapp.ui.home.HomeScreen
import com.example.smartwalkapp.ui.home.HomeViewModel
import com.example.smartwalkapp.ui.transport.TransportScreen
import com.example.smartwalkapp.ui.voice.VoiceAssistantScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    exploreViewModel: ExploreViewModel,
    transportViewModel: com.example.smartwalkapp.ui.transport.TransportViewModel,
    onVoiceSearchClick: () -> Unit = {}
) {
    var showVoiceAssistant by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateClick = { navController.navigate("transport") },
                onGoogleMapsClick = { navController.navigate("explore") },
                onVoiceAssistantClick = onVoiceSearchClick,
                onAddressClick = { homeViewModel.speakCurrentLocation() },
                bottomBar = {
                    SmartWalkBottomBar(
                        currentRoute = "home",
                        onNavigate = { route ->
                            if (route == "voice") {
                                onVoiceSearchClick()
                            } else if (route != "home") {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            )
        }
        composable("explore") {
            ExploreScreen(
                viewModel = exploreViewModel,
                onVoiceSearchClick = onVoiceSearchClick,
                bottomBar = {
                    SmartWalkBottomBar(
                        currentRoute = "explore",
                        onNavigate = { route ->
                            if (route == "voice") {
                                onVoiceSearchClick()
                            } else if (route != "explore") {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            )
        }
        composable("transport") {
            TransportScreen(
                onNoStopsFound = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                bottomBar = {
                    SmartWalkBottomBar(
                        currentRoute = "transport",
                        onNavigate = { route ->
                            if (route == "voice") {
                                onVoiceSearchClick()
                            } else if (route != "transport") {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                },
                onStartVoiceGuide = onVoiceSearchClick
            )
        }
    }

    if (showVoiceAssistant) {
        VoiceAssistantScreen(
            onDismiss = { showVoiceAssistant = false },
            onResult = { result ->
                // Handle voice result, e.g., search or navigate
                showVoiceAssistant = false
            }
        )
    }
}
