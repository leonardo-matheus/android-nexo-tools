package br.com.nexotools

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import br.com.nexotools.ui.NexoApp
import dagger.hilt.android.AndroidEntryPoint

/** Private notification entry. Other apps cannot trigger stored macro execution. */
@AndroidEntryPoint class MacroEntryActivity:ComponentActivity() {
    private lateinit var model:MainViewModel
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model=ViewModelProvider(this)[MainViewModel::class.java]
        setContent{NexoApp(model)}
        execute(intent)
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);execute(intent)}
    private fun execute(intent:Intent){
        intent.getStringExtra("nexo_tool_route")?.let{model.openTool(it)}
        intent.removeExtra("nexo_tool_route")
        val id=intent.getLongExtra("nexo_schedule_id",-1)
        val token=intent.getStringExtra("nexo_run_token")
        intent.removeExtra("nexo_schedule_id");intent.removeExtra("nexo_run_token")
        if(id>0 && token!=null)model.runScheduledMacro(id,token)
    }
}
