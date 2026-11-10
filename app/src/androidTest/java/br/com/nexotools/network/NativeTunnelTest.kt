package br.com.nexotools.network

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.nexotools.domain.PcapCodec
import br.com.nexotools.infra.LocalSocksProxy
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Uses synthetic IP data and a proxy which refuses all outbound sockets. No VPN or external traffic. */
@RunWith(AndroidJUnit4::class)
class NativeTunnelTest {
    @Test fun cancellationBeforeRunDoesNotPoisonTheNextSession() {
        assumeFalse(PacketCaptureStore.state.value.active)
        val old = NativeTunnel.prepare()
        assertTrue(old > 0)
        NativeTunnel.stop(old)
        val current = NativeTunnel.prepare()
        try {
            assertTrue(current > old)
            NativeTunnel.stop(old)
            assertEquals(-2, NativeTunnel.run("", -1, -1, old))
            assertEquals(0, NativeTunnel.prepare())
        } finally { NativeTunnel.stop(current) }
    }

    @Test(timeout = 20000) fun observesSyntheticPacketAndLeavesBorrowedDescriptorsOpen() {
        assumeFalse(PacketCaptureStore.state.value.active)
        val left = FileDescriptor(); val right = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, left, right)
        val tun = ParcelFileDescriptor.dup(left)
        val peer = ParcelFileDescriptor.dup(right)
        Os.close(left); Os.close(right)
        val mirror = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.AutoCloseOutputStream(peer)
        val executor = Executors.newFixedThreadPool(2)
        val seen = AtomicReference<ByteArray>()
        val captured = CountDownLatch(1)
        val proxy = LocalSocksProxy({ false }, { false }, { throw AssertionError("DNS must not run") })
        val port = proxy.start("fixture", "synthetic-session-password")
        val token = NativeTunnel.prepare()
        assertTrue(token > 0)
        val reader = executor.submit {
            ParcelFileDescriptor.AutoCloseInputStream(mirror[0]).use { input ->
                PcapCodec.read(input) { _, _, bytes -> if (bytes.size == 28 && bytes[9].toInt() == 17 && bytes[20].toInt() == 0x30 && bytes[21].toInt() == 0x39 && seen.compareAndSet(null, bytes)) captured.countDown() }
            }
        }
        val native = executor.submit<Int> {
            NativeTunnel.run("""
                tunnel:
                  name: fixture0
                  mtu: 1500
                  ipv4: 192.0.2.1
                  ipv6: '2001:db8::1'
                  icmp: 'off'
                socks5:
                  address: 127.0.0.1
                  port: $port
                  username: fixture
                  password: synthetic-session-password
                  udp: 'udp'
                misc:
                  log-file: '/dev/null'
                  log-level: error
                  connect-timeout: 1000
            """.trimIndent(), tun.fd, mirror[1].fd, token)
        }
        try {
            val packet = ByteArray(28)
            packet[0] = 0x45; packet[3] = 28; packet[8] = 64; packet[9] = 17
            byteArrayOf(192.toByte(), 0, 2, 1).copyInto(packet, 12)
            byteArrayOf(198.toByte(), 51, 100, 1).copyInto(packet, 16)
            packet[20] = 0x30; packet[21] = 0x39; packet[23] = 9; packet[25] = 8
            var checksum = 0
            for (i in 0 until 20 step 2) checksum += ((packet[i].toInt() and 255) shl 8) or (packet[i + 1].toInt() and 255)
            while (checksum > 65535) checksum = (checksum and 65535) + (checksum ushr 16)
            checksum = checksum.inv() and 65535
            packet[10] = (checksum ushr 8).toByte(); packet[11] = checksum.toByte()
            output.write(packet)
            assertTrue("Native observer must emit the input packet", captured.await(8, TimeUnit.SECONDS))
            assertArrayEquals(packet, seen.get())
            NativeTunnel.stop(token)
            assertEquals(-2, native.get(5, TimeUnit.SECONDS).toInt())
            assertTrue(Os.fcntlInt(tun.fileDescriptor, OsConstants.F_GETFL, 0) >= 0)
            assertTrue(Os.fcntlInt(mirror[1].fileDescriptor, OsConstants.F_GETFL, 0) >= 0)
        } finally {
            NativeTunnel.stop(token)
            runCatching { native.get(5, TimeUnit.SECONDS) }
            proxy.close(); tun.close(); output.close(); mirror[1].close()
            runCatching { reader.get(3, TimeUnit.SECONDS) }
            runCatching { mirror[0].close() }
            executor.shutdownNow()
        }
    }
}
