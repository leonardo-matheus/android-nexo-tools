package br.com.nexotools.ui

import android.Manifest
import android.os.Build
import android.view.Surface
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.nexotools.MainViewModel
import br.com.nexotools.data.PreferenceState
import br.com.nexotools.domain.*
import kotlinx.coroutines.flow.catch
import java.util.Locale

private val pt=Locale.forLanguageTag("pt-BR")
fun number(value:Double?,places:Int=1,unit:String=""):String=if(value==null||!value.isFinite())"Não disponível" else String.format(pt,"%.${places}f",value)+unit
private fun angle(value:Double?):String=AngleDisplay.format(value)

@Composable fun LevelScreen(vm:MainViewModel,prefs:PreferenceState,inclinometer:Boolean,onCalibration:()->Unit){
    val activity=LocalActivity.current
    val configuration=LocalConfiguration.current
    @Suppress("DEPRECATION") val rotation=remember(configuration,activity){
        if(Build.VERSION.SDK_INT>=30) activity?.display?.rotation?:Surface.ROTATION_0
        else activity?.windowManager?.defaultDisplay?.rotation?:Surface.ROTATION_0
    }
    var error by remember { mutableStateOf<String?>(null) }
    var frozen by remember { mutableStateOf<LevelReading?>(null) }
    var showLevelInfo by remember { mutableStateOf(false) }
    val flow=remember(prefs.calibration,rotation){vm.orientation.readings(prefs.calibration,rotation).catch{error="Sensor indisponível. Confira se este aparelho possui acelerômetro."}}
    val live by flow.collectAsStateWithLifecycle(initialValue=null)
    val reading=frozen?:live
    val attitude=reading?.let { LevelMath.attitude(it.gravity,prefs.calibration.reference,rotation) }
    val haptic=LocalHapticFeedback.current
    Panel {
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.md),verticalArrangement=Arrangement.spacedBy(Space.sm)){Text(if(frozen!=null)"Congelado" else if(reading?.stable==true)"Estável" else "Estabilizando",color=MaterialTheme.colorScheme.primary);Text(if(prefs.calibration.reference!=null)"Zero relativo" else "Horizontal",style=MaterialTheme.typography.labelSmall)}
        if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)
        else if(reading==null){LinearProgressIndicator(Modifier.fillMaxWidth());Hint("Aguardando dados do sensor")}
        if(inclinometer){
            Horizon(attitude?.pitchDegrees?:0.0,attitude?.rollDegrees?:0.0)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.lg)){
                Column(Modifier.weight(1f)){SectionLabel("X · Pitch");InstrumentReading(angle(attitude?.pitchDegrees))}
                Column(Modifier.weight(1f)){SectionLabel("Y · Roll");InstrumentReading(angle(attitude?.rollDegrees))}
            }
        }else{
            SectionLabel("Nível X · horizontal")
            Bubble(reading?.xDegrees?:0.0,modifier=Modifier.fillMaxWidth().height(Dimensions.vialThickness))
            InstrumentReading(angle(reading?.xDegrees))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val dialSize=minOf(Dimensions.planeMax,(maxWidth-Space.xl)/2)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.xl),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){SectionLabel("Nível Y");Bubble(reading?.yDegrees?:0.0,vertical=true,modifier=Modifier.width(Dimensions.vialThickness).height(Dimensions.vialLength));InstrumentReading(angle(reading?.yDegrees))}
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){SectionLabel("Plano");Bubble(reading?.xDegrees?:0.0,reading?.yDegrees?:0.0,plane=true,modifier=Modifier.size(dialSize));InstrumentReading(AngleDisplay.format(reading?.planeDegrees,signed=false))}
            }
            }
        }
        Hint("Apoie o aparelho em uma superfície firme.")
    }
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.sm),verticalArrangement=Arrangement.spacedBy(Space.sm)){
        Button(onClick={reading?.let{vm.act{vm.preferences.setCalibration(prefs.calibration.copy(reference=it.gravity))};haptic.performHapticFeedback(HapticFeedbackType.LongPress)}},enabled=reading?.stable==true&&frozen==null,modifier=Modifier.heightIn(min=Space.touch)){Text("Zerar")}
        OutlinedButton(onClick={frozen=if(frozen==null)live else null},enabled=live!=null,modifier=Modifier.heightIn(min=Space.touch)){Text(if(frozen!=null)"Retomar"else"Congelar")}
    }
    Panel {
        Metric("Fonte",reading?.source?:"Não disponível")
        TextButton(onClick={showLevelInfo=!showLevelInfo}){Text(if(showLevelInfo)"Ocultar detalhes" else "Sobre a leitura")}
        if(showLevelInfo){
            Metric("Dispersão estimada",AngleDisplay.dispersion(reading?.estimatedError))
            Hint("A dispersão indica estabilidade recente, sem comprovar precisão absoluta.")
            Hint(if(inclinometer)"Pitch: rotação em torno de X. Roll: rotação em torno de Y. O yaw não é medido."else"X aponta à direita e Y ao topo da tela. Plano é a inclinação da superfície em relação à horizontal (0–90°).")
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(Space.sm),verticalArrangement=Arrangement.spacedBy(Space.sm)){
            TextButton(onClick=onCalibration){Text("Calibrar sensores")}
            TextButton(onClick={vm.act{vm.preferences.setCalibration(prefs.calibration.copy(reference=null))}}){Text("Limpar zero")}
        }
        OutlinedButton(onClick={reading?.let{
            if(inclinometer) attitude?.let { a->vm.save("Inclinômetro","Pitch ${angle(a.pitchDegrees)} · Roll ${angle(a.rollDegrees)}") }
            else vm.save("Nível","X ${angle(it.xDegrees)} · Y ${angle(it.yDegrees)} · Plano ${AngleDisplay.format(it.planeDegrees,signed=false)}")
        }},enabled=reading!=null){Text("Salvar leitura")}
    }
}
@Composable fun WifiScreen(vm:MainViewModel,prefs:PreferenceState,details:Boolean,onDetails:()->Unit){
    var revision by remember{mutableIntStateOf(0)}
    PermissionPanel(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),"Para mostrar a rede Wi-Fi, o Android exige localização precisa e localização ativa. Nenhuma localização é salva."){revision++}
    val flow=remember(revision){vm.wifi.readings().catch{vm.setMessage("Não foi possível consultar a conexão Wi-Fi")}}
    val reading by flow.collectAsStateWithLifecycle(initialValue=WifiReading())
    val score=reading.rssi?.let(WifiMath::quality)
    val displayScore by animateIntAsState(score?:0,tween(450),label="qualidade do sinal")
    fun hidden(value:String?)=if(value==null)"Não disponível"else if(prefs.hideSensitive)"Oculto"else value
    if(!details)Panel {
        Hint("QUALIDADE DO SINAL")
        WifiDial(if(score==null)null else displayScore)
        InstrumentReading(score?.toString()?:"—",modifier=Modifier.align(Alignment.CenterHorizontally))
        Text(score?.let(WifiMath::label)?:reading.status,modifier=Modifier.align(Alignment.CenterHorizontally),color=MaterialTheme.colorScheme.primary)
        Metric("RSSI",reading.rssi?.let{"$it dBm"}?:"Não disponível")
        Metric("Rede",hidden(reading.ssid))
        Metric("Banda",reading.frequencyMhz?.let(WifiMath::band)?:"Não disponível")
        Metric("Enlace",reading.linkMbps?.let{"$it Mbps"}?:"Não disponível")
        Hint("Qualidade do sinal, não velocidade de internet.")
        Button(onClick=onDetails){Text("Detalhes da conexão")}
    }
    Panel {
        SectionLabel("Distância estimada")
        val distance=reading.rssi?.let{WifiMath.distance(it,prefs.distanceReferenceDbm,prefs.distanceExponent)}
        Text(number(distance,1," m"),style=MaterialTheme.typography.headlineMedium)
        Hint(if(distance==null)"Estimativa indisponível sem sinal válido."else"Baixa confiabilidade: paredes, antenas e interferência alteram a estimativa.")
    }
    if(details){
        Panel {
            SectionLabel("Conexão atual");Hint(reading.status)
            Hint("Pontuação linear do RSSI: −90 dBm = 0; −30 dBm = 100.")
            Metric("SSID",hidden(reading.ssid));Metric("BSSID",hidden(reading.bssid))
            Metric("Frequência",reading.frequencyMhz?.let{"$it MHz"}?:"Não disponível")
            Metric("Canal",reading.frequencyMhz?.let(WifiMath::channel)?.toString()?:"Não disponível")
            Metric("Padrão",reading.standard?:"Não disponível")
            Metric("IP local",hidden(reading.ip));Metric("Gateway",hidden(reading.gateway));Metric("DNS",hidden(reading.dns.takeIf{it.isNotEmpty()}?.joinToString()))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("Ocultar dados da conexão",Modifier.weight(1f));Switch(prefs.hideSensitive,{vm.act{vm.preferences.setHideSensitive(it)}})}
        }
        var ref by remember(prefs.distanceReferenceDbm){mutableStateOf(prefs.distanceReferenceDbm.toString())}
        var exponent by remember(prefs.distanceExponent){mutableStateOf(prefs.distanceExponent.toString())}
        Panel {
            SectionLabel("Modelo de distância")
            OutlinedTextField(ref,{ref=it},label={Text("RSSI a 1 metro (dBm)")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(exponent,{exponent=it},label={Text("Expoente de perda (1 a 6)")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Hint("d = 10^((RSSI₁ₘ − RSSI)/(10 × n)). Referência padrão −40 dBm; n = 2. Ajuste usando uma referência conhecida.")
            Button(onClick={val r=ref.replace(',','.').toDoubleOrNull();val n=exponent.replace(',','.').toDoubleOrNull();if(r==null||n==null||r !in -100.0..-10.0||n !in 1.0..6.0)vm.setMessage("Informe RSSI entre −100 e −10 dBm e expoente entre 1 e 6")else vm.act{vm.preferences.setDistanceModel(r,n);vm.setMessage("Modelo salvo")}}){Text("Salvar modelo")}
        }
    }
    OutlinedButton(onClick={reading.rssi?.let{vm.save("Wi-Fi","RSSI $it dBm · qualidade ${WifiMath.quality(it)}/100")}},enabled=reading.rssi!=null){Text("Salvar sinal")}
}
