package top.maler.motogps.gateway

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import top.maler.motogps.model.GatewayFailureEnvelope
import top.maler.motogps.model.PlaceSearchEnvelope
import top.maler.motogps.model.PlaceSearchResult
import top.maler.motogps.model.RouteEnvelope
import top.maler.motogps.model.RouteOptionsEnvelope
import top.maler.motogps.model.RoutePlan
import top.maler.motogps.model.RouteRequest
import top.maler.motogps.model.WGS84Point

sealed class GatewayException(message: String) : IOException(message) {
    class InvalidEndpoint : GatewayException("路线网关地址无效")
    class Rejected(val code: String, val serverMessage: String) : GatewayException("$serverMessage（$code）")
    class InvalidResponse : GatewayException("路线网关返回了无法识别的数据")
    class HttpStatus(val status: Int) :
        GatewayException(if (status == 429) "地图服务繁忙，请稍后继续" else "地图服务暂时不可用")
    class ResponseTooLarge : GatewayException("地图分块超过大小限制")
}

@Serializable
data class MapCity(
    val id: String,
    val name: String,
    val detail: String = "",
    @SerialName("bounds_wgs84") val boundsWgs84: List<Double>,
)

@Serializable
private data class MapCitiesEnvelope(val cities: List<MapCity> = emptyList())

@Serializable
data class GatewayHealth(
    val status: String,
    @SerialName("ready_for_live_navigation") val readyForLiveNavigation: Boolean = false,
)

val gatewayJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Calls the rider's own MOTO GPS gateway. It never accepts or transports an
 * AMap Web service key; that stays on the server.
 */
class GatewayClient(
    val baseUrl: String,
    private val http: OkHttpClient = sharedHttpClient,
) {
    suspend fun route(request: RouteRequest): RouteEnvelope {
        val response = post("v1/routes", request, timeoutSeconds = 15)
        return response.use { decodeSuccess(it, RouteEnvelope.serializer()) }
    }

    /**
     * Candidate routes for the preview screen. Older gateways without
     * /v1/route-options still work through their single /v1/routes result.
     */
    suspend fun routeOptions(request: RouteRequest): List<RoutePlan> {
        val response = post("v1/route-options", request, timeoutSeconds = 15)
        val envelope = response.use {
            if (it.code == 404 || it.code == 405 || it.code == 501) return listOf(route(request).route)
            decodeSuccess(it, RouteOptionsEnvelope.serializer())
        }
        if (envelope.protocolVersion != 1 || envelope.requestId != request.requestId) {
            throw GatewayException.InvalidResponse()
        }
        val seen = HashSet<String>()
        val routes = envelope.routes
            .filter { it.polyline.size >= 2 && it.totalDistanceM > 0 && it.totalDurationS > 0 && seen.add(it.routeId) }
            .take(3)
        if (routes.isEmpty()) throw GatewayException.InvalidResponse()
        return routes
    }

    suspend fun searchPlaces(keywords: String, near: WGS84Point?): List<PlaceSearchResult> {
        val query = keywords.trim()
        if (query.length < 2) return emptyList()
        val url = endpoint("v1/places").newBuilder().apply {
            addQueryParameter("keywords", query)
            if (near != null && near.isValid && near.coordinateSystem == "WGS84") {
                addQueryParameter("longitude_deg", String.format(Locale.US, "%.7f", near.longitudeDeg))
                addQueryParameter("latitude_deg", String.format(Locale.US, "%.7f", near.latitudeDeg))
            }
        }.build()
        val response = execute(get(url), timeoutSeconds = 10)
        val envelope = response.use { decodeSuccess(it, PlaceSearchEnvelope.serializer()) }
        if (envelope.protocolVersion != 1) throw GatewayException.InvalidResponse()
        return envelope.places.filter { it.location.coordinateSystem == "WGS84" && it.location.isValid }
    }

    suspend fun searchCities(keywords: String): List<MapCity> {
        val url = endpoint("v1/map/cities").newBuilder().addQueryParameter("keywords", keywords).build()
        val response = execute(get(url), timeoutSeconds = 20)
        val bytes = response.use {
            if (it.code != 200) throw GatewayException.HttpStatus(it.code)
            readBounded(it, 1_048_576)
        }
        val reply = decode(bytes, MapCitiesEnvelope.serializer())
        return reply.cities.take(30).filter { city ->
            val b = city.boundsWgs84
            b.size == 4 && b.all { it.isFinite() } && b[0] < b[2] && b[1] < b[3] &&
                b[0] >= -180 && b[2] <= 180 && b[1] >= -85 && b[3] <= 85
        }
    }

    /** Raw map tile JSON, retrying 429 responses with the server's Retry-After. */
    suspend fun mapTile(z: Int, x: Int, y: Int, maximumBytes: Int): ByteArray {
        val url = endpoint("v1/map/tiles/$z/$x/$y")
        repeat(3) { attempt ->
            val response = execute(get(url), timeoutSeconds = 15)
            val retryDelayMs: Long? = response.use {
                if (it.code == 429 && attempt < 2) {
                    retryAfterMs(it.header("Retry-After"), attempt)
                } else {
                    if (it.code !in 200..299) throw GatewayException.HttpStatus(it.code)
                    val length = it.body?.contentLength() ?: -1
                    if (length > maximumBytes) throw GatewayException.ResponseTooLarge()
                    return readBounded(it, maximumBytes)
                }
            }
            delay(retryDelayMs ?: 1_000)
        }
        throw GatewayException.InvalidResponse()
    }

    suspend fun health(): GatewayHealth {
        val request = Request.Builder().url(endpoint("healthz")).header("Cache-Control", "no-cache").get().build()
        val response = execute(request, timeoutSeconds = 10)
        val bytes = response.use {
            if (it.code != 200) throw GatewayException.HttpStatus(it.code)
            readBounded(it, 65_536)
        }
        return decode(bytes, GatewayHealth.serializer())
    }

    private fun endpoint(path: String): HttpUrl {
        val url = GatewayConfiguration.endpoint(baseUrl, path).toHttpUrlOrNull()
            ?: throw GatewayException.InvalidEndpoint()
        if (url.scheme != "https" && url.host != "127.0.0.1" && url.host != "localhost") {
            throw GatewayException.InvalidEndpoint()
        }
        return url
    }

    private fun get(url: HttpUrl) = Request.Builder().url(url).header("Accept", "application/json").get().build()

    private suspend fun post(path: String, body: RouteRequest, timeoutSeconds: Long): Response {
        val json = gatewayJson.encodeToString(RouteRequest.serializer(), body)
        val request = Request.Builder()
            .url(endpoint(path))
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, timeoutSeconds)
    }

    private suspend fun execute(request: Request, timeoutSeconds: Long): Response {
        val client = http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        return client.newCall(request).await()
    }

    private suspend fun <T> decodeSuccess(response: Response, serializer: kotlinx.serialization.KSerializer<T>): T {
        val bytes = readBounded(response, MAXIMUM_JSON_BYTES)
        if (response.code !in 200..299) {
            val failure = runCatching { gatewayJson.decodeFromString(GatewayFailureEnvelope.serializer(), bytes.decodeToString()) }
                .getOrNull()
            if (failure != null) throw GatewayException.Rejected(failure.error.code, failure.error.message)
            throw GatewayException.InvalidResponse()
        }
        return decode(bytes, serializer)
    }

    private fun <T> decode(bytes: ByteArray, serializer: kotlinx.serialization.KSerializer<T>): T = try {
        gatewayJson.decodeFromString(serializer, bytes.decodeToString())
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw GatewayException.InvalidResponse()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val MAXIMUM_JSON_BYTES = 8 * 1_024 * 1_024

        val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private suspend fun readBounded(response: Response, maximumBytes: Int): ByteArray =
            withContext(Dispatchers.IO) {
                val source = response.body?.source() ?: return@withContext ByteArray(0)
                val buffer = okio.Buffer()
                while (true) {
                    val read = source.read(buffer, 8_192)
                    if (read == -1L) break
                    if (buffer.size > maximumBytes) throw GatewayException.ResponseTooLarge()
                }
                buffer.readByteArray()
            }

        internal fun retryAfterMs(header: String?, attempt: Int): Long {
            val seconds = header?.trim()?.toDoubleOrNull()
                ?: header?.let { parseHttpDate(it) }?.let { (it - System.currentTimeMillis()) / 1_000.0 }
                ?: (attempt + 1).toDouble()
            val bounded = if (seconds.isFinite()) seconds.coerceIn(0.25, 30.0) else (attempt + 1).toDouble()
            return (bounded * 1_000).toLong()
        }

        private fun parseHttpDate(value: String): Long? = runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("GMT")
            }.parse(value)?.time
        }.getOrNull()
    }
}

/** Suspends on an OkHttp call and cancels it with the coroutine. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, _, _ -> response.close() }
        }
    })
}
