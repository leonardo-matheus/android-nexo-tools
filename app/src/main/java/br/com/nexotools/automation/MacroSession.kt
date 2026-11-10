package br.com.nexotools.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/** One user-started run across both private notification and launcher activities. */
object MacroSession {
    private var job:Job?=null
    val progress=MutableStateFlow<String?>(null)
    @Synchronized fun isRunning()=job?.isCompleted==false
    @Synchronized fun register(candidate:Job):Boolean {
        if(job?.isCompleted==false && job!==candidate)return false
        job=candidate;return true
    }
    @Synchronized fun clear(candidate:Job){if(job===candidate){job=null;progress.value=null}}
    @Synchronized fun cancel(){job?.cancel()}
}
class MacroStopReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){MacroSession.cancel()}}
