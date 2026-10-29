package top.maler.motogps.gateway

import java.net.URI
import java.net.URISyntaxException

class GatewayAddressException(message: String) : IllegalArgumentException(message)

/** Minimal persistence seam so address rules can be tested without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/**
 * The rider's own gateway root. Mirrors GatewayConfiguration in the shared
 * Swift package: HTTPS only, no credentials/query/fragment, and a base URL
 * rather than a concrete /healthz or /v1/... endpoint.
 */
object GatewayConfiguration {
    const val PREFERENCES_KEY = "MotoGPS.GatewayBaseURL.v1"

    private const val INVALID_ADDRESS = "请填写有效的 HTTPS 网关地址，不要包含账号、密码、查询参数或 #。"
    private const val ENDPOINT_INSTEAD_OF_BASE = "请填写网关根地址，不要带 /healthz 或 /v1/... 接口路径。"

    fun normalizedUrl(input: String): String {
        val value = input.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) throw GatewayAddressException(INVALID_ADDRESS)
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            throw GatewayAddressException(INVALID_ADDRESS)
        }
        val host = uri.host?.lowercase()
        val port = uri.port
        val path = uri.path ?: ""
        val valid = uri.scheme.equals("https", ignoreCase = true) &&
            !host.isNullOrEmpty() &&
            host != "example.invalid" && !host.endsWith(".invalid") &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (port == -1 || port in 1..65_535) &&
            path.split('/').none { it == "." || it == ".." }
        if (!valid) throw GatewayAddressException(INVALID_ADDRESS)

        val components = path.split('/').filter { it.isNotEmpty() }
        if (components.lastOrNull() == "healthz" || components.contains("v1")) {
            throw GatewayAddressException(ENDPOINT_INSTEAD_OF_BASE)
        }
        val rawPath = (uri.rawPath ?: "").let { if (it.endsWith("/")) it else "$it/" }
        val authority = if (port == -1) host else "$host:$port"
        return "https://$authority$rawPath"
    }

    fun isValid(input: String?): Boolean = input != null && runCatching { normalizedUrl(input) }.isSuccess

    /** Saved address first, then a valid build default; never a placeholder. */
    fun resolvedUrl(store: KeyValueStore, bundledAddress: String?): String? {
        store.getString(PREFERENCES_KEY)?.let { saved ->
            runCatching { normalizedUrl(saved) }.getOrNull()?.let { return it }
        }
        return bundledAddress?.takeIf { it.isNotBlank() }?.let { runCatching { normalizedUrl(it) }.getOrNull() }
    }

    fun save(address: String, store: KeyValueStore): String {
        val url = normalizedUrl(address)
        store.putString(PREFERENCES_KEY, url)
        return url
    }

    /** Joins a relative endpoint to a normalized base that always ends in '/'. */
    fun endpoint(baseUrl: String, relativePath: String): String =
        baseUrl.trimEnd('/') + "/" + relativePath.trimStart('/')
}
