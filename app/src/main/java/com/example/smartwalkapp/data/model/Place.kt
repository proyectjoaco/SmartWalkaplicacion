package com.example.smartwalkapp.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PlacesResponse(
    @Json(name = "results") val results: List<Place>
)

@JsonClass(generateAdapter = true)
data class Place(
    @Json(name = "name") val name: String,
    @Json(name = "vicinity") val vicinity: String?,
    @Json(name = "geometry") val geometry: Geometry,
    @Json(name = "types") val types: List<String>
)

@JsonClass(generateAdapter = true)
data class Geometry(
    @Json(name = "location") val location: Location
)

@JsonClass(generateAdapter = true)
data class Location(
    @Json(name = "lat") val lat: Double,
    @Json(name = "lng") val lng: Double
)
