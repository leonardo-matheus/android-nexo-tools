package br.com.nexotools.network

import br.com.nexotools.domain.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NativeSpeedSourceTest {
    private fun client() = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS)
        .writeTimeout(1, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private fun release(client: OkHttpClient) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    private val timings = SpeedTimings(targetMs = 300, hardPhaseMs = 1_500, totalMs = 10_000, progressMs = 25, loadedProbeMs = 50)
    // Safety guards only; event-driven cases stop themselves without waiting for these durations.
    private val eventTimings = timings.copy(targetMs = 60_000, hardPhaseMs = 90_000, totalMs = 120_000)

    @Test fun localTransfersUseOnlyDocumentedPathsAndRespectReservedBudget() = runBlocking {
        val requestedDown = AtomicLong(0)
        val receivedUp = AtomicLong(0)
        val paths = CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths.add(request.url.encodedPath)
                    val size = request.url.queryParameter("bytes")!!.toInt()
                    return if (request.url.encodedPath == "/__down") {
                        requestedDown.addAndGet(size.toLong())
                        MockResponse.Builder().code(200).body(Buffer().write(ByteArray(size))).build()
                    } else {
                        assertEquals("/__up", request.url.encodedPath)
                        assertEquals(size.toLong(), request.bodySize)
                        receivedUp.addAndGet(request.bodySize)
                        MockResponse.Builder().code(200).body("").headersDelay(100, TimeUnit.MILLISECONDS).build()
                    }
                }
            }
            server.start()
            val client = client()
            try {
                val states = withTimeout(12_000) { NativeSpeedSource.forTest(client, server.url("/"), timings).run(SpeedConfig(SpeedProfile.ECONOMY, false)).toList() }
                val result = states.last()
                assertEquals(SpeedStage.COMPLETE, result.stage)
                assertTrue(result.downloadMbps!! > 0); assertTrue(result.uploadMbps!! > 0)
                assertTrue(result.uploadMbps!! <= SpeedProfile.ECONOMY.uploadLimit * 8.0 / 100_000.0)
                assertTrue(requestedDown.get() <= SpeedProfile.ECONOMY.downloadLimit)
                assertTrue(receivedUp.get() <= SpeedProfile.ECONOMY.uploadLimit)
                assertTrue(result.downloadBytes <= SpeedProfile.ECONOMY.downloadLimit)
                assertTrue(result.uploadBytes <= SpeedProfile.ECONOMY.uploadLimit)
                assertTrue(result.shortSample)
                assertTrue(paths.all { it == "/__down" || it == "/__up" })
            } finally { release(client) }
        }
    }

    @Test fun rejectedEndpointDoesNotInventThroughputOrCompletedResult() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(503).body("synthetic unavailable").build()
            }
            server.start()
            val client = client()
            try {
                val states = NativeSpeedSource.forTest(client, server.url("/"), timings).run(SpeedConfig(SpeedProfile.ECONOMY, false)).toList()
                assertEquals(SpeedStage.ERROR, states.last().stage)
                assertNull(states.last().downloadMbps); assertNull(states.last().uploadMbps)
                assertFalse(states.any { it.stage == SpeedStage.COMPLETE })
            } finally { release(client) }
        }
    }

    @Test fun cancellingDuringLocalBodyReadClosesCallsPromptly() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<Call>()
        val listener = object : EventListener() {
            override fun callStart(call: Call) {
                if (call.request().url.queryParameter("bytes")!!.toLong() > 0) calls.add(call)
            }
            override fun responseBodyStart(call: Call) {
                if (call.request().url.queryParameter("bytes")!!.toLong() > 0) reading.complete(Unit)
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val size = request.url.queryParameter("bytes")!!.toInt()
                    val response = MockResponse.Builder().code(200).body(Buffer().write(ByteArray(size)))
                    if (size > 0) response.throttleBody(1024, 1, TimeUnit.SECONDS)
                    return response.build()
                }
            }
            server.start()
            val client = client().newBuilder().eventListener(listener).connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
            val stages = CopyOnWriteArrayList<SpeedStage>()
            val job = launch {
                NativeSpeedSource.forTest(client, server.url("/"), eventTimings).run(SpeedConfig(SpeedProfile.ECONOMY, false)).collect { stages.add(it.stage) }
            }
            try {
                // Observe an actual body read, independently of UI/progress scheduling.
                withTimeout(30_000) { reading.await() }
                job.cancel()
                assertTrue(calls.isNotEmpty())
                assertTrue(calls.all { it.isCanceled() })
                withTimeout(30_000) { job.join() }
                assertFalse(stages.contains(SpeedStage.COMPLETE))
            } finally { job.cancel(); withContext(NonCancellable) { job.join() }; release(client) }
        }
    }

    @Test fun slowLocalUploadKeepsSmallConfirmedChunksInsteadOfDiscardingLargeBlocks() = runBlocking {
        val plans = CopyOnWriteArrayList<Long>()
        val adapted = CompletableDeferred<Unit>()
        val listener = object : EventListener() {
            override fun callStart(call: Call) {
                if (call.request().method == "POST") {
                    plans.add(call.request().url.queryParameter("bytes")!!.toLong())
                    if (plans.size > 3) adapted.complete(Unit)
                }
            }
        }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun peek() = MockResponse.Builder().throttleBody(16_384, 100, TimeUnit.MILLISECONDS).build()
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val size = request.url.queryParameter("bytes")!!.toInt()
                    return if (request.url.encodedPath == "/__down") MockResponse.Builder().code(200).body(Buffer().write(ByteArray(size))).build()
                    else MockResponse.Builder().code(200).body("").build()
                }
            }
            server.start()
            val client = client().newBuilder().eventListener(listener).connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
            val job = launch {
                NativeSpeedSource.forTest(client, server.url("/"), eventTimings).run(SpeedConfig(SpeedProfile.ECONOMY, false)).collect { }
            }
            try {
                // A fourth POST is necessarily adapted after one of the first three responses.
                withTimeout(30_000) { adapted.await() }
                job.cancel()
                withTimeout(30_000) { job.join() }
                assertTrue(plans.size > 3)
                assertEquals(listOf(125_000L, 125_000L, 125_000L), plans.take(3))
                assertTrue(plans.drop(3).all { it in 16_384L..512_000L })
                assertTrue(plans.sum() <= SpeedProfile.ECONOMY.uploadLimit)
            } finally { job.cancel(); withContext(NonCancellable) { job.join() }; release(client) }
        }
    }
}
