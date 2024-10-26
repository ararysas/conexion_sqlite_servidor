package com.sistema.conexionasqlserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Verificar la conectividad de la red
        if (isNetworkAvailable(context) && LocationManagerHelper.isGPSOn(context)) {
            Log.d("NetworkChangeReceiver", "Conexión a Internet disponible y GPS activado.")

            CoroutineScope(Dispatchers.IO).launch {
                // Llamar al método para enviar ubicaciones pendientes
                DatabaseHelper.checkAndSendPendingLocations(context)
            }
        } else {
            Log.d("NetworkChangeReceiver", "No hay conexión a Internet o GPS desactivado.")
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

}
