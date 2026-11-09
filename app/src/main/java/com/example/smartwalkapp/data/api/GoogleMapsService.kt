package com.example.smartwalkapp.data.api

import com.example.smartwalkapp.data.model.Place
import com.example.smartwalkapp.data.model.PlacesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Maps con las APIs NUEVAS (Places API (New) y Routes API). Las "legacy"
 * (maps/api/place y maps/api/directions) Google ya no las deja usar en proyectos nuevos.
 *
 * Mantiene las mismas funciones y los mismos tipos de respuesta que antes, para que el
 * resto de la app no cambie: si Google rechaza algo, "status" trae el motivo
 * (por ejemplo PERMISSION_DENIED) y "error_message" el detalle.
 */
class GoogleMapsService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /** POST a una API de Google. Devuelve el JSON, o uno con "error" si falló. */
    private suspend fun post(url: String, apiKey: String, fieldMask: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", fieldMask)
                .post(body.toString().toRequestBody(json))
                .build()
            client.newCall(request).execute().use { r ->
                val texto = r.body?.string().orEmpty()
                try {
                    JSONObject(texto.ifBlank { "{}" })
                } catch (e: Exception) {
                    JSONObject().put("error", JSONObject()
                        .put("status", "HTTP_${r.code}")
                        .put("message", texto.take(200)))
                }
            }
        }

    private fun circulo(lat: Double, lng: Double, radio: Int) = JSONObject().put("circle", JSONObject()
        .put("center", JSONObject().put("latitude", lat).put("longitude", lng))
        .put("radius", radio.coerceIn(1, 50000).toDouble()))

    private fun lugares(resp: JSONObject): PlacesResponse {
        resp.optJSONObject("error")?.let { e ->
            return PlacesResponse(emptyList(), e.optString("status", "ERROR"), e.optString("message"))
        }
        val arr = resp.optJSONArray("places") ?: JSONArray()
        val lista = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val loc = p.optJSONObject("location") ?: return@mapNotNull null
            val tipos = p.optJSONArray("types")
            Place(
                name = p.optJSONObject("displayName")?.optString("text").orEmpty().ifEmpty { "Sin nombre" },
                vicinity = p.optString("formattedAddress").ifEmpty { null },
                geometry = com.example.smartwalkapp.data.model.Geometry(
                    com.example.smartwalkapp.data.model.Location(
                        loc.optDouble("latitude"), loc.optDouble("longitude")
                    )
                ),
                types = tipos?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList()
            )
        }
        return PlacesResponse(lista, if (lista.isEmpty()) "ZERO_RESULTS" else "OK", null)
    }

    /** Lugares de un tipo cerca de "lat,lng" (por ejemplo restaurant, hospital, bus_station). */
    suspend fun getNearbyPlaces(location: String, radius: Int = 2000, type: String? = null, apiKey: String): PlacesResponse {
        val (lat, lng) = location.split(",").map { it.trim().toDouble() }
        val body = JSONObject()
            .put("maxResultCount", 20)
            .put("languageCode", "es")
            .put("locationRestriction", circulo(lat, lng, radius))
        if (!type.isNullOrBlank()) body.put("includedTypes", JSONArray().put(type))
        return lugares(post(PLACES_NEARBY, apiKey, PLACES_FIELDS, body))
    }

    /** Búsqueda por texto ("farmacia", "plaza") priorizando lo que está cerca de "lat,lng". */
    suspend fun searchPlaces(query: String, location: String? = null, radius: Int? = null, apiKey: String): PlacesResponse {
        val body = JSONObject().put("textQuery", query).put("languageCode", "es")
        if (location != null) {
            val (lat, lng) = location.split(",").map { it.trim().toDouble() }
            body.put("locationBias", circulo(lat, lng, radius ?: 5000))
        }
        return lugares(post(PLACES_TEXT, apiKey, PLACES_FIELDS, body))
    }

    /** Ruta a pie paso a paso (Routes API). origin y destination son "lat,lng". */
    suspend fun getDirections(origin: String, destination: String, mode: String = "walking", language: String = "es", apiKey: String): DirectionsResponse {
        fun punto(s: String): JSONObject {
            val (lat, lng) = s.split(",").map { it.trim().toDouble() }
            return JSONObject().put("location", JSONObject().put("latLng",
                JSONObject().put("latitude", lat).put("longitude", lng)))
        }
        val body = JSONObject()
            .put("origin", punto(origin))
            .put("destination", punto(destination))
            .put("travelMode", when (mode) { "driving" -> "DRIVE"; "bicycling" -> "BICYCLE"; "transit" -> "TRANSIT"; else -> "WALK" })
            .put("languageCode", language)
            .put("units", "METRIC")

        val resp = post(ROUTES_URL, apiKey, ROUTES_FIELDS, body)
        resp.optJSONObject("error")?.let { e ->
            return DirectionsResponse(emptyList(), e.optString("status", "ERROR"), e.optString("message"))
        }
        val ruta = resp.optJSONArray("routes")?.optJSONObject(0)
            ?: return DirectionsResponse(emptyList(), "ZERO_RESULTS")

        val pasos = mutableListOf<Step>()
        val tramo = ruta.optJSONArray("legs")?.optJSONObject(0)
        val arr = tramo?.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val st = arr.optJSONObject(i) ?: continue
            val inicio = st.optJSONObject("startLocation")?.optJSONObject("latLng") ?: continue
            val metros = st.optInt("distanceMeters")
            val seg = segundos(st.optString("staticDuration"))
            val nav = st.optJSONObject("navigationInstruction")
            pasos += Step(
                html_instructions = nav?.optString("instructions").orEmpty().ifEmpty { "Continuá" },
                distance = Distance("$metros m", metros),
                duration = Duration("${(seg + 59) / 60} min", seg),
                start_location = Location(inicio.optDouble("latitude"), inicio.optDouble("longitude")),
                maneuver = nav?.optString("maneuver")?.ifEmpty { null }?.lowercase()?.replace('_', '-')
            )
        }
        if (pasos.isEmpty()) return DirectionsResponse(emptyList(), "ZERO_RESULTS")

        val metrosTotal = tramo?.optInt("distanceMeters") ?: 0
        val segTotal = segundos(tramo?.optString("duration").orEmpty())
        return DirectionsResponse(
            routes = listOf(
                Route(
                    overview_polyline = OverviewPolyline(
                        ruta.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
                    ),
                    legs = listOf(
                        Leg(pasos, Distance("$metrosTotal m", metrosTotal), Duration("${(segTotal + 59) / 60} min", segTotal))
                    )
                )
            ),
            status = "OK"
        )
    }

    // Las duraciones llegan como "123s"
    private fun segundos(texto: String): Int = texto.removeSuffix("s").toDoubleOrNull()?.toInt() ?: 0

    private companion object {
        const val PLACES_NEARBY = "https://places.googleapis.com/v1/places:searchNearby"
        const val PLACES_TEXT = "https://places.googleapis.com/v1/places:searchText"
        const val PLACES_FIELDS = "places.displayName,places.formattedAddress,places.location,places.types"
        const val ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
        const val ROUTES_FIELDS = "routes.polyline.encodedPolyline,routes.legs.distanceMeters,routes.legs.duration," +
            "routes.legs.steps.navigationInstruction,routes.legs.steps.distanceMeters," +
            "routes.legs.steps.staticDuration,routes.legs.steps.startLocation"
    }
}

data class Location(
    val lat: Double,
    val lng: Double
)

data class DirectionsResponse(
    val routes: List<Route> = emptyList(),
    val status: String,
    val error_message: String? = null
)

data class Route(
    val overview_polyline: OverviewPolyline,
    val legs: List<Leg>
)

data class Leg(
    val steps: List<Step>,
    val distance: Distance,
    val duration: Duration
)

data class Step(
    val html_instructions: String,
    val distance: Distance,
    val duration: Duration,
    val start_location: Location,
    val maneuver: String? = null
)

data class Distance(val text: String, val value: Int)
data class Duration(val text: String, val value: Int)

data class OverviewPolyline(
    val points: String
)
