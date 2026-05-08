package com.example.smartwalkapp.ui.home

import android.annotation.SuppressLint
import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartwalkapp.data.LocationHelper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*

class HomeViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val locationHelper = LocationHelper(application)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val _address = MutableStateFlow("Detecting location...")
    val address: StateFlow<String> = _address

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        tts = TextToSpeech(application, this)
        startLocationUpdates()
        // Simulamos un tiempo de carga mínimo para que la animación se vea
        viewModelScope.launch {
            kotlinx.coroutines.delay(2500)
            _isLoading.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    refreshLocation()
                }
            },
            null
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("es", "ES"))
            isTtsReady = true
            speakWelcomeMessage()
        }
    }

    private fun speakWelcomeMessage() {
        val welcomeText = "Bienvenido a SmartWalk, soy Walkie, tu asistente en este viaje"
        tts?.speak(welcomeText, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun speakCurrentLocation() {
        if (isTtsReady) {
            val locationText = "Tu ubicación actual es ${_address.value}"
            tts?.speak(locationText, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun refreshLocation() {
        viewModelScope.launch {
            val result = locationHelper.getCurrentAddress()
            _address.value = result ?: "Location not available"
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.shutdown()
    }
}
