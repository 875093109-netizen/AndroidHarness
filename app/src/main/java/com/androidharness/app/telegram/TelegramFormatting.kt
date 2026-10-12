package com.androidharness.app.telegram

internal fun telegramAgentError(text: String): String = when (Regex("^HTTP\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
    .find(text.trim())?.groupValues?.get(1)?.toIntOrNull()) {
    429 -> "The model provider is rate limited. Wait, or choose another model, then retry/resume this saved task."
    401, 403 -> "The model provider rejected access. Check its connection in AndroidHarness, then retry/resume this saved task."
    404 -> "The selected model is unavailable. Choose another model and retry/resume this saved task."
    in 500..599 -> "The model provider is temporarily unavailable. Retry/resume this saved task when it returns."
    else -> text
}

/** Telegram limits messages after entity parsing; keep margin for captions and avoid split surrogates. */
internal fun telegramChunks(text: String, limit: Int = 3_000): List<String> {
    require(limit in 100..3_500)
    if (text.isBlank()) return listOf("No text response.")
    val chunks = mutableListOf<String>()
    var rest = text
    while (rest.length > limit) {
        var cut = rest.lastIndexOf('\n', limit).takeIf { it >= limit / 2 } ?: limit
        if (Character.isHighSurrogate(rest[cut - 1])) cut--
        chunks += rest.substring(0, cut)
        rest = rest.substring(cut).trimStart('\n')
    }
    if (rest.isNotEmpty()) chunks += rest
    return chunks
}

internal fun telegramHtml(text: String): String {
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val fences = Regex("```(?:[a-zA-Z0-9_-]+)?\\n([\\s\\S]*?)```")
    val out = StringBuilder()
    var cursor = 0
    fun paragraph(value: String): String = Regex("\\*\\*([^\\n]+?)\\*\\*").replace(escape(value)) { "<b>${it.groupValues[1]}</b>" }
    fences.findAll(text).forEach { match ->
        out.append(paragraph(text.substring(cursor, match.range.first)))
        out.append("<pre>").append(escape(match.groupValues[1])).append("</pre>")
        cursor = match.range.last + 1
    }
    return out.append(paragraph(text.substring(cursor))).toString()
}
