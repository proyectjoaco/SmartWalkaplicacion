package com.example.smartwalkapp.data.api

import retrofit2.http.GET
import retrofit2.http.Query

interface GoogleMapsService {
    @GET("maps/api/directions/json")
    suspend fun getDirections(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("mode") mode: String = "walking",
        @Query("language") language: String = "es",
        @Query("key") apiKey: String
    ): DirectionsResponse

    @GET("maps/api/place/nearbysearch/json")
    suspend fun getNearbyPlaces(
        @Query("location") location: String,
        @Query("radius") radius: Int = 2000,
        @Query("type") type: String? = null,
        @Query("key") apiKey: String
    ): com.example.smartwalkapp.data.model.PlacesResponse

    @GET("maps/api/place/textsearch/json")
    suspend fun searchPlaces(
        @Query("query") query: String,
        @Query("location") location: String? = null,
        @Query("radius") radius: Int? = null,
        @Query("key") apiKey: String
    ): com.example.smartwalkapp.data.model.PlacesResponse

    @GET("maps/api/geocode/json")
    suspend fun geocodeAddress(
        @Query("address") address: String,
        @Query("key") apiKey: String
    ): GeocodingResponse
}

data class GeocodingResponse(
    val results: List<GeocodingResult>,
    val status: String
)

data class GeocodingResult(
    val geometry: Geometry
)

data class Geometry(
    val location: Location
)

data class Location(
    val lat: Double,
    val lng: Double
)

data class DirectionsResponse(
    val routes: List<Route>,
    val status: String
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
