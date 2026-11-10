package br.com.nexotools.domain

import java.time.Instant
import java.time.ZoneId

enum class MacroAction { IR, WAIT, OPEN_TOOL, NOTIFY, OPEN_APP, TAP, SWIPE, BACK, HOME }
data class MacroStep(val action:MacroAction,val argument:String="",val detail:String="",val seconds:Int=0)

object MacroRules {
    val toolRoutes=setOf("level","angle","audio","wifi","mobile","ping","speed","sonar","calibration","remotes","remoteLed","remoteLg","volumeBoost","nightCamera","uninstaller","packets","ports","dnsTls")
    val irProfiles=setOf("LED24","LED24_F7","LG_TV")
    fun validate(name:String,steps:List<MacroStep>) {
        require(name.trim().length in 1..64) { "Informe um nome de até 64 caracteres" }
        require(steps.size in 1..24) { "A macro precisa de 1 a 24 passos" }
        require(steps.filter{it.action==MacroAction.WAIT}.sumOf{it.seconds.toLong()}<=90) { "Limite total de espera: 90 segundos" }
        steps.forEach { step->when(step.action) {
            MacroAction.IR->require(IrProfiles.byId[step.argument]?.commandsById?.containsKey(step.detail)==true) { "Selecione um comando IR válido" }
            MacroAction.WAIT->require(step.seconds in 1..30) { "Cada espera deve ter de 1 a 30 segundos" }
            MacroAction.OPEN_TOOL->require(step.argument in toolRoutes) { "Selecione uma ferramenta do NEXO" }
            MacroAction.NOTIFY->require(step.argument.length<=120) { "Aviso deve ter até 120 caracteres" }
            MacroAction.OPEN_APP,MacroAction.TAP,MacroAction.SWIPE,MacroAction.BACK,MacroAction.HOME->{
                require(MacroGestureMath.validPackageName(step.argument) && step.argument!="br.com.nexotools") { "Selecione um aplicativo externo" }
                if(step.action==MacroAction.TAP)MacroGestureMath.parseTap(step.detail)
                if(step.action==MacroAction.SWIPE)MacroGestureMath.parseSwipe(step.detail)
            }
        } }
    }
    val externalActions=setOf(MacroAction.OPEN_APP,MacroAction.TAP,MacroAction.SWIPE,MacroAction.BACK,MacroAction.HOME)
    fun hasExternal(steps:List<MacroStep>)=steps.any{it.action in externalActions}
    fun needsForeground(steps:List<MacroStep>)=hasExternal(steps)||steps.any { it.action==MacroAction.OPEN_TOOL }
    fun nextDailyRun(previous:Long,now:Long,zone:ZoneId=ZoneId.systemDefault()):Long {
        val reference=Instant.ofEpochMilli(previous).atZone(zone)
        val current=Instant.ofEpochMilli(now).atZone(zone)
        var next=current.toLocalDate().atTime(reference.toLocalTime()).atZone(zone)
        if(!next.toInstant().isAfter(current.toInstant())) next=next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }
}
