package com.example.smartwalkapp.data.repository

import android.location.Location
import android.util.Log
import com.example.smartwalkapp.BuildConfig
import com.example.smartwalkapp.data.api.BusLocation
import com.example.smartwalkapp.data.api.GPSBahiaService
import com.example.smartwalkapp.data.api.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil

/** Colectivo ya convertido a números, listo para calcular distancias. */
data class BusPosition(
    val linea: String?,          // "500", "512", etc. (según el archivo track_data del que salió)
    val imei: String?,
    val interno: String,
    val nombre: String,
    val lat: Double,
    val lng: Double,
    val rumbo: Double?,          // hacia dónde apunta el colectivo (0-360, 0 = norte)
    val segundosSinSenal: Long?  // hace cuánto reportó por última vez
)

data class BusCercano(
    val bus: BusPosition,
    val metros: Double,
    val minutos: Int,
    val seAcerca: Boolean?       // null si no se sabe el rumbo
)

class GpsBahiaRepository(
    private val service: GPSBahiaService = RetrofitClient.gpsBahiaService,
    private val token: String = BuildConfig.GPS_BAHIA_TOKEN
) {
    private var sesionActiva = false

    private suspend fun activarSesion() {
        service.abrirPagina("https://www.gpsbahia.com.ar/").close()
        sesionActiva = true
    }

    /** Pide solo el archivo track_data de esa línea (ej: "500" -> 4.json). */
    private suspend fun pedirLinea(linea: String): List<BusPosition> {
        val id = TRACK_ID_POR_LINEA[linea] ?: return emptyList()
        return try {
            service.getBusLocations(id, token).data.mapNotNull { it.toPosition(linea) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GPS_BAHIA", "Error pidiendo track_data/$id (línea $linea)", e)
            emptyList()
        }
    }

    private suspend fun pedirLineas(lineas: Collection<String>): List<BusPosition> = coroutineScope {
        lineas.map { async { pedirLinea(it) } }.awaitAll().flatten()
    }

    /**
     * Colectivos de las líneas indicadas (por defecto, todas). Una consulta por línea,
     * en paralelo. Abre la página principal si hace falta y reintenta una vez si llega vacío.
     */
    suspend fun getBuses(lineas: Collection<String> = TRACK_ID_POR_LINEA.keys): List<BusPosition> {
        if (!sesionActiva) activarSesion()
        var buses = pedirLineas(lineas)
        if (buses.isEmpty()) {            // sesión vencida o sin colectivos: reactivar y reintentar una vez
            activarSesion()
            buses = pedirLineas(lineas)
        }
        Log.d("GPS_BAHIA", "Líneas ${lineas.joinToString()} -> ${buses.size} colectivos")
        return buses.distinctBy { it.imei ?: "${it.linea}-${it.interno}" }
    }

    /** Solo la línea pedida: "¿dónde está la 500?" -> únicamente 4.json. */
    suspend fun getBusesDeLinea(linea: String): List<BusPosition> = getBuses(listOf(linea))

    private fun BusLocation.toPosition(linea: String): BusPosition? {
        val la = lat?.toDoubleOrNull() ?: return null
        val lo = lng?.toDoubleOrNull() ?: return null
        return BusPosition(
            linea = linea,
            imei = imei,
            interno = interno,
            nombre = name ?: interno,
            lat = la,
            lng = lo,
            rumbo = angle?.toDoubleOrNull(),
            segundosSinSenal = segundosDesde(dt_tracker)
        )
    }

    // El servidor manda las horas en UTC ("2026-10-19 21:58:10").
    // Se usa SimpleDateFormat porque minSdk 24 no trae java.time sin desugaring.
    private fun segundosDesde(fecha: String?): Long? {
        if (fecha == null) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val t = fmt.parse(fecha)?.time ?: return null
            (System.currentTimeMillis() - t) / 1000
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val VELOCIDAD_PROMEDIO_KMH = 18.0
        private const val MAX_SEGUNDOS_SIN_SENAL = 180L

        /** Línea -> número del archivo track_data/N.json de gpsbahia.com.ar. */
        val TRACK_ID_POR_LINEA: Map<String, Int> = mapOf(
            "500" to 4,
            "502" to 34,
            "503" to 6,
            "504" to 7,
            "505" to 8,
            "506" to 9,
            "507" to 10,
            "509" to 1,
            "512" to 11,
            "513" to 12,
            "514" to 14,
            "516" to 15,
            "517" to 16,
            "518" to 17,
            "519" to 18,
            "520" to 30,
            "521" to 37
        )

        /** Descarta los que hace más de 3 minutos que no reportan (apagados / en cochera). */
        fun activos(buses: List<BusPosition>): List<BusPosition> =
            buses.filter { (it.segundosSinSenal ?: 0) < MAX_SEGUNDOS_SIN_SENAL }

        /**
         * Ordena los colectivos de más cerca a más lejos de (miLat, miLng).
         * Descarta los que hace más de 3 minutos que no reportan (apagados / en cochera).
         * La distancia es en línea recta, así que el tiempo es solo una aproximación.
         */
        fun calcularCercania(buses: List<BusPosition>, miLat: Double, miLng: Double): List<BusCercano> =
            buses
                .filter { (it.segundosSinSenal ?: 0) < MAX_SEGUNDOS_SIN_SENAL }
                .map { bus ->
                    val res = FloatArray(2)
                    // res[0] = distancia en metros, res[1] = rumbo inicial desde el colectivo hacia mí
                    Location.distanceBetween(bus.lat, bus.lng, miLat, miLng, res)
                    val metros = res[0].toDouble()
                    val minutos = ceil(metros / (VELOCIDAD_PROMEDIO_KMH * 1000 / 60)).toInt()
                    val seAcerca = bus.rumbo?.let { r ->
                        val bearing = (res[1] + 360.0) % 360.0
                        val diff = abs(((r - bearing + 540.0) % 360.0) - 180.0)
                        diff < 90.0
                    }
                    BusCercano(bus, metros, minutos, seAcerca)
                }
                .sortedBy { it.metros }
    }
}
