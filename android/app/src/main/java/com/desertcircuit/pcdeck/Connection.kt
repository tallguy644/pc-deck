package com.desertcircuit.pcdeck

import android.content.Context
import android.net.Uri

/** A PC to connect to: host[:port] plus the private token from the PC Deck server. */
data class Connection(val host: String, val port: Int, val token: String) {

    val deckUrl: String
        get() = "http://${hostForUrl()}:$port/?t=${Uri.encode(token)}"

    val baseUrl: String
        get() = "http://${hostForUrl()}:$port"

    private fun hostForUrl() = if (host.contains(':')) "[$host]" else host

    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val PREFS = "connection"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(ctx: Context): Connection? {
            val p = prefs(ctx)
            val host = p.getString(KEY_HOST, null) ?: return null
            val token = p.getString(KEY_TOKEN, null) ?: return null
            return Connection(host, p.getInt(KEY_PORT, DEFAULT_PORT), token)
        }

        fun clear(ctx: Context) = prefs(ctx).edit().clear().apply()

        /** Parses the link shown by the server: http://192.168.1.20:8765/?t=TOKEN */
        fun fromUrl(text: String): Connection? {
            val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "http" && uri.scheme != "https") return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val token = uri.getQueryParameter("t")?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port > 0) uri.port else DEFAULT_PORT
            return Connection(host, port, token)
        }

        /** Parses a typed address like "192.168.1.20", "192.168.1.20:8765" or a full link. */
        fun fromManual(address: String, token: String): Connection? {
            val a = address.trim()
            if (a.startsWith("http://") || a.startsWith("https://")) {
                fromUrl(a)?.let { return it }
                val uri = Uri.parse(a)
                val host = uri.host ?: return null
                if (token.isBlank()) return null
                return Connection(host, if (uri.port > 0) uri.port else DEFAULT_PORT, token.trim())
            }
            if (a.isEmpty() || token.isBlank()) return null
            val idx = a.lastIndexOf(':')
            val (host, port) = if (idx > 0 && a.count { it == ':' } == 1) {
                a.substring(0, idx) to (a.substring(idx + 1).toIntOrNull() ?: return null)
            } else {
                a to DEFAULT_PORT
            }
            if (host.isBlank() || port !in 1..65535) return null
            return Connection(host, port, token.trim())
        }
    }
}
