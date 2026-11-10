package br.com.nexotools.domain

import java.net.IDN
import java.util.Locale
import kotlinx.coroutines.flow.Flow

data class ProbeHost(val value: String, val literalBytes: ByteArray? = null, val scope: String? = null) {
    val tlsPeerName: String get() = value.substringBefore('%')
}

object ProbeInputs {
    const val MAX_PORTS = 256
    const val CONCURRENCY = 8
    const val CONNECT_TIMEOUT_MS = 1000
    val commonPorts = listOf(21, 22, 25, 53, 80, 110, 143, 443, 445, 587, 993, 995, 3306, 3389, 5432, 8080, 8443)

    fun host(input: String): ProbeHost {
        require(input.isNotEmpty() && input.length <= 300 && input.none {
            it.isWhitespace() || Character.isSpaceChar(it) || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
        }) { "Informe um host sem espaços" }
        require(input.none { it in "/\\?#@" }) { "Informe somente o host, sem URL ou caminho" }
        val value = if (input.startsWith('[')) {
            require(input.endsWith(']') && input.count { it == '[' } == 1 && input.count { it == ']' } == 1) { "Separe a porta do host" }
            input.substring(1, input.length - 1).also { require(':' in it) { "Colchetes são somente para IPv6" } }
        } else input.also { require('[' !in it && ']' !in it) { "Host inválido" } }
        if (':' in value) {
            val parts = value.split('%')
            require(parts.size <= 2) { "Escopo IPv6 inválido" }
            val scope = parts.getOrNull(1)?.also {
                require(it.matches(Regex("[A-Za-z0-9_.-]{1,32}")) && (it.any { c -> !c.isDigit() } || it.toIntOrNull() != null)) { "Escopo IPv6 inválido" }
            }
            val bytes = requireNotNull(ipv6(parts[0])) { "IPv6 inválido; informe a porta no campo separado" }
            return ProbeHost(parts[0].lowercase(Locale.ROOT) + (scope?.let { "%$it" } ?: ""), bytes, scope)
        }
        require('%' !in value) { "Escopo é somente para IPv6" }
        if (value.all { it.isDigit() || it == '.' }) {
            return ProbeHost(value, requireNotNull(ipv4(value)) { "IPv4 decimal inválido" })
        }
        val ascii = try { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT).removeSuffix(".") }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Nome de host inválido") }
        require(ascii.length in 1..253 && ascii.split('.').all { it.length in 1..63 }) { "Nome de host inválido" }
        if (ascii.all { it in '0'..'9' || it == '.' }) return ProbeHost(ascii, requireNotNull(ipv4(ascii)) { "IPv4 decimal inválido" })
        return ProbeHost(ascii)
    }

    fun ports(input: String): List<Int> {
        require(input.isNotBlank() && input.length <= 2048) { "Informe até 256 portas" }
        val result = sortedSetOf<Int>()
        input.split(',').forEach { raw ->
            val token = raw.trim()
            require(token.matches(Regex("[0-9]{1,5}(-[0-9]{1,5})?"))) { "Use portas ou faixas separadas por vírgula" }
            val ends = token.split('-').map { it.toInt() }
            val first = ends.first(); val last = ends.last()
            require(first in 1..65535 && last in first..65535) { "Portas devem estar entre 1 e 65535, em ordem crescente" }
            require(last - first + 1 <= MAX_PORTS) { "Limite de 256 portas por teste" }
            result.addAll(first..last)
            require(result.size <= MAX_PORTS) { "Limite de 256 portas por teste" }
        }
        return result.toList()
    }

    private fun ipv4(value: String): ByteArray? {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.length !in 1..3 || it.any { c -> c !in '0'..'9' } || (it.length > 1 && it[0] == '0') }) return null
        val values = parts.map { it.toInt() }
        if (values.any { it !in 0..255 }) return null
        return ByteArray(4) { values[it].toByte() }
    }

    private fun ipv6(value: String): ByteArray? {
        if (value.isEmpty() || value.any { it !in "0123456789abcdefABCDEF:." }) return null
        if ('.' in value && value.indexOf('.') < value.lastIndexOf(':')) return null
        val compressed = value.contains("::")
        if (compressed && value.indexOf("::") != value.lastIndexOf("::")) return null
        fun words(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val tokens = part.split(':'); val result = mutableListOf<Int>()
            tokens.forEachIndexed { index, token ->
                if ('.' in token) {
                    if (index != tokens.lastIndex) return null
                    val bytes = ipv4(token) ?: return null
                    result += ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
                    result += ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
                } else {
                    if (token.length !in 1..4) return null
                    result += token.toIntOrNull(16) ?: return null
                }
            }
            return result
        }
        val halves = if (compressed) value.split("::", limit = 2) else listOf(value, "")
        val left = words(halves[0]) ?: return null; val right = words(halves[1]) ?: return null
        val used = left.size + right.size
        if ((compressed && used >= 8) || (!compressed && used != 8)) return null
        val all = left + List(8 - used) { 0 } + right
        return ByteArray(16) { index -> (if (index % 2 == 0) all[index / 2] ushr 8 else all[index / 2]).toByte() }
    }
}

enum class PortProbeStatus(val label: String) { OPEN("Aberta"), REFUSED("Recusada"), NO_RESPONSE("Sem resposta"), ERROR("Erro") }
data class PortProbeResult(val port: Int, val address: String, val status: PortProbeStatus, val durationMs: Long, val detail: String? = null)
data class DnsAddress(val type: String, val address: String)
data class DnsProbeResult(val addresses: List<DnsAddress>, val durationMs: Long, val literal: Boolean)
data class TlsCertificate(val subject: String, val issuer: String, val notBeforeMs: Long, val notAfterMs: Long)
data class TlsProbeResult(val address: String, val protocol: String, val cipher: String, val certificates: List<TlsCertificate>, val durationMs: Long)
class ProbeFailure(message: String) : Exception(message)

interface NetworkProbeSource {
    fun scan(host: String, ports: List<Int>): Flow<PortProbeResult>
    suspend fun dns(host: String): DnsProbeResult
    suspend fun tls(host: String, port: Int = 443): TlsProbeResult
}
