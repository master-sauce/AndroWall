package com.androwall

object HttpInspector {
    private val METHODS = setOf("GET","POST","PUT","DELETE","HEAD","OPTIONS","PATCH","CONNECT","TRACE")

    data class HttpInfo(val method: String, val host: String, val path: String) {
        val url: String get() = "http://$host$path"
    }

    /**
     * Parses HTTP/1.x request bytes → method, Host, path.
     * Returns null if incomplete or not HTTP.
     */
    fun extract(data: ByteArray): HttpInfo? = runCatching {
        val text  = String(data, Charsets.ISO_8859_1)
        if (!text.contains("\r\n")) return null   // incomplete request line
        val lines = text.split("\r\n")

        val parts = lines[0].split(" ")
        if (parts.size < 2 || parts[0] !in METHODS) return null

        val method = parts[0]
        val path   = parts[1]
        val host   = lines.drop(1)
            .firstOrNull { it.startsWith("host:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
            ?.substringBefore(":")   // strip port
            ?: return null

        HttpInfo(method, host, path)
    }.getOrNull()
}