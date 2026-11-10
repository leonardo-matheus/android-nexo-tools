package br.com.nexotools.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import br.com.nexotools.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class AndroidSystemNetworkProbes @Inject constructor(@ApplicationContext context: Context) : NetworkProbeSource {
    private val ownPackage = context.packageName
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val dnsExecutor = Executors.newSingleThreadExecutor { Thread(it, "nexo-probe-dns").apply { isDaemon = true } }
    private val sockets = Executors.newFixedThreadPool(ProbeInputs.CONCURRENCY) { Thread(it, "nexo-probe-socket").apply { isDaemon = true } }

    override fun scan(host: String, ports: List<Int>): Flow<PortProbeResult> = channelFlow {
        val capture = PacketCaptureStore.state.value
        if (capture.active && (capture.selectedApp == null || capture.selectedApp == ownPackage))
            throw ProbeFailure("Pare a captura ou capture outro app antes de testar portas. A VPN local pode responder ao TCP antes do destino.")
        val target = ProbeInputs.host(host)
        require(ports.size in 1..ProbeInputs.MAX_PORTS && ports.all { it in 1..65535 }) { "Informe até 256 portas válidas" }
        val address = lookup(target).first()
        val gate = Semaphore(ProbeInputs.CONCURRENCY)
        ports.distinct().sorted().forEach { port -> launch { gate.withPermit { send(connect(address, port)) } } }
    }

    override suspend fun dns(host: String): DnsProbeResult {
        val target = ProbeInputs.host(host); val start = System.nanoTime()
        val answers = lookup(target)
        return DnsProbeResult(answers.map { DnsAddress(if (it is Inet4Address) "A" else "AAAA", requireNotNull(it.hostAddress)) }, elapsed(start), target.literalBytes != null)
    }

    override suspend fun tls(host: String, port: Int): TlsProbeResult {
        val target = ProbeInputs.host(host)
        require(port in 1..65535) { "Porta TLS deve estar entre 1 e 65535" }
        return try {
            withTimeout(10_000) { handshake(target, port, lookup(target).first()) }
        } catch (_: TimeoutCancellationException) { throw ProbeFailure("TLS sem resposta no limite de 10 segundos") }
    }

    private suspend fun lookup(target: ProbeHost): List<InetAddress> = try {
        withTimeout(5_000) { resolve(target).distinctBy { it.hostAddress }.also { if (it.isEmpty()) throw ProbeFailure("DNS não encontrou endereços A ou AAAA") } }
    } catch (_: TimeoutCancellationException) { throw ProbeFailure("DNS do sistema não respondeu em 5 segundos") }

    private suspend fun resolve(target: ProbeHost): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        target.literalBytes?.let { bytes ->
            try {
                val address = if (target.scope == null) InetAddress.getByAddress(bytes) else {
                    val number = target.scope.toIntOrNull()
                    if (number != null) Inet6Address.getByAddress(null, bytes, number)
                    else Inet6Address.getByAddress(null, bytes, requireNotNull(NetworkInterface.getByName(target.scope)) { "Interface IPv6 não disponível" })
                }
                continuation.resume(listOf(address))
            } catch (_: Exception) { continuation.resumeWithException(ProbeFailure("Endereço ou escopo IP não disponível")) }
            return@suspendCancellableCoroutine
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                DnsResolver.getInstance().query(connectivity?.activeNetwork, target.value, DnsResolver.FLAG_EMPTY, dnsExecutor, signal,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            if (continuation.isActive) {
                                if (rcode == 0) continuation.resume(answer)
                                else continuation.resumeWithException(ProbeFailure("DNS do sistema não encontrou o host"))
                            }
                        }
                        override fun onError(error: DnsResolver.DnsException) {
                            if (continuation.isActive) continuation.resumeWithException(ProbeFailure("Falha na resolução DNS do sistema"))
                        }
                    })
            } catch (_: RuntimeException) {
                if (continuation.isActive) continuation.resumeWithException(ProbeFailure("DNS do sistema indisponível"))
            }
        } else {
            // Android 26–28 cannot interrupt native DNS; one bounded worker ignores late answers.
            val future = dnsExecutor.submit {
                try {
                    val answer = InetAddress.getAllByName(target.value).toList()
                    if (continuation.isActive) continuation.resume(answer)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(ProbeFailure("Falha na resolução DNS do sistema"))
                }
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    }

    private suspend fun connect(address: InetAddress, port: Int): PortProbeResult = suspendCancellableCoroutine { continuation ->
        val socket = Socket()
        val future = sockets.submit {
            if (!continuation.isActive) { socket.close(); return@submit }
            val start = System.nanoTime()
            val result = try {
                socket.connect(InetSocketAddress(address, port), ProbeInputs.CONNECT_TIMEOUT_MS)
                PortProbeResult(port, requireNotNull(address.hostAddress), PortProbeStatus.OPEN, elapsed(start))
            } catch (error: IOException) {
                val causes = generateSequence<Throwable>(error) { it.cause }.take(8).map { it.message.orEmpty().lowercase(Locale.ROOT) }.toList()
                val refused = causes.any { "econnrefused" in it || "connection refused" in it }
                val timeout = error is SocketTimeoutException || causes.any { "etimedout" in it || "timed out" in it }
                val unreachable = causes.any { "enetunreach" in it || "ehostunreach" in it || "unreachable" in it || "no route" in it }
                val status = when { refused -> PortProbeStatus.REFUSED; timeout || unreachable -> PortProbeStatus.NO_RESPONSE; else -> PortProbeStatus.ERROR }
                val detail = when { timeout -> "Sem resposta em 1 segundo"; unreachable -> "Rede ou destino inacessível"; refused -> null; else -> "Não foi possível completar a conexão TCP" }
                PortProbeResult(port, requireNotNull(address.hostAddress), status, elapsed(start), detail)
            } catch (_: SecurityException) {
                PortProbeResult(port, requireNotNull(address.hostAddress), PortProbeStatus.ERROR, elapsed(start), "Android não permitiu a conexão")
            } finally { runCatching { socket.close() } }
            if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation { runCatching { socket.close() }; future.cancel(true) }
    }

    private suspend fun handshake(target: ProbeHost, port: Int, address: InetAddress): TlsProbeResult = suspendCancellableCoroutine { continuation ->
        val raw = Socket(); val wrapped = AtomicReference<SSLSocket?>()
        val future = sockets.submit {
            if (!continuation.isActive) { raw.close(); return@submit }
            val start = System.nanoTime()
            try {
                raw.connect(InetSocketAddress(address, port), 3_000)
                if (!continuation.isActive) return@submit
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, target.tlsPeerName, port, true) as SSLSocket
                wrapped.set(ssl)
                if (!continuation.isActive) return@submit
                ssl.useClientMode = true; ssl.soTimeout = 5_000
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = if (target.literalBytes == null) listOf(SNIHostName(target.tlsPeerName)) else emptyList()
                }
                ssl.startHandshake()
                val chain = ssl.session.peerCertificates.map { certificate ->
                    val x509 = certificate as? X509Certificate ?: throw CertificateException()
                    x509.checkValidity(Date())
                    TlsCertificate(x509.subjectX500Principal.name, x509.issuerX500Principal.name, x509.notBefore.time, x509.notAfter.time)
                }
                if (chain.isEmpty()) throw SSLPeerUnverifiedException("Sem certificado")
                val result = TlsProbeResult(requireNotNull(address.hostAddress), ssl.session.protocol, ssl.session.cipherSuite, chain, elapsed(start))
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                val message = when (error) {
                    is SSLHandshakeException, is SSLPeerUnverifiedException, is CertificateException -> "TLS inválido: certificado, validade ou nome do host rejeitado"
                    is SocketTimeoutException -> "TLS sem resposta no limite de tempo"
                    is SSLException -> "O destino rejeitou a negociação TLS"
                    else -> "Não foi possível conectar ao serviço TLS"
                }
                if (continuation.isActive) continuation.resumeWithException(ProbeFailure(message))
            } finally { runCatching { wrapped.get()?.close() }; runCatching { raw.close() } }
        }
        continuation.invokeOnCancellation { runCatching { raw.close() }; runCatching { wrapped.get()?.close() }; future.cancel(true) }
    }

    private fun elapsed(start: Long): Long = (System.nanoTime() - start) / 1_000_000
}
