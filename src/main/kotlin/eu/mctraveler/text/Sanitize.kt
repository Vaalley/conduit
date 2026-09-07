package eu.mctraveler.text

object Sanitize {
    fun line(text: String, maxLength: Int): String =
        text
            .replace(Regex("[\r\n]"), " ")
            .replace("§", "")
            .take(maxLength)
            .trim()
}
