package org.json

/**
 * Minimal, strict-enough JSON reader for the JVM probe.
 *
 * The probe runs on a plain JDK, where there is no `org.json` implementation. The
 * cases below only need objects, arrays, strings, numbers and booleans plus the
 * `optX/getX` accessors DiagnosticsStore and the controller actually call, so this
 * is a real parser instead of a stub: it must reject the same malformed inputs the
 * product rejects (a truncated reason, a bare payload), because that rejection is
 * part of what the probe asserts.
 *
 * Values are backed by LinkedHashMap/ArrayList so `toString()` reproduces JSON with
 * the original key order.
 */
class JSONException(message: String) : RuntimeException(message)

abstract class JSONValue {
    abstract fun value(): Any?
    override fun toString(): String = render(value(), StringBuilder()).toString()
}

internal fun render(value: Any?, out: StringBuilder): StringBuilder {
    when (value) {
        null, JSONObject.NULL -> out.append("null")
        is String -> {
            out.append('"')
            for (c in value) when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
            out.append('"')
        }
        is Boolean -> out.append(value)
        is Double -> out.append(if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15)
            value.toLong().toString() else value.toString())
        is Number -> out.append(value)
        is JSONObject -> value.render(out)
        is JSONArray -> value.render(out)
        else -> JSONObject.quote(value.toString()).let { out.append(it) }
    }
    return out
}

class JSONObject {
    private val map = LinkedHashMap<String, Any?>()

    constructor()
    constructor(text: String) { parse(text) }
    constructor(source: Map<*, *>) {
        for ((k, v) in source) put(k.toString(), v)
    }

    companion object {
        val NULL: Any = object {
            override fun toString() = "null"
        }
        fun quote(text: String) = StringBuilder().also { render(text, it) }.toString()
        fun numberToString(number: Number) = number.toString()
    }

    fun put(key: String, value: Any?): JSONObject {
        map[key] = if (value is JSONValue) value.value() else value
        return this
    }
    fun put(key: String, value: Boolean) = put(key, value as Any)
    fun put(key: String, value: Int) = put(key, value as Any)
    fun put(key: String, value: Long) = put(key, value as Any)
    fun put(key: String, value: Double) = put(key, value as Any)
    fun remove(key: String): Any? = map.remove(key)
    fun has(key: String): Boolean {
        val value = map[key] ?: return false
        return value !== NULL
    }
    fun length() = map.size
    fun isNull(key: String) = map[key] == null || map[key] === NULL
    fun keys(): Iterator<String> = map.keys.iterator()

    fun opt(key: String): Any? = map[key]?.takeUnless { it === NULL }
    fun optString(key: String, fallback: String = ""): String = when (val v = opt(key)) {
        null -> fallback
        is String -> v
        is Number, is Boolean -> v.toString()
        is JSONObject, is JSONArray -> v.toString()
        else -> fallback
    }
    fun getString(key: String): String {
        val v = opt(key) as? String ?: throw JSONException("not a string: $key")
        return v
    }
    fun optInt(key: String, fallback: Int = 0): Int = when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: fallback
        is Boolean -> if (v) 1 else 0
        else -> fallback
    }
    fun getInt(key: String) = getLong(key).toInt()
    fun optLong(key: String, fallback: Long = 0L): Long = when (val v = opt(key)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: fallback
        is Boolean -> if (v) 1L else 0L
        else -> fallback
    }
    fun getLong(key: String): Long = optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        ?: throw JSONException("not a number: $key")
    fun optDouble(key: String, fallback: Double = Double.NaN): Double = when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: fallback
        else -> fallback
    }
    fun getDouble(key: String) = optDouble(key)
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = when (val v = opt(key)) {
        is Boolean -> v
        is String -> v.equals("true", true)
        is Number -> v.toInt() != 0
        else -> fallback
    }
    fun getBoolean(key: String) = optBoolean(key)
    fun optJSONObject(key: String): JSONObject? = opt(key) as? JSONObject
    fun optJSONArray(key: String): JSONArray? = opt(key) as? JSONArray
    fun getJSONObject(key: String) = optJSONObject(key) ?: throw JSONException("not an object: $key")
    fun getJSONArray(key: String) = optJSONArray(key) ?: throw JSONException("not an array: $key")

    internal fun render(out: StringBuilder): StringBuilder {
        out.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) out.append(',')
            first = false
            render(k, out); out.append(':'); render(v, out)
        }
        return out.append('}')
    }
    override fun toString() = render(StringBuilder()).toString()

    private fun parse(text: String) {
        val parser = Parser(text)
        parser.skip()
        val value = parser.readValue()
        parser.skip()
        if (!parser.done()) throw JSONException("trailing data")
        val object_ = value as? JSONObject ?: throw JSONException("not an object")
        map.putAll(object_.map)
    }

    internal class Parser(private val text: String) {
        private var i = 0
        fun done() = i >= text.length
        fun skip() {
            while (i < text.length && text[i].isWhitespace()) i++
        }
        private fun expect(c: Char) {
            skip()
            if (i >= text.length || text[i] != c) throw JSONException("expected $c at $i of ${text.length}")
            i++
        }
        fun readValue(): Any? {
            skip()
            if (i >= text.length) throw JSONException("unexpected end")
            return when (val c = text[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { literal("true"); true }
                'f' -> { literal("false"); false }
                'n' -> { literal("null"); JSONObject.NULL }
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw JSONException("unexpected $c")
            }
        }
        private fun literal(word: String) {
            if (!text.startsWith(word, i)) throw JSONException("bad literal at $i")
            i += word.length
        }
        private fun readObject(): JSONObject {
            expect('{')
            val out = JSONObject()
            skip()
            if (i < text.length && text[i] == '}') { i++; return out }
            while (true) {
                skip()
                val key = readString()
                expect(':')
                out.put(key, readValue())
                skip()
                if (i >= text.length) throw JSONException("unterminated object")
                when (text[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw JSONException("bad object at $i")
                }
            }
        }
        private fun readArray(): JSONArray {
            expect('[')
            val out = JSONArray()
            skip()
            if (i < text.length && text[i] == ']') { i++; return out }
            while (true) {
                out.put(readValue())
                skip()
                if (i >= text.length) throw JSONException("unterminated array")
                when (text[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw JSONException("bad array at $i")
                }
            }
        }
        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (i < text.length) {
                when (val c = text[i++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= text.length) throw JSONException("bad escape")
                        when (val e = text[i++]) {
                            '"' -> out.append('"'); '\\' -> out.append('\\')
                            '/' -> out.append('/'); 'b' -> out.append('\b')
                            'f' -> out.append('\u000C'); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > text.length) throw JSONException("bad unicode escape")
                                out.append(text.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> throw JSONException("bad escape $e")
                        }
                    }
                    else -> out.append(c)
                }
            }
            throw JSONException("unterminated string")
        }
        private fun readNumber(): Any {
            val start = i
            if (i < text.length && text[i] == '-') i++
            while (i < text.length && (text[i].isDigit() || text[i] in ".eE+-")) i++
            val raw = text.substring(start, i)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw JSONException("bad number $raw")
        }
    }
}

class JSONArray {
    private val list = ArrayList<Any?>()
    constructor()
    constructor(text: String) {
        val parser = JSONObject.Parser(text)
        parser.skip()
        val value = parser.readValue() as? JSONArray ?: throw JSONException("not an array")
        list.addAll(value.list)
    }
    fun put(value: Any?): JSONArray { list += if (value is JSONValue) value.value() else value; return this }
    fun put(value: Int) = put(value as Any)
    fun put(value: Long) = put(value as Any)
    fun put(value: Boolean) = put(value as Any)
    fun put(value: Double) = put(value as Any)
    fun length() = list.size
    fun opt(index: Int): Any? = list.getOrNull(index)?.takeUnless { it === JSONObject.NULL }
    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject
    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray
    fun getJSONObject(index: Int) = optJSONObject(index) ?: throw JSONException("not an object at $index")
    fun getString(index: Int): String = opt(index) as? String
        ?: throw JSONException("not a string at $index")
    fun optString(index: Int, fallback: String = ""): String = when (val v = opt(index)) {
        null -> fallback
        is String -> v
        else -> v.toString()
    }
    fun isEmpty() = list.isEmpty()
    internal fun render(out: StringBuilder): StringBuilder {
        out.append('[')
        list.forEachIndexed { index, item ->
            if (index > 0) out.append(',')
            render(item, out)
        }
        return out.append(']')
    }
    override fun toString() = render(StringBuilder()).toString()
}
