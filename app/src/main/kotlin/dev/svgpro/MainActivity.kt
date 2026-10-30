package dev.svgpro

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.svgpro.ui.SVGproTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Compose側から登録されるキーハンドラ */
        @Volatile
        var keyHandler: ((KeyEvent) -> Boolean)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SVGproTheme {
                dev.svgpro.ui.EditorScreen()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (keyHandler?.invoke(event) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (isFinishing) keyHandler = null
        super.onDestroy()
    }
}
