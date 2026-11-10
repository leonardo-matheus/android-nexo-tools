# Controle infravermelho

`IrProfiles.byId` contém `LED24`, `LED24_F7`, `LG_TV`. Perfil tem `commands` em ordem visual, `commandsById` e `frame(commandId)`. `IrTransmitter.status()` retorna `IrHardwareStatus`; `supports(38000)` exige emissor e frequência informada. `send(profileId,commandId)` mantém os IDs usados por macros; `sendNec(address,command,extendedAddress)` atende configuração manual sem alterar os perfis globais. Módulo Hilt `IrModule` já incluído.

Manifesto: `android.permission.TRANSMIT_IR` normal e `<uses-feature android:name="android.hardware.consumerir" android:required="false" />`. Não existe solicitação de permissão em runtime, aprendizado via receptor ou suporte a adaptadores externos neste módulo.

Codificação própria NEC/NEC estendido: envelope 9 ms/4,5 ms, marca 562 µs, espaço 562/1687 µs, LSB primeiro, complemento do comando, marca de parada e silêncio de 40 ms. Android recebe 68 tempos positivos, menos de 2 s. `Mutex` serializa envio, em IO, com intervalo adicional de 100 ms depois da transmissão anterior. Retorno da API não confirma recepção nem mudança de estado do equipamento.

LED24: endereço lógico LSB `0x00`, palavra MSB legada `00FF`. LED24_F7: endereço estendido LSB `0xEF00`, palavra MSB legada `00F7`; não converter F7 em endereço NEC normal. LG_TV: endereço LSB `0x04`, Power `0x08`, legado MSB `20DF10EF`; é NEC de TVs convencionais, distinto do protocolo LG de ar-condicionado. Compatibilidade depende do modelo/controlador. Os nomes das cores representam as teclas, não calibração RGB garantida.

Fontes dos fatos de comandos, todas CC0 1.0 Universal (sem dependência nova e sem copiar código de implementação):

- https://github.com/Lucaslhm/Flipper-IRDB/blob/main/LED_Lighting/Sylvania/Sylvania_24_Button_LED.ir
- https://github.com/Lucaslhm/Flipper-IRDB/blob/main/LED_Lighting/Unknown/LED_RGB.ir e `Lethe/Lethe_RGB_LED_full.ir` para códigos complementares da variante.
- https://github.com/Lucaslhm/Flipper-IRDB/blob/main/TVs/LG/LG_AKB69680401.ir e `LG_AKB33871403.ir` para Exit.
- Licença dos fatos: https://github.com/Lucaslhm/Flipper-IRDB/blob/main/LICENSE

Verificação independente do vetor e ordem LSB: Arduino-IRremote `src/ir_NEC.hpp`, MIT, https://github.com/Arduino-IRremote/Arduino-IRremote/blob/master/src/ir_NEC.hpp . Encoder autoral; nenhum arquivo desse projeto incorporado. API Android verificada no SDK 37: https://developer.android.com/reference/android/hardware/ConsumerIrManager . Não foram usados os dados de irdb sujeitos à notificação do produto, nem código WLED EUPL.

Nenhum sinal real foi transmitido durante desenvolvimento. Testes verificam somente cálculo de envelope, vetores, checksums e disponibilidade matemática da frequência.
