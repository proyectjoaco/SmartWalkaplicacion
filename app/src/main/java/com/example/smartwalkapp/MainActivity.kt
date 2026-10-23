package com.example.smartwalkapp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.example.smartwalkapp.ui.explore.ExploreViewModel
import com.example.smartwalkapp.ui.home.HomeViewModel
import com.example.smartwalkapp.ui.transport.TransportViewModel
import com.example.smartwalkapp.ui.navigation.NavGraph
import com.example.smartwalkapp.ui.theme.SmartWalkappTheme

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("SmartWalk", "MainActivity onCreate started")
        enableEdgeToEdge()
        setContent {
            Log.d("SmartWalk", "setContent started")
            SmartWalkappTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val homeViewModel: HomeViewModel = viewModel()
                    val exploreViewModel: ExploreViewModel = viewModel()
                    val transportViewModel: TransportViewModel = viewModel()

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        Log.d("SmartWalk", "Permissions result: $permissions")
                        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                        ) {
                            homeViewModel.refreshLocation()
                            exploreViewModel.selectCategory(null)
                        }
                    }

                    LaunchedEffect(Unit) {
                        Log.d("SmartWalk", "LaunchedEffect: requesting permissions")
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.RECORD_AUDIO
                            )
                        )
                    }

                    val voiceSearchLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode == RESULT_OK) {
                            val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                            data?.get(0)?.let { query ->
                                val lowerQuery = query.lowercase()
                                // También cuenta como pregunta de colectivos si menciona un número de línea
                                // de 3 dígitos ("dónde está la 500") o pregunta cuánto falta / ya llega.
                                val esConsultaDeColectivo =
                                    lowerQuery.contains("colectivo") || lowerQuery.contains("bus") ||
                                    lowerQuery.contains("línea") || lowerQuery.contains("linea") ||
                                    lowerQuery.contains("parada") ||
                                    Regex("\\b\\d{3}\\b").containsMatchIn(lowerQuery) ||
                                    Regex("cu[aá]nto (falta|tarda|demora)|ya llega").containsMatchIn(lowerQuery)
                                if (esConsultaDeColectivo) {
                                    transportViewModel.processVoiceCommand(query)
                                    navController.navigate("transport")
                                } else {
                                    exploreViewModel.onVoiceInputReceived(query)
                                    navController.navigate("explore")
                                }
                            }
                        }
                    }

                    NavGraph(
                        navController = navController,
                        homeViewModel = homeViewModel,
                        exploreViewModel = exploreViewModel,
                        transportViewModel = transportViewModel,
                        onVoiceSearchClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "¿Qué quieres buscar? (Ej: 'colectivo 502' o 'hospital')")
                            }
                            voiceSearchLauncher.launch(intent)
                        }
                    )
                }
            }
        }
        Log.d("SmartWalk", "MainActivity onCreate finished")
    }
}
