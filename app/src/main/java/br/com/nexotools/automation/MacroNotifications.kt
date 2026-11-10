package br.com.nexotools.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import br.com.nexotools.MainActivity
import br.com.nexotools.MacroEntryActivity
import br.com.nexotools.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class MacroNotifications @Inject constructor(@ApplicationContext private val context:Context) {
    fun allowed():Boolean=NotificationManagerCompat.from(context).areNotificationsEnabled() && context.getSystemService(NotificationManager::class.java).getNotificationChannel("nexo_tasks")?.importance!=NotificationManager.IMPORTANCE_NONE &&
        (Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
    fun post(id:Long,name:String,text:String):Boolean {
        if(!allowed()) return false
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("nexo_tasks","Tarefas e macros",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending=PendingIntent.getActivity(context,(id xor (id ushr 32)).toInt(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=NotificationCompat.Builder(context,"nexo_tasks").setSmallIcon(R.drawable.ic_wrench).setContentTitle("NEXO Tools").setContentText("Lembrete de tarefa").build()
        val notice=NotificationCompat.Builder(context,"nexo_tasks").setSmallIcon(R.drawable.ic_wrench).setContentTitle(name.take(64)).setContentText(text.take(120)).setAutoCancel(true).setContentIntent(pending).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        try { NotificationManagerCompat.from(context).notify((id xor (id ushr 32)).toInt(),notice) } catch(_:SecurityException){return false}
        return true
    }
    fun deferred(id:Long,token:String,name:String):Boolean {
        if(!allowed())return false
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("nexo_tasks","Tarefas e macros",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MacroEntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("nexo_schedule_id",id).putExtra("nexo_run_token",token)
        val pending=PendingIntent.getActivity(context,(id xor(id ushr 32)).toInt(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=NotificationCompat.Builder(context,"nexo_tasks").setSmallIcon(R.drawable.ic_wrench).setContentTitle("NEXO Tools").setContentText("Lembrete de tarefa").build()
        val notification=NotificationCompat.Builder(context,"nexo_tasks").setSmallIcon(R.drawable.ic_wrench).setContentTitle(name.take(64)).setContentText("Toque para executar os passos no NEXO").setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        try{NotificationManagerCompat.from(context).notify("nexo-deferred",(id xor(id ushr 32)).toInt(),notification)}catch(_:SecurityException){return false}
        return true
    }
    fun clearDeferred(id:Long){
        val request=(id xor(id ushr 32)).toInt()
        NotificationManagerCompat.from(context).cancel("nexo-deferred",request)
        PendingIntent.getActivity(context,request,Intent(context,MacroEntryActivity::class.java),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.cancel()
    }
    fun active(name:String):Boolean {
        if(!allowed())return false
        val channel=NotificationChannel("nexo_tasks","Tarefas e macros",NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val stop=PendingIntent.getBroadcast(context,0,Intent(context,MacroStopReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,"nexo_tasks").setSmallIcon(R.drawable.ic_wrench).setContentTitle("Macro em execução").setContentText(name.take(64)).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).addAction(R.drawable.ic_pause,"Parar",stop).build()
        try{NotificationManagerCompat.from(context).notify(0x4E45584F,notification)}catch(_:SecurityException){return false}
        return true
    }
    fun clearActive(){NotificationManagerCompat.from(context).cancel(0x4E45584F)}
}
