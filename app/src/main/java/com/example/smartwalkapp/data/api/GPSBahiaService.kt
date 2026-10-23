package com.example.smartwalkapp.data.api

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

    /**
     * Paradas y horarios de una línea (ej: 15 = línea 516). Se devuelve el cuerpo crudo
     * y se lee con org.json (ver ParadasRepository), para tolerar cualquier tipo de campo.
     */
    @GET("web2/get_frecuencias_puntos/{id}")
    suspend fun getFrecuencias(@Path("id") id: Int): ResponseBody
}
