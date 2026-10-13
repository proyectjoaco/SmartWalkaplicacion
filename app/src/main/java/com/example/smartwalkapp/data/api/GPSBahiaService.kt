package com.example.smartwalkapp.data.api

import retrofit2.http.GET

data class GPSBahiaResponse(
    val status: String,
    val data: List<BusLocation>
)

data class BusLocation(
    val interno: String,
    val angle: String,
    val dt_server: String,
    val dt_tracker: String,
    val icon: String?,
    val imei: String,
    val lat: String,
    val lng: String,
    val name: String,
    val direccion: String
)

interface GPSBahiaService {

    @GET("app/track_data/4.json?vggaxqq=ad034191e01876438cebbb8abfa07c9e")
    suspend fun getBusLocations(): GPSBahiaResponse
}
