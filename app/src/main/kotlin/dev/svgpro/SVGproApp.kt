package dev.svgpro

import android.app.Application
import android.content.Context

/** Context をUI以外（ファイルI/Oヘルパ）から参照できるようにするホルダ */
object AppContextHolder {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext
}

class SVGproApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
    }
}
