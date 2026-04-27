package com.example.smartwalkapp.data.api

import com.example.smartwalkapp.data.model.PlacesResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface GooglePlacesApi {
    @GET("maps/api/place/nearbysearch/json")
    suspend fun getNearbyPlaces(
        @Query("location") location: String, // "lat,lng"
        @Query("radius") radius: Int,
        @Query("type") type: String?,
        @Query("key") apiKey: String
    ): PlacesResponse
}
