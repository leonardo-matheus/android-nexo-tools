package br.com.nexotools.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import br.com.nexotools.domain.MobileSource
import br.com.nexotools.domain.PingSource
import br.com.nexotools.domain.WifiSource
import br.com.nexotools.network.AndroidMobileSource
import br.com.nexotools.network.AndroidWifiSource
import br.com.nexotools.network.TcpPingSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Only measurements; never persist SSID, BSSID, IP, SIM identifiers or a user-entered host. */
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val tool: String,
    val summary: String,
    val samples: String = "",
)
@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 200") fun observe(): Flow<List<HistoryEntry>>
    @Insert suspend fun insert(entry: HistoryEntry)
    @Query("DELETE FROM history") suspend fun clear()
}
@Database(entities = [HistoryEntry::class,MacroEntity::class,ScheduleEntity::class], version = 2, exportSchema = true)
abstract class NexoDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun automationDao(): AutomationDao
}
val MIGRATION_1_2=object:Migration(1,2) {
    override fun migrate(db:SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS macros (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, stepsJson TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS schedules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, macroId INTEGER NOT NULL, runAt INTEGER NOT NULL, repeatDaily INTEGER NOT NULL, enabled INTEGER NOT NULL, status TEXT NOT NULL, pendingToken TEXT, pendingAt INTEGER, FOREIGN KEY(macroId) REFERENCES macros(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_schedules_macroId ON schedules(macroId)")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NexoDataModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): NexoDatabase =
        Room.databaseBuilder(context, NexoDatabase::class.java, "nexo_history.db").addMigrations(MIGRATION_1_2).build()
    @Provides fun historyDao(database: NexoDatabase): HistoryDao = database.historyDao()
    @Provides fun automationDao(database:NexoDatabase):AutomationDao=database.automationDao()
}
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkSourceModule {
    @Binds abstract fun wifi(source: AndroidWifiSource): WifiSource
    @Binds abstract fun mobile(source: AndroidMobileSource): MobileSource
    @Binds abstract fun ping(source: TcpPingSource): PingSource
}
