package br.com.nexotools.infra

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** All peers and resolver answers are loopback; no production endpoint or tunnel is contacted. */
class LocalSocksProxyTest {
    private val loop = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val user = "synthetic-user"
    private val password = "synthetic-password"

    private fun client(port: Int, secret: String = password): Socket {
        val socket = Socket(loop, port).apply { soTimeout = 2500 }
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        output.write(byteArrayOf(5, 1, 2)); output.flush()
        assertEquals(5, input.readUnsignedByte()); assertEquals(2, input.readUnsignedByte())
        val username = user.toByteArray(StandardCharsets.UTF_8)
        val bytes = secret.toByteArray(StandardCharsets.UTF_8)
        output.writeByte(1); output.writeByte(username.size); output.write(username)
        output.writeByte(bytes.size); output.write(bytes); output.flush()
        assertEquals(1, input.readUnsignedByte())
        assertEquals(if (secret == password) 0 else 1, input.readUnsignedByte())
        return socket
    }
    private fun request(socket: Socket, command: Int, address: InetAddress, port: Int): InetSocketAddress {
        val output = DataOutputStream(socket.getOutputStream())
        output.write(byteArrayOf(5, command.toByte(), 0, if (address is Inet4Address) 1 else 4))
        output.write(address.address); output.writeShort(port); output.flush()
        return response(socket)
    }
    private fun response(socket: Socket, expected: Int = 0): InetSocketAddress {
        val input = DataInputStream(socket.getInputStream())
        assertEquals(5, input.readUnsignedByte()); assertEquals(expected, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
        val size = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; else -> error("Unexpected address") }
        val address = InetAddress.getByAddress(ByteArray(size).also(input::readFully))
        return InetSocketAddress(address, input.readUnsignedShort())
    }
    private fun proxy(tcp: (Socket) -> Boolean = { true }, udp: (DatagramSocket) -> Boolean = { true }): LocalSocksProxy =
        LocalSocksProxy(tcp, udp) { name -> assertEquals("local.test", name); arrayOf(loop) }

    @Test(timeout = 10000) fun tcpDomainConnectProtectsBeforeConnectAndPreservesHalfClose() {
        ServerSocket(0, 1, loop).use { echo ->
            val done = CompletableFuture<Unit>()
            val executor = Executors.newSingleThreadExecutor()
            executor.execute {
                try {
                    echo.accept().use { peer ->
                        val body = peer.getInputStream().readBytes()
                        assertArrayEquals(byteArrayOf(10, 20, 30), body)
                        peer.getOutputStream().write(body.reversedArray())
                    }; done.complete(Unit)
                } catch (error: Throwable) { done.completeExceptionally(error) }
            }
            val calls = AtomicInteger()
            try {
                proxy(tcp = { assertFalse(it.isConnected); calls.incrementAndGet(); true }).use { proxy ->
                    val port = proxy.start(user, password)
                    assertTrue(proxy.listenAddress.isLoopbackAddress)
                    client(port).use { socket ->
                        val output = DataOutputStream(socket.getOutputStream())
                        val domain = "local.test".toByteArray(StandardCharsets.US_ASCII)
                        output.write(byteArrayOf(5, 1, 0, 3)); output.writeByte(domain.size); output.write(domain)
                        output.writeShort(echo.localPort); output.flush(); response(socket)
                        output.write(byteArrayOf(10, 20, 30)); output.flush(); socket.shutdownOutput()
                        assertArrayEquals(byteArrayOf(30, 20, 10), socket.getInputStream().readBytes())
                    }
                    assertEquals(1, calls.get()); done.get(3, TimeUnit.SECONDS)
                }
            } finally { echo.close(); executor.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun authenticationFailureNeverCreatesOutboundSocket() {
        val connections = AtomicInteger()
        proxy(tcp = { connections.incrementAndGet(); true }).use { proxy ->
            val port = proxy.start(user, password)
            client(port, "synthetic-wrong-password").use { socket -> assertEquals(-1, socket.getInputStream().read()) }
            assertEquals(0, connections.get())
        }
    }

    @Test(timeout = 10000) fun rawIpv6ConnectUsesOnlyTheLocalIpv6Server() {
        val bytes = ByteArray(16).also { it[15] = 1 }
        val ipv6 = InetAddress.getByAddress(bytes)
        val listener = try { ServerSocket(0, 1, ipv6) } catch (unavailable: IOException) {
            org.junit.Assume.assumeNoException(unavailable); return
        }
        listener.use {
            val executor = Executors.newSingleThreadExecutor()
            val served = CompletableFuture<Unit>()
            executor.execute {
                try { listener.accept().use { peer -> peer.getOutputStream().write(42) }; served.complete(Unit) }
                catch (error: Throwable) { served.completeExceptionally(error) }
            }
            try {
                proxy().use { proxy ->
                    client(proxy.start(user, password)).use { socket ->
                        val bound = request(socket, 1, ipv6, listener.localPort)
                        assertTrue(bound.address is Inet6Address)
                        assertEquals(42, socket.getInputStream().read())
                    }
                    served.get(3, TimeUnit.SECONDS)
                }
            } finally { listener.close(); executor.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun deniedUdpProtectionNeverBindsOrSendsOutbound() {
        val captured = AtomicReference<DatagramSocket>()
        proxy(udp = { captured.set(it); assertFalse(it.isBound); false }).use { proxy ->
            client(proxy.start(user, password)).use { socket ->
                val output = DataOutputStream(socket.getOutputStream())
                output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0)); output.writeShort(0); output.flush()
                response(socket, 2)
            }
        }
        assertTrue(captured.get().isClosed)
    }

    @Test(timeout = 10000) fun declaredUdpPeerPortRejectsAnotherLocalSender() {
        DatagramSocket(InetSocketAddress(loop, 0)).use { echo ->
            echo.soTimeout = 200
            DatagramSocket(InetSocketAddress(loop, 0)).use { expectedPeer ->
                DatagramSocket(InetSocketAddress(loop, 0)).use { wrongPeer ->
                    proxy().use { proxy ->
                        client(proxy.start(user, password)).use { control ->
                            val relay = request(control, 3, loop, expectedPeer.localPort)
                            val bytes = java.io.ByteArrayOutputStream()
                            DataOutputStream(bytes).apply {
                                writeShort(0); writeByte(0); writeByte(if (loop is Inet4Address) 1 else 4)
                                write(loop.address); writeShort(echo.localPort); writeByte(5)
                            }
                            val frame = bytes.toByteArray()
                            wrongPeer.send(DatagramPacket(frame, frame.size, relay))
                            try { echo.receive(DatagramPacket(ByteArray(128), 128)); fail("Unexpected UDP peer accepted") }
                            catch (_: SocketTimeoutException) { }
                            expectedPeer.send(DatagramPacket(frame, frame.size, relay))
                            echo.soTimeout = 2500
                            val received = DatagramPacket(ByteArray(128), 128); echo.receive(received)
                            assertEquals(1, received.length); assertEquals(5, received.data[0].toInt())
                        }
                    }
                }
            }
        }
    }

    @Test(timeout = 10000) fun deniedProtectionFailsConnectWithoutConnecting() {
        val captured = AtomicReference<Socket>()
        proxy(tcp = { captured.set(it); false }).use { proxy ->
            client(proxy.start(user, password)).use { socket ->
                val output = DataOutputStream(socket.getOutputStream())
                output.write(byteArrayOf(5, 1, 0, if (loop is Inet4Address) 1 else 4))
                output.write(loop.address); output.writeShort(9); output.flush()
                response(socket, 2)
                assertFalse(captured.get().isConnected)
            }
        }
        assertTrue(captured.get().isClosed)
    }

    @Test(timeout = 10000) fun udpAssociateRelaysDatagramsRejectsFragmentsAndClosesWithControl() {
        DatagramSocket(InetSocketAddress(loop, 0)).use { echo ->
            echo.soTimeout = 2500
            val outbound = AtomicReference<DatagramSocket>()
            val executor = Executors.newSingleThreadExecutor()
            val served = CompletableFuture<Unit>()
            executor.execute {
                try {
                    val packet = DatagramPacket(ByteArray(2048), 2048)
                    echo.receive(packet)
                    assertArrayEquals(byteArrayOf(7, 8, 9), packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                    echo.send(packet); served.complete(Unit)
                } catch (error: Throwable) { served.completeExceptionally(error) }
            }
            try {
                proxy(udp = { assertFalse(it.isConnected); outbound.set(it); true }).use { proxy ->
                    val control = client(proxy.start(user, password))
                    try {
                        val relay = request(control, 3, InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), 0)
                        assertTrue(relay.address.isLoopbackAddress)
                        DatagramSocket(InetSocketAddress(loop, 0)).use { socket ->
                            socket.soTimeout = 200
                            fun frame(fragment: Int): ByteArray {
                                val bytes = java.io.ByteArrayOutputStream()
                                DataOutputStream(bytes).apply {
                                    writeShort(0); writeByte(fragment); writeByte(if (loop is Inet4Address) 1 else 4)
                                    write(loop.address); writeShort(echo.localPort); write(byteArrayOf(7, 8, 9))
                                }
                                return bytes.toByteArray()
                            }
                            val invalid = frame(1); socket.send(DatagramPacket(invalid, invalid.size, relay))
                            try { socket.receive(DatagramPacket(ByteArray(2048), 2048)); fail("Fragment was relayed") }
                            catch (_: SocketTimeoutException) { }
                            socket.soTimeout = 2500
                            val valid = frame(0); socket.send(DatagramPacket(valid, valid.size, relay))
                            val packet = DatagramPacket(ByteArray(2048), 2048); socket.receive(packet)
                            val input = DataInputStream(java.io.ByteArrayInputStream(packet.data, packet.offset, packet.length))
                            assertEquals(0, input.readUnsignedShort()); assertEquals(0, input.readUnsignedByte())
                            val size = if (input.readUnsignedByte() == 1) 4 else 16
                            val remote = InetAddress.getByAddress(ByteArray(size).also(input::readFully))
                            assertEquals(loop, remote); assertEquals(echo.localPort, input.readUnsignedShort())
                            assertArrayEquals(byteArrayOf(7, 8, 9), input.readBytes())
                            served.get(3, TimeUnit.SECONDS)
                            control.close()
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                            while (!outbound.get().isClosed && System.nanoTime() < deadline) Thread.sleep(10)
                            assertTrue("TCP control close must close UDP association", outbound.get().isClosed)
                        }
                    } finally { control.close() }
                }
            } finally { echo.close(); executor.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun closeInterruptsActiveTcpRelaysAndIsIdempotent() {
        ServerSocket(0, 1, loop).use { listener ->
            val captured = AtomicReference<Socket>()
            val executor = Executors.newSingleThreadExecutor()
            val accepted = CompletableFuture<Socket>()
            executor.execute { accepted.complete(listener.accept()) }
            val proxy = proxy(tcp = { captured.set(it); true })
            try {
                val port = proxy.start(user, password)
                client(port).use { socket ->
                    request(socket, 1, loop, listener.localPort)
                    accepted.get(3, TimeUnit.SECONDS).use {
                        proxy.close(); proxy.close()
                        assertTrue(captured.get().isClosed)
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
                try { Socket(loop, port).use { fail("Closed listener accepted connection") } }
                catch (_: ConnectException) { }
            } finally { proxy.close(); listener.close(); executor.shutdownNow() }
        }
    }
}

