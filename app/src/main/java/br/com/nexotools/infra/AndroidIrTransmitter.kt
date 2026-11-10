package br.com.nexotools.infra

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.SystemClock
import br.com.nexotools.domain.IrFrequencyRange
import br.com.nexotools.domain.IrHardwareStatus
import br.com.nexotools.domain.IrProfiles
import br.com.nexotools.domain.IrTransmitter
import br.com.nexotools.domain.IrUnavailableException
import br.com.nexotools.domain.NecEncoder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Singleton
class AndroidIrTransmitter @Inject constructor(@ApplicationContext private val context: Context) : IrTransmitter {
    private val manager: ConsumerIrManager? get() = context.getSystemService(ConsumerIrManager::class.java)
    private val mutex = Mutex()
    private var lastTransmission = 0L

    override fun status(): IrHardwareStatus = try {
        val service = manager
        if (service == null || !service.hasIrEmitter()) IrHardwareStatus(false, message = "Este aparelho não possui emissor infravermelho")
        else {
            val ranges = service.carrierFrequencies?.map { IrFrequencyRange(it.minFrequency, it.maxFrequency) }.orEmpty()
            val supported = ranges.any { it.contains(NecEncoder.CARRIER_HZ) }
            IrHardwareStatus(true, ranges, when {
                ranges.isEmpty() -> "Faixas do emissor não disponíveis; transmissão desativada"
                !supported -> "O emissor não informa suporte a 38 kHz"
                else -> "Emissor IR disponível · 38 kHz"
            })
        }
    } catch (_: SecurityException) { IrHardwareStatus(false, message = "O Android não autorizou o acesso ao emissor IR") }
    catch (_: RuntimeException) { IrHardwareStatus(false, message = "Não foi possível consultar o emissor IR") }

    override suspend fun send(profileId: String, commandId: String) {
        val profile = requireNotNull(IrProfiles.byId[profileId]) { "Perfil IR não reconhecido" }
        transmit(profile.frame(commandId))
    }
    override suspend fun sendNec(address: Int, command: Int, extendedAddress: Boolean) {
        transmit(NecEncoder.frame(address, command, extendedAddress))
    }
    private suspend fun transmit(frame: Long) = withContext(Dispatchers.IO) {
        val pattern = NecEncoder.pattern(frame)
        mutex.withLock {
            val hardware = status()
            if (!hardware.supports(NecEncoder.CARRIER_HZ)) throw IrUnavailableException(hardware.message)
            val cooldown = 100L - (SystemClock.elapsedRealtime() - lastTransmission)
            if (cooldown > 0) delay(cooldown)
            coroutineContext.ensureActive()
            val service = manager ?: throw IrUnavailableException("Emissor IR não disponível")
            // ConsumerIrManager returns after the envelope is sent; there is no receiver ACK.
            service.transmit(NecEncoder.CARRIER_HZ, pattern)
            lastTransmission = SystemClock.elapsedRealtime()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class IrModule {
    @Binds abstract fun transmitter(source: AndroidIrTransmitter): IrTransmitter
}
