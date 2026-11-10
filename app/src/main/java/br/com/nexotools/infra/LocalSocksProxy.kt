package br.com.nexotools.infra

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** RFC 1928/1929 endpoint for the in-process tunnel. Never listens on a wildcard address. */
class LocalSocksProxy(
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val resolve: (String) -> Array<InetAddress>,
) : Closeable {
    val listenAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val closed = AtomicBoolean(false)
    private val slots = Semaphore(MAX_CLIENTS)
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<Client, Boolean>())
    private val sequence = AtomicInteger()
    private val workers = ThreadPoolExecutor(
        0, MAX_CLIENTS * 3, 30, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadFactory { task -> Thread(task, "Nexo-socks-${sequence.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var usernameBytes = ByteArray(0)
    @Volatile private var passwordBytes = ByteArray(0)

    @Synchronized fun start(username: String, password: String): Int {
        check(!closed.get() && server == null) { "Proxy already started or closed" }
        val user = username.toByteArray(StandardCharsets.UTF_8)
        val pass = password.toByteArray(StandardCharsets.UTF_8)
        require(user.size in 1..255 && pass.size in 1..255) { "Credentials must contain 1–255 UTF-8 bytes" }
        val listener = ServerSocket()
        try {
            listener.bind(InetSocketAddress(listenAddress, 0), MAX_CLIENTS)
            usernameBytes = user
            passwordBytes = pass
            server = listener
            acceptThread = Thread({ accept(listener) }, "Nexo-socks-accept").apply { isDaemon = true; start() }
            return listener.localPort
        } catch (failure: Throwable) {
            listener.close(); user.fill(0); pass.fill(0)
            throw failure
        }
    }

    private fun accept(listener: ServerSocket) {
        try {
            while (!closed.get()) {
                val socket = listener.accept()
                if (!socket.inetAddress.isLoopbackAddress || !slots.tryAcquire()) { socket.close(); continue }
                val client = Client(socket)
                clients.add(client)
                if (closed.get()) { client.close(); break }
                try { workers.execute { handle(client) } }
                catch (_: RejectedExecutionException) { client.close() }
            }
        } catch (_: IOException) {
            // Closing the listener interrupts accept; failures never include endpoints or payloads.
        } finally { close() }
    }

    private inner class Client(val control: Socket) : Closeable {
        private val done = AtomicBoolean()
        private val sockets = mutableListOf<Closeable>(control)
        @Synchronized fun <T : Closeable> track(socket: T): T {
            if (done.get()) { socket.close(); throw IOException("Client closed") }
            sockets += socket
            return socket
        }
        override fun close() {
            if (!done.compareAndSet(false, true)) return
            synchronized(this) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
            clients.remove(this)
            slots.release()
        }
    }

    private sealed interface Host {
        data class Raw(val address: InetAddress) : Host
        data class Domain(val name: String) : Host
    }
    private data class Target(val host: Host, val port: Int)
    private class ReplyFailure(val code: Int) : IOException()

    private fun handle(client: Client) {
        try {
            client.control.soTimeout = HANDSHAKE_MS
            client.control.tcpNoDelay = true
            val input = DataInputStream(client.control.getInputStream())
            val output = DataOutputStream(client.control.getOutputStream())
            if (!authenticate(input, output)) return
            if (input.readUnsignedByte() != 5) return
            val command = input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) { reply(output, 1); return }
            val target = try { readTarget(input) } catch (failure: ReplyFailure) { reply(output, failure.code); return }
            when (command) {
                1 -> connect(client, input, output, target)
                3 -> associate(client, input, output, target)
                else -> reply(output, 7)
            }
        } catch (_: Exception) {
            // Truncated frames, socket closure and cancellation terminate only this client.
        } finally { client.close() }
    }

    private fun authenticate(input: DataInputStream, output: DataOutputStream): Boolean {
        if (input.readUnsignedByte() != 5) return false
        val count = input.readUnsignedByte()
        if (count == 0) return false
        val methods = ByteArray(count).also(input::readFully)
        val selected = if (methods.any { it.toInt() and 255 == 2 }) 2 else 255
        output.write(byteArrayOf(5, selected.toByte())); output.flush()
        if (selected != 2) return false
        if (input.readUnsignedByte() != 1) { output.write(byteArrayOf(1, 1)); output.flush(); return false }
        val user = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val pass = ByteArray(input.readUnsignedByte()).also(input::readFully)
        try {
            val matchesUser = MessageDigest.isEqual(user, usernameBytes)
            val matchesPassword = MessageDigest.isEqual(pass, passwordBytes)
            val ok = user.isNotEmpty() && pass.isNotEmpty() && (matchesUser and matchesPassword)
            output.write(byteArrayOf(1, if (ok) 0 else 1)); output.flush()
            return ok
        } finally { user.fill(0); pass.fill(0) }
    }

    private fun readTarget(input: DataInputStream): Target {
        val host = when (input.readUnsignedByte()) {
            1 -> Host.Raw(InetAddress.getByAddress(ByteArray(4).also(input::readFully)))
            4 -> Host.Raw(InetAddress.getByAddress(ByteArray(16).also(input::readFully)))
            3 -> {
                val size = input.readUnsignedByte()
                if (size == 0) throw ReplyFailure(8)
                val bytes = ByteArray(size).also(input::readFully)
                if (bytes.any { it.toInt() and 255 !in 33..126 }) throw ReplyFailure(8)
                val name = String(bytes, StandardCharsets.US_ASCII)
                if (name.any { it == '/' || it == ':' || it == '\\' }) throw ReplyFailure(8)
                Host.Domain(name)
            }
            else -> throw ReplyFailure(8)
        }
        return Target(host, input.readUnsignedShort())
    }

    private fun addresses(host: Host): Array<InetAddress> = when (host) {
        is Host.Raw -> arrayOf(host.address)
        is Host.Domain -> resolve(host.name).takeIf { it.isNotEmpty() }?.take(16)?.toTypedArray() ?: throw UnknownHostException()
    }

    private fun connect(client: Client, input: DataInputStream, output: DataOutputStream, target: Target) {
        if (target.port == 0) { reply(output, 1); return }
        var connected: Socket? = null
        var failure: Exception? = null
        try {
            for (address in addresses(target.host)) {
                if (closed.get()) throw IOException("Proxy closed")
                val socket = client.track(Socket(Proxy.NO_PROXY))
                try {
                    if (!protectTcp(socket)) throw ReplyFailure(2)
                    socket.connect(InetSocketAddress(address, target.port), CONNECT_MS)
                    socket.soTimeout = TCP_IDLE_MS
                    socket.tcpNoDelay = true
                    connected = socket
                    break
                } catch (error: Exception) {
                    socket.close(); failure = error
                    if (error is ReplyFailure) throw error
                }
            }
            val remote = connected ?: throw (failure ?: UnknownHostException())
            client.control.soTimeout = TCP_IDLE_MS
            reply(output, 0, remote.localAddress, remote.localPort)
            val reverse = workers.submit {
                try {
                    copy(remote.getInputStream(), client.control.getOutputStream())
                    runCatching { client.control.shutdownOutput() }
                } catch (error: Exception) { client.close(); throw error }
            }
            copy(input, remote.getOutputStream())
            runCatching { remote.shutdownOutput() }
            reverse.get()
        } catch (error: Exception) {
            if (connected == null) runCatching {
                reply(output, when (error) {
                    is ReplyFailure -> error.code
                    is ConnectException -> 5
                    is NoRouteToHostException -> 3
                    is UnknownHostException, is SocketTimeoutException -> 4
                    is SecurityException -> 2
                    else -> 1
                })
            }
        }
    }

    private fun copy(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (!closed.get()) {
            val size = input.read(buffer)
            if (size < 0) return
            if (size > 0) { output.write(buffer, 0, size); output.flush() }
        }
    }

    private fun associate(client: Client, input: DataInputStream, output: DataOutputStream, requested: Target) {
        val validAddresses = try { addresses(requested.host) } catch (_: Exception) { reply(output, 2); return }
        if (validAddresses.none { it.isAnyLocalAddress || it == client.control.inetAddress }) { reply(output, 2); return }
        val relay = client.track(DatagramSocket(InetSocketAddress(listenAddress, 0)))
        val outbound = client.track(DatagramSocket(null))
        if (!protectUdp(outbound)) { reply(output, 2); return }
        outbound.bind(InetSocketAddress(0))
        relay.soTimeout = UDP_POLL_MS
        outbound.soTimeout = UDP_POLL_MS
        relay.receiveBufferSize = 64 * 1024
        outbound.receiveBufferSize = 64 * 1024
        client.control.soTimeout = UDP_POLL_MS
        val lock = Any()
        var peer: InetSocketAddress? = null
        val allowed = LinkedHashMap<InetSocketAddress, Long>()
        val dns = LinkedHashMap<String, Pair<Long, Array<InetAddress>>>()
        val activity = AtomicLong(System.nanoTime())
        fun idle(): Boolean = closed.get() || System.nanoTime() - activity.get() > UDP_IDLE_NS
        fun udpLoop(action: () -> Unit) = workers.submit {
            try { action() } catch (_: Exception) { client.close() }
        }
        udpLoop {
            val buffer = ByteArray(65535)
            while (!idle()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { relay.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val sender = packet.socketAddress as InetSocketAddress
                if (sender.address != client.control.inetAddress || (requested.port != 0 && sender.port != requested.port)) continue
                if (synchronized(lock) { peer != null && peer != sender }) continue
                val frame = DataInputStream(java.io.ByteArrayInputStream(packet.data, packet.offset, packet.length))
                if (packet.length < 4 || frame.readUnsignedShort() != 0 || frame.readUnsignedByte() != 0) continue
                val target = try { readTarget(frame) } catch (_: Exception) { continue }
                if (target.port == 0 || frame.available() > MAX_UDP_PAYLOAD) continue
                synchronized(lock) { if (peer == null) peer = sender }
                val now = System.nanoTime()
                val resolved = when (val host = target.host) {
                    is Host.Raw -> arrayOf(host.address)
                    is Host.Domain -> {
                        val cached = dns[host.name]?.takeIf { now - it.first < DNS_TTL_NS }?.second
                        cached ?: try { addresses(host).also {
                            if (dns.size >= MAX_DNS) dns.remove(dns.keys.first())
                            dns[host.name] = now to it
                        } } catch (_: Exception) { continue }
                    }
                }
                val destination = InetSocketAddress(resolved.first(), target.port)
                val payload = ByteArray(frame.available()).also(frame::readFully)
                synchronized(lock) {
                    allowed.entries.removeAll { now - it.value > UDP_IDLE_NS }
                    if (allowed.size >= MAX_UDP_PEERS && destination !in allowed) allowed.remove(allowed.keys.first())
                    allowed[destination] = now
                }
                try { outbound.send(DatagramPacket(payload, payload.size, destination)); activity.set(now) }
                catch (_: IOException) { synchronized(lock) { allowed.remove(destination) } }
            }
        }
        udpLoop {
            val buffer = ByteArray(65535)
            while (!idle()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { outbound.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val sender = packet.socketAddress as InetSocketAddress
                val recipient = synchronized(lock) {
                    val sent = allowed[sender]
                    peer?.takeIf { sent != null && System.nanoTime() - sent < UDP_IDLE_NS }
                } ?: continue
                val bytes = java.io.ByteArrayOutputStream()
                DataOutputStream(bytes).apply {
                    writeShort(0); writeByte(0); writeAddress(packet.address); writeShort(packet.port)
                    write(packet.data, packet.offset, packet.length)
                }
                val response = bytes.toByteArray()
                if (response.size <= 65507) relay.send(DatagramPacket(response, response.size, recipient))
                activity.set(System.nanoTime())
            }
        }
        reply(output, 0, listenAddress, relay.localPort)
        while (!idle()) {
            try { input.read(); return }
            catch (_: SocketTimeoutException) { /* The TCP control socket owns this association. */ }
        }
    }

    private fun DataOutputStream.writeAddress(address: InetAddress) {
        writeByte(if (address is Inet4Address) 1 else 4)
        write(address.address)
    }
    private fun reply(output: DataOutputStream, code: Int, address: InetAddress = listenAddress, port: Int = 0) {
        output.write(byteArrayOf(5, code.toByte(), 0))
        output.writeAddress(address); output.writeShort(port); output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        clients.toList().forEach { it.close() }
        usernameBytes.fill(0); passwordBytes.fill(0)
        workers.shutdownNow()
        if (!Thread.currentThread().name.startsWith("Nexo-socks-")) {
            runCatching { acceptThread?.join(1000) }
            runCatching { workers.awaitTermination(1000, TimeUnit.MILLISECONDS) }
        }
    }

    private companion object {
        const val MAX_CLIENTS = 64
        const val HANDSHAKE_MS = 10_000
        const val CONNECT_MS = 5_000
        const val TCP_IDLE_MS = 120_000
        const val UDP_POLL_MS = 1_000
        const val UDP_IDLE_NS = 60_000_000_000L
        const val DNS_TTL_NS = 30_000_000_000L
        const val MAX_DNS = 64
        const val MAX_UDP_PEERS = 256
        const val MAX_UDP_PAYLOAD = 65_245
    }
}

