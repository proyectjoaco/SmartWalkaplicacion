package com.example.smartwalkapp.data.repository

import android.location.Location
import android.util.Log
import com.example.smartwalkapp.BuildConfig
import com.example.smartwalkapp.data.api.BusLocation
import com.example.smartwalkapp.data.api.GPSBahiaService
import com.example.smartwalkapp.data.api.RetrofitClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil

/** Colectivo ya convertido a números, listo para calcular distancias. */
data class BusPosition(
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
    private val token: String = BuildConfig.GPS_BAHIA_TOKEN,
    // El "4" de track_data/4.json. Si la página tiene otros ids (otras empresas/líneas),
    // agregalos acá: listOf(1, 2, 3, 4)
    private val trackIds: List<Int> = listOf(4)
) {
    private var sesionActiva = false

    private suspend fun activarSesion() {
        service.abrirPagina("https://www.gpsbahia.com.ar/").close()
        sesionActiva = true
    }

    private suspend fun pedirTodos(): List<BusLocation> =
        trackIds.flatMap { id ->
            try {
                service.getBusLocations(id, token).data
            } catch (e: Exception) {
                Log.e("GPS_BAHIA", "Error pidiendo track_data/$id", e)
                emptyList()
            }
        }

    /** Devuelve los colectivos activos. Abre la página principal si hace falta. */
    suspend fun getBuses(): List<BusPosition> {
        if (!sesionActiva) activarSesion()
        var crudos = pedirTodos()
        if (crudos.isEmpty()) {            // sesión vencida: reactivar y reintentar una vez
            activarSesion()
            crudos = pedirTodos()
        }
        Log.d("GPS_BAHIA", "Colectivos recibidos: ${crudos.size}")
        return crudos.mapNotNull { it.toPosition() }
    }

    private fun BusLocation.toPosition(): BusPosition? {
        val la = lat?.toDoubleOrNull() ?: return null
        val lo = lng?.toDoubleOrNull() ?: return null
        return BusPosition(
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

        /** Distancia en metros desde un punto hasta la polilínea más cercana de una ruta. */
        fun distanciaARuta(lat: Double, lng: Double, puntos: List<Pair<Double, Double>>): Double {
            val metrosPorGrado = 111320.0
            val cosLat = Math.cos(Math.toRadians(lat))
            fun x(lo: Double) = lo * metrosPorGrado * cosLat
            fun y(la: Double) = la * metrosPorGrado

            val px = x(lng); val py = y(lat)
            var mejor = Double.MAX_VALUE
            for (i in 0 until puntos.size - 1) {
                val ax = x(puntos[i].second);     val ay = y(puntos[i].first)
                val bx = x(puntos[i + 1].second); val by = y(puntos[i + 1].first)
                val dx = bx - ax; val dy = by - ay
                val largo2 = dx * dx + dy * dy
                val t = if (largo2 == 0.0) 0.0
                        else (((px - ax) * dx + (py - ay) * dy) / largo2).coerceIn(0.0, 1.0)
                val d = Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
                if (d < mejor) mejor = d
            }
            return mejor
        }

        /**
         * El JSON de GPS Bahía no dice a qué línea pertenece cada colectivo, así que se
         * considera "de la línea X" al que está sobre (a menos de [toleranciaMetros]) alguno
         * de los recorridos de X. Es una aproximación: puede incluir un colectivo de otra
         * línea que comparta calles con X.
         */
        fun busesEnLinea(
            buses: List<BusPosition>,
            rutas: List<RutaLinea>,
            toleranciaMetros: Double = 80.0
        ): List<BusPosition> =
            buses.filter { bus ->
                (bus.segundosSinSenal ?: 0) < MAX_SEGUNDOS_SIN_SENAL &&
                    rutas.any { distanciaARuta(bus.lat, bus.lng, it.puntos) < toleranciaMetros }
            }

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
