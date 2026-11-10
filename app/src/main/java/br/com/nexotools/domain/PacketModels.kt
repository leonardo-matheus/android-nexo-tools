package br.com.nexotools.domain

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

enum class PacketProtocol { TCP, UDP, ICMP, ICMPV6, OTHER }

data class CapturedPacket(
    val number: Long,
    val timestampMicros: Long,
    val originalLength: Int,
    val bytes: ByteArray,
    val ipVersion: Int,
    val source: String,
    val destination: String,
    val protocol: PacketProtocol,
    val sourcePort: Int?,
    val destinationPort: Int?,
    val ttl: Int,
    val tcpFlags: Int? = null,
    val sequence: Long? = null,
    val acknowledgement: Long? = null,
    val dnsQuestion: String? = null,
    val uid: Int? = null,
    val app: String? = null,
) {
    val sourceEndpoint: String get() = endpoint(source, sourcePort)
    val destinationEndpoint: String get() = endpoint(destination, destinationPort)
    val connectionKey: String get() = listOf(sourceEndpoint, destinationEndpoint).sorted().joinToString("|") + "|" + protocol
    fun matches(protocolFilter: String, query: String): Boolean {
        if (protocolFilter == "DNS" && (protocol !in setOf(PacketProtocol.TCP, PacketProtocol.UDP) || (sourcePort != 53 && destinationPort != 53))) return false
        if (protocolFilter !in setOf("Todos", "DNS") && protocol.name != protocolFilter) return false
        val term = query.trim()
        return term.isEmpty() || listOf(sourceEndpoint, destinationEndpoint, app.orEmpty(), dnsQuestion.orEmpty(), protocol.name)
            .any { it.contains(term, ignoreCase = true) }
    }
    companion object {
        fun endpoint(address: String, port: Int?): String = if (port == null) address
            else if (':' in address) "[$address]:$port" else "$address:$port"
    }
}

object PacketDecoder {
    private fun ByteArray.u8(at: Int) = this[at].toInt() and 255
    private fun ByteArray.u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
    private fun ByteArray.u32(at: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or u8(at + i).toLong() }

    fun decode(number: Long, timestampMicros: Long, originalLength: Int, packet: ByteArray): CapturedPacket? {
        if (packet.isEmpty() || originalLength < packet.size) return null
        val version = packet.u8(0) ushr 4
        val source: String
        val destination: String
        val ttl: Int
        var next: Int
        var offset: Int
        var fragmented = false
        var moreFragments = false
        val declaredEnd: Int
        val packetEnd: Int
        when (version) {
            4 -> {
                if (packet.size < 20) return null
                offset = (packet.u8(0) and 15) * 4
                val length = packet.u16(2)
                if (offset < 20 || offset > packet.size || length < offset || length > originalLength) return null
                declaredEnd = length
                packetEnd = minOf(length, packet.size)
                source = InetAddress.getByAddress(packet.copyOfRange(12, 16)).hostAddress ?: return null
                destination = InetAddress.getByAddress(packet.copyOfRange(16, 20)).hostAddress ?: return null
                ttl = packet.u8(8); next = packet.u8(9)
                fragmented = packet.u16(6) and 0x1fff != 0
                moreFragments = packet.u16(6) and 0x2000 != 0
            }
            6 -> {
                if (packet.size < 40) return null
                declaredEnd = 40 + packet.u16(4)
                if (declaredEnd > originalLength) return null
                packetEnd = minOf(packet.size, declaredEnd)
                source = InetAddress.getByAddress(packet.copyOfRange(8, 24)).hostAddress ?: return null
                destination = InetAddress.getByAddress(packet.copyOfRange(24, 40)).hostAddress ?: return null
                ttl = packet.u8(7); next = packet.u8(6); offset = 40
                var extensions = 0
                while (next in setOf(0, 43, 44, 51, 60)) {
                    if (++extensions > 8 || offset + 2 > packetEnd) return null
                    val type = next
                    next = packet.u8(offset)
                    val length = when (type) { 44 -> 8; 51 -> (packet.u8(offset + 1) + 2) * 4; else -> (packet.u8(offset + 1) + 1) * 8 }
                    if (offset + length > packetEnd) return null
                    if (type == 44) {
                        fragmented = packet.u16(offset + 2) and 0xfff8 != 0
                        moreFragments = packet.u16(offset + 2) and 1 != 0
                    }
                    offset += length
                    if (fragmented) break
                }
            }
            else -> return null
        }
        val protocol = when (next) { 6 -> PacketProtocol.TCP; 17 -> PacketProtocol.UDP; 1 -> PacketProtocol.ICMP; 58 -> PacketProtocol.ICMPV6; else -> PacketProtocol.OTHER }
        var sourcePort: Int? = null
        var destinationPort: Int? = null
        var flags: Int? = null
        var sequence: Long? = null
        var acknowledgement: Long? = null
        var dns: String? = null
        if (!fragmented) when (protocol) {
            PacketProtocol.TCP -> if (offset + 20 <= packetEnd) {
                val header = (packet.u8(offset + 12) ushr 4) * 4
                if (header < 20 || (!moreFragments && offset + header > declaredEnd)) return null
                sourcePort = packet.u16(offset); destinationPort = packet.u16(offset + 2)
                flags = packet.u8(offset + 13)
                sequence = packet.u32(offset + 4); acknowledgement = packet.u32(offset + 8)
                if ((sourcePort == 53 || destinationPort == 53) && offset + header + 2 <= packetEnd) {
                    val start = offset + header + 2
                    val length = packet.u16(start - 2)
                    if (start + length <= packetEnd) dns = dnsQuestion(packet, start, start + length)
                }
            }
            PacketProtocol.UDP -> if (offset + 8 <= packetEnd) {
                val length = packet.u16(offset + 4)
                if (length < 8 || (!moreFragments && offset + length > declaredEnd)) return null
                sourcePort = packet.u16(offset); destinationPort = packet.u16(offset + 2)
                if (sourcePort == 53 || destinationPort == 53) dns = dnsQuestion(packet, offset + 8, minOf(packetEnd, offset + length))
            }
            else -> Unit
        }
        return CapturedPacket(number, timestampMicros, originalLength, packet, version, source, destination, protocol,
            sourcePort, destinationPort, ttl, flags, sequence, acknowledgement, dns)
    }

    private fun dnsQuestion(data: ByteArray, start: Int, end: Int): String? {
        if (end - start < 12 || data.u16(start + 4) == 0) return null
        var at = start + 12
        val visited = mutableSetOf<Int>()
        val labels = mutableListOf<String>()
        while (at < end && visited.size < 128 && visited.add(at)) {
            val length = data.u8(at++)
            if (length == 0) return (if (labels.isEmpty()) "." else labels.joinToString(".")).takeIf { it.length in 1..253 }
            if (length and 0xc0 == 0xc0) {
                if (at >= end) return null
                at = start + (((length and 0x3f) shl 8) or data.u8(at))
                if (at !in start until end) return null
            } else {
                if (length > 63 || at + length > end) return null
                val label = data.copyOfRange(at, at + length)
                if (label.any { (it.toInt() and 255) !in 33..126 }) return null
                labels += String(label, Charsets.US_ASCII)
                at += length
            }
        }
        return null
    }

    fun flags(value: Int): String = listOf(0x02 to "SYN", 0x10 to "ACK", 0x01 to "FIN", 0x04 to "RST", 0x08 to "PSH", 0x20 to "URG", 0x40 to "ECE", 0x80 to "CWR")
        .filter { value and it.first != 0 }.joinToString(", ") { it.second }.ifEmpty { "—" }

    fun hex(bytes: ByteArray): String = bytes.asList().chunked(16).mapIndexed { index, line ->
        val hex = line.joinToString(" ") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }.padEnd(47)
        val ascii = line.map { (it.toInt() and 255).let { code -> if (code in 32..126) code.toChar() else '.' } }.joinToString("")
        String.format(Locale.ROOT, "%04x  %s  %s", index * 16, hex, ascii)
    }.joinToString("\n")
}

object PcapCodec {
    const val SNAPLEN = 2048
    fun header(): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0xa1b2c3d4.toInt()).putShort(2).putShort(4).putInt(0).putInt(0).putInt(SNAPLEN).putInt(101).array()

    fun export(output: OutputStream, packets: List<CapturedPacket>) {
        output.write(header())
        packets.forEach { packet ->
            output.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((packet.timestampMicros / 1_000_000).toInt()).putInt((packet.timestampMicros % 1_000_000).toInt())
                .putInt(packet.bytes.size).putInt(packet.originalLength).array())
            output.write(packet.bytes)
        }
    }

    fun read(input: InputStream, consume: (Long, Int, ByteArray) -> Unit) {
        fun readFully(size: Int, allowEof: Boolean = false): ByteArray? {
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(buffer, offset, size - offset)
                if (count < 0) {
                    if (offset == 0 && allowEof) return null
                    throw java.io.EOFException("Registro PCAP incompleto")
                }
                if (count > 0) offset += count
            }
            return buffer
        }
        val header = requireNotNull(readFully(24)).let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
        require(header.int == 0xa1b2c3d4.toInt() && header.short.toInt() == 2 && header.short.toInt() == 4) { "Formato PCAP não suportado" }
        header.int; header.int
        require(header.int in 1..SNAPLEN && header.int == 101) { "Interface PCAP não suportada" }
        while (true) {
            val record = readFully(16, true) ?: return
            val fields = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
            val seconds = fields.int.toLong() and 0xffffffffL
            val micros = fields.int
            val captured = fields.int
            val original = fields.int
            require(micros in 0..999_999 && captured in 1..SNAPLEN && original >= captured) { "Registro PCAP inválido" }
            consume(seconds * 1_000_000 + micros, original, requireNotNull(readFully(captured)))
        }
    }
}
