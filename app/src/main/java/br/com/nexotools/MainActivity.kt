package br.com.nexotools

import android.os.Bundle
import android.content.Intent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.view.animation.PathInterpolator
import br.com.nexotools.ui.NexoApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var model:MainViewModel
    override fun onCreate(savedInstanceState:Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model=ViewModelProvider(this)[MainViewModel::class.java]
        var contentReady = false
        val startedAt = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition { !contentReady && SystemClock.uptimeMillis() - startedAt < 1_500 }
        splash.setOnExitAnimationListener { screen ->
            if (!ValueAnimator.areAnimatorsEnabled()) screen.remove()
            else {
                var removed = false
                fun finish() { if (!removed) { removed = true; screen.remove() } }
                val easing = PathInterpolator(.4f, 0f, .2f, 1f)
                screen.iconView.animate().scaleX(1.04f).scaleY(1.04f).setDuration(200)
                    .setInterpolator(easing).start()
                screen.view.animate().alpha(0f).setDuration(200)
                    .setInterpolator(easing)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = finish()
                        override fun onAnimationCancel(animation: Animator) = finish()
                    }).start()
            }
        }
        setContent { NexoApp(model, onReady = { contentReady = true }) }
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
