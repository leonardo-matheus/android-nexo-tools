package br.com.nexotools.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PacketModelsTest {
    @Test fun tcpIpv4PreservesUnsignedSequenceAcknowledgementAndFlags() {
        val packet = PacketFixtures.decode(PacketFixtures.ipv4(6, PacketFixtures.tcp()))
        assertEquals(4, packet.ipVersion)
        assertEquals("192.0.2.10", packet.source)
        assertEquals("198.51.100.20", packet.destination)
        assertEquals(PacketProtocol.TCP, packet.protocol)
        assertEquals(49152, packet.sourcePort)
        assertEquals(443, packet.destinationPort)
        assertEquals(64, packet.ttl)
        assertEquals(0x12, packet.tcpFlags)
        assertEquals(0x1234beefL, packet.sequence)
        assertEquals(0xfedcba98L, packet.acknowledgement)
        assertEquals("SYN, ACK", PacketDecoder.flags(requireNotNull(packet.tcpFlags)))
    }

    @Test fun udpRootDnsQuestionIsDecodedAndFilteredAsDns() {
        val question = PacketFixtures.dns(byteArrayOf(0, 0, 2, 0, 1))
        val packet = PacketFixtures.decode(PacketFixtures.ipv4(17, PacketFixtures.udp(payload = question)))
        assertEquals(PacketProtocol.UDP, packet.protocol)
        assertEquals(".", packet.dnsQuestion)
        assertTrue(packet.matches("DNS", ""))
        assertFalse(packet.matches("TCP", ""))
    }

    @Test fun dnsResponseWithNoQuestionsStillMatchesPort53() {
        val response = PacketFixtures.dns(ByteArray(0), questions = 0).apply { PacketFixtures.u16(this, 2, 0x8180) }
        val packet = PacketFixtures.decode(PacketFixtures.ipv4(17, PacketFixtures.udp(53, 49152, response), reverse = true))
        assertNull(packet.dnsQuestion)
        assertTrue(packet.matches("DNS", ""))
        assertFalse(PacketFixtures.decode(PacketFixtures.ipv4(17, PacketFixtures.udp(1234, 4321, response))).matches("DNS", ""))
    }

    @Test(timeout = 5_000) fun selfReferencingDnsCompressionPointerTerminates() {
        val question = PacketFixtures.dns(byteArrayOf(0xc0.toByte(), 0x0c))
        val packet = PacketFixtures.decode(PacketFixtures.ipv4(17, PacketFixtures.udp(payload = question)))
        assertNull(packet.dnsQuestion)
        assertTrue(packet.matches("DNS", ""))
    }

    @Test fun segmentedTcpDnsDoesNotNeedQuestionTextToMatchDns() {
        val tcp = PacketFixtures.tcp(destinationPort = 53, payload = byteArrayOf(0, 32, 1, 2))
        val packet = PacketFixtures.decode(PacketFixtures.ipv4(6, tcp))
        assertNull(packet.dnsQuestion)
        assertTrue(packet.matches("DNS", ""))
    }

    @Test fun ipv6ExtensionHeadersAreSkippedBeforeUdpPorts() {
        val hop = byteArrayOf(60, 0, 0, 0, 0, 0, 0, 0)
        val destinationOptions = byteArrayOf(17, 0, 0, 0, 0, 0, 0, 0)
        val packet = PacketFixtures.decode(PacketFixtures.ipv6(0, hop + destinationOptions + PacketFixtures.udp(12000, 8443)))
        assertEquals(6, packet.ipVersion)
        assertTrue(packet.source.startsWith("2001:db8:"))
        assertEquals(PacketProtocol.UDP, packet.protocol)
        assertEquals(12000, packet.sourcePort)
        assertEquals(8443, packet.destinationPort)
        assertEquals(48, packet.ttl)
    }

    @Test fun ipv6ExtensionLengthCannotExceedCapturedPacket() {
        val extension = byteArrayOf(17, 4, 0, 0, 0, 0, 0, 0)
        val raw = PacketFixtures.ipv6(0, extension + PacketFixtures.udp())
        assertNull(PacketDecoder.decode(1, PacketFixtures.TIME, raw.size, raw))
    }

    @Test fun nonInitialIpv4FragmentDoesNotInventTransportPorts() {
        val raw = PacketFixtures.ipv4(17, PacketFixtures.udp(), fragment = 1)
        val packet = PacketFixtures.decode(raw)
        assertEquals(PacketProtocol.UDP, packet.protocol)
        assertNull(packet.sourcePort); assertNull(packet.destinationPort)
        assertNull(packet.dnsQuestion)
        assertFalse(packet.matches("DNS", ""))
    }

    @Test fun nonInitialIpv6FragmentDoesNotInventTcpFields() {
        val fragment = ByteArray(8).apply { this[0] = 6; PacketFixtures.u16(this, 2, 8) }
        val packet = PacketFixtures.decode(PacketFixtures.ipv6(44, fragment + PacketFixtures.tcp()))
        assertEquals(PacketProtocol.TCP, packet.protocol)
        assertNull(packet.sourcePort); assertNull(packet.destinationPort)
        assertNull(packet.tcpFlags); assertNull(packet.sequence); assertNull(packet.acknowledgement)
    }

    @Test fun tcpHeaderLargerThanDeclaredIpPayloadIsRejected() {
        val raw = PacketFixtures.ipv4(6, PacketFixtures.tcp(headerWords = 15))
        assertNull(PacketDecoder.decode(1, PacketFixtures.TIME, raw.size, raw))
    }

    @Test fun udpLengthLargerThanDeclaredIpPayloadIsRejected() {
        val raw = PacketFixtures.ipv4(17, PacketFixtures.udp(declaredLength = 9))
        assertNull(PacketDecoder.decode(1, PacketFixtures.TIME, raw.size, raw))
    }

    @Test fun firstIpv4UdpFragmentMayDeclareTheWholeDatagramLength() {
        val udp = PacketFixtures.udp(destinationPort = 9000, payload = ByteArray(24), declaredLength = 1600)
        val packet = PacketFixtures.decode(PacketFixtures.ipv4(17, udp, fragment = 0x2000))
        assertEquals(49152, packet.sourcePort)
        assertEquals(9000, packet.destinationPort)
    }

    @Test fun firstIpv6UdpFragmentMayDeclareTheWholeDatagramLength() {
        val fragment = ByteArray(8).apply { this[0] = 17; PacketFixtures.u16(this, 2, 1) }
        val udp = PacketFixtures.udp(destinationPort = 9000, payload = ByteArray(24), declaredLength = 1600)
        val packet = PacketFixtures.decode(PacketFixtures.ipv6(44, fragment + udp))
        assertEquals(49152, packet.sourcePort)
        assertEquals(9000, packet.destinationPort)
    }

    @Test fun snaplenTruncationKeepsOriginalLengthAndVisibleUdpHeader() {
        val raw = PacketFixtures.ipv4(17, PacketFixtures.udp(destinationPort = 9000, payload = ByteArray(2300 - 28)))
        val captured = raw.copyOf(PcapCodec.SNAPLEN)
        val packet = PacketFixtures.decode(captured, originalLength = raw.size)
        assertEquals(2048, packet.bytes.size)
        assertEquals(2300, packet.originalLength)
        assertEquals(9000, packet.destinationPort)
        assertNull(PacketDecoder.decode(1, PacketFixtures.TIME, captured.size, captured))
    }

    @Test fun pcapExportReadRoundTripPreservesWireBytesLengthsAndMicroseconds() {
        val tcp = PacketFixtures.decode(PacketFixtures.ipv4(6, PacketFixtures.tcp()))
        val raw = PacketFixtures.ipv4(17, PacketFixtures.udp(destinationPort = 9000, payload = ByteArray(2300 - 28)))
        val truncated = PacketFixtures.decode(raw.copyOf(PcapCodec.SNAPLEN), number = 2, time = PacketFixtures.TIME + 987, originalLength = raw.size)
        val expected = listOf(tcp, truncated)
        val output = ByteArrayOutputStream()
        PcapCodec.export(output, expected)
        val decoded = mutableListOf<CapturedPacket>()
        PcapCodec.read(ByteArrayInputStream(output.toByteArray())) { time, length, bytes ->
            decoded += PacketFixtures.decode(bytes, decoded.size.toLong() + 1, time, length)
        }
        assertEquals(2, decoded.size)
        expected.zip(decoded).forEach { (before, after) ->
            assertEquals(before.timestampMicros, after.timestampMicros)
            assertEquals(before.originalLength, after.originalLength)
            assertEquals(before.protocol, after.protocol)
            assertEquals(before.sourcePort, after.sourcePort)
            assertEquals(before.sequence, after.sequence)
            assertArrayEquals(before.bytes, after.bytes)
        }
    }

    @Test fun pcapRejectsInvalidCaptureLengthsBeforeReadingPayload() {
        listOf(-1, 0, PcapCodec.SNAPLEN + 1).forEach { length ->
            assertThrows(IllegalArgumentException::class.java) {
                PcapCodec.read(ByteArrayInputStream(PcapCodec.header() + PacketFixtures.record(length, 4096))) { _, _, _ -> fail("Registro inválido não pode ser entregue") }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcapCodec.read(ByteArrayInputStream(PcapCodec.header() + PacketFixtures.record(40, 39))) { _, _, _ -> fail("Comprimento original inválido") }
        }
    }

    @Test fun pcapRejectsIncompletePayloadInsteadOfSilentlyEnding() {
        assertThrows(EOFException::class.java) {
            PcapCodec.read(ByteArrayInputStream(PcapCodec.header() + PacketFixtures.record(40, 40) + ByteArray(20))) { _, _, _ -> fail("Payload incompleto") }
        }
    }

    @Test fun connectionKeyIsBidirectionalAndSpecificToPortsAndProtocol() {
        val forward = PacketFixtures.decode(PacketFixtures.ipv4(6, PacketFixtures.tcp()))
        val reverse = PacketFixtures.decode(PacketFixtures.ipv4(6, PacketFixtures.tcp(443, 49152), reverse = true))
        val otherPort = PacketFixtures.decode(PacketFixtures.ipv4(6, PacketFixtures.tcp(destinationPort = 8443)))
        val otherAddress = PacketFixtures.decode(PacketFixtures.ipv4(6, PacketFixtures.tcp()).apply {
            byteArrayOf(203.toByte(), 0, 113, 30).copyInto(this, 16)
        })
        val udp = PacketFixtures.decode(PacketFixtures.ipv4(17, PacketFixtures.udp(destinationPort = 443)))
        assertEquals(forward.connectionKey, reverse.connectionKey)
        assertNotEquals(forward.connectionKey, otherPort.connectionKey)
        assertNotEquals(forward.connectionKey, otherAddress.connectionKey)
        assertNotEquals(forward.connectionKey, udp.connectionKey)
        assertEquals("[2001:db8::1]:443", CapturedPacket.endpoint("2001:db8::1", 443))
    }
}

internal object PacketFixtures {
    const val TIME = 1_700_000_000_123_456L
    fun u16(bytes: ByteArray, offset: Int, value: Int) { bytes[offset] = (value ushr 8).toByte(); bytes[offset + 1] = value.toByte() }
    private fun u32(bytes: ByteArray, offset: Int, value: Long) { for (index in 0..3) bytes[offset + index] = (value ushr (24 - index * 8)).toByte() }
    fun ipv4(protocol: Int, payload: ByteArray, fragment: Int = 0, reverse: Boolean = false): ByteArray = ByteArray(20 + payload.size).apply {
        this[0] = 0x45; u16(this, 2, size); u16(this, 6, fragment); this[8] = 64; this[9] = protocol.toByte()
        val source = byteArrayOf(192.toByte(), 0, 2, 10); val destination = byteArrayOf(198.toByte(), 51, 100, 20)
        (if (reverse) destination else source).copyInto(this, 12)
        (if (reverse) source else destination).copyInto(this, 16)
        payload.copyInto(this, 20)
    }
    fun ipv6(nextHeader: Int, payload: ByteArray): ByteArray = ByteArray(40 + payload.size).apply {
        this[0] = 0x60; u16(this, 4, payload.size); this[6] = nextHeader.toByte(); this[7] = 48
        val address = ByteArray(16).apply { this[0] = 0x20; this[1] = 1; this[2] = 0x0d; this[3] = 0xb8.toByte(); this[15] = 1 }
        address.copyInto(this, 8); address.apply { this[15] = 2 }.copyInto(this, 24)
        payload.copyInto(this, 40)
    }
    fun tcp(sourcePort: Int = 49152, destinationPort: Int = 443, headerWords: Int = 5, payload: ByteArray = ByteArray(0)): ByteArray = ByteArray(20 + payload.size).apply {
        u16(this, 0, sourcePort); u16(this, 2, destinationPort); u32(this, 4, 0x1234beef); u32(this, 8, 0xfedcba98)
        this[12] = (headerWords shl 4).toByte(); this[13] = 0x12; payload.copyInto(this, 20)
    }
    fun udp(sourcePort: Int = 49152, destinationPort: Int = 53, payload: ByteArray = ByteArray(0), declaredLength: Int = 8 + payload.size): ByteArray = ByteArray(8 + payload.size).apply {
        u16(this, 0, sourcePort); u16(this, 2, destinationPort); u16(this, 4, declaredLength); payload.copyInto(this, 8)
    }
    fun dns(question: ByteArray, questions: Int = 1): ByteArray = ByteArray(12 + question.size).apply {
        u16(this, 0, 0x1234); u16(this, 4, questions); question.copyInto(this, 12)
    }
    fun record(captured: Int, original: Int): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(1_700_000_000).putInt(123456).putInt(captured).putInt(original).array()
    fun decode(raw: ByteArray, number: Long = 1, time: Long = TIME, originalLength: Int = raw.size): CapturedPacket =
        requireNotNull(PacketDecoder.decode(number, time, originalLength, raw)) { "Fixture sintética deve ser decodificável" }
}
