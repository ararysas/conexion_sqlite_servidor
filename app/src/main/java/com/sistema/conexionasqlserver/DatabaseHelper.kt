package com.sistema.conexionasqlserver

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.DriverManager
import java.sql.SQLException

object DatabaseHelper {

    private const val DB_URL = DatabaseConfig.DB_URL
    private const val DB_USER = DatabaseConfig.DB_USER
    private const val DB_PASSWORD = DatabaseConfig.DB_PASSWORD

    // Método para guardar la ubicación en la base de datos remota
    suspend fun saveLocation(userId: Int, latitude: Double, longitude: Double, nota: String, codigo: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("DatabaseHelper", "Intentando conectar con la base de datos.")
                Class.forName("net.sourceforge.jtds.jdbc.Driver")
                DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD).use { connection ->
                    Log.d("DatabaseHelper", "Conexión establecida.")

                    val query = """
                        INSERT INTO t010_trakim (f010_ID_User, f010_Coordenadas, f010_Fecha, f010_Nota, f010_codigo)
                        VALUES (?, ?, GETDATE(), ?, ?)
                    """
                    connection.prepareStatement(query).use { preparedStatement ->
                        val coordenadas = "$latitude,$longitude"
                        preparedStatement.setInt(1, userId)
                        preparedStatement.setString(2, coordenadas)
                        preparedStatement.setString(3, nota)
                        preparedStatement.setInt(4, codigo)

                        Log.d("DatabaseHelper", "Parámetros de inserción: UserID=$userId, Coordenadas=$coordenadas, Nota='$nota', Código=$codigo")

                        val rowsInserted = preparedStatement.executeUpdate()
                        if (rowsInserted > 0) {
                            Log.d("DatabaseHelper", "Ubicación guardada exitosamente con nota '$nota' y código $codigo.")
                            return@withContext true
                        } else {
                            Log.e("DatabaseHelper", "No se pudo guardar la ubicación. Filas afectadas: $rowsInserted")
                            return@withContext false
                        }
                    }
                }
            } catch (e: SQLException) {
                Log.e("DatabaseError", "Error SQL: ${e.message}", e)
                return@withContext false
            } catch (e: ClassNotFoundException) {
                Log.e("DatabaseError", "Error al cargar el driver: ${e.message}", e)
                return@withContext false
            } catch (e: Exception) {
                Log.e("DatabaseError", "Error inesperado: ${e.message}", e)
                return@withContext false
            }
        }
    }

    // Método para obtener las ubicaciones pendientes desde SQLite
    private suspend fun getPendingLocationsFromSQLite(context: Context): List<LocationData> {
        return SQLiteHelper(context).getPendingLocations()
    }

    // Método para eliminar la ubicación de SQLite
    private suspend fun deleteLocationFromSQLite(context: Context, location: LocationData): Boolean {
        return SQLiteHelper(context).deleteLocation(location)
    }

    // Método para verificar y enviar ubicaciones pendientes
    suspend fun checkAndSendPendingLocations(context: Context) {
        val pendingLocations = getPendingLocationsFromSQLite(context)
        for (location in pendingLocations) {
            val coordinates = location.coordinates.split(",")
            val latitude = coordinates[0].toDouble()
            val longitude = coordinates[1].toDouble()
            val success = saveLocation(location.userId, latitude, longitude, location.note, location.code)
            if (success) {
                Log.d("DatabaseHelper", "Ubicación enviada exitosamente al servidor.")
                deleteLocationFromSQLite(context, location) // Eliminar la ubicación enviada
            } else {
                Log.e("DatabaseHelper", "Fallo al enviar la ubicación al servidor.")
            }
        }
    }

    // Método para obtener la configuración del usuario
    suspend fun getUserSettings(userId: Int): UserSettings? {
        return withContext(Dispatchers.IO) {
            try {
                Class.forName("net.sourceforge.jtds.jdbc.Driver")
                DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD).use { connection ->
                    val query = """
                        SELECT f001_Tiempo_espera, f001_movimiento 
                        FROM t001_usuarios 
                        WHERE f001_ID_usuarios = ?
                    """
                    connection.prepareStatement(query).use { preparedStatement ->
                        preparedStatement.setInt(1, userId)
                        val resultSet = preparedStatement.executeQuery()
                        if (resultSet.next()) {
                            val tiempoEspera = resultSet.getInt("f001_Tiempo_espera")
                            val tiempoMovimiento = resultSet.getInt("f001_movimiento")

                            UserSettings(tiempoEspera, tiempoMovimiento)
                        } else {
                            null
                        }
                    }
                }
            } catch (e: SQLException) {
                Log.e("DatabaseError", "Error SQL: ${e.message}", e)
                null
            } catch (e: ClassNotFoundException) {
                Log.e("DatabaseError", "Error al cargar el driver: ${e.message}", e)
                null
            } catch (e: Exception) {
                Log.e("DatabaseError", "Error inesperado: ${e.message}", e)
                null
            }
        }
    }

    // Método para obtener las últimas coordenadas del usuario
    suspend fun getLastCoordinates(userId: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                Class.forName("net.sourceforge.jtds.jdbc.Driver")
                DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD).use { connection ->
                    val query = """
                        SELECT TOP 1 f010_Coordenadas
                        FROM t010_trakim 
                        WHERE f010_ID_User = ?
                        ORDER BY f010_Fecha DESC
                    """
                    connection.prepareStatement(query).use { preparedStatement ->
                        preparedStatement.setInt(1, userId)
                        val resultSet = preparedStatement.executeQuery()
                        if (resultSet.next()) {
                            resultSet.getString("f010_Coordenadas")
                        } else {
                            null
                        }
                    }
                }
            } catch (e: SQLException) {
                Log.e("DatabaseError", "Error SQL: ${e.message}", e)
                null
            } catch (e: ClassNotFoundException) {
                Log.e("DatabaseError", "Error al cargar el driver: ${e.message}", e)
                null
            } catch (e: Exception) {
                Log.e("DatabaseError", "Error inesperado: ${e.message}", e)
                null
            }
        }
    }
}

// Clase de datos para representar la ubicación
data class LocationInfo(
    val userId: Int,
    val latitude: Double,
    val longitude: Double,
    val nota: String,
    val codigo: Int
)

data class UserSettings(val tiempoEspera: Int, val tiempoMovimiento: Int)
