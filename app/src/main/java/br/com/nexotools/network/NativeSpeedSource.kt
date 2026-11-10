package br.com.nexotools.network

import br.com.nexotools.domain.*
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

internal data class SpeedTimings(val targetMs: Long = 12_000, val hardPhaseMs: Long = 15_000,
    val totalMs: Long = 55_000, val progressMs: Long = 250, val loadedProbeMs: Long = 1_000)

/** Public Cloudflare measurement protocol only; no result telemetry, metadata or private APIs. */
@Singleton
class NativeSpeedSource private constructor(private val client: OkHttpClient, private val origin: HttpUrl, private val timings: SpeedTimings) : SpeedTestSource {
    @Inject constructor() : this(defaultClient(), "https://speed.cloudflare.com/".toHttpUrl(), SpeedTimings())

    override fun run(config: SpeedConfig): Flow<SpeedState> = channelFlow {
        val testStart = System.nanoTime()
        var state = SpeedState(stage = SpeedStage.WARMUP)
        suspend fun publish(next: SpeedState) {
            state = next.copy(elapsedMs = (System.nanoTime() - testStart) / 1_000_000)
            send(state)
        }
        try {
            withTimeout(timings.totalMs) {
                publish(state)
                repeat(2) { probe() } // connection warm-up, deliberately excluded from statistics
                publish(state.copy(stage = SpeedStage.LATENCY))
                val idle = mutableListOf<Double?>()
                repeat(6) {
                    idle.add(probe())
                    publish(state.copy(unloaded = SpeedMath.latency(idle)))
                }
                suspend fun perform(stage: SpeedStage, limit: Long) {
                    publish(state.copy(stage = stage, currentMbps = null, phaseProgress = 0f))
                    val outcome = transfer(stage == SpeedStage.DOWNLOAD, limit, config.measureLoadedLatency) { sample ->
                        val point = sample.current?.let { SpeedPoint((System.nanoTime() - testStart) / 1_000_000, it, stage) }
                        val trace = (state.trace + listOfNotNull(point)).takeLast(240)
                        publish(if (stage == SpeedStage.DOWNLOAD) state.copy(currentMbps = sample.current,
                            downloadMbps = sample.average, downloadBytes = sample.bytes, downLoaded = SpeedMath.latency(sample.latency),
                            phaseProgress = sample.progress, trace = trace)
                        else state.copy(currentMbps = sample.current, uploadMbps = sample.average, uploadBytes = sample.bytes,
                            upLoaded = SpeedMath.latency(sample.latency), phaseProgress = sample.progress, trace = trace))
                    }
                    publish(if (stage == SpeedStage.DOWNLOAD) state.copy(downloadMbps = outcome.average, downloadBytes = outcome.bytes,
                        downLoaded = SpeedMath.latency(outcome.latency), shortSample = state.shortSample || outcome.shortSample, phaseProgress = 1f)
                    else state.copy(uploadMbps = outcome.average, uploadBytes = outcome.bytes, upLoaded = SpeedMath.latency(outcome.latency),
                        shortSample = state.shortSample || outcome.shortSample, phaseProgress = 1f))
                }
                perform(SpeedStage.DOWNLOAD, config.profile.downloadLimit)
                perform(SpeedStage.UPLOAD, config.profile.uploadLimit)
                publish(state.copy(stage = SpeedStage.COMPLETE, currentMbps = null,
                    message = if (state.shortSample) "Amostra curta: o limite de dados foi atingido antes de estabilizar por 3 segundos." else "Teste concluído"))
            }
        } catch (_: TimeoutCancellationException) {
            publish(state.copy(stage = SpeedStage.ERROR, currentMbps = null, message = "Teste interrompido no limite de 55 segundos. Resultados parciais não são finais."))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            publish(state.copy(stage = SpeedStage.ERROR, currentMbps = null, message = "Não foi possível medir com o servidor. Confira sua conexão e tente novamente; dados ausentes não são estimados."))
        }
    }

    private suspend fun probe(): Double? = try {
        val start = System.nanoTime()
        val request = request("__down", 0).get().build()
        exchange(client.newCall(request)) { response ->
            check(response.code == 200)
            (System.nanoTime() - start) / 1_000_000.0
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private data class PhaseSample(val bytes: Long, val average: Double?, val current: Double?, val latency: List<Double?>, val progress: Float)
    private data class PhaseOutcome(val bytes: Long, val average: Double, val latency: List<Double?>, val shortSample: Boolean)

    private suspend fun transfer(down: Boolean, limit: Long, loadedLatency: Boolean, progress: suspend (PhaseSample) -> Unit): PhaseOutcome = coroutineScope {
        val start = System.nanoTime()
        val bytes = AtomicLong(0)
        val firstData = AtomicLong(0)
        val lastData = AtomicLong(0)
        val budget = TransferBudget(limit)
        val loaded = CopyOnWriteArrayList<Double?>()
        val errors = AtomicLong(0)
        val acknowledgedUpload = AtomicLong(0)
        val recording = AtomicBoolean(true)
        fun recorded(amount: Int) {
            if (!recording.get()) return
            val now = System.nanoTime()
            firstData.compareAndSet(0, now)
            bytes.addAndGet(amount.toLong())
            lastData.updateAndGet { previous -> maxOf(previous, now) }
        }
        val workers = List(3) {
            launch {
                var chunk = 125_000L
                var previousBytes = 0L
                var previousRequestNanos = 0L
                while (isActive) {
                    val remaining = timings.targetMs * 1_000_000 - (System.nanoTime() - start)
                    if (remaining <= 0) break
                    if (previousBytes > 0) {
                        chunk = SpeedMath.nextChunkBytes(previousBytes, previousRequestNanos, remaining)
                        if (chunk == 0L) break
                    }
                    val reserved = budget.claim(chunk)
                    if (reserved == 0L) break
                    val activeJob = currentCoroutineContext()[Job]!!
                    val builder = request(if (down) "__down" else "__up", reserved)
                    val outgoing = if (down) builder.get().build() else builder.post(object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaType()
                        override fun contentLength() = reserved
                        override fun writeTo(sink: BufferedSink) {
                            val buffer = ByteArray(32 * 1024) // synthetic upload bytes, never user data
                            var sent = 0L
                            while (sent < reserved) {
                                if (!activeJob.isActive) throw IOException("Canceled")
                                val amount = minOf(buffer.size.toLong(), reserved - sent).toInt()
                                sink.write(buffer, 0, amount)
                                recorded(amount)
                                sent += amount
                            }
                        }
                    }).build()
                    val requestStart = System.nanoTime()
                    try {
                        exchange(client.newCall(outgoing)) { response ->
                            if (response.code != 200) throw ProtocolException("Measurement endpoint rejected the request")
                            if (down) {
                                val declared = response.body.contentLength()
                                if (declared >= 0 && declared != reserved) throw ProtocolException("Wrong response size")
                                response.body.byteStream().use { input ->
                                    val buffer = ByteArray(32 * 1024)
                                    var received = 0L
                                    while (received < reserved) {
                                        if (!activeJob.isActive) throw IOException("Canceled")
                                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), reserved - received).toInt())
                                        if (count < 0) throw ProtocolException("Incomplete response")
                                        if (count > 0) { recorded(count); received += count }
                                    }
                                }
                            }
                            Unit
                        }
                        if (!down) acknowledgedUpload.addAndGet(reserved)
                        previousBytes = reserved
                        previousRequestNanos = (System.nanoTime() - requestStart).coerceAtLeast(1)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { errors.incrementAndGet(); break }
                }
            }
        }
        val timer = launch { delay(timings.targetMs); recording.set(false); workers.forEach { it.cancel() } }
        val latency = if (loadedLatency) launch {
            delay(timings.loadedProbeMs)
            while (isActive) { loaded.add(probe()); delay(timings.loadedProbeMs) }
        } else null
        val publisher = launch {
            var previous = 0L
            var previousTime = start
            while (isActive) {
                delay(timings.progressMs)
                val now = System.nanoTime()
                val count = bytes.get()
                val average = if (count > 0) SpeedMath.mbps(count, now - start) else null
                val current = if (count > 0) SpeedMath.mbps(count - previous, now - previousTime) else null
                val fraction = maxOf((now - start) / (timings.targetMs * 1_000_000.0), count.toDouble() / limit).coerceIn(0.0, 1.0).toFloat()
                progress(PhaseSample(count, average, current, loaded.toList(), fraction))
                previous = count; previousTime = now
            }
        }
        try { withTimeout(timings.hardPhaseMs) { workers.joinAll() } }
        finally {
            recording.set(false)
            workers.forEach { it.cancel() }
            timer.cancel()
            latency?.cancel()
            publisher.cancel()
            withContext(NonCancellable) { workers.joinAll(); timer.join(); latency?.join(); publisher.join() }
        }
        val count = bytes.get()
        // Include response/acknowledgement time, not only writes accepted by socket buffers.
        val end = System.nanoTime()
        val measured = if (down) count else acknowledgedUpload.get()
        if (measured <= 0 || end <= start || errors.get() > 0) throw IOException("No complete measurement window")
        val average = SpeedMath.mbps(measured, end - start) ?: throw IOException("Invalid measurement window")
        PhaseOutcome(count, average, loaded.toList(), lastData.get() - firstData.get() < 3_000_000_000L)
    }

    private fun request(path: String, bytes: Long): Request.Builder = Request.Builder()
        .url(origin.newBuilder().addPathSegment(path).addQueryParameter("bytes", bytes.toString()).build())
        .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache, no-store")
        .header("User-Agent", "NEXO-Tools/1.2.0 NativeSpeed")

    /** Continuation stays cancellable through body reads/writes; cancel closes the underlying call. */
    private suspend fun <T> exchange(call: Call, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try { response.use { val result = consume(it); if (continuation.isActive) continuation.resume(result) } }
                catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        })
    }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).cache(null)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        /** Not exposed by production UI; local tests replace the origin without internet traffic. */
        internal fun forTest(client: OkHttpClient, origin: HttpUrl, timings: SpeedTimings) = NativeSpeedSource(client, origin, timings)
    }
}
