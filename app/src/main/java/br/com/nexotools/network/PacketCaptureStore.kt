package br.com.nexotools.network

import br.com.nexotools.domain.CapturedPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PacketCaptureState(
    val starting: Boolean = false,
    val running: Boolean = false,
    val stopping: Boolean = false,
    val totalPackets: Long = 0,
    val totalBytes: Long = 0,
    val dropped: Long = 0,
    val retained: Int = 0,
    val revision: Long = 0,
    val error: String? = null,
    val selectedApp: String? = null,
) { val active: Boolean get() = starting || running || stopping }

/** Raw packets stay in memory until the user explicitly exports a PCAP. */
object PacketCaptureStore {
    const val MAX_PACKETS = 3000
    private val packets = ArrayDeque<CapturedPacket>()
    private val mutable = MutableStateFlow(PacketCaptureState())
    val state = mutable.asStateFlow()
    private var total = 0L
    private var bytes = 0L
    private var malformed = 0L

    @Synchronized fun begin(app: String?) {
        packets.clear(); total = 0; bytes = 0; malformed = 0
        mutable.value = PacketCaptureState(starting = true, selectedApp = app, revision = mutable.value.revision + 1)
    }
    @Synchronized fun started() { mutable.value = mutable.value.copy(starting = false, running = true) }
    @Synchronized fun stopping() { if (mutable.value.active) mutable.value = mutable.value.copy(starting = false, running = false, stopping = true) }
    @Synchronized fun received(packet: CapturedPacket?) {
        if (packet == null) { malformed++; return }
        total++; bytes += packet.originalLength
        if (packets.size == MAX_PACKETS) packets.removeFirst()
        packets.addLast(packet)
    }
    @Synchronized fun publish(nativeDropped: Long) {
        mutable.value = mutable.value.copy(totalPackets = total, totalBytes = bytes, dropped = malformed + nativeDropped,
            retained = packets.size, revision = mutable.value.revision + 1)
    }
    @Synchronized fun finish(error: String?) {
        mutable.value = mutable.value.copy(starting = false, running = false, stopping = false, error = error,
            totalPackets = total, totalBytes = bytes, retained = packets.size, revision = mutable.value.revision + 1)
    }
    @Synchronized fun snapshot(): List<CapturedPacket> = packets.toList()
    @Synchronized fun clear() {
        if (mutable.value.active) return
        packets.clear(); total = 0; bytes = 0; malformed = 0
        mutable.value = PacketCaptureState(revision = mutable.value.revision + 1)
    }
}
