package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.ktx.unwrap
import java.io.Closeable
import java.util.Locale

class HTTPClient : Closeable {
    companion object {
        const val MAX_SUBSCRIPTION_CHARS = 8 * 1024 * 1024
        const val MAX_UPDATE_CHARS = 2 * 1024 * 1024

        val userAgent by lazy {
            var userAgent = "SFA (sing-box "
            userAgent += Libbox.version()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }
    }

    private val client = Libbox.newHTTPClient()

    init {
        client.modernTLS()
    }

    fun getString(url: String, kind: RemoteUrlGuard.Kind): String {
        RemoteUrlGuard.requireAllowed(url, kind)
        val request = client.newRequest()
        request.setUserAgent(userAgent)
        request.setURL(url)
        val response = request.execute()
        val body = response.content.unwrap
        val max = when (kind) {
            RemoteUrlGuard.Kind.SCRIPT -> OverlayScripts.MAX_CODE_CHARS
            RemoteUrlGuard.Kind.UPDATE -> MAX_UPDATE_CHARS
            RemoteUrlGuard.Kind.SUBSCRIPTION -> MAX_SUBSCRIPTION_CHARS
        }
        if (body.length > max) {
            throw IllegalStateException("下载内容过大（>${max} 字符）")
        }
        return body
    }

    override fun close() {
        client.close()
    }
}
