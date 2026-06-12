package com.example.smartlocation.repository

import android.content.Context
import androidx.room.*
import java.util.*

@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val altitude: Double,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(location: LocationEntity)

    @Query("SELECT * FROM locations ORDER BY timestamp DESC")
    suspend fun getAllLocations(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getLocationsInRange(startTime: Long, endTime: Long): List<LocationEntity>

    @Query("DELETE FROM locations")
    suspend fun clearAll()
}

@Database(entities = [LocationEntity::class], version = 1)
abstract class LocationDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
}

class LocationRepository(private val context: Context) {
    private val database = Room.databaseBuilder(
        context,
        LocationDatabase::class.java,
        "location_database"
    ).build()

    private val dao = database.locationDao()

    suspend fun saveLocation(location: android.location.Location) {
        val entity = LocationEntity(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = location.speed,
            altitude = location.altitude
        )
        dao.insert(entity)
    }

    suspend fun getAllLocations(): List<LocationEntity> {
        return dao.getAllLocations()
    }

    suspend fun getTrackInRange(startTime: Long, endTime: Long): List<LocationEntity> {
        return dao.getLocationsInRange(startTime, endTime)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}
