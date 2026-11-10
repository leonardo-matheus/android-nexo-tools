package br.com.nexotools

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import androidx.work.Configuration
import androidx.work.WorkManager
import br.com.nexotools.automation.NexoWorkerFactory
import br.com.nexotools.automation.AutomationRepository
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltAndroidApp
class NexoApplication : Application(),Configuration.Provider {
    @Inject lateinit var workerFactory:NexoWorkerFactory
    @Inject lateinit var automation:AutomationRepository
    override val workManagerConfiguration:Configuration get()=Configuration.Builder().setWorkerFactory(workerFactory).build()
    override fun onCreate(){super.onCreate();WorkManager.getInstance(this);CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{runCatching{automation.reconcile()}}}
}
