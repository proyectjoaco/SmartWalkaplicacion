package com.example.smartwalkapp.ui.transport

import android.app.Application
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartwalkapp.data.api.RetrofitClient
import com.example.smartwalkapp.data.api.SapemStop
import com.example.smartwalkapp.data.api.BusArrival
import com.example.smartwalkapp.data.repository.GpsBahiaRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val gpsBahiaRepository = GpsBahiaRepository()

    init {
        tts = TextToSpeech(application, this)
        loadInitialData()
    }

    private fun loadInitialData() {
    viewModelScope.launch {
        _uiState.value = TransportUiState.Loading

        try {

            // PRUEBA GPS BAHIA
            try {

                val buses = gpsBahiaRepository.getBuses()

                Log.d(
                    "GPS_BAHIA",
                    "Cantidad de colectivos: ${buses.size}"
                )

                buses.take(5).forEach { bus ->
                    Log.d(
                        "GPS_BAHIA",
                        "${bus.interno} - ${bus.lat}, ${bus.lng}"
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    "GPS_BAHIA",
                    "Error GPSBahia",
                    e
                )
            }

            // TU CÓDIGO ORIGINAL

            val currentLocation = try {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    null
                ).await()
            } catch (e: Exception) {
                Log.e("TransportVM", "Location failed", e)
                null
            }

            val allStops =
                busRouteRepository.getAllStopsFromCsv()

            Log.d(
                "TransportVM",
                "Loaded ${allStops.size} stops from repository"
            )

            // aquí sigue TODO tu código original
            if (allStops.isNotEmpty()) {

    val processedStops = if (currentLocation != null) {
        allStops.sortedBy { stop ->
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                currentLocation.latitude,
                currentLocation.longitude,
                stop.lat,
                stop.lng,
                results
            )
            results[0]
        }.take(20)
    } else {
        allStops.take(20)
    }

    _uiState.value = TransportUiState.Success(processedStops)

    speak("He cargado las paradas de colectivo más cercanas.")

    if (currentLocation != null) {
        refreshStopsInBackground(
            currentLocation.latitude,
            currentLocation.longitude
        )
    }

} else {

    refreshStops()
}
        }
    }
}

    private suspend fun refreshStopsInBackground(lat: Double, lng: Double) {
        try {
            val sapemStops = RetrofitClient.sapemService.getNearbyStops(lat, lng)
            if (sapemStops.isNotEmpty()) {
                _uiState.value = TransportUiState.Success(sapemStops)
            }
        } catch (e: Exception) {
            Log.e("TransportVM", "Background refresh failed", e)
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
                        val stops = RetrofitClient.sapemService.getNearbyStops(location.latitude, location.longitude)
                        if (stops.isNotEmpty()) {
                            _uiState.value = TransportUiState.Success(stops)
                            speak("He actualizado las paradas de SAPEM.")
                        } else {
                            fallbackToGooglePlaces(location.latitude, location.longitude)
                        }
                    } catch (e: Exception) {
                        fallbackToGooglePlaces(location.latitude, location.longitude)
                    }
                } else {
                    // Si no hay ubicación, volvemos a las locales del CSV
                    loadInitialData()
                }
            } catch (e: Exception) {
                _uiState.value = TransportUiState.Error("No se pudo conectar con el servicio")
            }
        }
    }

    private suspend fun fallbackToGooglePlaces(lat: Double, lng: Double) {
        try {
            val response = RetrofitClient.googleMapsService.getNearbyPlaces(
                location = "$lat,$lng",
                radius = 1500,
                type = "bus_station",
                apiKey = GOOGLE_API_KEY
            )
            
            val googleStops = response.results.map { place ->
                SapemStop(
                    id = "google_${place.name}",
                    nombre = place.name,
                    lat = place.geometry.location.lat,
                    lng = place.geometry.location.lng,
                    lineas = listOf("Google Maps")
                )
            }
            
            if (googleStops.isNotEmpty()) {
                _uiState.value = TransportUiState.Success(googleStops)
                speak("He encontrado paradas cercanas usando el mapa.")
            } else {
                // Si incluso Google falla, usamos las locales como último recurso
                val local = busRouteRepository.getAllStopsFromCsv().take(10)
                _uiState.value = TransportUiState.Success(local)
                speak("Mostrando paradas de referencia locales.")
            }
        } catch (e: Exception) {
            _uiState.value = TransportUiState.Error("Error al buscar paradas cercanas")
        }
    }

    fun fetchArrivals(stop: SapemStop) {
        viewModelScope.launch {
            val isReference = stop.id.startsWith("e") || 
                              stop.id.startsWith("csv_") || 
                              stop.id.startsWith("google_")
            
            if (isReference) {
                speak("No tengo tiempos en tiempo real para esta parada de referencia, pero puedo guiarte hasta ella.")
                return@launch
            }
            
            try {
                val arrivals = RetrofitClient.sapemService.getArrivals(stop.id)
                if (arrivals.isNotEmpty()) {
                    val info = arrivals.joinToString(". ") { 
                        "Línea ${it.linea} en ${it.tiempoArribo} minutos" 
                    }
                    speak("En la parada ${stop.nombre}. $info")
                } else {
                    speak("No hay arribos próximos.")
                }
            } catch (e: Exception) {
                speak("Servicio de arribos no disponible por ahora.")
            }
        }
    }

    fun startNavigationToStop(stop: SapemStop) {
        viewModelScope.launch {
            val isReference = stop.id.startsWith("e") || 
                              stop.id.startsWith("csv_") || 
                              stop.id.startsWith("google_")
            val typeText = if (isReference) "parada de referencia" else "parada exacta"
            
            speak("Iniciando guía hacia la $typeText en ${stop.nombre}. Sigue mis instrucciones.")
            
            val intent = Intent(getApplication<Application>(), com.example.smartwalkapp.services.NavigationService::class.java).apply {
                putExtra("destination_name", stop.nombre)
                putExtra("destination_lat", stop.lat)
                putExtra("destination_lng", stop.lng)
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
        }
    }

    fun processVoiceCommand(query: String) {
        viewModelScope.launch {
            val lowerQuery = query.lowercase()

            // "¿Dónde está la 500?" -> ubicación aproximada de esa línea
            val lineaPreguntada = Regex("\\b(\\d{3})\\b").find(lowerQuery)?.groupValues?.get(1)
            val preguntaDondeLinea = Regex("d[oó]nde (est[aá]|viene|anda|va)|cu[aá]nto falta")
                .containsMatchIn(lowerQuery)
            if (lineaPreguntada != null && preguntaDondeLinea) {
                anunciarUbicacionLinea(lineaPreguntada)
                return@launch
            }

            // Preguntas tipo "¿dónde está el colectivo?" / "¿cuánto falta?" / "¿cuál está más cerca?"
            val preguntaPorUbicacion = Regex(
                "d[oó]nde (est[aá]|viene|anda)|cu[aá]nto (falta|tarda|demora)|m[aá]s cerca|ya llega"
            )
            if (preguntaPorUbicacion.containsMatchIn(lowerQuery)) {
                anunciarColectivosCercanos()
                return@launch
            }

            // Extraer el número de línea (ej: "512")
            val lineMatch = Regex("\\d+").find(lowerQuery)
            val lineNumber = lineMatch?.value ?: ""

            if (lineNumber.isEmpty()) {
                speak("¿Qué línea de colectivo buscas?")
                return@launch
            }

            // 1. Obtener ubicación actual
            val currentLocation = try {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            } catch (e: Exception) {
                null
            }

            // 2. Buscar en paradas actuales o locales
            val currentStops = (uiState.value as? TransportUiState.Success)?.stops ?: emptyList()
            val localStops = busRouteRepository.getAllStopsFromCsv()
            val allAvailableStops = (currentStops + localStops).distinctBy { it.id }

            // 3. Filtrar paradas que tengan esa línea
            val lineStops = allAvailableStops.filter { stop -> 
                stop.lineas.any { it.contains(lineNumber) } 
            }

            if (lineStops.isEmpty()) {
                speak("No encontré paradas para la línea $lineNumber en mi base de datos.")
                return@launch
            }

            // 4. Encontrar la más cercana
            val nearestStop = if (currentLocation != null) {
                lineStops.minByOrNull { stop ->
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(
                        currentLocation.latitude, currentLocation.longitude,
                        stop.lat, stop.lng,
                        results
                    )
                    results[0]
                }
            } else {
                lineStops.firstOrNull()
            }

            nearestStop?.let { stop ->
                startNavigationToStop(stop)
            }
        }
    }

    /** "Norte", "sudeste", etc. a partir de un rumbo en grados (0 = norte). */
    private fun puntoCardinal(grados: Float): String {
        val puntos = listOf("norte", "noreste", "este", "sudeste", "sur", "sudoeste", "oeste", "noroeste")
        val g = ((grados % 360f) + 360f) % 360f
        return puntos[((g + 22.5f) / 45f).toInt() % 8]
    }

    /** Calle y barrio aproximados de una coordenada (null si no se pudo averiguar). */
    private suspend fun nombreDeLugar(lat: Double, lng: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) return@withContext null
                @Suppress("DEPRECATION")
                val dir = Geocoder(getApplication(), Locale("es", "AR"))
                    .getFromLocation(lat, lng, 1)?.firstOrNull() ?: return@withContext null
                val calle = dir.thoroughfare
                val barrio = dir.subLocality
                when {
                    calle != null && barrio != null -> "$calle, barrio $barrio"
                    calle != null -> calle
                    barrio != null -> "barrio $barrio"
                    else -> null
                }
            } catch (e: Exception) {
                Log.e("TransportVM", "Geocoder falló", e)
                null
            }
        }

    private fun textoDistancia(metros: Double): String =
        if (metros < 1000) "${metros.toInt()} metros"
        else String.format(Locale("es", "AR"), "%.1f kilómetros", metros / 1000)

    /** "¿Dónde está la 500?": dice por voz dónde hay colectivos de esa línea, de forma aproximada. */
    private suspend fun anunciarUbicacionLinea(linea: String) {
        val rutas = busRouteRepository.getRutas().filter { it.linea == linea }
        if (rutas.isEmpty()) {
            speak("No tengo el recorrido de la línea $linea.")
            return
        }

        val buses = try {
            gpsBahiaRepository.getBuses()
        } catch (e: Exception) {
            Log.e("GPS_BAHIA", "No se pudo consultar GPS Bahía", e)
            speak("No pude consultar la ubicación de los colectivos ahora.")
            return
        }

        val enLinea = GpsBahiaRepository.busesEnLinea(buses, rutas)
        if (enLinea.isEmpty()) {
            speak("No encontré colectivos de la línea $linea circulando en este momento.")
            return
        }

        val location = try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        } catch (e: Exception) {
            null
        }

        val cantidad = if (enLinea.size == 1) "un colectivo" else "${enLinea.size} colectivos"
        if (location == null) {
            val lugar = nombreDeLugar(enLinea.first().lat, enLinea.first().lng)
            speak(
                "Encontré $cantidad que parecen ser de la línea $linea." +
                    (lugar?.let { " Uno está cerca de $it." } ?: "")
            )
            return
        }

        // El más cercano a mí, con su calle aproximada y hacia dónde queda
        val res = FloatArray(2)
        val masCercano = enLinea.minByOrNull {
            Location.distanceBetween(location.latitude, location.longitude, it.lat, it.lng, res)
            res[0]
        }!!
        Location.distanceBetween(location.latitude, location.longitude, masCercano.lat, masCercano.lng, res)

        val lugar = nombreDeLugar(masCercano.lat, masCercano.lng)
        val referencia = "a ${textoDistancia(res[0].toDouble())} hacia el ${puntoCardinal(res[1])} de donde estás"
        speak(
            "Encontré $cantidad que parecen ser de la línea $linea. " +
                "El más cercano está " +
                (lugar?.let { "cerca de $it, " } ?: "") + referencia + "."
        )
    }

    /** Dice por voz qué colectivos están más cerca de donde estoy y en cuánto tiempo llegarían (aprox). */
    private suspend fun anunciarColectivosCercanos() {
        val location = try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        } catch (e: Exception) {
            null
        }
        if (location == null) {
            speak("No pude obtener tu ubicación. Revisá que el GPS esté activado.")
            return
        }

        val buses = try {
            gpsBahiaRepository.getBuses()
        } catch (e: Exception) {
            Log.e("GPS_BAHIA", "No se pudo consultar GPS Bahía", e)
            speak("No pude consultar la ubicación de los colectivos ahora.")
            return
        }

        val cercanos = GpsBahiaRepository
            .calcularCercania(buses, location.latitude, location.longitude)
            // si se sabe que se alejan, se descartan; si no se sabe el rumbo, se dejan
            .filter { it.seAcerca != false }
            .take(3)

        if (cercanos.isEmpty()) {
            speak("No encontré colectivos acercándose en este momento.")
            return
        }

        val texto = cercanos.joinToString(". ") { c ->
            val distancia = if (c.metros < 1000) {
                "${c.metros.toInt()} metros"
            } else {
                String.format(Locale("es", "AR"), "%.1f kilómetros", c.metros / 1000)
            }
            val tiempo = if (c.minutos <= 1) "menos de un minuto" else "unos ${c.minutos} minutos"
            "El colectivo ${c.bus.nombre} está a $distancia, llega en aproximadamente $tiempo"
        }
        speak(texto)
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
