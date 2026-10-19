package com.example.smartwalkapp.services

import com.example.smartwalkapp.BuildConfig

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smartwalkapp.R
import com.example.smartwalkapp.MainActivity
import com.example.smartwalkapp.data.api.RetrofitClient
import com.example.smartwalkapp.data.api.Step
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.*

class NavigationService : Service(), TextToSpeech.OnInitListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val CHANNEL_ID = "navigation_channel"
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var navigationSteps = mutableListOf<Step>()
    private var currentStepIndex = 0
    private var destinationLat: Double = 0.0
    private var destinationLng: Double = 0.0
    private var hasAnnouncedArrival = false
    private val GOOGLE_API_KEY = BuildConfig.GOOGLE_API_KEY

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                checkNavigationProgress(location.latitude, location.longitude)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("es", "ES"))
            isTtsReady = true
        }
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            val cleanText = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val destName = intent?.getStringExtra("destination_name") ?: "Parada de bus"
        destinationLat = intent?.getDoubleExtra("destination_lat", 0.0) ?: 0.0
        destinationLng = intent?.getDoubleExtra("destination_lng", 0.0) ?: 0.0
        hasAnnouncedArrival = false
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Navegación SmartWalk")
            .setContentText("Caminando hacia: $destName")
            .setSmallIcon(R.drawable.app_logo)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }

        fetchRoute(destinationLat, destinationLng)
        startLocationUpdates()

        return START_STICKY
    }

    private fun fetchRoute(destLat: Double, destLng: Double) {
        serviceScope.launch {
            try {
                val lastLocation = try {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            serviceScope.launch {
                                getDirections("${location.latitude},${location.longitude}", "$destLat,$destLng")
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    null
                }
            } catch (e: Exception) {
                Log.e("NavService", "Error getting route", e)
            }
        }
    }

    private suspend fun getDirections(origin: String, destination: String) {
        try {
            val response = RetrofitClient.googleMapsService.getDirections(
                origin = origin,
                destination = destination,
                apiKey = GOOGLE_API_KEY
            )
            if (response.status == "OK" && response.routes.isNotEmpty()) {
                navigationSteps = response.routes[0].legs[0].steps.toMutableList()
                currentStepIndex = 0
                if (navigationSteps.isNotEmpty()) {
                    speak("Ruta encontrada. " + navigationSteps[0].html_instructions)
                }
            } else {
                Log.e("NavService", "Google Directions: ${response.status} - ${response.error_message}")
                speak("No pude calcular la ruta con Google Maps.")
            }
        } catch (e: Exception) {
            Log.e("NavService", "Directions API failed", e)
        }
    }

    private fun checkNavigationProgress(lat: Double, lng: Double) {
        // 1. Verificar si llegamos al destino final
        val distToDest = FloatArray(1)
        android.location.Location.distanceBetween(lat, lng, destinationLat, destinationLng, distToDest)
        
        if (distToDest[0] < 20f && !hasAnnouncedArrival) {
            speak("Has llegado a tu destino. La parada está muy cerca.")
            hasAnnouncedArrival = true
            // Podríamos detener el servicio aquí o dejar que el usuario lo haga
            return
        }

        // 2. Guía paso a paso
        if (currentStepIndex >= navigationSteps.size) return

        val nextStep = navigationSteps[currentStepIndex]
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            lat, lng,
            nextStep.start_location.lat, nextStep.start_location.lng,
            results
        )

        val distanceToNextStep = results[0]

        // Si estamos a menos de 15 metros del inicio del siguiente paso, damos la instrucción
        if (distanceToNextStep < 15f) {
            speak(nextStep.html_instructions)
            currentStepIndex++
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        } catch (unlikely: SecurityException) {
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal de Navegación SmartWalk",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        tts?.shutdown()
    }
}
