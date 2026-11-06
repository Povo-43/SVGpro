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

        /**
         * テキスト入力（テキストフィールド/ダイアログ内の入力欄）にフォーカスがある間は true。
         * この間はグローバルショートカット（1キーのツール切替や Ctrl+C 等）を
         * 横取りしない。横取りすると物理キーボードで文字が入力できなくなる。
         */
        @Volatile
        var textInputFocused: Boolean = false
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
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !textInputFocused) {
            if (keyHandler?.invoke(event) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (isFinishing) keyHandler = null
        super.onDestroy()
    }
}
