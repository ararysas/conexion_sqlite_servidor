package com.sistema.conexionasqlserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.d("BootReceiver", "El dispositivo se ha reiniciado. Iniciando el servicio de ubicación.")

                val serviceIntent = Intent(context, LocationService::class.java)
                serviceIntent.putExtra("USER_ID", context.getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE)
                    .getInt("USER_ID", -1))

                // Usar startService para compatibilidad con API 24
                context.startService(serviceIntent)

                // Guardar mensaje de reinicio en la base de datos
                val userId = context.getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE)
                    .getInt("USER_ID", -1)
                if (userId != -1) {
                    saveSystemEventToDatabase(context, userId, "Dipositivo Iniciado", 4)
                }
            }
            Intent.ACTION_SHUTDOWN -> {
                Log.d("BootReceiver", "El dispositivo se está apagando.")

                val userId = context.getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE)
                    .getInt("USER_ID", -1)

                // Enviar alerta sobre la última ubicación antes de apagarse
                if (userId != -1) {
                    getLastLocation(context, userId)
                }
            }
        }
    }

    private fun getLastLocation(context: Context, userId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            val coordenadas = DatabaseHelper.getLastCoordinates(userId)
            coordenadas?.let {
                val latLong = it.split(",")
                if (latLong.size == 2) {
                    val location = Location("").apply {
                        latitude = latLong[0].toDouble()
                        longitude = latLong[1].toDouble()
                    }

                    // Enviar la ubicación y código 3 (apagado) al servicio
                    val locationServiceIntent = Intent(context, LocationService::class.java)
                    locationServiceIntent.putExtra("USER_ID", userId)
                    locationServiceIntent.putExtra("LOCATION_LAT", location.latitude)
                    locationServiceIntent.putExtra("LOCATION_LON", location.longitude)
                    locationServiceIntent.putExtra("NOTA", "Apagado del dispositivo")
                    locationServiceIntent.putExtra("CODIGO", 3)

                    context.startService(locationServiceIntent)

                    // Guardar mensaje de apagado en la base de datos
                    saveSystemEventToDatabase(context, userId, "Apagado del dispositivo", 3)
                } else {
                    Log.e("BootReceiver", "Coordenadas no válidas: $it")
                }
            } ?: run {
                Log.e("BootReceiver", "No se pudo obtener coordenadas para el usuario $userId.")
            }
        }
    }


    private fun saveSystemEventToDatabase(context: Context, userId: Int, nota: String, codigo: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            DatabaseHelper.saveLocation(userId, 0.0, 0.0, nota, codigo)
        }

    }
}