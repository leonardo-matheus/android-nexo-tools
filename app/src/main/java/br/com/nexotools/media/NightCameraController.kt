package br.com.nexotools.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Range
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import br.com.nexotools.domain.LuminanceFrame
import br.com.nexotools.domain.NightLuminanceMath
import br.com.nexotools.domain.NightTint
import com.google.common.util.concurrent.ListenableFuture
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NightCameraState(
    val loading: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val lowLightAvailable: Boolean = false,
    val lowLightEnabled: Boolean = false,
    val lowLightStatus: String = "Não disponível",
    val exposureMin: Int = 0,
    val exposureMax: Int = 0,
    val exposureIndex: Int = 0,
    val exposureStepEv: Double = 0.0,
    val frontAvailable: Boolean = false,
    val backAvailable: Boolean = false,
    val controlsBusy: Boolean = false,
    val controlError: String? = null,
)

/** Holds and unbinds only this tool's Preview/ImageAnalysis use cases. */
class NightCameraController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "NexoLuminance") }
    private val mutableState = MutableStateFlow(NightCameraState())
    val state = mutableState.asStateFlow()
    private val mutableFrame = MutableStateFlow<Bitmap?>(null)
    val frame = mutableFrame.asStateFlow()
    private val lookup = AtomicReference(NightLuminanceMath.lookup(1f, NightTint.GREEN))
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var bindJob: Job? = null
    private var controlJob: Job? = null
    private var timeoutJob: Job? = null
    private var torchObserver: Observer<Int>? = null
    private var lowLightObserver: Observer<Int>? = null
    private var cameraObserver: Observer<CameraState>? = null
    private var closed = false
    @Volatile private var generation = 0

    fun setProcessing(boost: Float, tint: NightTint) { lookup.set(NightLuminanceMath.lookup(boost, tint)) }

    fun bind(owner: LifecycleOwner, view: PreviewView, lensFacing: Int, rotation: Int = 0) {
        if (closed) return
        bindJob?.cancel()
        controlJob?.cancel()
        timeoutJob?.cancel()
        releaseUseCases()
        mutableFrame.value = null
        mutableState.value = NightCameraState(loading = true)
        val token = ++generation
        bindJob = scope.launch {
            try {
                check(appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                val cameraProvider = withTimeout(15_000) { ProcessCameraProvider.getInstance(appContext).awaitCameraFuture() }
                ensureActive()
                if (closed || token != generation) return@launch
                provider = cameraProvider
                val back = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                val front = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                mutableState.value = mutableState.value.copy(frontAvailable = front, backAvailable = back)
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                check(cameraProvider.hasCamera(selector)) { "Câmera selecionada não disponível" }
                val ownPreview = Preview.Builder().setTargetRotation(rotation).setTargetFrameRate(Range(15, 30)).build()
                ownPreview.setSurfaceProvider(view.surfaceProvider)
                val resolution = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER),
                ).build()
                val ownAnalysis = ImageAnalysis.Builder().setResolutionSelector(resolution)
                    .setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build()
                var lastFrame = 0L
                ownAnalysis.setAnalyzer(executor) { image ->
                    try {
                        if (closed || token != generation) return@setAnalyzer
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (now - lastFrame < 100_000_000L) return@setAnalyzer
                        lastFrame = now
                        val plane = image.planes.firstOrNull() ?: return@setAnalyzer
                        val buffer = plane.buffer.duplicate()
                        buffer.position(0)
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val crop = image.cropRect
                        val processed = NightLuminanceMath.render(LuminanceFrame(bytes, image.width, image.height,
                            plane.rowStride, plane.pixelStride, crop.left, crop.top, crop.width(), crop.height(),
                            image.imageInfo.rotationDegrees, lensFacing == CameraSelector.LENS_FACING_FRONT), lookup.get())
                        val bitmap = Bitmap.createBitmap(processed.pixels, processed.width, processed.height, Bitmap.Config.ARGB_8888)
                        scope.launch {
                            if (!closed && token == generation && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                mutableFrame.value = bitmap
                                mutableState.value = mutableState.value.copy(loading = false, ready = true, error = null)
                            }
                        }
                    } catch (_: Exception) {
                        scope.launch { if (!closed && token == generation) mutableState.value = mutableState.value.copy(error = "Não foi possível processar o quadro da câmera") }
                    } finally { image.close() }
                }
                preview = ownPreview
                analysis = ownAnalysis
                val bound = cameraProvider.bindToLifecycle(owner, selector, ownPreview, ownAnalysis)
                camera = bound
                val info = bound.cameraInfo
                val exposure = info.exposureState
                mutableState.value = mutableState.value.copy(frontAvailable = front, backAvailable = back,
                    torchAvailable = info.hasFlashUnit(), lowLightAvailable = info.isLowLightBoostSupported,
                    lowLightStatus = if (info.isLowLightBoostSupported) "Desligado" else "Hardware não oferece boost de baixa luz",
                    exposureMin = exposure.exposureCompensationRange.lower, exposureMax = exposure.exposureCompensationRange.upper,
                    exposureIndex = exposure.exposureCompensationIndex, exposureStepEv = exposure.exposureCompensationStep.toDouble())
                torchObserver = Observer<Int> { value ->
                    if (token == generation) mutableState.value = mutableState.value.copy(torchOn = value == TorchState.ON)
                }.also { info.torchState.observe(owner, it) }
                lowLightObserver = Observer<Int> { value ->
                    if (token == generation) mutableState.value = mutableState.value.copy(lowLightEnabled = value != LowLightBoostState.OFF, lowLightStatus = when (value) {
                        LowLightBoostState.ACTIVE -> "Ativo pelo hardware"
                        LowLightBoostState.INACTIVE -> "Armado · aguardando pouca luz"
                        else -> if (!mutableState.value.lowLightAvailable) "Hardware não oferece boost de baixa luz" else "Desligado"
                    })
                }.also { info.lowLightBoostState.observe(owner, it) }
                cameraObserver = Observer<CameraState> { value ->
                    if (token == generation && value.error != null) mutableState.value = mutableState.value.copy(loading = false, ready = false, error = "Câmera indisponível ou em uso por outro aplicativo")
                }.also { info.cameraState.observe(owner, it) }
                timeoutJob = scope.launch {
                    delay(10_000)
                    if (token == generation && mutableFrame.value == null && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        mutableState.value = mutableState.value.copy(loading = false, ready = false, error = "A câmera não enviou imagens. Confira o acesso no Android e tente novamente.")
                }
            } catch (_: TimeoutCancellationException) {
                if (token == generation) {
                    releaseUseCases()
                    mutableState.value = mutableState.value.copy(loading = false, ready = false, error = "A câmera não abriu no prazo. Tente novamente.")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == generation) {
                    releaseUseCases()
                    mutableState.value = mutableState.value.copy(loading = false, ready = false, error = "Não foi possível abrir essa câmera. Verifique a permissão e a disponibilidade do aparelho.")
                }
            }
        }
    }

    fun setTorch(enabled: Boolean) = control { current ->
        check(mutableState.value.torchAvailable)
        if (enabled && mutableState.value.lowLightEnabled) {
            current.cameraControl.enableLowLightBoostAsync(false).awaitCameraFuture()
            mutableState.value = mutableState.value.copy(lowLightEnabled = false)
        }
        current.cameraControl.enableTorch(enabled).awaitCameraFuture()
    }

    fun setLowLight(enabled: Boolean) = control { current ->
        check(current.cameraInfo.isLowLightBoostSupported)
        if (enabled && mutableState.value.torchOn) current.cameraControl.enableTorch(false).awaitCameraFuture()
        current.cameraControl.enableLowLightBoostAsync(enabled).awaitCameraFuture()
        mutableState.value = mutableState.value.copy(lowLightEnabled = enabled,
            lowLightStatus = if (!enabled) "Desligado" else mutableState.value.lowLightStatus)
    }

    fun setExposure(index: Int) = control { current ->
        val range = current.cameraInfo.exposureState.exposureCompensationRange
        val applied = current.cameraControl.setExposureCompensationIndex(index.coerceIn(range.lower, range.upper)).awaitCameraFuture()
        mutableState.value = mutableState.value.copy(exposureIndex = applied)
    }

    private fun control(action: suspend (Camera) -> Unit) {
        val current = camera ?: return
        if (closed || mutableState.value.controlsBusy) return
        controlJob = scope.launch {
            mutableState.value = mutableState.value.copy(controlsBusy = true, controlError = null)
            try { withTimeout(5_000) { action(current) } }
            catch (_: TimeoutCancellationException) { if (camera === current) mutableState.value = mutableState.value.copy(controlError = "O ajuste não foi confirmado no prazo pelo hardware") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (camera === current) mutableState.value = mutableState.value.copy(controlError = "O hardware não aceitou esse ajuste. O filtro de luminosidade continua disponível.") }
            finally { if (camera === current) mutableState.value = mutableState.value.copy(controlsBusy = false) }
        }
    }

    fun pauseForBackground() {
        controlJob?.cancel()
        camera?.let { current ->
            runCatching { current.cameraControl.enableTorch(false) }
            if (current.cameraInfo.isLowLightBoostSupported) runCatching { current.cameraControl.enableLowLightBoostAsync(false) }
        }
        unbind()
    }

    fun unbind() {
        generation++
        bindJob?.cancel(); controlJob?.cancel(); timeoutJob?.cancel()
        releaseUseCases()
        mutableFrame.value = null
        mutableState.value = NightCameraState()
    }

    private fun releaseUseCases() {
        camera?.cameraInfo?.let { info ->
            torchObserver?.let { info.torchState.removeObserver(it) }
            lowLightObserver?.let { info.lowLightBoostState.removeObserver(it) }
            cameraObserver?.let { info.cameraState.removeObserver(it) }
            runCatching { camera?.cameraControl?.enableTorch(false) }
        }
        torchObserver = null; lowLightObserver = null; cameraObserver = null
        analysis?.clearAnalyzer()
        val ownPreview = preview
        val ownAnalysis = analysis
        if (ownPreview != null && ownAnalysis != null) runCatching { provider?.unbind(ownPreview, ownAnalysis) }
        camera = null; preview = null; analysis = null
    }

    override fun close() {
        closed = true
        generation++
        scope.cancel()
        releaseUseCases()
        executor.shutdown()
        mutableFrame.value = null
    }

    private suspend fun <T> ListenableFuture<T>.awaitCameraFuture(): T = suspendCancellableCoroutine { continuation ->
        addListener({
            if (continuation.isActive) runCatching { get() }
                .onSuccess { continuation.resume(it) }.onFailure { continuation.resumeWithException(it) }
        }, mainExecutor)
        continuation.invokeOnCancellation { cancel(false) }
    }
}
