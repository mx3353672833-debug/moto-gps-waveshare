package top.maler.motogps.gateway

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.maler.motogps.model.RouteRequest
import top.maler.motogps.model.WGS84Point

class GatewayClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GatewayClient

    private val origin = WGS84Point(longitudeDeg = 117.12, latitudeDeg = 36.67)
    private val request = RouteRequest(requestId = 7, origin = origin, destination = origin)

    private fun route(id: String) = """
        {"schema_version":1,"route_id":"$id","provider":"amap","coordinate_system":"GCJ-02","generated_at_ms":1,
         "total_distance_m":1200.5,"total_duration_s":300,
         "polyline":[{"longitude_deg":117.1,"latitude_deg":36.6},{"longitude_deg":117.2,"latitude_deg":36.7}],
         "maneuvers":[{"id":1,"type":"right","route_offset_m":100,"road_name":"经十路","instruction":"右转进入经十路"}],
         "traffic":[{"start_offset_m":0,"end_offset_m":200,"level":"slow"}]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // The client only allows plain HTTP for local development hosts.
        client = GatewayClient(server.url("/moto-gps/api/").toString().replace(server.hostName, "localhost"))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun routeOptionsKeepsValidDistinctRoutesAndSendsTheV1Request() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"protocol_version":1,"request_id":7,"routes":[${route("a")},${route("a")},${route("b")}]}""",
            ),
        )
        val routes = client.routeOptions(request)
        assertEquals(listOf("a", "b"), routes.map { it.routeId })
        assertEquals("经十路", routes[0].maneuvers[0].roadName)
        val recorded = server.takeRequest()
        assertEquals("/moto-gps/api/v1/route-options", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body, body.contains("\"protocol_version\":1"))
        assertTrue(body, body.contains("\"coordinate_system\":\"WGS84\""))
        assertTrue(body, !body.contains("previous_route_id"))
    }

    @Test
    fun olderGatewayFallsBackToSingleRoute() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"protocol_version":1,"request_id":7,"route":${route("only")}}"""))
        assertEquals(listOf("only"), client.routeOptions(request).map { it.routeId })
        assertEquals("/moto-gps/api/v1/route-options", server.takeRequest().path)
        assertEquals("/moto-gps/api/v1/routes", server.takeRequest().path)
    }

    @Test
    fun mismatchedRequestIdIsRejected() {
        server.enqueue(MockResponse().setBody("""{"protocol_version":1,"request_id":8,"routes":[${route("a")}]}"""))
        assertThrows(GatewayException.InvalidResponse::class.java) { runBlocking { client.routeOptions(request) } }
    }

    @Test
    fun gatewayErrorEnvelopeSurfacesItsMessage() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"UPSTREAM_UNAVAILABLE","message":"高德暂时不可用"}}"""))
        val error = assertThrows(GatewayException.Rejected::class.java) { runBlocking { client.route(request) } }
        assertEquals("高德暂时不可用（UPSTREAM_UNAVAILABLE）", error.message)
    }

    @Test
    fun placesAreBiasedAndNonWgs84ResultsDropped() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"protocol_version":1,"query":"奥体","places":[
                  {"id":"B1","name":"奥体中心","address":"经十路","city":"济南市","district":"历下区","display_area":"济南 · 历下",
                   "location":{"coordinate_system":"WGS84","longitude_deg":117.1,"latitude_deg":36.65},"distance_m":1200},
                  {"id":"B2","name":"错误坐标","location":{"coordinate_system":"GCJ-02","longitude_deg":117.1,"latitude_deg":36.65}}]}""",
            ),
        )
        val places = client.searchPlaces(" 奥体 ", origin)
        assertEquals(listOf("奥体中心"), places.map { it.name })
        val path = server.takeRequest().requestUrl!!
        assertEquals("奥体", path.queryParameter("keywords"))
        assertEquals("117.1200000", path.queryParameter("longitude_deg"))
        assertEquals("36.6700000", path.queryParameter("latitude_deg"))
    }

    @Test
    fun insecureRemoteGatewayIsRefused() {
        val remote = GatewayClient("http://nav.example.com/")
        assertThrows(GatewayException.InvalidEndpoint::class.java) { runBlocking { remote.route(request) } }
    }

    @Test
    fun retryAfterIsBounded() {
        assertEquals(2_000L, GatewayClient.retryAfterMs("2", 0))
        assertEquals(30_000L, GatewayClient.retryAfterMs("600", 0))
        assertEquals(250L, GatewayClient.retryAfterMs("0", 0))
        assertEquals(2_000L, GatewayClient.retryAfterMs(null, 1))
    }
}
