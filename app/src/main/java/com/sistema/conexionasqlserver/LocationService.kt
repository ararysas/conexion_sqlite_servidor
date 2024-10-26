package com.sistema.conexionasqlserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.location.Location
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sharedPreferences: SharedPreferences
    private val userTrackers = mutableMapOf<Int, UserTracker>()

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sharedPreferences = getSharedPreferences("MyAppPrefs", MODE_PRIVATE)

        // Registrar el BroadcastReceiver para apagado
        registerReceiver(ShutdownReceiver(), IntentFilter(Intent.ACTION_SHUTDOWN))

        // Inicializar el LocationManagerHelper
        LocationManagerHelper.initialize(this)

        checkUserCredentials()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val userId = intent?.getIntExtra("USER_ID", -1) ?: getUserIdFromPreferences()

        if (userId != -1) {
            if (!userTrackers.containsKey(userId)) {
                userTrackers[userId] = UserTracker(userId) { nota, codigo ->
                    saveLocation(userId, nota, codigo)
                }
                userTrackers[userId]?.startTracking(fusedLocationClient)
            }
        }

        startForegroundService()
        return START_STICKY
    }

    private fun getUserIdFromPreferences(): Int {
        return sharedPreferences.getInt("USER_ID", -1)
    }

    private fun startForegroundService() {
        val channelId = "location_service_channel"
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Location Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ubicación en seguimiento")
            .setContentText("El servicio de ubicación está activo.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        startForeground(1, notification)
    }

    private fun saveLocation(userId: Int, nota: String, codigo: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            if (isWithinRestrictedHours()) {
                val userTracker = userTrackers[userId]
                userTracker?.lastLocation?.let { lastLocation ->
                    when {
                        !LocationManagerHelper.isInternetAvailable(this@LocationService) -> {
                            Log.d("LocationService", "No hay conexión a Internet. Guardando ubicación localmente con código 6.")
                            LocationManagerHelper.saveLocationLocally(this@LocationService, userId, lastLocation, nota)
                        }
                        !LocationManagerHelper.isGPSOn(this@LocationService) -> {
                            Log.d("LocationService", "GPS no está habilitado. Guardando ubicación localmente con código 5.")
                            LocationManagerHelper.saveLocationLocally(this@LocationService, userId, lastLocation, nota) // Código 6 para "sin GPS"
                        }
                        else -> {
                            // Si hay conexión y GPS, guarda en SQL Server
                            DatabaseHelper.saveLocation(userId, lastLocation.latitude, lastLocation.longitude, nota, codigo)
                            Log.d("LocationService", "Ubicación guardada exitosamente para el usuario $userId: $nota con código $codigo.")
                        }
                    }
                } ?: run {
                    Log.e("LocationService", "La última ubicación es nula para el usuario $userId.")
                }
            } else {
                Log.d("LocationService", "No se guardará la ubicación: estamos fuera del rango restringido.")
            }
        }
    }

    private fun isWithinRestrictedHours(): Boolean {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        // Rango de horas restringido (8:00 AM a 6:00 PM)
        val startHour = 8  // 8 AM
        val endHour = 18    // 6 PM

        // Verificar si la hora actual está dentro del rango restringido
        return (currentHour == startHour && currentMinute >= 0) ||
                (currentHour in startHour + 1 until endHour)  // Desde la 1 PM hasta antes de las 6 PM
    }

    private fun checkUserCredentials() {
        val username = sharedPreferences.getString("username", null)
        val password = sharedPreferences.getString("password", null)

        if (username != null && password != null) {
            Log.d("LocationService", "Usuario encontrado: $username")
        } else {
            Log.d("LocationService", "No hay credenciales guardadas.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        userTrackers.values.forEach { it.stopTracking() }
    }

    inner class ShutdownReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SHUTDOWN) {
                val userId = getUserIdFromPreferences()
                val lastLocation = userTrackers[userId]?.lastLocation
                if (lastLocation != null) {
                    Log.d("LocationService", "Dispositivo apagándose. Última ubicación: ${lastLocation.latitude}, ${lastLocation.longitude}")
                    saveLocation(userId, "Dispositivo apagado o Reiniciado", 3) // Código 3 para "dispositivo apagado"
                }
            }
        }
    }

    inner class UserTracker(private val userId: Int, private val onSaveLocation: (String, Int) -> Unit) {
        var lastLocation: Location? = null
        private var lastSavedTime: Long = 0
        private val handler = android.os.Handler(Looper.getMainLooper())
        private val interval = 60 * 1000L // 1 minuto

        fun startTracking(fusedLocationClient: FusedLocationProviderClient) {
            startLocationUpdates(fusedLocationClient)

            // Programar la verificación cada minuto
            handler.postDelayed(object : Runnable {
                override fun run() {
                    checkLocationAndSave()
                    handler.postDelayed(this, interval) // Reprogramar
                }
            }, interval)
        }

        private fun checkLocationAndSave() {
            CoroutineScope(Dispatchers.IO).launch {
                val currentLocation = lastLocation
                val internetAvailable = LocationManagerHelper.isInternetAvailable(applicationContext)
                val gpsOn = LocationManagerHelper.isGPSOn(applicationContext)

                if (currentLocation != null) {
                    when {
                        !internetAvailable -> {
                            // Guardar ubicación en SQLite si no hay Internet
                            LocationManagerHelper.saveLocationLocally(applicationContext, userId, currentLocation, "Sin Internet")
                        }
                        !gpsOn -> {
                            // Guardar ubicación en SQLite si el GPS está desactivado
                            LocationManagerHelper.saveLocationLocally(applicationContext, userId, currentLocation, "GPS Apagado")
                        }
                    }
                } else {
                    Log.e("UserTracker", "No hay ubicación disponible para guardar.")
                }
            }
        }

        private fun startLocationUpdates(fusedLocationClient: FusedLocationProviderClient) {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                10 * 60 * 1000L // 10 minutos, puedes ajustar esto
            ).apply {
                setMinUpdateIntervalMillis(5000L)
            }.build()

            fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { newLocation ->
                        handleNewLocation(newLocation)
                    }
                }
            }, Looper.getMainLooper())
        }

        private fun handleNewLocation(newLocation: Location) {
            val previousLocation = lastLocation
            val currentTime = System.currentTimeMillis()

            if (previousLocation == null) {
                lastLocation = newLocation
                lastSavedTime = currentTime
                onSaveLocation("Usuario mismo lugar", 1)
            } else {
                val distance = newLocation.distanceTo(previousLocation)
                if (distance > 100 && (currentTime - lastSavedTime >= interval)) {
                    lastLocation = newLocation
                    lastSavedTime = currentTime
                    onSaveLocation("Usuario en movimiento", 2)
                } else if (currentTime - lastSavedTime >= interval) {
                    onSaveLocation("Usuario mismo lugar", 1)
                    lastSavedTime = currentTime
                }
            }
        }

        fun stopTracking() {
            handler.removeCallbacksAndMessages(null)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
