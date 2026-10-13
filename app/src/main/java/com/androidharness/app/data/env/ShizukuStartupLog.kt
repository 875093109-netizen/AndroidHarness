package com.androidharness.app.data.env

import java.io.InputStream

data class ShizukuStartupReport(val report: String, val status: String)

/** Restrict the export to this package's helper and service records, including their stack traces. */
internal fun filterShizukuStartupLog(raw: String, packageName: String): String {
    val header = Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+\d+\s+[VDIWEF]\s+([^:]+):\s?(.*)$""")
    data class Entry(val line: String, val pid: String, val tag: String, val message: String)
    val entries = raw.lineSequence().mapNotNull { line ->
        val match = header.matchEntire(line) ?: return@mapNotNull null
        Entry(line, match.groupValues[1], match.groupValues[2].trim(), match.groupValues[3])
    }.toList()
    val packageReference = Regex("""(?<![\w.])${Regex.escape(packageName)}(?=[:/\s]|$)""")
    val helperPids = entries.filter {
        it.tag == "ShizukuServiceStarter" && packageReference.containsMatchIn(it.message) ||
            it.tag == "AndroidRuntime" && it.message.startsWith("Process: $packageName:")
    }.map { it.pid }.toSet()
    val recordTokens = entries.filter {
        it.tag == "UserServiceManager" && packageReference.containsMatchIn(it.message)
    }.flatMap { Regex("""\(([0-9a-fA-F-]{20,})\)""").findAll(it.message).map { match -> match.groupValues[1] }.toList() }.toSet()
    return entries.filter { entry ->
        when (entry.tag) {
            "ShizukuServiceStarter", "AndroidRuntime", "libc", "DEBUG" -> entry.pid in helperPids
            "UserServiceManager", "UserServiceRecord" -> packageReference.containsMatchIn(entry.message) ||
                recordTokens.any { it in entry.message }
            else -> false
        }
    }.joinToString("\n") { it.line }.takeLast(60_000)
}

/** Drain the pipe even past the limit so logcat cannot block on a full output buffer. */
internal fun readStartupLogTail(stream: InputStream, maxChars: Int): String {
    require(maxChars > 0)
    val tail = StringBuilder()
    stream.bufferedReader().use { reader ->
        val buffer = CharArray(4_096)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            tail.append(buffer, 0, read)
            if (tail.length > maxChars) tail.delete(0, tail.length - maxChars)
        }
    }
    return tail.toString()
}
