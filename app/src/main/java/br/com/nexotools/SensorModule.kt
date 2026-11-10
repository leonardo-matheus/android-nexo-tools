package br.com.nexotools

import br.com.nexotools.domain.*
import br.com.nexotools.sensors.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SensorModule {
    @Binds abstract fun orientation(source:AndroidOrientationSource):OrientationSource
    @Binds abstract fun audio(source:AndroidAudioSource):AudioSource
}
