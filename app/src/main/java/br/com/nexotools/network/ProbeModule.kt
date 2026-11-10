package br.com.nexotools.network

import br.com.nexotools.domain.NetworkProbeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ProbeModule {
    @Binds abstract fun probes(source: AndroidSystemNetworkProbes): NetworkProbeSource
}
