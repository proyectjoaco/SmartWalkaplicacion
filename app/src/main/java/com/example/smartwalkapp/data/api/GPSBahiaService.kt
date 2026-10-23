package com.example.smartwalkapp.data.api

import com.squareup.moshi.Json
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

// Todos los campos tienen valor por defecto / son opcionales para que Moshi no
// falle si el servidor devuelve un campo en null o directamente no lo manda
// (por ejemplo cuando responde {"status":"ok","token":"ok","data":[]}).
data class GPSBahiaResponse(
    val status: String? = null,
    val data: List<BusLocation> = emptyList()
)

data class BusLocation(
    val interno: String = "",
    val angle: String? = null,
    val dt_server: String? = null,
    val dt_tracker: String? = null,
    val icon: String? = null,
    val imei: String? = null,
    val lat: String? = null,
    val lng: String? = null,
    val name: String? = null,
    val direccion: String? = null
)

// Respuesta de web2/get_frecuencias_puntos/{id}: las paradas de una línea con sus horarios.
data class FrecuenciasResponse(
    val status: String? = null,
    val frecuencias: List<Frecuencia> = emptyList()
)

data class Frecuencia(
    @Json(name = "Id") val id: String? = null,
    @Json(name = "punto_id") val puntoId: String? = null,
    @Json(name = "linea_id") val lineaId: String? = null,
    // Horas separadas por coma, por ejemplo "6:24, 6:54, 7:24"
    val horarios: String? = null,
    @Json(name = "horarios_sabados") val horariosSabados: String? = null,
    @Json(name = "horarios_domingos") val horariosDomingos: String? = null,
    val punto: PuntoParada? = null
)

data class PuntoParada(
    @Json(name = "Id") val id: String? = null,
    val nombre: String? = null,
    val direccion: String? = null,   // sentido: "IDA" o "VUELTA"
    val lat: String? = null,
    val lng: String? = null
)

interface GPSBahiaService {

    /**
     * Abre una página completa de gpsbahia.com.ar. El servidor solo devuelve
     * datos en track_data después de que la página principal fue visitada
     * (la respuesta llega con "data": [] hasta entonces).
     */
    @GET
    suspend fun abrirPagina(@Url url: String): ResponseBody

    @GET("app/track_data/{id}.json")
    suspend fun getBusLocations(
        @Path("id") id: Int,
        @Query("vggaxqq") token: String
    ): GPSBahiaResponse

    /** Paradas y horarios de una línea (ej: 15 = línea 516). */
    @GET("web2/get_frecuencias_puntos/{id}")
    suspend fun getFrecuencias(@Path("id") id: Int): FrecuenciasResponse
}
