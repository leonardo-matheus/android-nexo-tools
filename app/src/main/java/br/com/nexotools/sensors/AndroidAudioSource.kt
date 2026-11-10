package br.com.nexotools.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import br.com.nexotools.domain.AudioMath
import br.com.nexotools.domain.AudioReading
import br.com.nexotools.domain.AudioSource
import br.com.nexotools.domain.SourceUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

@Singleton
class AndroidAudioSource @Inject constructor(@ApplicationContext private val context: Context) : AudioSource {
    @SuppressLint("MissingPermission")
    override fun readings(offsetDb: Double?): Flow<AudioReading> = flow {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Permita o microfone para medir o ruído")
        }
        val rate = 48_000
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw SourceUnavailableException("Microfone PCM não disponível")
        val audioManager = context.getSystemService(AudioManager::class.java)
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val recorder = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, rate / 5 * 2))
            .build()
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw SourceUnavailableException("Não foi possível abrir o microfone")
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw SourceUnavailableException("O microfone está ocupado ou indisponível")
            val buffer = ShortArray(rate / 10)
            while (currentCoroutineContext().isActive) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) throw SourceUnavailableException("Falha de captura do microfone ($count)")
                if (count > 0) emit(AudioMath.reading(buffer, count, offsetDb))
            }
        } finally {
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)
}
