package com.sistema.conexionasqlserver

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log

object LocationManagerHelper {

    private lateinit var sqliteHelper: SQLiteHelper

    // Inicializa el SQLiteHelper con el contexto de la aplicación
    fun initialize(context: Context) {
        sqliteHelper = SQLiteHelper(context)
        Log.d("LocationManagerHelper", "SQLiteHelper inicializado.")
    }


    // Nuevo método para obtener la instancia de SQLiteHelper
    fun getSQLiteHelper(): SQLiteHelper {
        return sqliteHelper
    }

    // Verifica si el GPS está encendido
    fun isGPSOn(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        Log.d("LocationManagerHelper", "El GPS está ${if (gpsOn) "encendido" else "apagado"}.")
        return gpsOn
    }

    // Verifica si hay conexión a Internet
    fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Guarda la ubicación localmente en SQLite
    suspend fun saveLocationLocally(context: Context, userId: Int, location: Location, note: String) {
        val code = when {
            !isGPSOn(context) -> 5
            !isInternetAvailable(context) -> 6
            else -> 0
        }

        withContext(Dispatchers.IO) {
            val coordinates = "${location.latitude},${location.longitude}"
            val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val date = dateFormatter.format(Date()) // Genera la fecha

            Log.d("LocationManagerHelper", "Guardando ubicación localmente:  $coordinates, Fecha: $date, Nota: $note, Código: $code")

            try {
                sqliteHelper.insertLocation(userId, coordinates, date, note, code)
                Log.d("LocationManagerHelper", "Ubicación guardada exitosamente.")
            } catch (e: Exception) {
                Log.e("LocationManagerHelper", "Error al guardar la ubicación: ${e.message}")
            }
        }
    }

    // Función que envía la ubicación al servidor
    suspend fun enviarAlServidor(location: LocationData): Boolean {
        return try {
            // Aquí deberías implementar la lógica de envío al servidor.
            // Ejemplo: Usa Retrofit o HttpURLConnection para hacer la llamada HTTP

            // Simulación de éxito del envío
            Log.d("LocationManagerHelper", "Simulación de envío exitoso de la ubicación: ${location.coordinates}")
            true
        } catch (e: Exception) {
            Log.e("LocationManagerHelper", "Error al enviar la ubicación: ${e.message}")
            false
        }
    }

    // Función que envía las ubicaciones pendientes almacenadas en SQLite
    suspend fun sendPendingLocationsToServer(userId: Int) {
        // Obtén ubicaciones pendientes de la base de datos SQLite
        val pendingLocations = sqliteHelper.getPendingLocations()

        for (location in pendingLocations) {
            val success = enviarAlServidor(location) // Aquí envías la ubicación sin modificar

            if (success) {
                // Si el envío es exitoso, eliminar la ubicación de SQLite
                try {
                    sqliteHelper.deleteLocation(location)
                    Log.d("LocationManagerHelper", "Ubicación enviada y eliminada con éxito de SQLite.")
                } catch (e: Exception) {
                    Log.e("LocationManagerHelper", "Error al eliminar la ubicación: ${e.message}")
                }
            } else {
                Log.e("LocationManagerHelper", "Error al enviar la ubicación al servidor.")
            }
        }
    }

    // Verifica si GPS e Internet están disponibles, y si es así, envía ubicaciones pendientes
    suspend fun checkAndSendLocations(context: Context, userId: Int) {
        if (!isGPSOn(context) || !isInternetAvailable(context)) {
            Log.d("LocationManagerHelper", "No se pueden enviar ubicaciones pendientes. GPS o Internet no están disponibles.")
            return
        }
        Log.d("LocationManagerHelper", "Intentando enviar ubicaciones pendientes.")
        sendPendingLocationsToServer(userId)
    }
}
