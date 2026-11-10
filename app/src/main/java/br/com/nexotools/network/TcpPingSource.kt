package br.com.nexotools.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import br.com.nexotools.domain.PingSample
import br.com.nexotools.domain.PingMath
import br.com.nexotools.domain.PingSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

@Singleton
class TcpPingSource @Inject constructor(@ApplicationContext private val context: Context) : PingSource {
    // At most one legacy DNS worker. Cancellation closes a TCP socket immediately; Android <29 DNS
    // has no cancellable public API, so its worker may outlive a timed-out request until the OS returns.
    private val dnsExecutor = Executors.newSingleThreadExecutor { job -> Thread(job, "nexo-dns").apply { isDaemon = true } }
    private val socketExecutor = Executors.newCachedThreadPool { job -> Thread(job, "nexo-tcp").apply { isDaemon = true } }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun run(host: String, port: Int, count: Int, timeoutMs: Int): Flow<PingSample> = flow {
        require(host.isNotBlank() && host.length <= 253 && !host.any { it.isWhitespace() || it == '/' || it == ':' }) { "Informe um nome de host ou IPv4 válido" }
        require(port in 1..65535 && count in 1..100 && timeoutMs in 100..30000) { "Parâmetros de teste inválidos" }
        val address = withTimeoutOrNull(timeoutMs.toLong()) { resolve(host.trim()) }
        repeat(count) { index ->
            coroutineContext.ensureActive()
            val latency = if (address == null || connectivity?.activeNetwork == null) null else
                withTimeoutOrNull(timeoutMs.toLong()) { connect(address, port, timeoutMs) }
            emit(PingSample(index + 1, latency))
            if (index < count - 1) delay(1000)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun resolve(host: String): InetAddress? = suspendCancellableCoroutine { continuation ->
        PingMath.ipv4Bytes(host)?.let {
            continuation.resume(InetAddress.getByAddress(it))
            return@suspendCancellableCoroutine
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            try {
                DnsResolver.getInstance().query(connectivity?.activeNetwork, host, DnsResolver.FLAG_EMPTY,
                    dnsExecutor, cancel, object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            if (continuation.isActive) continuation.resume(if (rcode == 0) answer.firstOrNull() else null)
                        }
                        override fun onError(error: DnsResolver.DnsException) { if (continuation.isActive) continuation.resume(null) }
                    })
            } catch (_: RuntimeException) { if (continuation.isActive) continuation.resume(null) }
        } else {
            val future = dnsExecutor.submit {
                val address = runCatching { InetAddress.getAllByName(host).firstOrNull() }.getOrNull()
                if (continuation.isActive) continuation.resume(address)
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    }
    private suspend fun connect(address: InetAddress, port: Int, timeoutMs: Int): Double? = suspendCancellableCoroutine { continuation ->
        val socket = Socket()
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        socketExecutor.execute {
            val result = try {
                socket.use {
                    val start = SystemClock.elapsedRealtimeNanos()
                    it.connect(InetSocketAddress(address, port), timeoutMs)
                    (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                }
            } catch (_: java.io.IOException) { null }
            catch (_: SecurityException) { null }
            if (continuation.isActive) continuation.resume(result)
        }
    }
}
