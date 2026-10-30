package cn.shiyan.betweenus

import android.os.Build
import androidx.annotation.RequiresApi

/** Explicit script normalization, not OCR correction. Callers must preserve the raw result. */
object ChineseScript {
    val supported
        get() = Build.VERSION.SDK_INT >= 29

    fun display(raw: String, requested: Boolean): String {
        if (!requested || Build.VERSION.SDK_INT < 29) return raw
        return Modern.simplify(raw)
    }

    @RequiresApi(29)
    private object Modern {
        private val converter =
            android.icu.text.Transliterator.getInstance("Traditional-Simplified")

        @Synchronized fun simplify(raw: String): String = converter.transliterate(raw)
    }
}
