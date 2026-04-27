package com.example.smartwalkapp.data.repository

import android.content.Context
import com.example.smartwalkapp.data.api.SapemStop
import java.io.BufferedReader
import java.io.InputStreamReader

class BusRouteRepository(private val context: Context) {

    fun getLinesFromCsv(): List<String> {
        val lines = mutableListOf<String>()
        try {
            val inputStream = context.assets.open("recorridos-colectivos.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.readLine() // Skip header
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val columns = line?.split(";") ?: continue
                if (columns.isNotEmpty()) {
                    val name = columns[0].replace("\"", "").trim()
                    if (name.isNotEmpty()) lines.add(name)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return lines.distinct()
    }

    fun getRouteDescription(lineName: String): String? {
        try {
            val inputStream = context.assets.open("recorridos-colectivos.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.readLine()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val columns = line?.split(";") ?: continue
                val name = columns[0].replace("\"", "").trim()
                if (name.equals(lineName, ignoreCase = true)) {
                    return columns[1].replace("\"", "").replace("<br>", " ")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // Busca patrones de esquinas reales en el texto
    fun extractIntersections(description: String): List<String> {
        val cleanDescription = description
            .replace("RECORRIDO:", "", ignoreCase = true)
            .replace("<br>", " ")
            .replace("\"", "")

        val intersections = mutableListOf<String>()
        
        // Buscamos el patrón "Calle y Calle" o "Calle , Calle"
        val regex = Regex("([A-Z0-9][a-zA-Z0-9.]+(?: [a-zA-Z0-9.]+)*)\\s+(?:y|,)\\s+([A-Z0-9][a-zA-Z0-9.]+(?: [a-zA-Z0-9.]+)*)")
        val matches = regex.findAll(cleanDescription)
        
        for (match in matches) {
            val corner = match.value.trim()
            if (corner.length > 10) {
                intersections.add(corner)
            }
        }

        // Si no encontró intersecciones, probamos con las partes separadas por comas
        if (intersections.isEmpty()) {
            return cleanDescription.split(",")
                .map { it.trim() }
                .filter { it.length in 6..40 }
                .take(15)
        }

        return intersections.distinct().take(15)
    }
}
