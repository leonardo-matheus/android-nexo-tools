package br.com.nexotools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.nexotools.data.*
import br.com.nexotools.domain.*
import br.com.nexotools.automation.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    val preferences:PreferencesStore,
    val orientation:OrientationSource,
    val audio:AudioSource,
    val wifi:WifiSource,
    val nearby:NearbyWifiSource,
    val nativeSpeed:SpeedTestSource,
    val mobile:MobileSource,
    val ping:PingSource,
    val networkProbes:NetworkProbeSource,
    val ir:IrTransmitter,
    val automation:AutomationRepository,
    private val notices:MacroNotifications,
    val external:ExternalAppAutomation,
    private val dao:HistoryDao,
) : ViewModel() {
    val prefs=preferences.state.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val history=dao.observe().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val macros=automation.macros.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val schedules=automation.schedules.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val _navigation=MutableStateFlow<String?>(null)
    val navigation=_navigation.asStateFlow()
    val macroProgress=MacroSession.progress.asStateFlow()
    private var macroJob:Job?=null
    private var externalRun=false
    private val _message=MutableStateFlow<String?>(null)
    val message=_message.asStateFlow()
    fun setMessage(value:String) { _message.value=value }
    fun clearMessage() { _message.value=null }
    fun clearNavigation(){_navigation.value=null}
    fun openTool(route:String){if(route in MacroRules.toolRoutes)_navigation.value=route}
    fun runScheduledMacro(id:Long,token:String)=act {
        if(MacroSession.isRunning()){setMessage("Já há uma macro em execução");return@act}
        automation.consume(id,token)?.let{runMacro(it)}?:setMessage("Esta tarefa foi cancelada, usada ou expirou")
    }
    fun runMacro(macro:MacroEntity) {
        if(MacroSession.isRunning()){setMessage("Já há uma macro em execução");return}
        macroJob=viewModelScope.launch {
            val current=currentCoroutineContext()[Job]?:return@launch
            if(!MacroSession.register(current)){setMessage("Já há uma macro em execução");return@launch}
            try {
                val steps=MacroCodec.decode(macro.stepsJson)
                MacroRules.validate(macro.name,steps)
                externalRun=MacroRules.hasExternal(steps)
                if(externalRun){
                    external.requireReady()
                    check(notices.active(macro.name)) { "Permita notificações para poder interromper a macro" }
                    external.beginUserSession()
                }
                steps.forEachIndexed { index,step->
                    if(externalRun){external.requireReady();check(notices.allowed()){ "Notificações não estão disponíveis para interromper a macro" }}
                    MacroSession.progress.value="Passo ${index+1} de ${steps.size}"
                    when(step.action) {
                        MacroAction.IR->ir.send(step.argument,step.detail)
                        MacroAction.WAIT->delay(step.seconds*1000L)
                        MacroAction.OPEN_TOOL->if(externalRun)external.openNexoTool(step.argument)else openTool(step.argument)
                        MacroAction.NOTIFY->if(!notices.post(macro.id,macro.name,step.argument.ifBlank{"Lembrete da tarefa"}))setMessage(step.argument.ifBlank{"Lembrete da tarefa"})
                        else->external.run(step)
                    }
                }
                setMessage("Macro concluída; comandos IR não confirmam o estado do receptor")
            } catch(e:CancellationException){throw e}
            catch(_:Exception){setMessage("Macro interrompida. Confira o hardware e os passos.")}
            finally {if(externalRun)external.endUserSession();externalRun=false;notices.clearActive();MacroSession.clear(current)}
        }
    }
    fun cancelMacro(){MacroSession.cancel();macroJob?.cancel()}
    fun onBackground(){if(!externalRun)macroJob?.cancel()}
    fun act(block:suspend()->Unit) = viewModelScope.launch {
        try { block() } catch(e:CancellationException) { throw e }
        catch(_:Exception){ setMessage("Não foi possível concluir. Tente novamente.") }
    }
    fun save(tool:String,summary:String,samples:String="") = act {
        dao.insert(HistoryEntry(timestamp=System.currentTimeMillis(),tool=tool,summary=summary,samples=samples))
        setMessage("Medição salva no aparelho")
    }
    fun clearHistory()=act { dao.clear(); setMessage("Histórico apagado") }
}
