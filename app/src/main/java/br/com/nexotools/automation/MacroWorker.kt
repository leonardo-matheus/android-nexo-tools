package br.com.nexotools.automation

import android.content.Context
import androidx.work.*
import br.com.nexotools.data.*
import br.com.nexotools.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

class MacroWorker(
    context:Context,parameters:WorkerParameters,
    private val dao:AutomationDao,
    private val ir:IrTransmitter,
    private val notices:MacroNotifications,
    private val repository:AutomationRepository,
):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val schedule=dao.schedule(inputData.getLong("schedule_id",-1))?:return Result.success()
        if(inputData.getLong("run_at",-1)!=schedule.runAt)return Result.success()
        if(System.currentTimeMillis()<schedule.runAt)return Result.retry()
        if(!schedule.enabled || schedule.status !in setOf("PENDING","RUNNING"))return Result.success()
        val macro=dao.macro(schedule.macroId)?:return Result.success()
        var status="FAILED"
        try {
            if(schedule.status=="RUNNING") {
                status="INTERRUPTED"
                notices.post(macro.id,macro.name,"Execução interrompida; sinais não são repetidos automaticamente")
            } else {
                val steps=MacroCodec.decode(macro.stepsJson)
                MacroRules.validate(macro.name,steps)
                if(dao.markRunning(schedule.id)!=1)return Result.success()
                if(MacroRules.needsForeground(steps)) {
                    repository.defer(schedule.copy(status="RUNNING"),macro)
                    status="WAITING_TAP"
                } else {
                    for(step in steps)when(step.action) {
                        MacroAction.IR->ir.send(step.argument,step.detail)
                        MacroAction.WAIT->delay(step.seconds*1000L)
                        MacroAction.NOTIFY->check(notices.post(macro.id,macro.name,step.argument.ifBlank{"Lembrete da sua tarefa"}))
                        MacroAction.OPEN_TOOL->error("Abertura de tela exige interação")
                        else->error("Ações em outros apps exigem interação")
                    }
                    status="COMPLETED"
                    notices.post(macro.id,macro.name,if(steps.any{it.action==MacroAction.IR})"Sinais IR enviados sem confirmação do receptor"else"Passos concluídos")
                }
            }
        } catch(e:CancellationException){throw e}
        catch(_:Exception){notices.post(macro.id,macro.name,"Não foi possível concluir. Confira o hardware e a macro.")}
        val nextTime=if(schedule.repeatDaily)MacroRules.nextDailyRun(schedule.runAt,System.currentTimeMillis())else schedule.runAt
        dao.finishSchedule(schedule.id,nextTime,schedule.repeatDaily,if(schedule.repeatDaily)"PENDING"else status)
        val next=dao.schedule(schedule.id)
        if(next?.enabled==true)repository.enqueue(next)
        return Result.success()
    }
}
@Singleton class NexoWorkerFactory @Inject constructor(
    private val dao:AutomationDao,private val ir:IrTransmitter,private val notices:MacroNotifications,private val repository:AutomationRepository,
):WorkerFactory() {
    override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker?=
        if(workerClassName==MacroWorker::class.java.name)MacroWorker(appContext,workerParameters,dao,ir,notices,repository)else null
}
