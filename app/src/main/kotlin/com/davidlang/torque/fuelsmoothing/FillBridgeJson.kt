package com.davidlang.torque.fuelsmoothing

import java.text.Normalizer

/** Strict bounded ingress, not JSONObject's permissive duplicate/number parser. No diagnostics contain input. */
internal object FillBridgeJson {
    class Invalid : IllegalArgumentException()

    fun parse(text: String): Any? = Parser(text).parse()

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: throw Invalid()
    fun string(value: Any?): String = value as? String ?: throw Invalid()
    fun integer(value: Any?): Long = value as? Long ?: throw Invalid()
    fun bool(value: Any?): Boolean = value as? Boolean ?: throw Invalid()
    fun keys(o: Map<String, Any?>, vararg expected: String) {
        if (o.keys != expected.toSet()) throw Invalid()
    }

    fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Long -> value.toString()
        is Boolean -> value.toString()
        is Map<*, *> -> value.keys.map { it as String }.sorted().joinToString(",", "{", "}") {
            quote(it) + ":" + canonical(value[it])
        }
        is List<*> -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> throw Invalid()
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\u000c' -> append("\\f")
            '\r' -> append("\\r")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }

    private class Parser(private val text: String) {
        private var at = 0
        private var nodes = 0
        fun parse(): Any? {
            val result = value(0)
            space()
            if (at != text.length) throw Invalid()
            return result
        }
        private fun space() {
            while (at < text.length && text[at] in " \t\r\n") at++
        }
        private fun take(c: Char): Boolean {
            space()
            if (at < text.length && text[at] == c) { at++; return true }
            return false
        }
        private fun requireChar(c: Char) { if (!take(c)) throw Invalid() }
        private fun value(depth: Int): Any? {
            if (depth > 24 || ++nodes > 8192) throw Invalid()
            space()
            if (at >= text.length) throw Invalid()
            return when (text[at]) {
                '{' -> {
                    at++
                    val result = linkedMapOf<String, Any?>()
                    if (!take('}')) {
                        do {
                            space()
                            val key = readString()
                            if (result.containsKey(key)) throw Invalid()
                            requireChar(':')
                            result[key] = value(depth + 1)
                        } while (take(','))
                        requireChar('}')
                    }
                    result
                }
                '[' -> {
                    at++
                    val result = arrayListOf<Any?>()
                    if (!take(']')) {
                        do { result.add(value(depth + 1)) } while (take(','))
                        requireChar(']')
                    }
                    result
                }
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }
        private fun literal(token: String, result: Any?): Any? {
            if (!text.startsWith(token, at)) throw Invalid()
            at += token.length
            return result
        }
        private fun number(): Long {
            val start = at
            if (at < text.length && text[at] == '-') at++
            if (at >= text.length || text[at] !in '0'..'9') throw Invalid()
            if (text[at++] != '0') while (at < text.length && text[at] in '0'..'9') at++
            // Fractions, exponents and leading zeroes fail enclosing delimiter/EOF validation.
            return text.substring(start, at).toLongOrNull() ?: throw Invalid()
        }
        private fun readString(): String {
            if (at >= text.length || text[at++] != '"') throw Invalid()
            val out = StringBuilder()
            var ended = false
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') { ended = true; break }
                if (c < ' ') throw Invalid()
                if (c != '\\') out.append(c) else {
                    if (at >= text.length) throw Invalid()
                    when (val escape = text[at++]) {
                        '"', '\\', '/' -> out.append(escape)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (at + 4 > text.length) throw Invalid()
                            val hex = text.substring(at, at + 4)
                            if (hex.any { it !in "0123456789abcdefABCDEF" }) throw Invalid()
                            out.append(hex.toInt(16).toChar()); at += 4
                        }
                        else -> throw Invalid()
                    }
                }
            }
            if (!ended) throw Invalid()
            var i = 0
            while (i < out.length) {
                val c = out[i++]
                if (Character.isHighSurrogate(c)) {
                    if (i >= out.length || !Character.isLowSurrogate(out[i++])) throw Invalid()
                } else if (Character.isLowSurrogate(c)) throw Invalid()
            }
            return Normalizer.normalize(out, Normalizer.Form.NFC)
        }
    }
}
