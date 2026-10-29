package top.maler.motogps.nav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.maler.motogps.core.NavCoreBridge
import top.maler.motogps.core.NavCoreCommand
import top.maler.motogps.core.NavCoreSnapshot
import top.maler.motogps.model.GCJ02Point
import top.maler.motogps.model.NavigationFix
import top.maler.motogps.model.RoutePlan
import top.maler.motogps.model.RouteRequest
import top.maler.motogps.model.WGS84Point

/**
 * Executes platform effects around the authoritative shared C++ NavApp/NavCore.
 * It contains no route matching, maneuver progression, reroute or traffic policy.
 * Port of SharedNavigationRuntime.swift; all calls happen on the main thread.
 */
class SharedNavigationRuntime(
    private val scope: CoroutineScope,
    private val locationSource: NavigationLocationSource,
    private val routeProvider: NavigationRouteProviding,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var onSnapshot: ((NavCoreSnapshot) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null

    private val bridge = NavCoreBridge()
    private val networkJobs = HashMap<Long, Job>()
    private var tickJob: Job? = null
    private var activeRoute: ActiveRoute? = null
    private var destinationPoiId: String? = null
    private var closed = false

    /** The accepted route may change after a reroute; offline downloads follow it. */
    val activeRoutePolyline: List<GCJ02Point> get() = activeRoute?.plan?.polyline ?: emptyList()

    val snapshot: NavCoreSnapshot get() = bridge.snapshot

    private data class ActiveRoute(
        val plan: RoutePlan,
        val origin: WGS84Point,
        val destination: WGS84Point,
        val destinationPoiId: String?,
    )

    fun start(destination: WGS84Point, destinationPoiId: String? = null): Boolean {
        stop()
        this.destinationPoiId = destinationPoiId
        publish(bridge.reset())
        publish(bridge.setNetworkState("online"))
        publish(bridge.beginNavigation(destination.longitudeDeg, destination.latitudeDeg))

        try {
            locationSource.start(
                onFix = { fix -> accept(fix) },
                onFailure = { message -> onFailure?.invoke(message) },
            )
        } catch (error: Exception) {
            onFailure?.invoke(error.message ?: "无法启动定位")
            this.destinationPoiId = null
            publish(bridge.cancelNavigation())
            return false
        }

        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                publish(bridge.tick(clock()))
            }
        }
        return true
    }

    fun stop() {
        if (closed) return
        locationSource.stop()
        tickJob?.cancel()
        tickJob = null
        networkJobs.values.forEach { it.cancel() }
        networkJobs.clear()
        activeRoute = null
        destinationPoiId = null
        publish(bridge.cancelNavigation())
    }

    /** Stops the session and frees the native NavApp. */
    fun close() {
        stop()
        closed = true
        bridge.close()
    }

    /**
     * Commits an ESP32 page selection through the same shared NavCore that
     * produces subsequent BLE snapshots, so the phone cannot switch it back.
     */
    fun selectDisplayPage(rawValue: Int): Boolean {
        val name = when (rawValue) {
            0 -> "navigation"
            1 -> "speed"
            2 -> "compass"
            3 -> "music"
            else -> return false
        }
        publish(bridge.selectDisplayPage(name))
        return bridge.snapshot.displayPageName == name
    }

    private fun accept(fix: NavigationFix) {
        if (closed) return
        publish(
            bridge.pushFix(
                longitudeDeg = fix.coordinate.longitudeDeg,
                latitudeDeg = fix.coordinate.latitudeDeg,
                accuracyM = fix.horizontalAccuracyM,
                speedMps = fix.speedMps ?: 0.0,
                headingDeg = fix.courseDeg ?: Double.NaN,
                timestampMs = fix.timestampMs.coerceAtLeast(0),
            ),
        )
    }

    private fun publish(commands: List<NavCoreCommand>) {
        if (closed) return
        onSnapshot?.invoke(bridge.snapshot)
        execute(commands)
    }

    private fun execute(commands: List<NavCoreCommand>) {
        for (command in commands) {
            if (networkJobs.containsKey(command.requestId)) continue
            when (command.typeName) {
                "request_route" -> runRouteRequest(
                    RouteRequest(
                        requestId = command.requestId,
                        origin = WGS84Point(
                            longitudeDeg = command.originLongitudeDeg,
                            latitudeDeg = command.originLatitudeDeg,
                        ),
                        destination = WGS84Point(
                            longitudeDeg = command.destinationLongitudeDeg,
                            latitudeDeg = command.destinationLatitudeDeg,
                        ),
                        isReroute = command.reroute,
                        previousRouteId = bridge.snapshot.routeId.ifEmpty { null },
                        destinationPoiId = destinationPoiId,
                    ),
                )

                "request_traffic" -> {
                    // A traffic segment offset is measured from the beginning of
                    // the complete route. Refresh with that route's original
                    // endpoints, never with the rider's current position.
                    val baseline = activeRoute
                    if (baseline == null || baseline.plan.routeId != command.routeId) {
                        publish(bridge.rejectTraffic(command.requestId, clock()))
                        continue
                    }
                    runTrafficRequest(
                        RouteRequest(
                            requestId = command.requestId,
                            origin = baseline.origin,
                            destination = baseline.destination,
                            previousRouteId = baseline.plan.routeId,
                            destinationPoiId = baseline.destinationPoiId,
                        ),
                        baseline,
                    )
                }
            }
        }
    }

    // LAZY start: the job is registered before its body can run, even when the
    // provider answers without suspending (the preview-selected first route).
    private fun runRouteRequest(request: RouteRequest) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val envelope = routeProvider.route(request)
                if (envelope.requestId != request.requestId || closed) return@launch
                val previousGeneration = bridge.snapshot.routeGeneration
                val commands = bridge.acceptRoute(envelope.route, request.requestId, clock())
                if (bridge.snapshot.routeGeneration != previousGeneration &&
                    bridge.snapshot.routeId == envelope.route.routeId
                ) {
                    activeRoute = ActiveRoute(envelope.route, request.origin, request.destination, request.destinationPoiId)
                }
                publish(commands)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (closed) return@launch
                publish(bridge.rejectRoute(request.requestId, retryable = true, receivedAtMs = clock()))
                onFailure?.invoke(error.message ?: "路线规划失败")
            } finally {
                // A cancelled job from an earlier session must not unregister
                // a newer request that reuses its request ID.
                if (networkJobs[request.requestId] === coroutineContext[Job]) {
                    networkJobs.remove(request.requestId)
                }
            }
        }
        networkJobs[request.requestId] = job
        job.start()
    }

    private fun runTrafficRequest(request: RouteRequest, baseline: ActiveRoute) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val envelope = routeProvider.route(request)
                if (envelope.requestId != request.requestId || closed) return@launch
                // AMap can choose a different route during a refresh. Its
                // traffic offsets are unsafe for the displayed route unless
                // the complete GCJ-02 polyline still matches exactly.
                if (activeRoute?.plan?.routeId != baseline.plan.routeId ||
                    !TrafficRefreshPolicy.sharesCompleteRouteBaseline(baseline.plan, envelope.route)
                ) {
                    publish(bridge.rejectTraffic(request.requestId, clock()))
                    return@launch
                }
                val snapshot = bridge.snapshot
                val remainingDurationS = TrafficRefreshPolicy.remainingDurationSeconds(
                    refreshedCompleteDurationS = envelope.route.totalDurationS,
                    remainingDistanceM = snapshot.remainingDistanceM,
                    completeDistanceM = snapshot.totalDistanceM,
                )
                publish(
                    bridge.acceptTraffic(
                        routeId = baseline.plan.routeId,
                        segments = envelope.route.traffic,
                        remainingDurationS = remainingDurationS,
                        requestId = request.requestId,
                        observedAtMs = clock(),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!closed) publish(bridge.rejectTraffic(request.requestId, clock()))
            } finally {
                // A cancelled job from an earlier session must not unregister
                // a newer request that reuses its request ID.
                if (networkJobs[request.requestId] === coroutineContext[Job]) {
                    networkJobs.remove(request.requestId)
                }
            }
        }
        networkJobs[request.requestId] = job
        job.start()
    }
}
