package com.example.smartwalkapp.ui.transport

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartwalkapp.data.api.RetrofitClient
import com.example.smartwalkapp.data.api.SapemStop
import com.example.smartwalkapp.data.api.BusArrival
import com.example.smartwalkapp.data.model.Place
import com.example.smartwalkapp.data.model.Geometry
import com.example.smartwalkapp.data.model.Location as MyLocation
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

class TransportViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val GOOGLE_API_KEY = "AIzaSyBNxTN1HpmuINzIQT9q8EEHTfVg1kphJeg"

    private val _uiState = MutableStateFlow<TransportUiState>(TransportUiState.Loading)
    val uiState: StateFlow<TransportUiState> = _uiState

    private val busRouteRepository = com.example.smartwalkapp.data.repository.BusRouteRepository(application)

    init {
        tts = TextToSpeech(application, this)
        loadStopsFromCsv()
    }

    private fun loadStopsFromCsv() {
        viewModelScope.launch {
            _uiState.value = TransportUiState.Loading
            try {
                val lines = busRouteRepository.getLinesFromCsv()
                val stopsFromCsv = mutableListOf<SapemStop>()
                
                // Procesamos las primeras 3 líneas para no exceder límites de API rápidamente
                for (lineName in lines.take(3)) {
                    val description = busRouteRepository.getRouteDescription(lineName) ?: ""
                    val intersections = busRouteRepository.extractIntersections(description)
                    
                    for ((index, corner) in intersections.withIndex()) {
                        val address = "$corner, Bahía Blanca, Argentina"
                        try {
                            val geocodeResponse = RetrofitClient.googleMapsService.geocodeAddress(address, GOOGLE_API_KEY)
                            if (geocodeResponse.status == "OK" && geocodeResponse.results.isNotEmpty()) {
                                val loc = geocodeResponse.results[0].geometry.location
                                stopsFromCsv.add(
                                    SapemStop(
                                        id = "csv_${lineName}_$index",
                                        nombre = "$lineName: $corner",
                                        lat = loc.lat,
                                        lng = loc.lng,
                                        lineas = listOf(lineName)
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("TransportVM", "Error geocoding $address", e)
                        }
                    }
                }

                if (stopsFromCsv.isNotEmpty()) {
                    val currentLocation = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                    
                    val filteredStops = if (currentLocation != null) {
                        stopsFromCsv.sortedBy { stop ->
                            val results = FloatArray(1)
                            android.location.Location.distanceBetween(
                                currentLocation.latitude, currentLocation.longitude,
                                stop.lat, stop.lng,
                                results
                            )
                            results[0]
                        }.take(10) // Mostramos las 10 más cercanas
                    } else {
                        stopsFromCsv.take(10)
                    }

                    _uiState.value = TransportUiState.Success(filteredStops)
                    if (filteredStops.isNotEmpty()) {
                        speak("He encontrado ${filteredStops.size} paradas cercanas a tu ubicación.")
                    } else {
                        speak("No hay paradas en el archivo que coincidan con tu zona actual.")
                    }
                } else {
                    refreshStops()
                }
            } catch (e: Exception) {
                Log.e("TransportVM", "Error loading CSV", e)
                refreshStops()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("es", "ES"))
            isTtsReady = true
        }
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun refreshStops() {
        viewModelScope.launch {
            _uiState.value = TransportUiState.Loading
            try {
                val location = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                if (location != null) {
                    try {
                        // Intento 1: SAPEM API (con User-Agent corregido)
                        val stops = RetrofitClient.sapemService.getNearbyStops(location.latitude, location.longitude)
                        _uiState.value = TransportUiState.Success(stops)
                        speak("He encontrado ${stops.size} paradas de SAPEM cerca.")
                    } catch (e: Exception) {
                        Log.e("TransportVM", "SAPEM failed, falling back to Google", e)
                        // Intento 2: Backup con Google Places
                        val locationStr = "${location.latitude},${location.longitude}"
                        val response = RetrofitClient.googleMapsService.getNearbyPlaces(
                            location = locationStr,
                            radius = 1000,
                            type = "bus_station",
                            apiKey = GOOGLE_API_KEY
                        )
                        
                        val googleStops = response.results.map { place ->
                            SapemStop(
                                id = "google_${place.name}",
                                nombre = place.name,
                                lat = place.geometry.location.lat,
                                lng = place.geometry.location.lng,
                                lineas = listOf("Información no disponible")
                            )
                        }
                        
                        _uiState.value = TransportUiState.Success(googleStops)
                        if (googleStops.isNotEmpty()) {
                            speak("La API de SAPEM no responde, pero he encontrado ${googleStops.size} paradas cercanas en el mapa.")
                        } else {
                            speak("No he encontrado paradas cerca.")
                        }
                    }
                } else {
                    _uiState.value = TransportUiState.Error("No se pudo obtener tu ubicación")
                }
            } catch (e: Exception) {
                _uiState.value = TransportUiState.Error("Error general de conexión")
            }
        }
    }

    fun fetchArrivals(stop: SapemStop) {
        viewModelScope.launch {
            if (stop.id.startsWith("google_")) {
                speak("Lo siento, no tengo tiempos en tiempo real para esta parada de Google. Pero está ubicada en ${stop.nombre}")
                return@launch
            }
            
            try {
                val arrivals = RetrofitClient.sapemService.getArrivals(stop.id)
                if (arrivals.isNotEmpty()) {
                    val info = arrivals.joinToString(". ") { 
                        "Línea ${it.linea} llega en ${it.tiempoArribo} minutos" 
                    }
                    speak("En la parada ${stop.nombre}. $info")
                } else {
                    speak("No hay arribos próximos para la parada ${stop.nombre}")
                }
            } catch (e: Exception) {
                speak("No pude conectar con el servicio de tiempos de llegada de SAPEM.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.shutdown()
    }
}

sealed class TransportUiState {
    object Loading : TransportUiState()
    data class Success(val stops: List<SapemStop>) : TransportUiState()
    data class Error(val message: String) : TransportUiState()
}
