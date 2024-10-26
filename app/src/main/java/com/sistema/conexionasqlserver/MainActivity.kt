package com.sistema.conexionasqlserver

import android.Manifest
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.SettingsClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.sql.DriverManager
import java.sql.SQLException

class MainActivity : AppCompatActivity() {

    private lateinit var etNombre: EditText
    private lateinit var etContrasena: EditText
    private lateinit var btnLogin: Button
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var sqliteHelper: SQLiteHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializa el SQLiteHelper
        sqliteHelper = SQLiteHelper(this)

        // Inicializa el LocationManagerHelper
        LocationManagerHelper.initialize(this)

        // Inicializar vistas
        etNombre = findViewById(R.id.nameField)
        etContrasena = findViewById(R.id.passwordField)
        btnLogin = findViewById(R.id.loginButton)

        // Inicializar el launcher para permisos
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                checkLocationSettings()
            } else {
                Toast.makeText(this, "Se requieren permisos de ubicación para esta funcionalidad", Toast.LENGTH_SHORT).show()
            }
        }

        btnLogin.setOnClickListener {
            checkLocationPermissions()
        }
    }

    private fun checkLocationPermissions() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                checkLocationSettings()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun checkLocationSettings() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L
        ).apply {
            setMinUpdateIntervalMillis(5000L)
        }.build()

        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client: SettingsClient = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            validateUserAndStartLocationService()
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this, 1001)
                } catch (sendEx: IntentSender.SendIntentException) {
                    Log.e("LocationSettings", "Error al solicitar la activación del GPS: ${sendEx.message}")
                }
            } else {
                Log.e("LocationSettings", "Error en la configuración de ubicación: ${exception.message}")
            }
        }
    }

    private fun validateUserAndStartLocationService() {
        val nombre = etNombre.text.toString().trim()
        val contrasena = etContrasena.text.toString().trim()
        CoroutineScope(Dispatchers.IO).launch {
            val userId = obtenerIdUsuario(nombre, contrasena)
            withContext(Dispatchers.Main) {
                if (userId != null) {
                    Toast.makeText(this@MainActivity, "Usuario encontrado: $nombre", Toast.LENGTH_SHORT).show()
                    startLocationService(userId)

                    // Iniciar la sincronización después de iniciar sesión
                    syncLocations()
                } else {
                    Toast.makeText(this@MainActivity, "Usuario o contraseña incorrectos", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun obtenerIdUsuario(nombre: String, contrasena: String): Int? {
        var userId: Int? = null
        try {
            Class.forName("net.sourceforge.jtds.jdbc.Driver")
            DriverManager.getConnection(DatabaseConfig.DB_URL, DatabaseConfig.DB_USER, DatabaseConfig.DB_PASSWORD).use { connection ->
                val query = """
                    SELECT f001_ID_usuarios FROM t001_usuarios 
                    WHERE f001_Nombre = ? AND f001_Contraseña = ?
                """
                connection.prepareStatement(query).use { preparedStatement ->
                    preparedStatement.setString(1, nombre)
                    preparedStatement.setString(2, contrasena)
                    preparedStatement.executeQuery().use { resultSet ->
                        if (resultSet.next()) {
                            userId = resultSet.getInt("f001_ID_usuarios")
                            Log.d("DatabaseCheck", "Usuario encontrado: ID = $userId")
                        } else {
                            Log.d("DatabaseCheck", "Usuario no encontrado")
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            Log.e("DatabaseError", "Error SQL: ${e.message}", e)
        } catch (e: ClassNotFoundException) {
            Log.e("DatabaseError", "Error al cargar el driver: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("DatabaseError", "Error inesperado: ${e.message}", e)
        }
        return userId
    }

    private fun startLocationService(userId: Int) {
        // Guardar el ID del usuario en SharedPreferences
        val sharedPreferences = getSharedPreferences("MyAppPrefs", MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putInt("USER_ID", userId)
            apply()
        }

        val intent = Intent(this, LocationService::class.java).apply {
            putExtra("USER_ID", userId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun syncLocations() {
        CoroutineScope(Dispatchers.IO).launch {
            // Obtener las ubicaciones pendientes desde SQLite
            val pendingLocations = sqliteHelper.getPendingLocations()

            // Enviar cada ubicación al servidor
            for (location in pendingLocations) {
                val isSuccess = DatabaseHelper.saveLocation(
                    userId = location.userId,
                    latitude = location.coordinates.split(",")[0].toDouble(),
                    longitude = location.coordinates.split(",")[1].toDouble(),
                    nota = location.note,
                    codigo = location.code
                )

                // Si el envío fue exitoso, eliminar la ubicación de SQLite
                if (isSuccess) {
                    sqliteHelper.deleteLocation(location)
                }
            }
        }
    }
}
