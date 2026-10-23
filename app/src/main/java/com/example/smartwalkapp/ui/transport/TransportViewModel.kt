package com.example.smartwalkapp.ui.transport

import com.example.smartwalkapp.BuildConfig

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
import com.example.smartwalkapp.data.repository.ParadasRepository
import com.example.smartwalkapp.data.repository.ParadaCercana
import com.example.smartwalkapp.data.repository.toSapemStop
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
    private val GOOGLE_API_KEY = BuildConfig.GOOGLE_API_KEY

    private val _uiState = MutableStateFlow<TransportUiState>(TransportUiState.Loading)
    val uiState: StateFlow<TransportUiState> = _uiState

    private val busRouteRepository = com.example.smartwalkapp.data.repository.BusRouteRepository(application)
    private val gpsBahiaRepository = GpsBahiaRepository()
    private val paradasRepository = ParadasRepository()

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

                // Solo una línea de prueba, para no pedir los 17 archivos cada vez que se abre la pantalla
                val buses = gpsBahiaRepository.getBusesDeLinea("500")

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

    // Paradas reales de la 516 (gpsbahia: get_frecuencias_puntos/15), primero y con su próximo horario
    val paradasGps = if (currentLocation != null) {
        try {
            paradasRepository.paradasCercanas(
                "516", currentLocation.latitude, currentLocation.longitude, 6
            )
        } catch (e: Exception) {
            Log.e("TransportVM", "No se pudieron cargar las paradas de GPS Bahía", e)
            emptyList()
        }
    } else {
        emptyList()
    }

    val todasLasParadas = (paradasGps.map { it.toSapemStop() } + processedStops).distinctBy { it.id }
    _uiState.value = TransportUiState.Success(todasLasParadas)

    speak(
        paradasGps.firstOrNull()?.let {
            "La parada de la 516 más cercana es ${it.parada.nombre}, a ${textoDistancia(it.metros)}."
        } ?: "He cargado las paradas de colectivo más cercanas."
    )

    if (currentLocation != null) {
        refreshStopsInBackground(
            currentLocation.latitude,
            currentLocation.longitude
        )
    }

} else {

    refreshStops()
}
        } catch (e: Exception) {
            Log.e("TransportVM", "loadInitialData failed", e)
            _uiState.value = TransportUiState.Error("No se pudieron cargar las paradas")
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
            
            if (response.status != null && response.status != "OK" && response.status != "ZERO_RESULTS") {
                Log.e("TransportVM", "Google Places: ${response.status} - ${response.errorMessage}")
            }

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
            // Paradas de GPS Bahía: se leen los horarios programados de esa parada
            if (stop.id.startsWith("gps_")) {
                val linea = stop.id.split("_").getOrNull(1) ?: return@launch
                val parada = try {
                    paradasRepository.getParadas(linea).firstOrNull { it.id == stop.id }
                } catch (e: Exception) {
                    null
                }
                val proxima = parada?.let { paradasRepository.proximaLlegada(it) }
                if (proxima == null) {
                    speak("No tengo horarios para esta parada.")
                } else {
                    speak("En la parada ${stop.nombre}. " + textoProximaLlegada(linea, proxima))
                }
                return@launch
            }

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

            // "¿Dónde está la parada de la 516 más cercana?" -> parada y horario, con tu ubicación.
            // Se evalúa antes que "dónde está la 516", que habla del colectivo y no de la parada.
            if (lowerQuery.contains("parada")) {
                val lineaParada = Regex("\\b(\\d{3})\\b").find(lowerQuery)?.groupValues?.get(1)
                    ?: ParadasRepository.LINEA_ID.keys.singleOrNull()   // si hay una sola línea habilitada
                if (lineaParada != null) {
                    anunciarParadaCercana(lineaParada)
                    return@launch
                }
            }

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

    private fun textoMinutos(minutos: Int): String =
        if (minutos <= 1) "menos de un minuto" else "unos $minutos minutos"

    /** "El próximo 516 pasa a las 14:32, en unos 12 minutos. Después a las 15:02." */
    private fun textoProximaLlegada(linea: String, p: com.example.smartwalkapp.data.repository.ProximaLlegada): String {
        val base = if (p.esManana) {
            "El próximo $linea pasa mañana a las ${p.hora}."
        } else {
            "El próximo $linea pasa a las ${p.hora}, en ${textoMinutos(p.minutos)}."
        }
        return base + (p.siguiente?.let { " Después a las $it." } ?: "")
    }

    /** "¿Dónde está la parada de la 516 más cercana?": parada más cercana de cada sentido y su horario. */
    private suspend fun anunciarParadaCercana(linea: String) {
        if (linea !in ParadasRepository.LINEA_ID) {
            speak("Por ahora solo tengo las paradas de la línea ${ParadasRepository.LINEA_ID.keys.joinToString(" y ")}.")
            return
        }

        val location = try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        } catch (e: Exception) {
            null
        }
        if (location == null) {
            speak("No pude obtener tu ubicación. Revisá que el GPS esté activado.")
            return
        }

        val cercanas: List<ParadaCercana> = try {
            paradasRepository.paradasCercanas(linea, location.latitude, location.longitude, 500)
        } catch (e: Exception) {
            Log.e("PARADAS", "No se pudieron leer las paradas", e)
            emptyList()
        }
        if (cercanas.isEmpty()) {
            speak("No pude obtener las paradas de la línea $linea ahora.")
            return
        }

        // La más cercana de cada sentido (ida y vuelta), la más próxima primero
        val porSentido = cercanas
            .groupBy { it.parada.sentido }
            .values.map { it.first() }
            .sortedBy { it.metros }
            .take(2)

        val partes = porSentido.mapIndexed { i, c ->
            val sentido = if (c.parada.sentido.isNotEmpty()) "sentido ${c.parada.sentido.lowercase()}, " else ""
            val donde = "${c.parada.nombre}, $sentido" +
                "a ${textoDistancia(c.metros)} hacia el ${puntoCardinal(c.rumboDesdeMi)} de donde estás."
            val horario = c.proxima?.let { " " + textoProximaLlegada(linea, it) } ?: " No tengo horarios para esta parada."
            if (i == 0) "La parada de la $linea más cercana es $donde$horario"
            else "En el otro sentido, la más cercana es $donde$horario"
        }
        speak(partes.joinToString(" "))
    }

    /** "¿Dónde está la 500?": dice por voz dónde hay colectivos de esa línea, de forma aproximada. */
    private suspend fun anunciarUbicacionLinea(linea: String) {
        if (linea !in GpsBahiaRepository.TRACK_ID_POR_LINEA) {
            speak("No tengo datos en vivo de la línea $linea.")
            return
        }

        // Solo se consulta el archivo de esa línea (500 -> 4.json, 516 -> 15.json, etc.)
        val buses = try {
            gpsBahiaRepository.getBusesDeLinea(linea)
        } catch (e: Exception) {
            Log.e("GPS_BAHIA", "No se pudo consultar GPS Bahía", e)
            speak("No pude consultar la ubicación de los colectivos ahora.")
            return
        }

        val enLinea = GpsBahiaRepository.activos(buses)
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
                "Encontré $cantidad de la línea $linea." +
                    (lugar?.let { " Uno está cerca de $it." } ?: "")
            )
            return
        }

        // El más cercano a mí, con su calle aproximada, hacia dónde queda y si se acerca
        val masCercano = GpsBahiaRepository
            .calcularCercania(enLinea, location.latitude, location.longitude)
            .firstOrNull()
        if (masCercano == null) {
            speak("No encontré colectivos de la línea $linea circulando en este momento.")
            return
        }

        val res = FloatArray(2)
        Location.distanceBetween(
            location.latitude, location.longitude,
            masCercano.bus.lat, masCercano.bus.lng, res
        )
        val lugar = nombreDeLugar(masCercano.bus.lat, masCercano.bus.lng)
        val referencia = "a ${textoDistancia(masCercano.metros)} hacia el ${puntoCardinal(res[1])} de donde estás"
        val movimiento = when (masCercano.seAcerca) {
            true -> ", y se está acercando, llegaría en unos ${masCercano.minutos} minutos"
            false -> ", pero parece que se está alejando"
            null -> ""
        }
        speak(
            "Encontré $cantidad de la línea $linea. " +
                "El más cercano está " +
                (lugar?.let { "cerca de $it, " } ?: "") + referencia + movimiento + "."
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
            val deLinea = c.bus.linea?.let { " de la línea $it" } ?: ""
            "El colectivo ${c.bus.nombre}$deLinea está a $distancia, llega en aproximadamente $tiempo"
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
