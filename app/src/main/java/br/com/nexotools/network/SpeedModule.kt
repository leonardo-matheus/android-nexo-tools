package br.com.nexotools.network

import br.com.nexotools.domain.SpeedTestSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SpeedModule {
    @Binds abstract fun speed(source:NativeSpeedSource):SpeedTestSource
}
