package com.davidlang.torque.fuelsmoothing

import android.content.Context
import java.util.Locale

internal object AppStrings {
    private var app: Context? = null

    fun install(context: Context) {
        app = context.applicationContext
    }

    fun peek(): Context? = app
}

private val FORMAT = Regex("%(?:\\d+\\$)?[-#+0]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAn%]")

/** False when any % is not %% or a printf conversion. S leaves those sentences unformatted. */
internal fun sentenceFormats(sentence: String): Boolean {
    var i = 0
    while (i < sentence.length) {
        if (sentence[i] != '%') {
            i++
            continue
        }
        val match = FORMAT.find(sentence, i) ?: return false
        if (match.range.first != i) return false
        i = match.range.last + 1
    }
    return true
}

fun S(sentence: String, vararg args: Any?): String {
    val format = args.isNotEmpty() && sentenceFormats(sentence)
    val id = I18nSentences.id(sentence)
    val ctx = AppStrings.peek()
    val safe = Array(args.size) { args[it] ?: "" }
    if (id != 0 && ctx != null) {
        return if (format) ctx.getString(id, *safe) else ctx.getString(id)
    }
    if (!format) return sentence
    return String.format(Locale.getDefault(), sentence, *safe)
}
