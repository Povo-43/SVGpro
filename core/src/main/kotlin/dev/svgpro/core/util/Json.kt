package dev.svgpro.core.util

/**
 * 最小限のJSONエンコーダ/デコーダ（外部ライブラリ非依存）。
 * アートボード情報等の構造化データを SVG の data- 属性に可逆保持するために使用する。
 */
object Json {

    /** Any → JSON文字列（数値はそのまま、文字列はエスケープして二重引用符で囲む、nullはnull） */
    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> {
            val entries = value.entries.map { (k, v) ->
                "\"${escapeString(k.toString())}\":${stringify(v)}"
            }
            "{" + entries.joinToString(",") + "}"
        }
        is List<*> -> {
            "[" + value.map { stringify(it) }.joinToString(",") + "]"
        }
        is String -> "\"${escapeString(value)}\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> "null"
    }

    /** JSON文字列 → Any（Map<String, Any> / List<Any> / String / Double / Boolean / null） */
    fun parse(json: String?): Any? {
        if (json.isNullOrBlank()) return null
        val trimmed = json.trim()
        return when {
            trimmed.startsWith("{") -> parseObject(trimmed)
            trimmed.startsWith("[") -> parseArray(trimmed)
            else -> null
        }
    }

    private fun escapeString(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    // ―――― パーサ（簡易再帰下降）―――――

    private class Parser(private val s: String, private var pos: Int = 0) {
        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
        private fun peek(): Char = if (pos < s.length) s[pos] else Char.MIN_VALUE
        private fun nextChar(): Char { val c = s[pos]; pos++; return c }
        private fun expect(c: Char) { skipWs(); if (peek() != c) throw IllegalArgumentException("expected '$c' at $pos"); nextChar() }

        fun parseValue(): Any? {
            skipWs()
            return when (peek()) {
                '{' -> parseObjectInternal()
                '[' -> parseArrayInternal()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObjectInternal(): Map<String, Any?> {
            expect('{')
            skipWs()
            val map = linkedMapOf<String, Any?>()
            if (peek() == '}') { nextChar(); return map }
            while (true) {
                skipWs()
                val key = parseString() as String
                skipWs()
                expect(':')
                map[key] = parseValue()
                skipWs()
                if (peek() == ',') { nextChar(); continue }
                if (peek() == '}') { nextChar(); break }
                throw IllegalArgumentException("expected ',' or '}' at $pos")
            }
            return map
        }

        private fun parseArrayInternal(): List<Any?> {
            expect('[')
            skipWs()
            val list = arrayListOf<Any?>()
            if (peek() == ']') { nextChar(); return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (peek() == ',') { nextChar(); continue }
                if (peek() == ']') { nextChar(); break }
                throw IllegalArgumentException("expected ',' or ']' at $pos")
            }
            return list
        }

        private fun parseString(): Any {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw IllegalArgumentException("unterminated string")
                val c = nextChar()
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    val esc = nextChar()
                    sb.append(when (esc) {
                        '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                        'b' -> '\u0008'; 'f' -> '\u000C'
                        'n' -> '\u000A'; 'r' -> '\u000D'; 't' -> '\u0009'
                        'u' -> {
                            val hex = s.substring(pos, pos + 4)
                            pos += 4
                            Integer.parseInt(hex, 16).toChar()
                        }
                        else -> esc
                    })
                } else {
                    sb.append(c)
                }
            }
        }

        private fun parseBool(): Any? {
            if (s.substring(pos).startsWith("true")) { pos += 4; return true }
            if (s.substring(pos).startsWith("false")) { pos += 5; return false }
            throw IllegalArgumentException("invalid boolean at $pos")
        }

        private fun parseNull(): Any? {
            if (s.substring(pos).startsWith("null")) { pos += 4; return null }
            throw IllegalArgumentException("invalid null at $pos")
        }

        private fun parseNumber(): Any? {
            val start = pos
            if (peek() == '-') nextChar()
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            val numStr = s.substring(start, pos)
            return if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
                numStr.toDouble()
            } else {
                numStr.toInt()
            }
        }
    }

    private fun parseObject(json: String): Any? = Parser(json).parseValue()
    private fun parseArray(json: String): Any? = Parser(json).parseValue()
}
