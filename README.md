<div align="center">
  <h1>NEXO Tools</h1>
  <p>Instrumentos, diagnóstico de rede e utilidades em um só app Android.</p>
  <p>
    <img alt="Versão 1.3.0" src="https://img.shields.io/badge/vers%C3%A3o-1.3.0-184E77?style=flat-square" />
    <img alt="Android 8.0 ou superior" src="https://img.shields.io/badge/Android-8.0%2B-26785F?style=flat-square&amp;logo=android&amp;logoColor=white" />
    <img alt="Kotlin e Jetpack Compose" src="https://img.shields.io/badge/Kotlin-Jetpack_Compose-7F52FF?style=flat-square&amp;logo=kotlin&amp;logoColor=white" />
  </p>
  <p>
    <a href="#ferramentas">Ferramentas</a> ·
    <a href="#executar">Executar</a> ·
    <a href="#privacidade-e-limites">Privacidade e limites</a> ·
    <a href="#desenvolvimento">Desenvolvimento</a>
  </p>
</div>

Interface em português, temas claro e escuro e favoritos na tela inicial. Sem conta, anúncios ou telemetria.

## Ferramentas

| Área | O que você encontra |
| --- | --- |
| **Rede** | Wi-Fi, rede móvel, Sonar Wi-Fi, velocidade, latência TCP, monitor de tráfego, portas TCP e DNS/TLS. |
| **Ferramentas** | Nível de bolha, inclinômetro, medidor de ruído e calibração. |
| **Utilidades** | Controles IR LED/LG, Volume +, câmera noturna, tarefas/macros e desinstalador. |

- **Favoritos:** toque na estrela para fixar uma ferramenta na tela inicial.
- **Controle principal:** escolha LED ou LG na configuração IR e mantenha sua preferência.
- **Captura nativa:** filtre TCP/UDP por app, protocolo ou conexão e exporte PCAP. Funciona por VPN local, sem root ou outro app.

## Executar

Abra o projeto no Android Studio compatível com **AGP 9.5.1** e configure:

| Requisito | Versão |
| --- | --- |
| JDK | 17 ou superior, compatível com Gradle 9.6 |
| Android SDK | Platform 37 e Build Tools 36.0.0 |
| Android NDK | 28.2.13676358 |

O Gradle Wrapper está incluído. Configure o SDK pelo Android Studio ou por `ANDROID_HOME`.

**Windows / PowerShell**

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Habilite a depuração USB e autorize o computador. Como alternativa, selecione o aparelho no Android Studio e use **Run**.

Em Linux/macOS, execute `chmod +x gradlew` e substitua `.\gradlew.bat` por `./gradlew`.

## Privacidade e limites

Preferências, calibração e histórico são locais. Desinstalações exigem confirmação do Android; exportações dependem de ação do usuário.

| Recurso | Limite importante |
| --- | --- |
| **Monitor de tráfego** | Observa a interface VPN virtual. Não descriptografa HTTPS, QUIC ou DNS criptografado. ICMP e protocolos não encaminhados podem falhar. No Android 8/9, selecione o app antes de capturar. |
| **Buffer e PCAP** | Até **3.000 pacotes × 2.048 bytes** em memória. A exportação respeita os filtros e pode conter dados sensíveis. |
| **Sonar e WPS** | Mostra anúncios de rede, sem comprovar vulnerabilidade, direção ou distância. O app não inclui modo monitor, brute force WPS ou Pixie Dust. |
| **Medições** | Leituras dependem do aparelho e do ambiente; distância Wi-Fi é aproximada. Ângulos são exibidos sem decimais; dB SPL exige referência de calibração. Não substituem instrumentos certificados. |
| **IR, áudio e câmera** | IR exige emissor físico. Volume + atua no player do NEXO. A câmera realça luz existente, sem infravermelho ou visão na escuridão total. |
| **Testes de rede** | Latência é TCP. Speedtest nativo usa Cloudflare: limites de 24 MB a ~806 MB por perfil, mais overhead. FAST.com/Speedtest abrem no navegador. |
| **Macros** | Ações externas exigem consentimento e acessibilidade habilitada. Horários são aproximados, sujeitos à bateria do Android; tarefas que abrem telas aguardam toque na notificação. |

A captura continua em segundo plano; encerre pelo NEXO, pela notificação quando habilitada ou pelo controle de VPN do Android. Perder a rede encerra a sessão. Não opera com outra VPN. Ao testar portas TCP, pare a captura do próprio NEXO ou selecione outro app para evitar falsos positivos.

Consulte a [política de privacidade](PRIVACY.md) para detalhes sobre permissões e dados.

## Desenvolvimento

**Kotlin · Jetpack Compose · Hilt · Room · DataStore · CameraX · WorkManager**

O motor de captura usa **HEV SOCKS5 Tunnel 2.19.3**. O build inclui ARMv7, ARM64, x86 e x86_64, com alinhamento de 16 KB.

```powershell
# Testes unitários e análise estática
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug

# Compilar o APK de testes instrumentados
.\gradlew.bat :app:assembleDebugAndroidTest
```

**Verificado na versão 1.3.0:** build e Lint aprovados, **99 testes unitários** e **2 testes nativos no emulador**. A validação de uma sessão completa da VPN no celular permanece pendente.

**Código:** [Kotlin/Compose](app/src/main/java/br/com/nexotools/) · [Motor nativo](app/src/main/cpp/hev/) · [Testes unitários](app/src/test/) · [Testes instrumentados](app/src/androidTest/)

<details>
<summary><strong>Build de distribuição</strong></summary>

```powershell
.\gradlew.bat :app:assembleRelease :app:bundleRelease
```

Saídas em `app/build/outputs/apk/release/` e `app/build/outputs/bundle/release/`. Assine com sua chave pelo **Generate Signed App Bundle or APK** do Android Studio. AAB não se instala diretamente no aparelho.

</details>

<details>
<summary><strong>Notas de build e publicação</strong></summary>

- **Windows:** se ocorrer `Unable to establish loopback connection`, configure uma pasta temporária curta e existente em `JAVA_TOOL_OPTIONS`, com `-Djdk.net.unixdomain.tmpdir=<pasta>` e `-Djava.io.tmpdir=<pasta>`.
- **Lint:** `BidiSpoofing` está desativado por um problema no UAST do AGP; `BidirectionalSourceTest` verifica os caracteres de controle nos fontes. A exceção de `QUERY_ALL_PACKAGES` é específica do inventário completo de apps.
- **Publicação:** os usos de inventário de apps, VPN e acessibilidade precisam ser avaliados conforme as políticas da loja escolhida.

</details>

## Documentação

[Design](DESIGN.md) · [Comandos IR](IR_NOTES.md) · [Privacidade](PRIVACY.md) · [Licenças visuais](licenses/) · [Licenças do motor](app/src/main/assets/capture-licenses.txt)
