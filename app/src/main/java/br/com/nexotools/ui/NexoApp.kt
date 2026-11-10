package br.com.nexotools.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import br.com.nexotools.MainViewModel
import br.com.nexotools.R

private val titles=mapOf("network" to "Rede", "tools" to "Ferramentas", "utilities" to "Utilidades", "level" to "Nível de bolha", "angle" to "Inclinômetro", "audio" to "Medidor de ruído", "wifi" to "Analisador Wi-Fi", "wifiDetails" to "Detalhes do Wi-Fi", "mobile" to "Rede móvel", "ping" to "Latência TCP", "speed" to "Teste de velocidade", "sonar" to "Sonar Wi-Fi", "calibration" to "Calibração", "privacy" to "Permissões e privacidade", "history" to "Histórico", "settings" to "Ajustes", "remotes" to "Controles infravermelho", "remoteLed" to "Controles infravermelho", "remoteLg" to "Controles infravermelho", "volumeBoost" to "Volume +", "nightCamera" to "Câmera noturna", "macros" to "Tarefas e macros")
private val catalogRoutes=setOf("home","network","tools","utilities")
private data class ToolSpec(val route:String,val title:String,val subtitle:String,val icon:Int,val category:String)
private val toolSpecs=listOf(
    ToolSpec("wifi","Wi-Fi","Sinal e qualidade",R.drawable.ic_wifi,"network"),
    ToolSpec("mobile","Rede móvel","Métricas 4G e 5G",R.drawable.ic_smartphone,"network"),
    ToolSpec("ping","Latência TCP","Conexão e jitter",R.drawable.ic_activity,"network"),
    ToolSpec("speed","Velocidade","Download, upload e latência",R.drawable.ic_gauge,"network"),
    ToolSpec("sonar","Sonar Wi-Fi","Redes próximas e configuração",R.drawable.ic_wifi,"network"),
    ToolSpec("level","Nível de bolha","X, Y e plano",R.drawable.ic_target,"tools"),
    ToolSpec("angle","Inclinômetro","Ângulo e horizonte",R.drawable.ic_ruler,"tools"),
    ToolSpec("audio","Ruído","RMS do microfone",R.drawable.ic_audio_lines,"tools"),
    ToolSpec("calibration","Calibração","Ajuste seus sensores",R.drawable.ic_sliders_horizontal,"tools"),
    ToolSpec("remotes","Controles IR","LED e TV LG",R.drawable.ic_monitor,"utilities"),
    ToolSpec("volumeBoost","Volume +","Ganho no player NEXO",R.drawable.ic_audio_lines,"utilities"),
    ToolSpec("nightCamera","Câmera noturna","Luz e contraste",R.drawable.ic_moon,"utilities"),
    ToolSpec("macros","Tarefas e macros","Rotinas e lembretes",R.drawable.ic_calendar_clock,"utilities"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NexoApp(vm:MainViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val navigation by vm.navigation.collectAsStateWithLifecycle()
    val macroProgress by vm.macroProgress.collectAsStateWithLifecycle()
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner){
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP)vm.onBackground()}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer)}
    }
    var route by rememberSaveable { mutableStateOf("home") }
    var lastCatalog by rememberSaveable { mutableStateOf("home") }
    val snackbar=remember { SnackbarHostState() }
    NexoTheme(prefs?.theme?:"SYSTEM") {
        val preferences=prefs
        if(preferences==null) { SplashScreen(); return@NexoTheme }
        if(!preferences.onboardingDone) { OnboardingScreen{vm.act{vm.preferences.completeOnboarding()}};return@NexoTheme }
        fun navigate(to:String){
            if(to in catalogRoutes)lastCatalog=to
            route=to
        }
        fun back(){
            if(route=="wifiDetails")route="wifi"
            else if(route in catalogRoutes)navigate("home")
            else route=lastCatalog
        }
        LaunchedEffect(message){message?.let{snackbar.showSnackbar(it);vm.clearMessage()}}
        LaunchedEffect(navigation){navigation?.let{navigate(it);vm.clearNavigation()}}
        val root=route in catalogRoutes
        BackHandler(route!="home"){back()}
        Scaffold(
            containerColor=MaterialTheme.colorScheme.background,
            snackbarHost={SnackbarHost(snackbar)},
            topBar={TopAppBar(
                title={if(route=="home") Text("NEXO TOOLS",fontWeight=FontWeight.Bold,letterSpacing=2.sp,style=MaterialTheme.typography.titleMedium)else Text(titles[route]?:"NEXO Tools",style=MaterialTheme.typography.titleLarge)},
                navigationIcon={if(!root)IconButton(onClick={back()}){Glyph(R.drawable.ic_arrow_left,"Voltar")}},
                actions={
                    if(route!="history")IconButton(onClick={navigate("history")}){Glyph(R.drawable.ic_rotate_ccw,"Histórico")}
                    if(route!="settings")IconButton(onClick={navigate("settings")}){Glyph(R.drawable.ic_settings_2,"Ajustes")}
                },
                colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background)
            )},
            bottomBar={if(root) NavigationBar(containerColor=MaterialTheme.colorScheme.surface){
                listOf(Triple("home","Início",R.drawable.ic_star),Triple("network","Rede",R.drawable.ic_wifi),Triple("tools","Ferramentas",R.drawable.ic_wrench),Triple("utilities","Utilidades",R.drawable.ic_sliders_horizontal)).forEach{(key,label,icon)->NavigationBarItem(selected=route==key,onClick={navigate(key)},icon={Glyph(icon)},label={Text(label)})}
            }}
        ){padding->
            Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter){
                key(route) {
                Column(Modifier.widthIn(max=Dimensions.contentMax).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=Space.lg,vertical=Space.md),verticalArrangement=Arrangement.spacedBy(Space.lg)) {
                    macroProgress?.let{progress->Panel{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(progress,Modifier.weight(1f));TextButton({vm.cancelMacro()}){Text("Interromper macro")}}}}
                    val onFavorite:(String,Boolean)->Unit={key,enabled->vm.act{vm.preferences.setFavorite(key,enabled)}}
                    when(route){
                        "home"->HomeScreen(preferences.favorites,onFavorite,::navigate)
                        "network","tools","utilities"->CatalogScreen(route,preferences.favorites,onFavorite,::navigate)
                        "level"->LevelScreen(vm,preferences,false){navigate("calibration")}
                        "angle"->LevelScreen(vm,preferences,true){navigate("calibration")}
                        "wifi"->WifiScreen(vm,preferences,false){navigate("wifiDetails")}
                        "wifiDetails"->WifiScreen(vm,preferences,true){}
                        "sonar"->SonarScreen(vm)
                        "audio"->AudioScreen(vm,preferences)
                        "mobile"->MobileScreen(vm,preferences)
                        "ping"->PingScreen(vm,preferences)
                        "speed"->NativeSpeedScreen(vm,preferences)
                        "calibration"->CalibrationScreen(vm,preferences)
                        "history"->HistoryScreen(vm)
                        "settings"->SettingsScreen(vm,preferences,::navigate)
                        "privacy"->PrivacyScreen()
                        "remotes","remoteLed"->UnifiedRemoteScreen(vm,initialTab="LED")
                        "remoteLg"->UnifiedRemoteScreen(vm,initialTab="LG")
                        "volumeBoost"->VolumeBoostScreen(vm)
                        "nightCamera"->NightVisionScreen(vm)
                        "macros"->AutomationScreen(vm)
                    }
                    Spacer(Modifier.height(Space.md))
                }
                }
            }
        }
    }
}
@Composable private fun SplashScreen(){
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(Space.lg)){Text("NEXO",style=InstrumentStyle,color=MaterialTheme.colorScheme.primary);Text("TOOLS",letterSpacing=6.sp);CircularProgressIndicator(Modifier.size(Dimensions.glyph),strokeWidth=Dimensions.stroke)}}
}
@Composable private fun OnboardingScreen(onComplete:()->Unit){
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){Column(Modifier.widthIn(max=Dimensions.onboardingMax).padding(Space.xl).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Space.xl)){
        Text("NEXO TOOLS",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleLarge,letterSpacing=2.sp)
        Text("Seu próximo trabalho,\nna medida certa.",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
        Panel {Glyph(R.drawable.ic_ruler);SectionLabel("Instrumentos no seu bolso");Hint("Nível, inclinação e ruído com sensores do seu aparelho. Calibre antes de usar e confira a estabilidade.")}
        Panel {Glyph(R.drawable.ic_wifi);SectionLabel("Entenda sua conexão");Hint("Sinal Wi-Fi, rede móvel e latência TCP. Os dados disponíveis dependem do Android e do hardware.")}
        Hint("As permissões são solicitadas na ferramenta que precisa delas. Medições são locais; testes de rede acessam o destino escolhido. O aparelho não substitui instrumentos certificados.")
        Button(onClick=onComplete,modifier=Modifier.fillMaxWidth().heightIn(min=Space.touch)){Text("Começar a usar")}
    }}
}

@Composable private fun HomeScreen(favorites:Set<String>,onFavorite:(String,Boolean)->Unit,onNavigate:(String)->Unit){
    SectionLabel("Favoritos")
    val pinned=toolSpecs.filter{it.route in favorites}
    if(pinned.isEmpty())Hint("Fixe suas ferramentas usando a estrela nas categorias.")
    else ToolGrid(pinned,favorites,onFavorite,onNavigate)
}
@Composable private fun CatalogScreen(category:String,favorites:Set<String>,onFavorite:(String,Boolean)->Unit,onNavigate:(String)->Unit){
    ToolGrid(toolSpecs.filter{it.category==category},favorites,onFavorite,onNavigate)
}
@Composable private fun ToolGrid(tools:List<ToolSpec>,favorites:Set<String>,onFavorite:(String,Boolean)->Unit,onNavigate:(String)->Unit){
    val columns=if(LocalConfiguration.current.screenWidthDp<360 || LocalDensity.current.fontScale>1.3f)1 else 2
    tools.chunked(columns).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.md)){
        row.forEach{tool->
            val pinned=tool.route in favorites
            Card(onClick={onNavigate(tool.route)},modifier=Modifier.weight(1f).heightIn(min=Dimensions.tileMinHeight),shape=androidx.compose.foundation.shape.RoundedCornerShape(Dimensions.panelRadius),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
                Column(Modifier.padding(Space.lg),verticalArrangement=Arrangement.spacedBy(Space.md)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                        Glyph(tool.icon)
                        IconButton(onClick={onFavorite(tool.route,!pinned)},modifier=Modifier.size(Space.touch),colors=IconButtonDefaults.iconButtonColors(contentColor=if(pinned)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)){
                            Glyph(R.drawable.ic_star,if(pinned)"Desafixar "+tool.title else "Fixar "+tool.title)
                        }
                    }
                    Text(tool.title,style=MaterialTheme.typography.titleMedium)
                    Hint(tool.subtitle)
                }
            }
        }
    }}
}

