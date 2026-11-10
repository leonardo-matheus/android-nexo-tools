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
private fun angle(value:Double?,places:Int):String=if(value==null)"—" else String.format(pt,"%+.${places}f°",value)

@Composable fun LevelScreen(vm:MainViewModel,prefs:PreferenceState,inclinometer:Boolean,onCalibration:()->Unit){
    val activity=LocalActivity.current
    val configuration=LocalConfiguration.current
    @Suppress("DEPRECATION") val rotation=remember(configuration,activity){
        if(Build.VERSION.SDK_INT>=30) activity?.display?.rotation?:Surface.ROTATION_0
        else activity?.windowManager?.defaultDisplay?.rotation?:Surface.ROTATION_0
    }
    var error by remember { mutableStateOf<String?>(null) }
    var frozen by remember { mutableStateOf<LevelReading?>(null) }
    val flow=remember(prefs.calibration,rotation){vm.orientation.readings(prefs.calibration,rotation).catch{error="Sensor indisponível. Confira se este aparelho possui acelerômetro."}}
    val live by flow.collectAsStateWithLifecycle(initialValue=null)
    val reading=frozen?:live
    val attitude=reading?.let { LevelMath.attitude(it.gravity,prefs.calibration.reference,rotation) }
    val haptic=LocalHapticFeedback.current
    Panel {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(if(frozen!=null)"Leitura congelada" else if(reading?.stable==true)"Leitura estável" else "Aguardando estabilidade",color=MaterialTheme.colorScheme.primary);Text(if(prefs.calibration.reference!=null)"Zero relativo" else "Referência horizontal",style=MaterialTheme.typography.labelSmall)}
        if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)
        else if(reading==null){LinearProgressIndicator(Modifier.fillMaxWidth());Hint("Aguardando dados do sensor")}
        if(inclinometer){
            Horizon(attitude?.pitchDegrees?:0.0,attitude?.rollDegrees?:0.0)
            Text(angle(attitude?.pitchDegrees,prefs.decimals),style=InstrumentStyle,modifier=Modifier.align(Alignment.CenterHorizontally))
            Metric("Pitch · em torno de X",angle(attitude?.pitchDegrees,prefs.decimals));Metric("Roll · em torno de Y",angle(attitude?.rollDegrees,prefs.decimals))
        }else{
            SectionLabel("Nível X · horizontal")
            Bubble(reading?.xDegrees?:0.0,modifier=Modifier.fillMaxWidth().height(Dimensions.vialThickness))
            Text(angle(reading?.xDegrees,prefs.decimals),style=InstrumentStyle)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val dialSize=minOf(Dimensions.planeMax,(maxWidth-Space.xl)/2)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.xl),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){SectionLabel("Nível Y");Bubble(reading?.yDegrees?:0.0,vertical=true,modifier=Modifier.width(Dimensions.vialThickness).height(Dimensions.vialLength));Text(angle(reading?.yDegrees,prefs.decimals),style=MaterialTheme.typography.headlineSmall)}
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){SectionLabel("Plano");Bubble(reading?.xDegrees?:0.0,reading?.yDegrees?:0.0,plane=true,modifier=Modifier.size(dialSize));Text(if(reading==null)"—"else number(reading.planeDegrees,prefs.decimals,"°"),style=MaterialTheme.typography.headlineSmall)}
            }
            }
        }
        Hint(if(inclinometer)"Convenção Android: pitch = atan2(−gy, √(gx²+gz²)); roll = atan2(gx, gz). O yaw não é medido."else"X: direita da tela. Y: topo da tela. Plano: inclinação da superfície em relação à horizontal (0–90°).")
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.sm)){
        Button(onClick={reading?.let{vm.act{vm.preferences.setCalibration(prefs.calibration.copy(reference=it.gravity))};haptic.performHapticFeedback(HapticFeedbackType.LongPress)}},enabled=reading?.stable==true&&frozen==null,modifier=Modifier.weight(1f).heightIn(min=Space.touch)){Text("Zerar")}
        OutlinedButton(onClick={frozen=if(frozen==null)live else null},enabled=live!=null,modifier=Modifier.weight(1f).heightIn(min=Space.touch)){Text(if(frozen!=null)"Retomar"else"Congelar")}
    }
    Panel {
        Metric("Fonte",reading?.source?:"Não disponível")
        Metric("Repetibilidade estimada",number(reading?.estimatedError,2,"°"))
        Hint("A dispersão indica estabilidade recente. Não comprova precisão absoluta. Apoie o aparelho em uma superfície imóvel.")
        Row(horizontalArrangement=Arrangement.spacedBy(Space.sm)){
            TextButton(onClick=onCalibration){Text("Calibrar sensores")}
            TextButton(onClick={vm.act{vm.preferences.setCalibration(prefs.calibration.copy(reference=null))}}){Text("Limpar zero")}
        }
        OutlinedButton(onClick={reading?.let{
            if(inclinometer) attitude?.let { a->vm.save("Inclinômetro","Pitch ${angle(a.pitchDegrees,prefs.decimals)} · Roll ${angle(a.rollDegrees,prefs.decimals)}") }
            else vm.save("Nível","X ${angle(it.xDegrees,prefs.decimals)} · Y ${angle(it.yDegrees,prefs.decimals)} · Plano ${number(it.planeDegrees,prefs.decimals,"°")}")
        }},enabled=reading!=null){Text("Salvar leitura")}
    }
}
@Composable fun WifiScreen(vm:MainViewModel,prefs:PreferenceState,details:Boolean,onDetails:()->Unit){
    var revision by remember{mutableIntStateOf(0)}
    PermissionPanel(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),"O Android exige localização precisa e serviços de localização ativos para revelar o nome e a identificação do Wi-Fi. Nenhuma localização é armazenada."){revision++}
    val flow=remember(revision){vm.wifi.readings().catch{vm.setMessage("Não foi possível consultar a conexão Wi-Fi")}}
    val reading by flow.collectAsStateWithLifecycle(initialValue=WifiReading())
    val score=reading.rssi?.let(WifiMath::quality)
    val displayScore by animateIntAsState(score?:0,tween(450),label="qualidade do sinal")
    fun hidden(value:String?)=if(value==null)"Não disponível"else if(prefs.hideSensitive)"Oculto"else value
    if(!details)Panel {
        Hint("QUALIDADE DO SINAL")
        WifiDial(if(score==null)null else displayScore)
        Text(score?.toString()?:"—",style=InstrumentStyle,modifier=Modifier.align(Alignment.CenterHorizontally))
        Text(score?.let(WifiMath::label)?:reading.status,modifier=Modifier.align(Alignment.CenterHorizontally),color=MaterialTheme.colorScheme.primary)
        Metric("RSSI",reading.rssi?.let{"$it dBm"}?:"Não disponível")
        Metric("Rede",hidden(reading.ssid))
        Metric("Banda",reading.frequencyMhz?.let(WifiMath::band)?:"Não disponível")
        Metric("Velocidade do enlace",reading.linkMbps?.let{"$it Mbps"}?:"Não disponível")
        Hint("Pontuação linear: −90 dBm = 0; −30 dBm = 100. Mede força do sinal, não velocidade de internet.")
        Button(onClick=onDetails){Text("Ver detalhes da conexão")}
    }
    Panel {
        SectionLabel("Distância estimada")
        val distance=reading.rssi?.let{WifiMath.distance(it,prefs.distanceReferenceDbm,prefs.distanceExponent)}
        Text(number(distance,1," m"),style=MaterialTheme.typography.headlineMedium)
        Hint(if(distance==null)"Estimativa indisponível sem RSSI válido."else"Baixa confiabilidade · modelo de perda de percurso. Paredes, antenas, potência e interferência alteram o resultado.")
    }
    if(details){
        Panel {
            SectionLabel("Conexão atual");Hint(reading.status)
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
            OutlinedTextField(ref,{ref=it},label={Text("RSSI de referência a 1 metro (dBm)")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(exponent,{exponent=it},label={Text("Expoente de perda (1 a 6)")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Hint("d = 10^((RSSI₁ₘ − RSSI)/(10 × n)). Referência padrão −40 dBm; n = 2. Ajuste usando uma referência conhecida.")
            Button(onClick={val r=ref.replace(',','.').toDoubleOrNull();val n=exponent.replace(',','.').toDoubleOrNull();if(r==null||n==null||r !in -100.0..-10.0||n !in 1.0..6.0)vm.setMessage("Informe RSSI entre −100 e −10 dBm e expoente entre 1 e 6")else vm.act{vm.preferences.setDistanceModel(r,n);vm.setMessage("Modelo salvo")}}){Text("Salvar modelo")}
        }
    }
    OutlinedButton(onClick={reading.rssi?.let{vm.save("Wi-Fi","RSSI $it dBm · qualidade ${WifiMath.quality(it)}/100")}},enabled=reading.rssi!=null){Text("Salvar medição de sinal")}
}
