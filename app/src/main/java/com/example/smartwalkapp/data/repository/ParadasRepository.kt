package com.example.smartwalkapp.data.repository

import android.location.Location
import android.util.Log
import com.example.smartwalkapp.data.api.Frecuencia
import com.example.smartwalkapp.data.api.GPSBahiaService
import com.example.smartwalkapp.data.api.RetrofitClient
import com.example.smartwalkapp.data.api.SapemStop
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Una parada de una línea, con sus horarios (en minutos desde la medianoche). */
data class ParadaLinea(
    val linea: String,
    val puntoId: String,
    val nombre: String,
    val sentido: String,                 // "IDA" o "VUELTA"
    val lat: Double,
    val lng: Double,
    val horariosHabiles: List<Int>,
    val horariosSabados: List<Int>,
    val horariosDomingos: List<Int>
) {
    /** Id único de la parada; el prefijo "gps_" la distingue de las paradas del CSV o de Google. */
    val id: String get() = "gps_${linea}_${puntoId}_$sentido"
}

data class ProximaLlegada(
    val hora: String,          // "14:32"
    val minutos: Int,          // minutos desde ahora
    val esManana: Boolean,     // true si ya no quedan horarios hoy
    val siguiente: String?     // la hora que sigue a esa, si existe
)

data class ParadaCercana(
    val parada: ParadaLinea,
    val metros: Double,
    val rumboDesdeMi: Float,   // dirección en que queda la parada respecto de mi posición (0 = norte)
    val proxima: ProximaLlegada?
)

class ParadasRepository(
    private val service: GPSBahiaService = RetrofitClient.gpsBahiaService
) {
    private val cache = mutableMapOf<String, Pair<Long, List<ParadaLinea>>>()

    /** Paradas y horarios de la línea. Se guardan 10 minutos para no pedirlos a cada pregunta. */
    suspend fun getParadas(linea: String): List<ParadaLinea> {
        val id = LINEA_ID[linea] ?: return emptyList()
        cache[linea]?.let { (hora, lista) ->
            if (System.currentTimeMillis() - hora < CACHE_MS && lista.isNotEmpty()) return lista
        }

        var paradas = pedir(linea, id)
        if (paradas.isEmpty()) {
            // Puede que el servidor aún no nos haya "activado": abrir la página y reintentar una vez
            try {
                service.abrirPagina("https://www.gpsbahia.com.ar/").close()
            } catch (e: Exception) {
                Log.e("PARADAS", "No se pudo abrir gpsbahia.com.ar", e)
            }
            paradas = pedir(linea, id)
        }
        if (paradas.isNotEmpty()) cache[linea] = System.currentTimeMillis() to paradas
        Log.d("PARADAS", "Línea $linea: ${paradas.size} paradas")
        return paradas
    }

    private suspend fun pedir(linea: String, id: Int): List<ParadaLinea> =
        try {
            service.getFrecuencias(id).frecuencias.mapNotNull { it.toParada(linea) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("PARADAS", "Error pidiendo get_frecuencias_puntos/$id", e)
            emptyList()
        }

    /** Las paradas de la línea ordenadas de más cerca a más lejos de (lat, lng). */
    suspend fun paradasCercanas(linea: String, lat: Double, lng: Double, max: Int = 6): List<ParadaCercana> {
        val res = FloatArray(2)
        val ahora = Calendar.getInstance(ZONA)
        return getParadas(linea)
            .map { p ->
                Location.distanceBetween(lat, lng, p.lat, p.lng, res)
                ParadaCercana(p, res[0].toDouble(), (res[1] + 360f) % 360f, proximaLlegada(p, ahora))
            }
            .sortedBy { it.metros }
            .take(max)
    }

    /**
     * Próximo horario de la parada según la hora de Argentina y el día (hábil, sábado o domingo).
     * OJO: son los horarios programados de la tabla, no la posición real del colectivo,
     * y no se distinguen feriados.
     */
    fun proximaLlegada(p: ParadaLinea, ahora: Calendar = Calendar.getInstance(ZONA)): ProximaLlegada? {
        val minAhora = ahora.get(Calendar.HOUR_OF_DAY) * 60 + ahora.get(Calendar.MINUTE)
        val dia = ahora.get(Calendar.DAY_OF_WEEK)

        val restantes = listaDelDia(p, dia).filter { it >= minAhora }
        if (restantes.isNotEmpty()) {
            val t = restantes.first()
            return ProximaLlegada(formato(t), t - minAhora, false, restantes.getOrNull(1)?.let { formato(it) })
        }

        val manana = listaDelDia(p, dia % 7 + 1)
        val t = manana.firstOrNull() ?: return null
        return ProximaLlegada(formato(t), 1440 - minAhora + t, true, manana.getOrNull(1)?.let { formato(it) })
    }

    private fun listaDelDia(p: ParadaLinea, diaSemana: Int): List<Int> = when (diaSemana) {
        Calendar.SATURDAY -> p.horariosSabados
        Calendar.SUNDAY -> p.horariosDomingos
        else -> p.horariosHabiles
    }

    private fun formato(minutos: Int): String =
        String.format(Locale.US, "%d:%02d", (minutos / 60) % 24, minutos % 60)

    private fun Frecuencia.toParada(linea: String): ParadaLinea? {
        val p = punto ?: return null
        val la = p.lat?.toDoubleOrNull() ?: return null
        val lo = p.lng?.toDoubleOrNull() ?: return null
        return ParadaLinea(
            linea = linea,
            puntoId = p.id ?: puntoId ?: return null,
            nombre = p.nombre ?: "Parada",
            sentido = (p.direccion ?: "").uppercase(),
            lat = la,
            lng = lo,
            horariosHabiles = parseHorarios(horarios),
            horariosSabados = parseHorarios(horariosSabados),
            horariosDomingos = parseHorarios(horariosDomingos)
        )
    }

    // Acepta "6:24, 6:54" o "06:24;06:54": busca todas las horas H:MM del texto.
    private fun parseHorarios(texto: String?): List<Int> =
        Regex("(\\d{1,2}):(\\d{2})").findAll(texto ?: "")
            .mapNotNull { m ->
                val h = m.groupValues[1].toIntOrNull()
                val mi = m.groupValues[2].toIntOrNull()
                if (h != null && mi != null && mi < 60) h * 60 + mi else null
            }
            .toList().sorted().distinct()

    companion object {
        /**
         * Líneas con paradas y horarios habilitadas por ahora (solo la 516 para probar).
         * Para sumar más: "500" to 4, "512" to 11, etc. El número es el de get_frecuencias_puntos/N.
         */
        val LINEA_ID: Map<String, Int> = mapOf("516" to 15)

        private const val CACHE_MS = 10 * 60 * 1000L
        private val ZONA: TimeZone = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
    }
}

/** Convierte una parada de gpsbahia al tipo que ya muestra la pantalla de Transporte. */
fun ParadaCercana.toSapemStop(): SapemStop {
    val horario = proxima?.let {
        if (it.esManana) " · próximo mañana ${it.hora}"
        else " · próximo ${it.hora} (en ${it.minutos} min)"
    } ?: ""
    return SapemStop(
        id = parada.id,
        nombre = "${parada.nombre} (${parada.sentido.lowercase()})",
        lat = parada.lat,
        lng = parada.lng,
        lineas = listOf("${parada.linea}$horario")
    )
}
