package br.com.nexotools.automation

import android.content.Context
import androidx.work.*
import br.com.nexotools.data.*
import br.com.nexotools.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit
import java.util.UUID
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton class AutomationRepository @Inject constructor(
    @ApplicationContext private val context:Context,
    private val dao:AutomationDao,
    private val notices:MacroNotifications,
) {
    val macros=dao.macros()
    val schedules=dao.schedules()
    suspend fun save(name:String,steps:List<MacroStep>):Long {
        MacroRules.validate(name,steps)
        return dao.insertMacro(MacroEntity(name=name.trim(),stepsJson=MacroCodec.encode(steps),updatedAt=System.currentTimeMillis()))
    }
    suspend fun get(id:Long)=dao.macro(id)
    suspend fun consume(id:Long,token:String):MacroEntity? {
        val macro=dao.consumePending(id,token,System.currentTimeMillis())
        if(macro!=null)notices.clearDeferred(id)
        return macro
    }
    suspend fun defer(schedule:ScheduleEntity,macro:MacroEntity):ScheduleEntity {
        val next=schedule.copy(pendingToken=UUID.randomUUID().toString(),pendingAt=System.currentTimeMillis())
        dao.updateSchedule(next)
        notices.clearDeferred(schedule.id)
        check(notices.deferred(schedule.id,next.pendingToken!!,macro.name)){ "Notificações indisponíveis" }
        return next
    }
    suspend fun reconcile(){
        dao.pendingSchedules().forEach{enqueue(it)}
        dao.pendingTaps().forEach{task->
            if(task.enabled && task.runAt<=System.currentTimeMillis())return@forEach
            val created=task.pendingAt?:0
            if(System.currentTimeMillis()-created in 0..86_400_000L){
                dao.macro(task.macroId)?.let{notices.deferred(task.id,task.pendingToken!!,it.name)}
            }else{notices.clearDeferred(task.id);dao.updateSchedule(task.copy(pendingToken=null,pendingAt=null))}
        }
    }
    suspend fun delete(id:Long) {
        dao.schedulesFor(id).forEach{cancel(it.id)}
        dao.deleteMacro(id)
    }
    suspend fun schedule(id:Long,runAt:Long,daily:Boolean):Long {
        require(notices.allowed()){ "Permita notificações para agendar tarefas" }
        require(dao.macro(id)!=null && runAt>System.currentTimeMillis()){ "Escolha uma macro e horário futuro" }
        val record=ScheduleEntity(macroId=id,runAt=runAt,repeatDaily=daily)
        val scheduleId=dao.insertSchedule(record)
        try { enqueue(record.copy(id=scheduleId)) } catch(e:Exception) {dao.deleteSchedule(scheduleId);throw e}
        return scheduleId
    }
    suspend fun cancel(id:Long) {
        awaitOperation(WorkManager.getInstance(context).cancelAllWorkByTag("nexo-schedule-$id"))
        notices.clearDeferred(id)
        dao.deleteSchedule(id)
    }
    suspend fun enqueue(schedule:ScheduleEntity) {
        val request=OneTimeWorkRequestBuilder<MacroWorker>()
            .setInputData(workDataOf("schedule_id" to schedule.id,"run_at" to schedule.runAt))
            .setInitialDelay((schedule.runAt-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
            .addTag("nexo-schedule-${schedule.id}").build()
        awaitOperation(WorkManager.getInstance(context).enqueueUniqueWork("nexo-schedule-${schedule.id}-${schedule.runAt}",ExistingWorkPolicy.KEEP,request))
    }
    private suspend fun awaitOperation(operation:Operation)=suspendCancellableCoroutine<Unit>{continuation->
        operation.result.addListener({
            try{operation.result.get();if(continuation.isActive)continuation.resume(Unit)}
            catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
        },ContextCompat.getMainExecutor(context))
    }
}
