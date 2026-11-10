package br.com.nexotools.media

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import br.com.nexotools.domain.AudioGainMath
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioBoostState(
    val fileName: String? = null,
    val loading: Boolean = false,
    val ready: Boolean = false,
    val playing: Boolean = false,
    val durationMs: Int = 0,
    val positionMs: Int = 0,
    val gainDb: Float = 0f,
    val effectAvailable: Boolean = false,
    val effectError: String? = null,
    val error: String? = null,
)

/** One local file/player session. It never attaches an effect to the global session (0). */
class AudioBoostPlayer(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(AudioBoostState())
    val state = mutableState.asStateFlow()
    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var loadingJob: Job? = null
    private var positionJob: Job? = null
    private var closed = false
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var focusHeld = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause()
        }, Handler(Looper.getMainLooper())).build()
    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    init {
        ContextCompat.registerReceiver(appContext, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
    }

    fun load(uri: Uri) {
        if (closed) return
        loadingJob?.cancel()
        releasePlayer()
        mutableState.value = AudioBoostState(loading = true)
        val candidate = try { MediaPlayer().apply { setAudioAttributes(audioAttributes) } }
        catch (_: Exception) { mutableState.value = AudioBoostState(error = "O aparelho não disponibilizou um player de áudio"); return }
        player = candidate
        loadingJob = scope.launch {
            try {
                val name = withTimeout(15_000) { withContext(Dispatchers.IO) {
                    appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } }
                if (player !== candidate) return@launch
                mutableState.value = mutableState.value.copy(fileName = name ?: "Arquivo de áudio selecionado")
                withTimeout(15_000) { withContext(Dispatchers.IO) {
                    val descriptor = appContext.contentResolver.openAssetFileDescriptor(uri, "r") ?: error("Arquivo indisponível")
                    descriptor.use { file ->
                        withContext(Dispatchers.Main.immediate) {
                            ensureActive()
                            if (player !== candidate) throw CancellationException("Arquivo substituído")
                            if (file.declaredLength < 0) candidate.setDataSource(file.fileDescriptor)
                            else candidate.setDataSource(file.fileDescriptor, file.startOffset, file.declaredLength)
                        }
                    }
                } }
                withTimeout(20_000) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        candidate.setOnPreparedListener { if (continuation.isActive) continuation.resume(Unit) }
                        candidate.setOnErrorListener { _, _, _ ->
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Não foi possível decodificar este áudio"))
                            else if (player === candidate) fail("A reprodução foi interrompida pelo aparelho")
                            true
                        }
                        candidate.prepareAsync()
                    }
                }
                ensureActive()
                if (player !== candidate) return@launch
                candidate.setOnCompletionListener {
                    if (player === candidate) {
                        positionJob?.cancel(); abandonFocus()
                        mutableState.value = mutableState.value.copy(playing = false, positionMs = mutableState.value.durationMs)
                    }
                }
                attachEnhancer(candidate.audioSessionId)
                mutableState.value = mutableState.value.copy(loading = false, ready = true, durationMs = candidate.duration.coerceAtLeast(0), error = null)
            } catch (_: TimeoutCancellationException) {
                if (player === candidate) fail("O áudio não ficou pronto no prazo. Tente outro arquivo local.")
            } catch (cancelled: CancellationException) {
                if (player === candidate) releasePlayer()
                throw cancelled
            } catch (_: Exception) {
                if (player === candidate) fail("Não foi possível abrir este áudio. Selecione um arquivo local compatível e acessível.")
            }
        }
    }

    private fun attachEnhancer(session: Int) {
        if (session <= 0) {
            mutableState.value = mutableState.value.copy(effectError = "Sessão de áudio não permite ganho", effectAvailable = false)
            return
        }
        try {
            val effect = LoudnessEnhancer(session)
            enhancer = effect
            effect.setTargetGain(0)
            check(effect.getTargetGain() == 0f)
            check(effect.setEnabled(true) == AudioEffect.SUCCESS)
            mutableState.value = mutableState.value.copy(effectAvailable = true, effectError = null)
        } catch (_: Exception) {
            runCatching { enhancer?.release() }
            enhancer = null
            mutableState.value = mutableState.value.copy(effectAvailable = false, gainDb = 0f,
                effectError = "LoudnessEnhancer não está disponível neste aparelho. A reprodução continua sem ganho.")
        }
    }

    fun setGain(value: Float) {
        if (closed || !mutableState.value.effectAvailable) return
        val gain = AudioGainMath.clampDb(value)
        try {
            val effect = enhancer ?: return
            val target = AudioGainMath.milliBels(gain)
            effect.setTargetGain(target)
            check(kotlin.math.abs(effect.getTargetGain() - target) < 0.5f)
            mutableState.value = mutableState.value.copy(gainDb = gain)
        } catch (_: Exception) {
            runCatching { enhancer?.release() }
            enhancer = null
            mutableState.value = mutableState.value.copy(effectAvailable = false, gainDb = 0f, effectError = "O aparelho deixou de disponibilizar o efeito de ganho")
        }
    }

    fun togglePlayback() {
        val current = player ?: return
        if (!mutableState.value.ready) return
        try {
            if (mutableState.value.playing) pause() else {
                if (!focusHeld) {
                    focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (!focusHeld) { mutableState.value = mutableState.value.copy(error = "Outro áudio impede a reprodução agora. Tente novamente quando estiver disponível."); return }
                }
                if (mutableState.value.positionMs >= mutableState.value.durationMs) current.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
                current.start()
                mutableState.value = mutableState.value.copy(playing = true, error = null)
                positionJob?.cancel()
                positionJob = scope.launch {
                    while (isActive && mutableState.value.playing) {
                        delay(250)
                        player?.let { active -> runCatching { active.currentPosition }.onSuccess { position ->
                            mutableState.value = mutableState.value.copy(positionMs = position)
                        } }
                    }
                }
            }
        } catch (_: Exception) { fail("Não foi possível reproduzir este áudio") }
    }

    fun seek(position: Int) {
        if (!mutableState.value.ready) return
        try {
            val target = position.coerceIn(0, mutableState.value.durationMs)
            player?.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
            mutableState.value = mutableState.value.copy(positionMs = target)
        } catch (_: Exception) { fail("Não foi possível avançar neste áudio") }
    }

    fun pause() {
        positionJob?.cancel()
        if (mutableState.value.playing) try { player?.pause() } catch (_: Exception) { fail("A reprodução foi encerrada porque o aparelho não aceitou a pausa"); return }
        mutableState.value = mutableState.value.copy(playing = false)
        abandonFocus()
    }

    fun pauseForBackground() {
        pause()
        if (mutableState.value.loading) {
            loadingJob?.cancel()
            releasePlayer()
            mutableState.value = mutableState.value.copy(loading = false, ready = false, error = "Preparação interrompida ao sair da tela. Selecione o áudio novamente.")
        }
    }

    fun reset() { pause(); seek(0); setGain(0f) }

    private fun fail(message: String) {
        releasePlayer()
        mutableState.value = mutableState.value.copy(loading = false, ready = false, playing = false, error = message, effectAvailable = false, gainDb = 0f)
    }
    private fun releasePlayer() {
        positionJob?.cancel()
        abandonFocus()
        runCatching { enhancer?.release() }; enhancer = null
        runCatching { player?.release() }; player = null
    }
    override fun close() {
        closed = true
        scope.cancel()
        releasePlayer()
        if (noisyRegistered) { runCatching { appContext.unregisterReceiver(noisyReceiver) }; noisyRegistered = false }
    }
    private fun abandonFocus() {
        if (focusHeld) runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        focusHeld = false
    }
}
