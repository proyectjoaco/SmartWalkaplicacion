package com.example.smartwalkapp.ui.explore

import com.example.smartwalkapp.BuildConfig

import android.annotation.SuppressLint
import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartwalkapp.data.model.Place
import com.example.smartwalkapp.data.model.Location as MyLocation
import com.example.smartwalkapp.data.model.Geometry
import com.example.smartwalkapp.data.api.RetrofitClient
import com.example.smartwalkapp.data.api.Step
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*
import android.location.Location as AndroidLocation

class ExploreViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private val GOOGLE_API_KEY = BuildConfig.GOOGLE_API_KEY
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val _uiState = MutableStateFlow<ExploreUiState>(ExploreUiState.Loading)
    val uiState: StateFlow<ExploreUiState> = _uiState

    private val _currentRoute = MutableStateFlow<List<LatLng>?>(null)
    val currentRoute: StateFlow<List<LatLng>?> = _currentRoute

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory

    private val _navigationSteps = MutableStateFlow<List<Step>>(emptyList())
    private var lastSpokenStepIndex = -1
    private var destinationLocation: MyLocation? = null

    // Memory for POIs
    private val poiMemory = mutableMapOf<String, MyLocation>()
    private val sharedPrefs = application.getSharedPreferences("smartwalk_pois", android.content.Context.MODE_PRIVATE)

    init {
        loadPois()
        tts = TextToSpeech(application, this)
        startLocationUpdates()
    }

    private fun loadPois() {
        sharedPrefs.all.forEach { (key, value) ->
            if (value is String) {
                val parts = value.split(",")
                if (parts.size == 2) {
                    val lat = parts[0].toDoubleOrNull()
                    val lng = parts[1].toDoubleOrNull()
                    if (lat != null && lng != null) {
                        poiMemory[key] = MyLocation(lat, lng)
                    }
                }
            }
        }
    }

    private fun savePoi(label: String, location: MyLocation) {
        poiMemory[label] = location
        sharedPrefs.edit().putString(label, "${location.lat},${location.lng}").apply()
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

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val location = locationResult.lastLocation ?: return
                    updateUserLocationInState(location)
                    checkNavigationProgress(location)
                }
            },
            null
        )
    }

    private fun updateUserLocationInState(location: AndroidLocation) {
        val currentState = _uiState.value
        if (currentState is ExploreUiState.Success) {
            _uiState.value = currentState.copy(userLocation = MyLocation(location.latitude, location.longitude))
        } else if (currentState is ExploreUiState.Loading) {
            _uiState.value = ExploreUiState.Success(emptyList(), MyLocation(location.latitude, location.longitude))
        }
    }

    private fun checkNavigationProgress(currentLocation: AndroidLocation) {
        val route = _currentRoute.value ?: return
        val currentLatLng = LatLng(currentLocation.latitude, currentLocation.longitude)

        // 1. Recálculo si se desvía más de 40 metros
        if (!PolyUtil.isLocationOnPath(currentLatLng, route, true, 40.0)) {
            destinationLocation?.let {
                Log.d("Nav", "Desviado. Recalculando...")
                calculateRouteTo(it, isRecalculating = true)
            }
            return
        }

        // 2. Navegación paso a paso por voz
        val steps = _navigationSteps.value
        if (steps.isNotEmpty()) {
            // Buscamos el paso en el que estamos
            for (i in (lastSpokenStepIndex + 1) until steps.size) {
                val step = steps[i]
                val stepLocation = step.start_location
                val results = FloatArray(1)
                AndroidLocation.distanceBetween(
                    currentLocation.latitude, currentLocation.longitude,
                    stepLocation.lat, stepLocation.lng,
                    results
                )

                // Si estamos a menos de 15 metros del inicio de un nuevo paso, lo anunciamos
                if (results[0] < 15f) {
                    speak(stripHtml(step.html_instructions))
                    lastSpokenStepIndex = i
                    break
                }
            }
            
            // Si estamos cerca del destino final
            destinationLocation?.let { dest ->
                val results = FloatArray(1)
                AndroidLocation.distanceBetween(currentLocation.latitude, currentLocation.longitude, dest.lat, dest.lng, results)
                if (results[0] < 15f && lastSpokenStepIndex < 999) {
                    speak("Has llegado a tu destino.")
                    lastSpokenStepIndex = 1000
                    _currentRoute.value = null
                    return
                }
            }
        }
    }

    fun calculateRouteTo(destination: MyLocation, isRecalculating: Boolean = false) {
        destinationLocation = destination
        viewModelScope.launch {
            val userLoc = getCurrentUserLocation() ?: return@launch
            try {
                val response = RetrofitClient.googleMapsService.getDirections(
                    "${userLoc.lat},${userLoc.lng}", 
                    "${destination.lat},${destination.lng}", 
                    apiKey = GOOGLE_API_KEY
                )
                if (response.status == "OK" && response.routes.isNotEmpty()) {
                    val route = response.routes[0]
                    _currentRoute.value = PolyUtil.decode(route.overview_polyline.points)
                    _navigationSteps.value = route.legs[0].steps
                    lastSpokenStepIndex = 0
                    
                    val firstStep = stripHtml(route.legs[0].steps[0].html_instructions)
                    if (isRecalculating) {
                        speak("Te has desviado. Nueva ruta calculada. $firstStep")
                    } else {
                        speak("Ruta iniciada. $firstStep")
                    }
                } else {
                    Log.e("Routing", "Google Directions: ${response.status} - ${response.error_message}")
                    speak("No pude calcular la ruta con Google Maps.")
                }
            } catch (e: Exception) {
                Log.e("Routing", "Error", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentUserLocation(): MyLocation? {
        return try {
            val location = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            location?.let { MyLocation(it.latitude, it.longitude) }
        } catch (e: Exception) { null }
    }

    /** true si Google rechazó la consulta (clave inválida, API no habilitada, cuota, etc.). */
    private fun placesFallo(r: com.example.smartwalkapp.data.model.PlacesResponse): Boolean {
        val s = r.status
        if (s != null && s != "OK" && s != "ZERO_RESULTS") {
            Log.e("Explore", "Google Places: $s - ${r.errorMessage}")
            speak("No pude buscar con Google Maps. Revisá la clave de la API.")
            return true
        }
        return false
    }

    fun onSearchQueryChange(newQuery: String) { _searchQuery.value = newQuery }
    fun executeSearch() { performSearch(_searchQuery.value) }

    fun executeSearchAndNavigateFirst() {
        viewModelScope.launch {
            val userLoc = getCurrentUserLocation() ?: return@launch
            try {
                val response = RetrofitClient.googleMapsService.searchPlaces(_searchQuery.value, "${userLoc.lat},${userLoc.lng}", 5000, GOOGLE_API_KEY)
                if (placesFallo(response)) return@launch
                if (response.results.isNotEmpty()) {
                    // Ordenar por distancia antes de elegir el primero
                    val sortedResults = response.results.sortedBy { place ->
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(
                            userLoc.lat, userLoc.lng,
                            place.geometry.location.lat, place.geometry.location.lng,
                            results
                        )
                        results[0]
                    }
                    val closestPlace = sortedResults[0]
                    _uiState.value = ExploreUiState.Success(sortedResults, userLoc)
                    calculateRouteTo(closestPlace.geometry.location)
                } else {
                    speak("No se encontraron resultados para ${_searchQuery.value}")
                }
            } catch (e: Exception) {
                Log.e("Explore", "Search error", e)
            }
        }
    }

    private fun performSearch(query: String) {
        viewModelScope.launch {
            val userLoc = getCurrentUserLocation() ?: return@launch
            try {
                val response = RetrofitClient.googleMapsService.searchPlaces(query, "${userLoc.lat},${userLoc.lng}", 5000, GOOGLE_API_KEY)
                if (placesFallo(response)) return@launch
                _uiState.value = ExploreUiState.Success(response.results, userLoc)
                speak("Encontrados ${response.results.size} resultados.")
            } catch (e: Exception) {
                Log.e("Explore", "Search error", e)
            }
        }
    }

    fun selectCategory(type: String?) {
        _selectedCategory.value = type
        viewModelScope.launch {
            val userLoc = getCurrentUserLocation() ?: return@launch
            try {
                val response = RetrofitClient.googleMapsService.getNearbyPlaces("${userLoc.lat},${userLoc.lng}", 2000, type, GOOGLE_API_KEY)
                if (placesFallo(response)) return@launch
                _uiState.value = ExploreUiState.Success(response.results, userLoc)
                val locationHelper = com.example.smartwalkapp.data.LocationHelper(getApplication())
                val address = locationHelper.getCurrentAddress() ?: "desconocida"
                speak("Tu dirección actual es $address")
            } catch (e: Exception) { }
        }
    }

    fun onVoiceInputReceived(input: String) {
        val lowerInput = input.lowercase()
        viewModelScope.launch {
            when {
                lowerInput.contains("guardar ubicación como") || lowerInput.contains("guardar ubicacion como") -> {
                    val label = lowerInput.split("como").last().trim()
                    val userLoc = getCurrentUserLocation()
                    if (userLoc != null && label.isNotEmpty()) {
                        savePoi(label, userLoc)
                        speak("Ubicación guardada como $label")
                    } else {
                        speak("No pude obtener tu ubicación actual para guardar.")
                    }
                }
                lowerInput.contains("ir a") -> {
                    val label = lowerInput.split("ir a").last().trim()
                    val savedLoc = poiMemory[label]
                    if (savedLoc != null) {
                        speak("Calculando ruta a tu $label")
                        calculateRouteTo(savedLoc)
                    } else {
                        // Si no está en memoria, buscamos normalmente
                        _searchQuery.value = label
                        executeSearchAndNavigateFirst()
                    }
                }
                else -> {
                    _searchQuery.value = input
                    executeSearchAndNavigateFirst()
                }
            }
        }
    }

    private fun stripHtml(html: String) = html.replace(Regex("<[^>]*>"), "")

    override fun onCleared() { super.onCleared(); tts?.shutdown() }
}

sealed class ExploreUiState {
    object Loading : ExploreUiState()
    data class Success(val places: List<Place>, val userLocation: MyLocation) : ExploreUiState()
    data class Error(val message: String) : ExploreUiState()
}
