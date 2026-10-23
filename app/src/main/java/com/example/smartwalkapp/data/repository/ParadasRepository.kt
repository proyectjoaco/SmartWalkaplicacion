package com.example.smartwalkapp.data.repository

import android.location.Location
import android.util.Log
import com.example.smartwalkapp.data.api.GPSBahiaService
import com.example.smartwalkapp.data.api.RetrofitClient
import com.example.smartwalkapp.data.api.SapemStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Una línea que pasa por una parada, con el sentido en que lo hace ("ida" o "vuelta"). */
data class LineaDeParada(
    val linea: String,      // "500", "516", "513 EX", etc.
    val sentido: String
)

/** Una parada de gpsbahia.com.ar (web2/get_paradas). Puede servir a varias líneas. */
data class ParadaGps(
    val id: String,         // "gps_<Id>"; el prefijo distingue estas paradas de las del CSV o de Google
    val nombre: String,     // por ejemplo "J.B. JUSTO 2099"
    val lat: Double,
    val lng: Double,
    val sentido: String,
    val lineas: List<LineaDeParada>
) {
    val nombresDeLineas: List<String> get() = lineas.map { it.linea }.distinct().sorted()
}

data class ParadaCercana(
    val parada: ParadaGps,
    val metros: Double,
    val rumboDesdeMi: Float,   // hacia dónde queda la parada respecto de mi posición (0 = norte)
    val sentido: String        // sentido de la línea consultada (o el de la parada si no se filtró)
)

class ParadasRepository(
    private val service: GPSBahiaService = RetrofitClient.gpsBahiaService
) {
    private var cache: Pair<Long, List<ParadaGps>>? = null

    /** Todas las paradas de todas las líneas. Se guardan 6 horas: casi nunca cambian. */
    suspend fun getTodas(): List<ParadaGps> {
        cache?.let { (hora, lista) ->
            if (System.currentTimeMillis() - hora < CACHE_MS && lista.isNotEmpty()) return lista
        }

        var paradas = pedir()
        if (paradas.isEmpty()) {
            // Puede que el servidor aún no nos haya "activado": abrir la página y reintentar una vez
            try {
                service.abrirPagina("https://www.gpsbahia.com.ar/").close()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PARADAS", "No se pudo abrir gpsbahia.com.ar", e)
            }
            paradas = pedir()
        }
        if (paradas.isNotEmpty()) cache = System.currentTimeMillis() to paradas
        Log.d("PARADAS", "Paradas cargadas: ${paradas.size}")
        return paradas
    }

    private suspend fun pedir(): List<ParadaGps> =
        try {
            val crudo = withContext(Dispatchers.IO) {
                service.getParadas().use { it.string() }
            }
            parsear(crudo)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("PARADAS", "Error pidiendo web2/get_paradas", e)
            emptyList()
        }

    /**
     * Lee la respuesta con org.json (tolera cualquier tipo de campo). Cada parada trae
     * "latLng" y "lineas" como texto que contiene otro JSON, así que se leen en un segundo paso.
     */
    private fun parsear(crudo: String): List<ParadaGps> {
        val ini = crudo.indexOf('{')
        val fin = crudo.lastIndexOf('}')
        if (ini < 0 || fin <= ini) {
            Log.e("PARADAS", "La respuesta no es JSON. Empieza con: ${crudo.take(200)}")
            return emptyList()
        }

        val arreglo = JSONObject(crudo.substring(ini, fin + 1)).optJSONArray("paradas")
        if (arreglo == null) {
            Log.e("PARADAS", "La respuesta no trae 'paradas'. Empieza con: ${crudo.take(200)}")
            return emptyList()
        }

        val paradas = mutableListOf<ParadaGps>()
        for (i in 0 until arreglo.length()) {
            val p = arreglo.optJSONObject(i) ?: continue
            if (!p.isNull("deleted_at") && p.optString("deleted_at").isNotEmpty()) continue   // dada de baja

            val id = p.optString("Id").ifEmpty { p.optString("id_parada") }
            if (id.isEmpty()) continue
            val (lat, lng) = coordenadas(p.opt("latLng")) ?: continue
            val lineas = lineasDe(p.opt("lineas"))
            if (lineas.isEmpty()) continue                              // paradas sin ninguna línea

            paradas += ParadaGps(
                id = "gps_$id",
                nombre = p.optString("nombre").trim().ifEmpty { "Parada" },
                lat = lat,
                lng = lng,
                sentido = p.optString("sentido").lowercase(),
                lineas = lineas
            )
        }
        return unirRepetidas(paradas)
    }

    /**
     * get_paradas trae la misma parada física varias veces (una por línea o por sentido).
     * Se unen las que tienen el mismo nombre y están a menos de ~15 m, sumando sus líneas.
     */
    private fun unirRepetidas(paradas: List<ParadaGps>): List<ParadaGps> {
        val grupos = LinkedHashMap<String, MutableList<ParadaGps>>()
        for (p in paradas) {
            val clave = "${p.nombre.lowercase().replace(Regex("\\s+"), " ")}|" +
                "${Math.round(p.lat * 7000)}|${Math.round(p.lng * 7000)}"
            grupos.getOrPut(clave) { mutableListOf() }.add(p)
        }
        return grupos.values.map { g ->
            if (g.size == 1) g[0]
            else g[0].copy(lineas = g.flatMap { it.lineas }.distinctBy { it.linea to it.sentido })
        }
    }

    /** "latLng" viene como texto: {"lat":-38.77,"lng":-62.24}. También acepta un objeto directo. */
    private fun coordenadas(valor: Any?): Pair<Double, Double>? {
        val json = when (valor) {
            is JSONObject -> valor
            is String -> try { JSONObject(valor) } catch (e: Exception) { null }
            else -> null
        } ?: return null
        val lat = json.optDouble("lat", Double.NaN)
        val lng = json.optDouble("lng", Double.NaN)
        return if (lat.isNaN() || lng.isNaN()) null else lat to lng
    }

    /** "lineas" viene como texto: [{"linea_id":"4","sentido":"ida","nombre":"500"}]. */
    private fun lineasDe(valor: Any?): List<LineaDeParada> {
        val arreglo = when (valor) {
            is JSONArray -> valor
            is String -> try { JSONArray(valor) } catch (e: Exception) { null }
            else -> null
        } ?: return emptyList()

        val lineas = mutableListOf<LineaDeParada>()
        for (i in 0 until arreglo.length()) {
            val o = arreglo.optJSONObject(i) ?: continue
            val nombre = o.optString("nombre").trim()
            if (nombre.isNotEmpty()) lineas += LineaDeParada(nombre, o.optString("sentido").lowercase())
        }
        return lineas
    }

    /**
     * Las paradas más cercanas a (lat, lng), de la más próxima a la más lejana.
     * Si se indica [linea] ("516"), solo las de esa línea (también cuenta "513 EX" para "513").
     */
    suspend fun cercanas(lat: Double, lng: Double, max: Int = 20, linea: String? = null): List<ParadaCercana> {
        val res = FloatArray(2)
        return getTodas()
            .mapNotNull { p ->
                val sentido = if (linea != null) {
                    val l = p.lineas.firstOrNull { coincide(it.linea, linea) } ?: return@mapNotNull null
                    l.sentido.ifEmpty { p.sentido }
                } else {
                    p.sentido
                }
                Location.distanceBetween(lat, lng, p.lat, p.lng, res)
                ParadaCercana(p, res[0].toDouble(), (res[1] + 360f) % 360f, sentido)
            }
            .sortedBy { it.metros }
            .take(max)
    }

    private fun coincide(nombreLinea: String, buscada: String): Boolean =
        nombreLinea == buscada || nombreLinea.startsWith("$buscada ")

    companion object {
        private const val CACHE_MS = 6 * 60 * 60 * 1000L
    }
}

/** Convierte una parada de gpsbahia al tipo que ya muestra la pantalla de Transporte. */
fun ParadaCercana.toSapemStop(): SapemStop = SapemStop(
    id = parada.id,
    nombre = if (sentido.isNotEmpty()) "${parada.nombre} ($sentido)" else parada.nombre,
    lat = parada.lat,
    lng = parada.lng,
    lineas = parada.nombresDeLineas
)
