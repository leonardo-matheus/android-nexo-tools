package br.com.nexotools.network

import br.com.nexotools.domain.PacketFixtures
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PacketCaptureStoreTest {
    @Before fun reset() { PacketCaptureStore.finish(null); PacketCaptureStore.clear() }
    @After fun cleanup() { PacketCaptureStore.finish(null); PacketCaptureStore.clear() }

    @Test fun rollingBufferKeepsTheLatest3000AndCountsTheWholeSession() {
        PacketCaptureStore.begin(null); PacketCaptureStore.started()
        val raw = PacketFixtures.ipv4(6, PacketFixtures.tcp())
        for (number in 1..3005) PacketCaptureStore.received(PacketFixtures.decode(raw.copyOf(), number.toLong()))
        PacketCaptureStore.publish(7)
        val state = PacketCaptureStore.state.value
        val snapshot = PacketCaptureStore.snapshot()
        assertTrue(state.running)
        assertEquals(3005L, state.totalPackets)
        assertEquals(3005L * raw.size, state.totalBytes)
        assertEquals(7L, state.dropped)
        assertEquals(3000, state.retained)
        assertEquals(3000, snapshot.size)
        assertEquals(6L, snapshot.first().number)
        assertEquals(3005L, snapshot.last().number)
        PacketCaptureStore.received(PacketFixtures.decode(raw.copyOf(), 3006))
        assertEquals(6L, snapshot.first().number)
        assertEquals(3005L, snapshot.last().number)
        assertEquals(7L, PacketCaptureStore.snapshot().first().number)
    }

    @Test fun malformedPacketsAndNativeDropsDoNotIncreaseRetainedTraffic() {
        PacketCaptureStore.begin("app.ficticio"); PacketCaptureStore.started()
        val packet = PacketFixtures.decode(PacketFixtures.ipv4(6, PacketFixtures.tcp()))
        PacketCaptureStore.received(null); PacketCaptureStore.received(packet); PacketCaptureStore.publish(3)
        val state = PacketCaptureStore.state.value
        assertEquals(1L, state.totalPackets)
        assertEquals(packet.originalLength.toLong(), state.totalBytes)
        assertEquals(4L, state.dropped)
        assertEquals(1, state.retained)
        PacketCaptureStore.finish("Falha sintética")
        assertFalse(PacketCaptureStore.state.value.active)
        assertEquals("Falha sintética", PacketCaptureStore.state.value.error)
        assertEquals(1, PacketCaptureStore.snapshot().size)
    }

    @Test fun clearWaitsUntilCaptureStopsThenRemovesStoredPackets() {
        PacketCaptureStore.begin("app.ficticio"); PacketCaptureStore.started()
        PacketCaptureStore.received(PacketFixtures.decode(PacketFixtures.ipv4(6, PacketFixtures.tcp())))
        PacketCaptureStore.publish(0); PacketCaptureStore.clear()
        assertTrue(PacketCaptureStore.state.value.active)
        assertEquals(1, PacketCaptureStore.snapshot().size)
        PacketCaptureStore.stopping()
        assertTrue(PacketCaptureStore.state.value.stopping)
        assertFalse(PacketCaptureStore.state.value.running)
        PacketCaptureStore.clear(); assertEquals(1, PacketCaptureStore.snapshot().size)
        PacketCaptureStore.finish(null); PacketCaptureStore.clear()
        assertFalse(PacketCaptureStore.state.value.active)
        assertEquals(0L, PacketCaptureStore.state.value.totalPackets)
        assertEquals(0L, PacketCaptureStore.state.value.totalBytes)
        assertNull(PacketCaptureStore.state.value.selectedApp)
        assertTrue(PacketCaptureStore.snapshot().isEmpty())
    }

    @Test fun newSessionResetsPacketsCountersDropsAndPreviousFailure() {
        PacketCaptureStore.begin("app.ficticio.primeiro"); PacketCaptureStore.started()
        PacketCaptureStore.received(PacketFixtures.decode(PacketFixtures.ipv4(6, PacketFixtures.tcp())))
        PacketCaptureStore.received(null); PacketCaptureStore.publish(9); PacketCaptureStore.finish("Falha sintética")
        val previousRevision = PacketCaptureStore.state.value.revision
        PacketCaptureStore.begin("app.ficticio.segundo"); PacketCaptureStore.publish(0)
        val state = PacketCaptureStore.state.value
        assertTrue(state.starting); assertFalse(state.running); assertFalse(state.stopping)
        assertEquals("app.ficticio.segundo", state.selectedApp)
        assertNull(state.error)
        assertEquals(0L, state.totalPackets); assertEquals(0L, state.totalBytes); assertEquals(0L, state.dropped)
        assertEquals(0, state.retained)
        assertTrue(state.revision > previousRevision)
        assertTrue(PacketCaptureStore.snapshot().isEmpty())
    }
}
