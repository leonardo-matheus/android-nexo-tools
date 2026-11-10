package br.com.nexotools.ui

import android.Manifest
import android.os.Build
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.nexotools.MainViewModel
import br.com.nexotools.R
import br.com.nexotools.data.*
import br.com.nexotools.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val macroRouteLabels=mapOf("level" to "Nível", "angle" to "Inclinômetro", "audio" to "Ruído", "wifi" to "Wi-Fi", "mobile" to "Rede móvel", "ping" to "Latência", "speed" to "Velocidade", "sonar" to "Sonar Wi-Fi", "remotes" to "Controles IR", "calibration" to "Calibração", "remoteLed" to "LED 24", "remoteLg" to "TV LG", "volumeBoost" to "Volume +", "nightCamera" to "Câmera noturna", "uninstaller" to "Desinstalador", "packets" to "Monitor de tráfego", "ports" to "Portas TCP", "dnsTls" to "DNS e TLS")
private val actionLabels=mapOf(MacroAction.IR to "Comando IR",MacroAction.WAIT to "Espera",MacroAction.OPEN_TOOL to "Abrir ferramenta",MacroAction.NOTIFY to "Lembrete",MacroAction.OPEN_APP to "Abrir aplicativo",MacroAction.TAP to "Toque em aplicativo",MacroAction.SWIPE to "Deslize em aplicativo",MacroAction.BACK to "Voltar no aplicativo",MacroAction.HOME to "Ir à tela inicial")
private fun stepLabel(step:MacroStep,apps:List<Pair<String,String>>):String=when(step.action) {
    MacroAction.IR->"${IrProfiles.byId[step.argument]?.title?:step.argument}: ${IrProfiles.byId[step.argument]?.commandsById?.get(step.detail)?.label?:step.detail}"
    MacroAction.WAIT->"Esperar ${step.seconds} segundos"
    MacroAction.OPEN_TOOL->"Abrir ${macroRouteLabels[step.argument]}"
    MacroAction.NOTIFY->"Aviso: ${step.argument.ifBlank{"Lembrete da tarefa"}}"
    else->"${actionLabels.getValue(step.action)} · ${apps.firstOrNull{it.first==step.argument}?.second?:step.argument}${step.detail.takeIf{it.isNotBlank()}?.let{" · $it"}?:""}"
}
@Composable private fun MacroChoice(label:String,selected:String,choices:List<Pair<String,String>>,onSelect:(String)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    Box {
        OutlinedButton({expanded=true},Modifier.fillMaxWidth().heightIn(min=Space.touch)){Text("$label: ${choices.firstOrNull{it.first==selected}?.second?:"Escolher"}",maxLines=2,overflow=TextOverflow.Ellipsis)}
        DropdownMenu(expanded,{expanded=false}){choices.forEach{(id,title)->DropdownMenuItem(text={Text(title)},onClick={onSelect(id);expanded=false})}}
    }
}
@Composable fun AutomationScreen(vm:MainViewModel) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var disclosure by remember{mutableStateOf(false)}
    var serviceEnabled by remember{mutableStateOf(vm.external.serviceEnabled())}
    var appChoices by remember{mutableStateOf(emptyList<Pair<String,String>>())}
    var app by remember{mutableStateOf("")}
    var coordinates by remember{mutableStateOf("50,50")}
    DisposableEffect(owner){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)serviceEnabled=vm.external.serviceEnabled()}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(owner){owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(isActive){serviceEnabled=vm.external.serviceEnabled();delay(1000)}}}
    LaunchedEffect(Unit){appChoices=vm.external.listApps().map{it.packageName to it.label};app=appChoices.firstOrNull()?.first?:""}
    val macros by vm.macros.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val progress by vm.macroProgress.collectAsStateWithLifecycle()
    var name by remember{mutableStateOf("Nova macro")}
    val steps=remember{mutableStateListOf<MacroStep>()}
    var action by remember{mutableStateOf(MacroAction.NOTIFY)}
    var profile by remember{mutableStateOf("LED24")}
    var command by remember{mutableStateOf("ON")}
    var wait by remember{mutableStateOf("2")}
    var route by remember{mutableStateOf("level")}
    var note by remember{mutableStateOf("Lembrete da tarefa")}
    var selectedMacro by remember{mutableStateOf<Long?>(null)}
    val initial=remember{LocalDateTime.now().plusMinutes(5)}
    var date by remember{mutableStateOf(initial.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")))}
    var time by remember{mutableStateOf(initial.format(DateTimeFormatter.ofPattern("HH:mm")))}
    var daily by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf<MacroEntity?>(null)}
    var expandedMacro by remember{mutableStateOf<Long?>(null)}
    val displayDate=remember{DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm",Locale.forLanguageTag("pt-BR"))}
    if(Build.VERSION.SDK_INT>=33)PermissionPanel(arrayOf(Manifest.permission.POST_NOTIFICATIONS),"Permita notificações para lembretes e execução interativa. Macros internas manuais continuam disponíveis.")
    Panel {
        Glyph(R.drawable.ic_calendar_clock)
        SectionLabel("Rotinas do NEXO")
        Hint("Combine ações do aparelho. O Android pode adiar agendamentos para economizar bateria.")
    }
    Panel {
        SectionLabel("Ações em outros aplicativos")
        Hint("Executa só gestos definidos por você no app escolhido, sem ler textos, senhas ou imagens.")
        Metric("Consentimento",if(prefs?.externalAutomationConsent==true)"Concedido"else"Não concedido")
        Metric("Serviço no Android",if(serviceEnabled)"Ativado"else"Desativado")
        Button({disclosure=true},modifier=Modifier.fillMaxWidth().heightIn(min=Space.touch)){Text("Configurar acessibilidade")}
        if(prefs?.externalAutomationConsent==true)TextButton({vm.cancelMacro();vm.act{vm.preferences.setExternalAutomationConsent(false)}}){Text("Revogar consentimento")}
        Hint("A tela deve estar desbloqueada. Use Parar na notificação para cancelar os próximos passos.")
    }
    Panel {
        SectionLabel("Criar macro")
        OutlinedTextField(name,{name=it.take(64)},label={Text("Nome da rotina")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        MacroChoice("Passo",action.name,MacroAction.entries.map{it.name to actionLabels.getValue(it)}){action=MacroAction.valueOf(it);coordinates=if(action==MacroAction.SWIPE)"50,80,50,20,500"else"50,50"}
        when(action) {
            MacroAction.IR->{
                MacroChoice("Perfil",profile,IrProfiles.byId.values.map{it.id to it.title}){profile=it;command=IrProfiles.byId.getValue(it).commands.first().id}
                MacroChoice("Comando",command,IrProfiles.byId.getValue(profile).commands.map{it.id to it.label}){command=it}
                Hint("Exige emissor IR compatível; o estado do receptor não é confirmado.")
            }
            MacroAction.WAIT->OutlinedTextField(wait,{wait=it},label={Text("Espera de 1 a 30 segundos")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            MacroAction.OPEN_TOOL->MacroChoice("Ferramenta",route,macroRouteLabels.entries.map{it.key to it.value}){route=it}
            MacroAction.NOTIFY->OutlinedTextField(note,{note=it.take(120)},label={Text("Texto do lembrete")},modifier=Modifier.fillMaxWidth())
            else->{
                MacroChoice("Aplicativo",app,appChoices){app=it}
                if(action==MacroAction.TAP)OutlinedTextField(coordinates,{coordinates=it},label={Text("Posição X%,Y%")},placeholder={Text("50,50")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                if(action==MacroAction.SWIPE)OutlinedTextField(coordinates,{coordinates=it},label={Text("Deslize em % e duração")},supportingText={Text("X1,Y1,X2,Y2,milissegundos")},modifier=Modifier.fillMaxWidth())
                Hint("Abra o app antes dos gestos. Use o mesmo tamanho, orientação e layout; a macro para se outro app aparecer.")
            }
        }
        OutlinedButton(onClick={
            val step=when(action){
                MacroAction.IR->MacroStep(action,profile,command)
                MacroAction.WAIT->MacroStep(action,seconds=wait.toIntOrNull()?:0)
                MacroAction.OPEN_TOOL->MacroStep(action,route)
                MacroAction.NOTIFY->MacroStep(action,note)
                else->MacroStep(action,app,if(action in setOf(MacroAction.TAP,MacroAction.SWIPE))coordinates else "")
            }
            runCatching{MacroRules.validate(name,steps.toList()+step)}.onSuccess{steps.add(step)}.onFailure{vm.setMessage(it.message?:"Passo inválido")}
        }){Text("Adicionar passo")}
        steps.forEachIndexed{index,step->
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.sm),verticalArrangement=Arrangement.spacedBy(Space.sm)){
                Text("${index+1}. ${stepLabel(step,appChoices)}",Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodySmall)
                IconButton(onClick={val previous=steps[index-1];steps[index-1]=step;steps[index]=previous},enabled=index>0){Glyph(R.drawable.ic_arrow_up,"Mover passo para cima")}
                TextButton({steps.removeAt(index)}){Text("Remover")}
            }
        }
        Button(onClick={vm.act{val id=vm.automation.save(name,steps.toList());selectedMacro=id;steps.clear();name="Nova macro";vm.setMessage("Macro salva no aparelho")}},enabled=steps.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=Space.touch)){Text("Salvar macro")}
        Hint("Até 24 passos e 90 segundos de espera no total. A interrupção cancela os próximos passos; sinais já enviados não são desfeitos.")
    }
    Panel {
        SectionLabel("Macros salvas")
        if(macros.isEmpty())Hint("Crie sua primeira rotina acima.")
        macros.forEach{macro->
            Text(macro.name,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
            val savedSteps=runCatching{MacroCodec.decode(macro.stepsJson)}.getOrNull()
            Hint(savedSteps?.let{"${it.size} passos"}?:"Passos indisponíveis")
            if(expandedMacro==macro.id)savedSteps?.forEachIndexed{index,step->Text("${index+1}. ${stepLabel(step,appChoices)}",style=MaterialTheme.typography.bodySmall)}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(Space.sm),verticalArrangement=Arrangement.spacedBy(Space.sm)){
                OutlinedButton({vm.runMacro(macro)},enabled=progress==null){Text("Executar")}
                TextButton({selectedMacro=macro.id}){Text("Agendar")}
                TextButton({expandedMacro=if(expandedMacro==macro.id)null else macro.id}){Text(if(expandedMacro==macro.id)"Ocultar passos"else"Ver passos")}
                TextButton({deleting=macro}){Text("Excluir")}
            }
        }
    }
    Panel {
        SectionLabel("Agendar tarefa")
        if(macros.isNotEmpty())MacroChoice("Macro",selectedMacro?.toString()?:"",macros.map{it.id.toString() to it.name}){selectedMacro=it.toLong()}
        OutlinedTextField(date,{date=it},label={Text("Data")},placeholder={Text("DD/MM/AAAA")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(time,{time=it},label={Text("Hora")},placeholder={Text("HH:mm")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Repetir diariamente",Modifier.weight(1f));Switch(daily,{daily=it})}
        Button(onClick={vm.act{
            val day=LocalDate.parse(date,DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT))
            val hour=LocalTime.parse(time,DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT))
            val id=selectedMacro?:error("Selecione uma macro")
            vm.automation.schedule(id,day.atTime(hour).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),daily)
            vm.setMessage("Tarefa agendada; horário sujeito às restrições do Android")
        }},enabled=selectedMacro!=null,modifier=Modifier.fillMaxWidth().heightIn(min=Space.touch)){Text("Agendar")}
        Hint("Macros interativas aguardam toque no lembrete. Após forçar parada, abra o NEXO para retomar agendamentos.")
    }
    Panel {
        SectionLabel("Tarefas agendadas")
        if(schedules.isEmpty())Hint("Nenhuma tarefa agendada.")
        schedules.forEach{task->
            Text(macros.firstOrNull{it.id==task.macroId}?.name?:"Macro",style=MaterialTheme.typography.titleSmall)
            Hint("${Instant.ofEpochMilli(task.runAt).atZone(ZoneId.systemDefault()).format(displayDate)} · ${if(task.repeatDaily)"Diária"else"Uma vez"}")
            Hint(when(task.status){"PENDING"->"Aguardando horário aproximado";"RUNNING"->"Executando";"WAITING_TAP"->"Aguardando toque no lembrete";"STARTED_BY_USER"->"Execução iniciada por toque";"COMPLETED"->"Passos concluídos";"INTERRUPTED"->"Interrompida; comandos não repetidos";else->"Falha na execução"})
            TextButton({vm.act{vm.automation.cancel(task.id);vm.setMessage("Tarefa removida")}}){Text("Cancelar tarefa")}
        }
    }
    deleting?.let{macro->AlertDialog(onDismissRequest={deleting=null},title={Text("Excluir macro?")},text={Text("Remove a macro e cancela seus agendamentos no aparelho.")},confirmButton={TextButton({deleting=null;vm.act{vm.automation.delete(macro.id)}}){Text("Excluir")}},dismissButton={TextButton({deleting=null}){Text("Cancelar")}})}
    if(disclosure)AlertDialog(onDismissRequest={disclosure=false},title={Text("Autorizar macros interativas?")},text={Text("O NEXO usa a API de Acessibilidade para realizar gestos e navegação nos apps escolhidos por você. Observa somente o nome do pacote da janela ativa para impedir toques fora do destino. Esses dados ficam em memória, sem coleta de textos, senhas, imagens, histórico de uso ou envio a servidores. As ações são determinísticas, configuradas por você; não há decisões autônomas. Você pode revogar a autorização aqui e desativar o serviço nos ajustes do Android.",Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton({disclosure=false;vm.act{vm.preferences.setExternalAutomationConsent(true);context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}){Text("Autorizo · abrir ajustes")}},dismissButton={TextButton({disclosure=false}){Text("Cancelar")}})
}
