package br.com.nexotools.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import br.com.nexotools.domain.*
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName="macros")
data class MacroEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val name:String,val stepsJson:String,val updatedAt:Long)
@Entity(tableName="schedules",foreignKeys=[ForeignKey(entity=MacroEntity::class,parentColumns=["id"],childColumns=["macroId"],onDelete=ForeignKey.CASCADE)],indices=[Index("macroId")])
data class ScheduleEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val macroId:Long,val runAt:Long,val repeatDaily:Boolean,val enabled:Boolean=true,val status:String="PENDING",val pendingToken:String?=null,val pendingAt:Long?=null)

@Dao interface AutomationDao {
    @Query("SELECT * FROM macros ORDER BY updatedAt DESC") fun macros():Flow<List<MacroEntity>>
    @Query("SELECT * FROM schedules ORDER BY runAt") fun schedules():Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM macros WHERE id=:id") suspend fun macro(id:Long):MacroEntity?
    @Query("SELECT * FROM schedules WHERE id=:id") suspend fun schedule(id:Long):ScheduleEntity?
    @Query("SELECT * FROM schedules WHERE macroId=:id") suspend fun schedulesFor(id:Long):List<ScheduleEntity>
    @Query("SELECT * FROM schedules WHERE enabled=1 AND status IN ('PENDING','RUNNING')") suspend fun pendingSchedules():List<ScheduleEntity>
    @Query("SELECT * FROM schedules WHERE pendingToken IS NOT NULL") suspend fun pendingTaps():List<ScheduleEntity>
    @Insert suspend fun insertMacro(macro:MacroEntity):Long
    @Insert suspend fun insertSchedule(schedule:ScheduleEntity):Long
    @Update suspend fun updateSchedule(schedule:ScheduleEntity)
    @Query("UPDATE schedules SET status='RUNNING' WHERE id=:id AND enabled=1 AND status='PENDING'") suspend fun markRunning(id:Long):Int
    @Query("UPDATE schedules SET runAt=:runAt,enabled=:enabled,status=CASE WHEN :status='WAITING_TAP' AND pendingToken IS NULL THEN 'STARTED_BY_USER' ELSE :status END WHERE id=:id") suspend fun finishSchedule(id:Long,runAt:Long,enabled:Boolean,status:String)
    @Query("DELETE FROM macros WHERE id=:id") suspend fun deleteMacro(id:Long)
    @Query("DELETE FROM schedules WHERE id=:id") suspend fun deleteSchedule(id:Long)
    @Query("UPDATE schedules SET pendingToken=NULL,pendingAt=NULL,status=CASE WHEN repeatDaily=1 THEN 'PENDING' ELSE 'STARTED_BY_USER' END WHERE id=:id AND pendingToken=:token") suspend fun consumeToken(id:Long,token:String):Int
    @Transaction suspend fun consumePending(id:Long,token:String,now:Long):MacroEntity? {
        val task=schedule(id)?:return null
        val created=task.pendingAt?:return null
        if(task.pendingToken!=token || now<created || now-created>86_400_000L)return null
        val macro=macro(task.macroId)?:return null
        return if(consumeToken(id,token)==1)macro else null
    }
}
object MacroCodec {
    fun encode(steps:List<MacroStep>):String=JSONArray().apply {
        steps.forEach { put(JSONObject().put("action",it.action.name).put("argument",it.argument).put("detail",it.detail).put("seconds",it.seconds)) }
    }.toString()
    fun decode(value:String):List<MacroStep> {
        val array=JSONArray(value)
        require(array.length() in 1..24) { "Macro inválida" }
        return List(array.length()) { index->array.getJSONObject(index).let {
            MacroStep(MacroAction.valueOf(it.getString("action")),it.optString("argument"),it.optString("detail"),it.optInt("seconds"))
        } }
    }
}
