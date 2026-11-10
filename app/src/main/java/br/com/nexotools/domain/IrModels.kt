package br.com.nexotools.domain

data class IrFrequencyRange(val minimumHz: Int, val maximumHz: Int) {
    fun contains(hertz: Int): Boolean = minimumHz > 0 && maximumHz >= minimumHz && hertz in minimumHz..maximumHz
}
data class IrHardwareStatus(
    val hasEmitter: Boolean,
    val frequencies: List<IrFrequencyRange> = emptyList(),
    val message: String,
) {
    fun supports(hertz: Int): Boolean = hasEmitter && frequencies.any { it.contains(hertz) }
}
interface IrTransmitter {
    fun status(): IrHardwareStatus
    suspend fun send(profileId: String, commandId: String)
    /** Manual NEC address/command in native LSB numeric notation, not legacy MSB codes. */
    suspend fun sendNec(address: Int, command: Int, extendedAddress: Boolean = false)
}
class IrUnavailableException(message: String) : IllegalStateException(message)

data class IrCommand(val id: String, val label: String, val command: Int)
data class IrProfile(
    val id: String,
    val title: String,
    val address: Int,
    val extendedAddress: Boolean,
    val commands: List<IrCommand>,
) {
    val commandsById: Map<String, IrCommand> = commands.associateBy { it.id }
    fun frame(commandId: String): Long {
        val selected = requireNotNull(commandsById[commandId]) { "Comando IR não reconhecido" }
        return NecEncoder.frame(address, selected.command, extendedAddress)
    }
}

/** NEC1 envelope; numeric frame bits are sent LSB first, including each address/command byte. */
object NecEncoder {
    const val CARRIER_HZ = 38_000
    const val HEADER_MARK_US = 9_000
    const val HEADER_SPACE_US = 4_500
    const val BIT_MARK_US = 562
    const val ZERO_SPACE_US = 562
    const val ONE_SPACE_US = 1_687
    const val TRAILING_SPACE_US = 40_000

    fun frame(address: Int, command: Int, extendedAddress: Boolean = false): Long {
        require(command in 0..255) { "Comando NEC deve ter 8 bits" }
        require(address in 0..(if (extendedAddress) 65_535 else 255)) { "Endereço fora da faixa NEC" }
        val addressBytes = if (extendedAddress) address else address or ((address xor 0xFF) shl 8)
        return addressBytes.toLong() or (command.toLong() shl 16) or ((command xor 0xFF).toLong() shl 24)
    }
    fun legacyMsb(frameLsb: Long): Long {
        require(frameLsb in 0..0xFFFF_FFFFL)
        return java.lang.Long.reverse(frameLsb) ushr 32
    }
    fun hasCommandChecksum(frameLsb: Long): Boolean = frameLsb in 0..0xFFFF_FFFFL &&
        (((frameLsb ushr 16) and 0xFF) xor ((frameLsb ushr 24) and 0xFF)) == 0xFFL

    fun pattern(frameLsb: Long): IntArray {
        require(hasCommandChecksum(frameLsb)) { "Quadro NEC ou complemento do comando inválido" }
        val pattern = IntArray(68)
        pattern[0] = HEADER_MARK_US; pattern[1] = HEADER_SPACE_US
        repeat(32) { bit ->
            pattern[2 + bit * 2] = BIT_MARK_US
            pattern[3 + bit * 2] = if ((frameLsb ushr bit) and 1L == 1L) ONE_SPACE_US else ZERO_SPACE_US
        }
        pattern[66] = BIT_MARK_US
        // Android alternates carrier on/off; the stop mark is followed by actual silence.
        pattern[67] = TRAILING_SPACE_US
        require(pattern.all { it > 0 } && pattern.sumOf { it.toLong() } < 2_000_000L)
        return pattern
    }
}

/** Command facts from Flipper-IRDB (CC0). See the source URLs in IR_NOTES.md. */
object IrProfiles {
    // The order is the physical 4 x 6 layout, shared by both LED variants and the UI.
    private fun ledCommands(codes: IntArray): List<IrCommand> {
        val keys = listOf(
            "BRIGHT_UP" to "Brilho +", "BRIGHT_DOWN" to "Brilho −", "OFF" to "Desligar", "ON" to "Ligar",
            "RED" to "Vermelho", "GREEN" to "Verde", "BLUE" to "Azul", "WHITE" to "Branco",
            "RED_LIGHT" to "Coral", "GREEN_LIGHT" to "Verde claro", "BLUE_DEEP" to "Azul intenso", "FLASH" to "Flash",
            "ORANGE" to "Laranja", "TURQUOISE" to "Turquesa", "PURPLE" to "Roxo", "STROBE" to "Strobe",
            "ORANGE_LIGHT" to "Âmbar", "CYAN" to "Ciano", "MAGENTA" to "Magenta", "FADE" to "Fade",
            "YELLOW" to "Amarelo", "TEAL" to "Verde água", "PINK" to "Rosa", "SMOOTH" to "Smooth",
        )
        require(codes.size == keys.size)
        return keys.mapIndexed { index, (id, label) -> IrCommand(id, label, codes[index]) }
    }
    val led24 = IrProfile("LED24", "LED 24 · 00FF", 0x00, false, ledCommands(intArrayOf(
        0x09, 0x1D, 0x1F, 0x0D,
        0x19, 0x1B, 0x11, 0x15,
        0x17, 0x12, 0x16, 0x4D,
        0x40, 0x4C, 0x04, 0x00,
        0x0A, 0x1E, 0x0E, 0x1A,
        0x1C, 0x14, 0x0F, 0x0C,
    )))
    val led24F7 = IrProfile("LED24_F7", "LED 24 · 00F7", 0xEF00, true, ledCommands(intArrayOf(
        0x00, 0x01, 0x02, 0x03,
        0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B,
        0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x12, 0x13,
        0x14, 0x15, 0x16, 0x17,
    )))
    val lgTv = IrProfile("LG_TV", "TV LG · NEC clássico", 0x04, false, listOf(
        IrCommand("POWER", "Ligar / desligar", 0x08), IrCommand("INPUT", "Entrada", 0x0B),
        IrCommand("MUTE", "Silenciar", 0x09), IrCommand("VOL_UP", "Volume +", 0x02), IrCommand("VOL_DOWN", "Volume −", 0x03),
        IrCommand("CH_UP", "Canal +", 0x00), IrCommand("CH_DOWN", "Canal −", 0x01),
        IrCommand("UP", "Acima", 0x40), IrCommand("DOWN", "Abaixo", 0x41), IrCommand("LEFT", "Esquerda", 0x07), IrCommand("RIGHT", "Direita", 0x06),
        IrCommand("OK", "OK", 0x44), IrCommand("MENU", "Menu", 0x43), IrCommand("BACK", "Voltar", 0x28), IrCommand("EXIT", "Sair", 0x5B),
    ) + (0..9).map { IrCommand("DIGIT_$it", it.toString(), 0x10 + it) })
    val byId: Map<String, IrProfile> = listOf(led24, led24F7, lgTv).associateBy { it.id }
}
