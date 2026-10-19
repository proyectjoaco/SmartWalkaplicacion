package com.example.smartwalkapp.data

import com.example.smartwalkapp.BuildConfig
import com.example.smartwalkapp.data.api.GooglePlacesApi
import com.example.smartwalkapp.data.model.Place
import com.example.smartwalkapp.data.model.Geometry
import com.example.smartwalkapp.data.model.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlacesRepository(private val api: GooglePlacesApi) {
    
    // In a real app, this would be in local.properties
    private val API_KEY = BuildConfig.GOOGLE_API_KEY

    suspend fun getNearbyPlaces(lat: Double, lng: Double, type: String?): List<Place> = withContext(Dispatchers.IO) {
        if (API_KEY.isEmpty()) {
            return@withContext getMockPlaces(type)
        }
        try {
            val response = api.getNearbyPlaces("$lat,$lng", 1500, type, API_KEY)
            response.results
        } catch (e: Exception) {
            getMockPlaces(type)
        }
    }

    private fun getMockPlaces(type: String?): List<Place> {
        return listOf(
            Place(
                name = if (type == "restaurant") "Carrito de la 14" else "Paseo del Sol",
                vicinity = if (type == "restaurant") "Av. Alem 1400, Bahía Blanca" else "Sarmiento 2100, Bahía Blanca",
                geometry = Geometry(Location(-38.7183, -62.2663)),
                types = listOf(type ?: "point_of_interest")
            ),
            Place(
                name = if (type == "health") "Hospital Municipal" else "Plaza Rivadavia",
                vicinity = if (type == "health") "Estomba 900, Bahía Blanca" else "San Martín 1, Bahía Blanca",
                geometry = Geometry(Location(-38.7190, -62.2655)),
                types = listOf(type ?: "point_of_interest")
            )
        )
    }
}
