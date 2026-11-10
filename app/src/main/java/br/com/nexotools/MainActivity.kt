package br.com.nexotools

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import br.com.nexotools.ui.NexoApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var model:MainViewModel
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model=ViewModelProvider(this)[MainViewModel::class.java]
        setContent { NexoApp(model) }
        openRequestedTool(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedTool(intent)
    }
    private fun openRequestedTool(intent: Intent) {
        if (intent.getBooleanExtra("nexo_open_packets", false)) {
            intent.removeExtra("nexo_open_packets")
            model.openTool("packets")
        }
    }
}
