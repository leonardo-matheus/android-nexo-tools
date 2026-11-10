# NEXO Tools

Aplicativo Android nativo em português brasileiro: nível X/Y e plano, inclinômetro, ruído, Wi-Fi, rede móvel, latência TCP, Sonar Wi-Fi, velocidade nativa e acesso ao FAST.com/Speedtest, controles IR, player com ganho, câmera noturna, tarefas/macros, histórico e calibração. Tema claro, escuro ou do sistema; preferências e calibrações persistidas localmente. Versão 1.3.0.

## Abrir e compilar

Abra esta pasta no Android Studio compatível com AGP 9.5. Instale **Android SDK Platform 37.0** e **Build Tools 36.0.0**. Use JDK 17 ou superior compatível com Gradle 9.6. Configure o SDK pelo Android Studio ou por `ANDROID_HOME`. O projeto não inclui caminhos locais nem credenciais.

No Windows, dentro desta pasta:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleRelease :app:bundleRelease
```

Em Linux/macOS: `chmod +x gradlew` e use `./gradlew` com as mesmas tarefas. APK de desenvolvimento: `app/build/outputs/apk/debug/app-debug.apk`. APK release não assinado: `app/build/outputs/apk/release/app-release-unsigned.apk`. AAB: `app/build/outputs/bundle/release/app-release.aab`.

O APK debug pode ser instalado por `adb install -r app-debug.apk`. A assinatura debug é gerada pelo Android, para desenvolvimento. O release precisa da assinatura do proprietário: Android Studio → Build → Generate Signed App Bundle or APK. Guarde a chave fora do projeto; não envie senhas ao código. AAB não se instala diretamente em um aparelho.

Se o Windows retornar `Unable to establish loopback connection` com um caminho temporário longo, configure uma pasta temporária curta e existente via `JAVA_TOOL_OPTIONS`, usando `-Djdk.net.unixdomain.tmpdir=<pasta>` e `-Djava.io.tmpdir=<pasta>`. O problema ocorre no canal local da JVM, antes da compilação.

## Arquitetura

Um módulo `app`, sem servidor ou conta. `ui/` contém destinos Compose e componentes; `MainViewModel` coordena preferências, histórico e ações. `domain/` contém modelos, interfaces, filtros e cálculos testáveis. `sensors/` adapta SensorManager e AudioRecord; `network/` adapta conectividade, modem e TCP. `data/` contém Room e DataStore; Hilt injeta implementações substituíveis. Fluxos são frios: coleta começa na ferramenta ativa e termina ao cancelar a coleção. Leituras são coletadas somente com o ciclo de vida iniciado; áudio, calibração e TCP também interrompem ao sair para segundo plano.

Navegação: abertura → onboarding persistido → Início / Rede / Ferramentas / Utilidades. Início reúne favoritos persistidos; as estrelas nos cards fixam e removem atalhos. Histórico e Ajustes ficam na barra superior; controles IR usam uma única tela com abas LED e LG. Ferramentas abrem destinos próprios; Wi-Fi possui detalhes; calibração e privacidade também partem de Ajustes. O código usa um catálogo de destinos simples, sem dependência de navegação adicional.

Stack verificada em repositórios/documentação oficiais: AGP 9.5.1, Gradle 9.6.0, Kotlin integrado ao AGP com plugin Compose 2.4.21, Compose BOM 2026.09.00, Activity 1.13.0, Lifecycle 2.11.0, Hilt 2.60.1, KSP 2.3.13, Room 2.8.5 e DataStore 1.3.0. `minSdk=26`, `targetSdk=36`, `compileSdk=37`. Recursos de API superior são condicionados à versão do aparelho. O wrapper verifica SHA-256 da distribuição.

Contorno de ferramenta: o detector Lint `BidiSpoofing` entrou em loop no UAST do AGP 9.5 ao percorrer comentários Kotlin, confirmado por duas capturas de threads. Somente esse detector foi desativado; `BidirectionalSourceTest` verifica os controles invisíveis equivalentes diretamente nos fontes. Os demais checks Lint permanecem habilitados. Reavaliar o contorno em futura atualização do AGP.

## Medições e limites

- **Nível:** coordenadas naturais do Android são remapeadas para a rotação da tela. Com gravidade normalizada `g`, X = `asin(gx)`, Y = `asin(gy)` e plano = `acos(abs(gz))`, em graus. Plano mede 0–90°; uma superfície horizontal com tela para baixo também vale zero. Não há yaw. O zero relativo usa a rotação mínima que alinha a gravidade de referência a +Z.
- **Fusão:** acelerômetro e giroscópio com timestamps reais, integração angular e correção gravitacional; sem gyro, acelerômetro filtrado; sem acelerômetro, gravidade do Android quando disponível. A janela de estabilidade mede dispersão e proximidade de 1g. “Repetibilidade estimada” descreve dispersão recente, não exatidão comprovada. Vibração e acelerações sustentadas comprometem a leitura.
- **Calibração:** seis faces estimam viés e escala diagonal por eixo; não corrigem desalinhamento entre eixos. Cada posição precisa de 40 amostras estáveis consecutivas e alinhamento físico válido. As instruções de bordas se referem à orientação natural do dispositivo. Em tablets de orientação natural paisagem, confira os eixos. O viés gyro é a média em repouso. Restauração remove também zero e referência sonora.
- **Inclinômetro:** compartilha a fonte do nível. Convenção Android: pitch em torno de X = `atan2(−gy, √(gx²+gz²))`, roll em torno de Y = `atan2(gx, gz)`. Esses ângulos diferem das inclinações isoladas X/Y em posições compostas. O horizonte é uma representação pela gravidade, não uma referência inercial de navegação. Congelar retém a leitura exibida, identificada na tela.
- **Ruído:** PCM16 mono 48 kHz, janelas de 100 ms; RMS/32768 e `20 log10(RMS)` em dBFS. Silêncio digital tem piso −120 dBFS. Média energética em vez de média aritmética de dB. dB SPL é estimado apenas após informar referência acústica conhecida; a calibração exige uma janela estável. Sem ponderação A. Usa áudio não processado quando suportado; fallback pode sofrer processamento do fabricante. Não é sonômetro certificado. Áudio bruto não é armazenado.
- **Wi-Fi:** pontuação `round(clamp((RSSI + 90) / 60 × 100))`; excelente ≥80, bom ≥60, regular ≥35, ruim <35. É força de sinal, sem inferência de throughput. Enlace negociado não é velocidade da internet. Canal inclui exceções 2484 MHz e 5935 MHz. SSID/BSSID e endereços podem ser ocultados pelo Android ou pela preferência do usuário.
- **Distância Wi-Fi:** `d = 10^((RSSI₁ₘ − RSSI)/(10n))`. Padrões: RSSI a 1 m = −40 dBm, n = 2. São parâmetros configuráveis, não calibração universal. Baixa confiabilidade; paredes, potência, antenas e interferência podem produzir erros grandes. Sem RSSI válido, não há estimativa.
- **Rede móvel:** separa métricas LTE de NR SS. Valores sentinela ficam indisponíveis. SINR LTE é convertido de décimos de dB somente nas versões antigas que expõem essa unidade. Indicador NSA descreve disponibilidade com âncora LTE, sem garantir sessão NR ativa. Não presume SA. SIM e dados de célula dependem de permissões, serviço de localização, modem e cache do Android.
- **Latência:** TCP connect, 10 conexões, intervalo de 1 s e timeout de 3 s. DNS é resolvido antes e excluído do tempo de conexão. Jitter = média da diferença absoluta de latências de sucessos adjacentes; não atravessa falhas. “Falhas de conexão” não é perda ICMP. Cancelamento fecha o socket; Android anterior ao 29 não oferece cancelamento público da resolução DNS, cujo worker pode aguardar o sistema após o timeout.
- **Velocidade:** teste nativo com endpoints públicos documentados [Cloudflare](https://github.com/cloudflare/speedtest), OkHttp 5.3.2, três transferências paralelas, aquecimento, latência HTTP sem/com carga, jitter e download/upload medidos. Limites de payload por perfil: 24 MB, 192 MiB (até 202 MB) e 768 MiB (até 806 MB), além de overhead de protocolo. Alvo de 12 s por direção e limite total de 55 s; amostras curtas são identificadas. Upload final usa bytes confirmados e janela que inclui a resposta HTTP. Cancelar/sair encerra chamadas; resultado parcial não pode ser salvo como concluído. O app não envia dados para `__results`. FAST/Ookla continuam disponíveis no navegador para comparação; infraestruturas e métodos diferentes podem gerar valores diferentes. Os repositórios FAST indicados usam APIs Netflix não documentadas; não foram incorporados. O SDK oficial Ookla é uma integração empresarial contratada.
- **Sonar Wi-Fi:** consulta anúncios das redes próximas, RSSI, frequência/canal, WPS, WPA/WPA2/WPA3/OWE e protocolos antigos. O radar usa RSSI; ângulo é ilustrativo. Não comprova direção, distância ou vulnerabilidade. Varredura respeita localização precisa, Wi-Fi/localização ativos, cache do Android e limites de consultas; coleta termina ao sair. Fabricante usa registro público IEEE MA-L local (`oui-router.csv`), somente prefixos/organizações, sem endereços. O registrante pode ser fabricante do rádio/OEM; MAC local/registro ausente aparece não identificado. Nenhum identificador de rede próxima vai para histórico/servidores.

Histórico guarda apenas leituras efetivamente obtidas e salvas pelo usuário. Identificadores de rede, SIM e destinos digitados não são gravados. Sessões de medição em andamento são interrompidas em recriação da tela; calibrações e histórico já salvos permanecem.

## Design e publicação

Layouts editáveis: [Figma NEXO Tools](https://www.figma.com/design/172NaHtxcCPdj6mVkU6Sf1?node-id=32-2). Especificação: [DESIGN.md](DESIGN.md). Ícones vetoriais Lucide e fonte Inter incluídos com licenças em `licenses/`.

Antes de publicar: assinar o AAB com a chave do proprietário, validar em aparelhos físicos e nas versões Android suportadas, conferir precisão com referências externas, hospedar a política de privacidade, preencher Data Safety/Classificação e preparar imagens/ficha na Play Console. Validar permissões revogadas, sensor ausente, 4G/5G de diferentes fabricantes, orientação, TalkBack e fonte ampliada. Compilação e testes não equivalem a certificação metrológica nem aprovação na Play.

## Extensões da versão 1.1

- **LED 24 teclas IR:** grade física 4×6, brilho/energia, 16 cores e quatro efeitos. Perfis `LED24` (00FF/Sylvania) e `LED24_F7` (00F7/NEC estendido) selecionáveis. **TV LG não smart:** NEC clássico, energia/entrada, volume/canal/mudo, navegação/menu/voltar/sair e dígitos. ConsumerIrManager transmite a 38 kHz somente com emissor e faixa compatível. Não há confirmação do receptor nem compatibilidade universal; teste o perfil no equipamento. Painel manual usa endereço/comando NEC em formato numérico LSB. Fontes de comandos CC0 e conversões em [IR_NOTES.md](IR_NOTES.md).
- **Volume +:** arquivo escolhido pelo seletor Android, MediaPlayer próprio e LoudnessEnhancer da sessão (sempre ID>0). Ganho alvo 0–12 dB, aproximadamente 100–398% de amplitude teórica; não representa dB SPL nem garantia de volume físico. Não amplia globalmente outros apps nem altera limites de segurança do Android. Ganho alto pode distorcer. Respeita foco de áudio, remoção de fones e pausa em segundo plano. Sem microfone/acesso amplo ao armazenamento.
- **Câmera noturna:** CameraX 1.6.2, análise de luminância Y com stride/crop/rotação/espelhamento, até 640×480 e 10 fps. Filtro gamma e verde/amarelo realçam luz já disponível; não fornecem visão infravermelha nem detalhes em escuridão total. Boost de baixa luz do hardware somente quando suportado; não combina com lanterna. Exposição usa limites reais da câmera. Captura salva somente a prévia processada em PNG no destino escolhido, sem áudio. Captura/lanterna são liberadas ao sair/segundo plano.
- **Tarefas e macros:** listas determinísticas de até 24 passos, definidas pelo usuário: IR, espera, abertura de ferramenta, lembrete, abrir app, toque/deslize e Voltar/Início. Cada espera aceita 1–30 s, total até 90 s. Coordenadas externas são percentuais da tela, incluindo barras; teste em mesma orientação/layout. Não há gravação de tela, leitura de textos/senhas, clipboard ou planejamento autônomo. Gestos exigem app de destino em foco e tela ativa/desbloqueada; gesto iniciado pode terminar em até 3 s após cancelar, próximos passos são interrompidos.
- **Agendamento:** WorkManager 2.11.2 local, uma vez ou diário, horário aproximado sujeito a Doze/bateria. Macros que abrem telas ou usam outros apps aguardam toque na notificação. IR/lembretes podem executar sem abrir tela. Requer notificações liberadas, inclusive canal Tarefas e macros. Após forçar parada, abra o app para reconciliar tarefas. Execuções interrompidas não repetem automaticamente sinais IR; notificações de tarefas canceladas/consumidas/expiradas não disparam macros. Room migra de versão 1 para 2 preservando histórico.

Para ações externas: na tela Tarefas e macros, aceite a explicação de uso, abra os ajustes Android e ative **NEXO Tools · macros**. A ativação do serviço não substitui consentimento dentro do app. Permita notificações para manter o controle Parar durante execução. Android pode exigir liberar configurações restritas para APK instalado fora da Play; isso é uma escolha manual do usuário. O serviço é `isAccessibilityTool=false` e usa eventos somente para identificar o pacote da janela ativa em memória, sem conteúdo. Publicação exige declaração e aprovação da [política de Acessibilidade Google Play](https://support.google.com/googleplay/android-developer/answer/10964491).

## Validação executada — versão 1.0

Em 9/11/2026, as tarefas `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleRelease` e `bundleRelease` concluíram com `BUILD SUCCESSFUL`. **21 testes passaram**; Lint: **zero erros**, com avisos de compatibilidade/estilo e versões disponíveis, mantendo o contorno de `BidiSpoofing` explicado acima. APK verificado com `zipalign -c -P 16 4` (alinhamento de 16 KB aprovado). AAB release gerado sem assinatura do proprietário.

Emulador Android 17: abertura, Home, nível com dados de sensores emulados, zeragem, congelamento, tema escuro, permissões negadas, início/pausa do microfone e gravação de medição no Room exercitados. Celular conectado com Android 16: APK instalado, abertura confirmada e processo ativo sem erro fatal. Esses checks não validam exatidão metrológica, modems de todos os fabricantes, TalkBack ou todas as versões Android; isso permanece para ensaio físico e homologação.

Referências oficiais: [AGP 9.5 e compatibilidade](https://developer.android.com/build/releases/agp-9-5-0-release-notes), [Kotlin integrado](https://developer.android.com/build/migrate-to-built-in-kotlin), [Compose BOM](https://developer.android.com/develop/ui/compose/bom/bom-mapping), [sensores](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview), [WifiInfo](https://developer.android.com/reference/android/net/wifi/WifiInfo), [permissões Wi-Fi](https://developer.android.com/develop/connectivity/wifi/wifi-permissions), [TelephonyDisplayInfo](https://developer.android.com/reference/android/telephony/TelephonyDisplayInfo), [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord), [DnsResolver](https://developer.android.com/reference/android/net/DnsResolver).

## Validação da versão 1.2

O APK debug foi compilado. A suíte contém 55 testes; a nova execução completa pelo Gradle e os testes no celular ficaram para a próxima sessão, conforme combinado. Os testes do motor de velocidade passaram isoladamente; os casos de upload lento e cancelamento receberam sincronização por eventos para evitar dependência do desempenho do computador. Não há confirmação física dos novos layouts e das redes próximas nesta versão.


## Ferramentas locais e captura de tráfego

- **Controle principal:** escolha LED ou LG na configuração IR. A escolha persiste; alternar a aba não muda o principal. Macros com destinos LED/LG explícitos mantêm o destino escolhido.
- **Desinstalador:** busca apps do usuário neste perfil, inclusive sem launcher, e abre a confirmação do Android. Não remove apps de sistema nem o próprio NEXO. O inventário usa `QUERY_ALL_PACKAGES` localmente; a remoção usa `REQUEST_DELETE_PACKAGES` e a tela do sistema.
- **Portas TCP:** um host e até 256 portas, com oito conexões simultâneas e timeout de um segundo. Recusa e ausência de resposta são diferentes. A captura do próprio NEXO precisa estar parada para evitar falsos positivos causados pela terminação TCP da VPN local.
- **DNS e TLS:** resolução A/AAAA pelo sistema e inspeção de um handshake TLS com validação padrão de confiança, validade e hostname. Endereços e certificados ficam em memória.
- **Monitor de tráfego:** VPN inteiramente local, sem root ou outro app. HEV SOCKS5 Tunnel 2.19.3 e um proxy local autenticado encaminham TCP/UDP. O filtro de app é aplicado antes da captura; protocolos, IPs, portas e conexões podem ser filtrados na tela. Apenas 3.000 pacotes, de até 2.048 bytes cada, permanecem em memória; a exportação PCAP respeita o filtro escolhido. Descartes do coletor são contados.

A captura representa a interface virtual, sem modo monitor 802.11. HTTPS, QUIC e DNS criptografado não são descriptografados. ICMP e outros protocolos IP não encaminhados podem falhar durante a sessão. A VPN termina ao parar, revogar sua autorização ou perder a rede usada. No Android 8/9, a identificação por app exige selecionar o app antes de iniciar. WPS brute force e Pixie Dust não estão disponíveis no cenário sem root e sem hardware com modo monitor/injeção.

O motor nativo é compilado com NDK **28.2.13676358**, para armeabi-v7a, arm64-v8a, x86 e x86_64, com alinhamento de 16 KB. Os fontes estão em `app/src/main/cpp/hev`; licenças e avisos são incluídos em `assets/capture-licenses.txt`. A integração aplica apenas hooks PCAP/JNI, cancelamento por sessão e ajustes de build sobre o código vendorizado.
