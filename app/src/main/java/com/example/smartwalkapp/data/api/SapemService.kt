package com.example.smartwalkapp.data.api

import retrofit2.http.GET
import retrofit2.http.Query

data class SapemStop(
    val id: String,
    val nombre: String,
    val lat: Double,
    val lng: Double,
    val lineas: List<String>
)

data class BusArrival(
    val linea: String,
    val tiempoArribo: Int, // en minutos
    val distancia: String
)

interface SapemService {
    @GET("paradas/cercanas")
    suspend fun getNearbyStops(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radio") radio: Int = 500
    ): List<SapemStop>

    @GET("arribos")
    suspend fun getArrivals(
        @Query("stopId") stopId: String
    ): List<BusArrival>
}
