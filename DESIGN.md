# NEXO Tools — especificação Android

Referência preservada: Figma `172NaHtxcCPdj6mVkU6Sf1`, páginas Page 1 e v3 refined. A evolução do produto recebe uma página própria. Layouts com números de exemplo são identificados como dados ilustrativos; nenhum valor é fonte de medição.

## Informação e navegação

Abertura nativa → onboarding uma vez → Início. Navegação inferior com Início, Rede, Ferramentas e Utilidades; Histórico e Ajustes ficam na barra superior. Início reúne favoritos persistidos por estrelas nos cards; sem instrumento em destaque. Controles IR compartilham uma tela com abas LED 24 e TV LG. Uma ferramenta abre em tela própria com retorno ao catálogo. Nível/inclinômetro compartilham sensores; Wi-Fi abre detalhes avançados; calibração e privacidade partem de Ajustes. Histórico guarda exclusivamente leituras reais mediante ação do usuário ou teste concluído. Links externos FAST/Ookla preservam o retorno Android.

## Design system

Semânticos Dark/Light: background #0B2030/#F4F8F6; surface #122D40/#FFFFFF; elevated #1B3C50/#E7F0EC; text #F4F8F6/#16384B; muted #A7BCC7/#506B7A; accent #D9ED92/#184E77; positive #76C893/#26785F; outline #355264/#C7D9D5. Ação principal: #D9ED92/#184E77 com texto #16384B/#FFFFFF. Cores funcionais de erro e atenção complementam a paleta da marca. Primitivos de marca: D9ED92, B5E48C, 99D98C, 76C893, 52B69A, 34A0A4, 168AAD, 1A759F, 1E6091, 184E77.

Tipografia Inter: título 28/34 semibold, seção20/26 semibold, corpo15/22 regular, label14/20 medium, legenda12/18 regular. Leituras Roboto Mono44/52 e28/36 medium; sinal e unidade claros. Espaços4/8/12/16/24/32; cantos12/20/28; ícones Lucide24px stroke2; alvo48dp. Telas390px de referência, margens20–24; catálogo2col, muda1col se texto grande/área estreita. Conteúdo rola sem esconder ações/sistemas; instrumentos ficam simultâneos no nível padrão, conservam semântica acessível com fonte ampliada. Tablets: conteúdo limitado a720dp e instrumentos em grid.

Componentes: botão Primary/Secondary × Default/Pressed/Disabled; tool card Default/Pressed; status Success/Warning/Error/Unavailable; métrica Default/Accent; toggle On/Off/Disabled; campo Empty/Filled/Error; top bar; bottom navigation; painel; ampola Horizontal/Vertical; plano circular; gauge semicircular; gráfico temporal. Estados vazios usam “Não disponível” e explicação. Loading exibe coleta em andamento; permissão negada oferece nova solicitação quando cabível e configurações Android. Sucesso não depende só de cor.

## Instrumentos

Nível: X é projeção inclinada no eixo horizontal da tela, Y no vertical. Ampola X horizontal, Y vertical; Plano mede inclinação total da superfície pela gravidade, nunca yaw. Bolhas limitadas visualmente à ampola; valores matemáticos não sofrem interpolação de UI. Exibir sinal graus, estabilidade, referência absoluta/relativa, fonte sensor e erro estimado quando calculável. Botões Zerar/Restaurar/Congelar e acesso à calibração.

Wi-Fi: arco180° de0–100 e ponteiro; nota `round(clamp((RSSI+90)/60×100))`, categorias ruim<35, regular<60, bom<80, excelente≥80. RSSI separado em dBm. Distância RSSI é estimativa de baixa confiança com referência e expoente configuráveis, nunca medição precisa. SSID/BSSID e endereços ocultos por padrão. Conexão inexistente não mostra gauge ativo. Detalhes exibem banda/frequência/canal/enlace/padrão/IP/gateway/DNS somente quando disponíveis.

Inclinômetro: arco angular, linha horizonte, pitch/roll, zeragem e congelamento. Ruído: dBFS padrão, SPL somente com calibração de referência, atual/min/max/média/gráfico e início/pausa/reinício; captação local sem armazenamento de áudio. Rede móvel: tecnologia, SIM selecionável, operadora, métricas LTE/NR separadas, NSA/SA apenas conforme evidência; modem desconhecido = Não disponível. Ping: destino/porta editáveis, método TCP connect explícito, latência/min/max/média/jitter/falhas de conexão TCP e amostras; timeout e cancelamento visíveis. Velocidade: dois cards FAST.com e Speedtest, explicar navegador externo e abrir endereço oficial; sem histórico inventado.

## Calibração

Zero relativo à superfície; viés gyro com repouso; acelerômetro seis faces com orientações e indicador de amostras estáveis; referência sonora opcional obtida com medidor externo. Persistência local, restauração padrão. Rejeitar amostragem instável e explicar ruído. Não usar alegação de precisão certificada.

## Animação e acessibilidade

Transição tema180–220ms; entrada de ferramenta150ms; pressionamento80ms; ponteiro/bolha120ms apenas visual; atualização numérica direta. Respeitar escala/remoção de animação Android. Feedback tátil leve no zero/congelamento aceito. Contraste texto≥4,5:1 (≥3:1 texto grande), ícone funcional≥3:1. TalkBack lê rótulo, sinal, valor e unidade, evita anunciar contínuo cada amostra. Font scale200% (critério de aceitação, ainda requer teste no dispositivo), alvo48dp, áreas seguras, orientação portrait/landscape. Estados incluem ícone e texto.

## Aceitação por tela

1. Splash: marca NEXO consistente e abertura sem espera artificial.
2. Onboarding: explica instrumentos, limites e privacidade; avançar persiste uma vez.
3. Home: sete ferramentas acessíveis, agrupamento medição/conectividade, retorno consistente.
4. Nível: X/Y/Plano simultâneos, Y vertical, sinal, estabilidade, zero/restaurar e ausência sensor.
5. Inclinômetro: horizonte acompanha leitura real; congelamento identificável; referência correta.
6. Ruído: permissão solicitada ao iniciar; pausa encerra AudioRecord; unidade correta e estatísticas.
7. Wi-Fi: gauge e RSSI reais; indisponibilidade visível; pontuação reprodutível.
8. Wi-Fi detalhes: campos disponíveis, privacidade e estimativa explicitada.
9. Rede móvel: SIM, tecnologia e métricas reais; sem misturar LTE e NR; sem inferir dados ausentes.
10. Ping: destino validado, TCP rotulado, timeout/cancelamento, resultados e gráfico reais.
11. Velocidade: links oficiais externos, serviço identificado, retorno Android, sem scraping.
12. Histórico: exclusivamente medições obtidas; vazio e exclusão local.
13. Calibração: assistentes zero/gyro/seis faces/áudio e reset; persistência.
14. Privacidade: motivo por permissão, revogação Android, ocultação de identidade rede.
15. Ajustes: Sistema/Claro/Escuro persistidos, casas decimais e calibração/privacidade.

Validação física obrigatória antes de alegações de precisão, publicação e ficha Google Play. O design é editável no Figma; o código deve usar as mesmas semânticas.

## Layouts finalizados e verificação de design

Página Produto: https://www.figma.com/design/172NaHtxcCPdj6mVkU6Sf1?node-id=32-2

Home Dark/Light: nodes36:39 /38:86. Nível Dark/Light:38:188 /38:262. Wi-Fi Gauge Dark/Light:40:183 /40:252. Detalhes Wi-Fi Dark/Light:42:219 /42:298. Demais fluxos Dark: Inclinômetro44:245, Ruído44:307, Rede móvel44:365, Ping44:433, Velocidade44:491, Histórico44:545, Calibração44:593, Privacidade44:650, Ajustes44:707, Onboarding44:784, Splash44:836. Estados de coleta/sensor/Wi-Fi/permissão/timeout:50:416.

Verificado: screenshots das19telas, revisão visual do conjunto e inspeção dos fluxos prioritários. Aviso de layout ilustrativo presente em19/19telas.38variáveis de cor possuem sintaxe Android. Componentes reutilizados com variantes e semânticos Dark/Light.88conexões de protótipo configuradas, incluindo links públicos FAST.com/Speedtest. Correção de contraste dos ícones Light após herança de estilos do componente original.

Validação visual do Figma não comprova comportamento Android, desempenho, sensor físico, contraste em todos os estados, TalkBack ou fonte200%; esses critérios exigem testes do aplicativo. Os fluxos secundários têm referência Dark; o código deve responder ao tema usando os mesmos tokens.

# NEXO Tools — extensões

Segue a especificação original em `work/design/DESIGN.md`. Temas, tipografia, espaçamentos, componentes e as 38 variáveis do produto foram preservados. As novas telas ficam na página Produto, abaixo dos fluxos existentes.

## Controle LED24

Grade 4 × 6 com 24 alvos de 75 × 48 px: brilho ±, desligar/ligar, 16 cores e quatro efeitos. Ordem e IDs seguem a tabela de comandos IR verificada. Perfis LED24 00FF e LED24 00F7 selecionáveis; rótulos de compatibilidade não afirmam universalidade. Sem emissor IR, teclas desativadas e estado indisponível explícito. A coleção de 16 amostras de cor pertence somente aos comandos do LED; não altera temas. As amostras visuais não medem a cor emitida pela fita.

## Controle TV LG

TVs clássicas por infravermelho: ligar/desligar, entrada, navegação/OK, menu/voltar/info/sair, volume±/mudo, canal± e dígitos. A interface rola para o teclado numérico. Ausência de emissor desativa comandos; o perfil pode variar por modelo. Sem controle de rede ou simulação de envio bem-sucedido.

## Áudio e volume

Player de arquivo local. Seleção pelo seletor de arquivos Android, reprodução/pausa/posição e ganho de 0 até +12 dB na sessão deste player. Sem arquivo, reprodução e restauração desativadas. Ganho alto pode distorcer o som. O recurso não representa amplificação global de outros aplicativos.

## Câmera noturna

Viewport inicial “Aguardando câmera”, sem fotografia simulada. A permissão é solicitada ao usar a câmera. Realce digital com gamma e modo normal/verde; depende de luz ambiente e não fornece visão infravermelha. Lanterna depende de disponibilidade. A captura para ao sair da ferramenta. Sem valores falsos de FPS, brilho ou sensibilidade.

## Macros e horários

Escopo confirmado: ações internas do NEXO (enviar IR, aguardar, abrir ferramenta, mostrar notificação) e ações em aplicativos escolhidos pelo usuário (abrir aplicativo, toque, deslize, voltar e início). Editor em rascunho e ausência de macros salvas; salvar fica desativado sem etapas. A acessibilidade é opcional, exige consentimento e ativação manual no Android; sem leitura de texto ou imagens. Posições e gestos são definidos pelo usuário. Horário é aproximado, sujeito ao Android/WorkManager. Ações em aplicativos agendadas aguardam toque na notificação. O layout usa apenas “Aplicativo escolhido”, sem nomes ou imagens de aplicativos reais.

## Validação e limites

Dez telas Dark/Light com Auto Layout, textos editáveis, ícones Lucide oficiais e aviso fixo de layout ilustrativo. Catálogo recebe cinco entradas e rotas de ida/volta. Verificação estrutural: dez telas de 390 × 844, modos corretos, 24 teclas LED por tema com alvos de 75 × 48 e 38 variáveis originais mantidas. Screenshots das dez telas revisados. A validação de Figma não substitui câmera física, áudio, emissor/receptor IR, temporização Android ou acessibilidade.

Página: https://www.figma.com/design/172NaHtxcCPdj6mVkU6Sf1?node-id=32-2

Nodes Dark/Light: LED 61:650 / 62:676; LG 59:434 / 60:542; Áudio 59:544 / 60:652; Câmera 59:609 / 60:714; Macros 59:666 / 60:770.

