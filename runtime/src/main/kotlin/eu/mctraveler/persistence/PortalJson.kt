package eu.mctraveler.persistence

/**
 * Field-preserving access to a Portal-format JSON object file.
 *
 * A player record is one top-level JSON object whose live fields this mod
 * rewrites and whose other fields (legacy economy data, dead tracking data,
 * fields added by future versions) must survive read-modify-write
 * byte-for-byte. Parsing a field's value into a JSON tree and re-serializing
 * cannot promise that — number formatting and string escapes get normalized —
 * so this scanner splits the object into raw text slices per field instead:
 * untouched fields are re-emitted verbatim, and only fields the caller
 * replaces are re-encoded.
 *
 * Values are never validated below their top-level span (a bracket/string scan
 * finds where each value ends), so the one guarantee on unknown content is the
 * one we need: its bytes come back out exactly as they went in. Malformed
 * top-level structure throws — a file we cannot read is never overwritten.
 */
internal object PortalJson {
    /** One field of the object: both slices verbatim, [rawKey] with its quotes. */
    data class Field(val rawKey: String, val rawValue: String)

    /** Decoded key → field, in file order. */
    fun parse(text: String): LinkedHashMap<String, Field> {
        val fields = LinkedHashMap<String, Field>()
        var i = skipWhitespace(text, 0)
        require(i < text.length && text[i] == '{') { "expected a JSON object" }
        i = skipWhitespace(text, i + 1)
        if (i < text.length && text[i] == '}') {
            requireOnlyTrailingWhitespace(text, i + 1)
            return fields
        }
        while (true) {
            val keyStart = i
            i = skipString(text, i)
            val rawKey = text.substring(keyStart, i)
            i = skipWhitespace(text, i)
            require(i < text.length && text[i] == ':') { "expected ':' after object key" }
            i = skipWhitespace(text, i + 1)
            val valueStart = i
            i = skipValue(text, i)
            val key = decodeString(rawKey)
            require(fields.put(key, Field(rawKey, text.substring(valueStart, i))) == null) {
                "duplicate key \"$key\" — refusing to guess which value to keep"
            }
            i = skipWhitespace(text, i)
            require(i < text.length) { "unterminated JSON object" }
            when (text[i]) {
                ',' -> i = skipWhitespace(text, i + 1)
                '}' -> {
                    requireOnlyTrailingWhitespace(text, i + 1)
                    return fields
                }
                else -> throw IllegalArgumentException("expected ',' or '}' after object field")
            }
        }
    }

    /** The object re-assembled from raw slices, compact between fields. */
    fun emit(fields: Collection<Field>): String =
        fields.joinToString(",", "{", "}") { "${it.rawKey}:${it.rawValue}" }

    /** The string list a raw array-of-strings slice denotes. */
    fun parseStringArray(rawArray: String): List<String> {
        var i = skipWhitespace(rawArray, 0)
        require(i < rawArray.length && rawArray[i] == '[') { "expected a JSON array" }
        i = skipWhitespace(rawArray, i + 1)
        val strings = mutableListOf<String>()
        if (i < rawArray.length && rawArray[i] == ']') {
            requireOnlyTrailingWhitespace(rawArray, i + 1)
            return strings
        }
        while (true) {
            val start = i
            i = skipString(rawArray, i)
            strings.add(decodeString(rawArray.substring(start, i)))
            i = skipWhitespace(rawArray, i)
            require(i < rawArray.length) { "unterminated JSON array" }
            when (rawArray[i]) {
                ',' -> i = skipWhitespace(rawArray, i + 1)
                ']' -> {
                    requireOnlyTrailingWhitespace(rawArray, i + 1)
                    return strings
                }
                else -> throw IllegalArgumentException("expected ',' or ']' in array")
            }
        }
    }

    /** The string value a raw quoted-string slice denotes, escapes decoded. */
    fun decodeString(rawString: String): String {
        require(rawString.length >= 2 && rawString.first() == '"' && rawString.last() == '"') {
            "expected a JSON string"
        }
        val out = StringBuilder(rawString.length - 2)
        var i = 1
        while (i < rawString.length - 1) {
            val c = rawString[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            i++
            when (val escaped = rawString[i]) {
                '"', '\\', '/' -> out.append(escaped)
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    out.append(rawString.substring(i + 1, i + 5).toInt(16).toChar())
                    i += 4
                }
                else -> throw IllegalArgumentException("invalid escape '\\$escaped'")
            }
            i++
        }
        return out.toString()
    }

    /** [value] as a raw quoted-string slice, with canonical JSON escaping. */
    fun encodeString(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] in " \t\n\r") i++
        return i
    }

    private fun requireOnlyTrailingWhitespace(text: String, from: Int) {
        require(skipWhitespace(text, from) == text.length) { "content after end of JSON object" }
    }

    /** Index just past the string starting (with '"') at [from]. */
    private fun skipString(text: String, from: Int): Int {
        require(from < text.length && text[from] == '"') { "expected a JSON string" }
        var i = from + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        throw IllegalArgumentException("unterminated JSON string")
    }

    /** Index just past the value starting at [from]. */
    private fun skipValue(text: String, from: Int): Int = when (text.getOrNull(from)) {
        '"' -> skipString(text, from)
        '{' -> skipBracketed(text, from, '{', '}')
        '[' -> skipBracketed(text, from, '[', ']')
        else -> { // number / true / false / null: runs until a delimiter
            var i = from
            while (i < text.length && text[i] !in ",}] \t\n\r") i++
            require(i > from) { "expected a JSON value" }
            i
        }
    }

    /**
     * Index just past the bracketed value starting at [from]. Counting one
     * bracket pair while skipping strings is sound because valid JSON nests
     * brackets properly; structural errors surface as parse failures.
     */
    private fun skipBracketed(text: String, from: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '"' -> {
                    i = skipString(text, i)
                    continue
                }
                open -> depth++
                close -> if (--depth == 0) return i + 1
            }
            i++
        }
        throw IllegalArgumentException("unterminated '$open'")
    }
}
