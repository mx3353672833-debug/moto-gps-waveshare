package com.motogps.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.viewinterop.AndroidView
import com.motogps.android.viewmodel.PlaceSearchResult
import com.motogps.android.viewmodel.RouteOption
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

@Composable
fun RouteMapView(
    geoPoints: List<GeoPoint>,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
            }
        },
        update = { mapView ->
            mapView.overlays.clear()
            if (geoPoints.isNotEmpty()) {
                val polyline = Polyline().apply {
                    setPoints(geoPoints)
                    outlinePaint.color = android.graphics.Color.BLUE
                    outlinePaint.strokeWidth = 12f
                }
                mapView.overlays.add(polyline)

                val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
                mapView.post {
                    mapView.zoomToBoundingBox(boundingBox, true, 64)
                }
            }
            mapView.invalidate()
        },
        modifier = modifier
    )
}

fun extractGeoPoints(
    selectedRoute: RouteOption?,
    currentLocation: Pair<Double, Double>?,
    destination: PlaceSearchResult?
): List<GeoPoint> {
    val geoPoints = mutableListOf<GeoPoint>()

    if (selectedRoute != null) {
        val rawJson = selectedRoute.rawRouteJson
        val geomArray = rawJson.optJSONArray("geometry_gcj02")
            ?: rawJson.optJSONArray("polyline")
            ?: rawJson.optJSONArray("coordinates")

        if (geomArray != null) {
            for (i in 0 until geomArray.length()) {
                val item = geomArray.optJSONObject(i)
                if (item != null) {
                    val lat = item.optDouble("latitude_deg", item.optDouble("latitude", item.optDouble("lat", Double.NaN)))
                    val lon = item.optDouble("longitude_deg", item.optDouble("longitude", item.optDouble("lon", Double.NaN)))
                    if (!lat.isNaN() && !lon.isNaN()) {
                        geoPoints.add(GeoPoint(lat, lon))
                    }
                } else {
                    val coordArr = geomArray.optJSONArray(i)
                    if (coordArr != null && coordArr.length() >= 2) {
                        val lon = coordArr.optDouble(0, Double.NaN)
                        val lat = coordArr.optDouble(1, Double.NaN)
                        if (!lat.isNaN() && !lon.isNaN()) {
                            geoPoints.add(GeoPoint(lat, lon))
                        }
                    }
                }
            }
        }

        if (geoPoints.isEmpty()) {
            val stepsArr = rawJson.optJSONArray("steps") ?: rawJson.optJSONArray("maneuvers")
            if (stepsArr != null) {
                for (i in 0 until stepsArr.length()) {
                    val step = stepsArr.optJSONObject(i) ?: continue
                    val stepPoly = step.opt("polyline")
                    if (stepPoly is JSONArray) {
                        for (j in 0 until stepPoly.length()) {
                            val pt = stepPoly.optJSONObject(j) ?: continue
                            val lat = pt.optDouble("latitude_deg", pt.optDouble("latitude", pt.optDouble("lat", Double.NaN)))
                            val lon = pt.optDouble("longitude_deg", pt.optDouble("longitude", pt.optDouble("lon", Double.NaN)))
                            if (!lat.isNaN() && !lon.isNaN()) {
                                geoPoints.add(GeoPoint(lat, lon))
                            }
                        }
                    }
                }
            }
        }
    }

    if (geoPoints.isEmpty()) {
        if (currentLocation != null && (currentLocation.first != 0.0 || currentLocation.second != 0.0)) {
            geoPoints.add(GeoPoint(currentLocation.first, currentLocation.second))
        }
        if (destination != null) {
            geoPoints.add(GeoPoint(destination.latitude, destination.longitude))
        }
    }

    return geoPoints
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    initialGateway: String,
    onGatewaySave: (String) -> Unit,
    onSearch: (String) -> Unit,
    onConnectRequest: () -> Unit,
    connectionState: String,
    isLoading: Boolean,
    searchResults: List<PlaceSearchResult>,
    routePreview: PlaceSearchResult?,
    routeOptions: List<RouteOption>,
    selectedRouteIndex: Int,
    currentLocation: Pair<Double, Double>? = null,
    onRouteOptionSelect: (Int) -> Unit,
    onRouteSelected: (PlaceSearchResult) -> Unit,
    onStartNavigation: (PlaceSearchResult) -> Unit,
    onBackToSearch: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var currentGateway by remember { mutableStateOf(initialGateway) }
    var showGatewayDialog by remember { mutableStateOf(initialGateway.isBlank()) }
    var tempGateway by remember { mutableStateOf(initialGateway) }
    val focusManager = LocalFocusManager.current

    if (showGatewayDialog) {
        AlertDialog(
            onDismissRequest = {
                if (currentGateway.isNotBlank()) showGatewayDialog = false
            },
            title = { Text("Configurazione Gateway") },
            text = {
                OutlinedTextField(
                    value = tempGateway,
                    onValueChange = { tempGateway = it },
                    label = { Text("Indirizzo Gateway") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempGateway.isNotBlank()) {
                            onGatewaySave(tempGateway)
                            currentGateway = tempGateway
                            showGatewayDialog = false
                        }
                    }
                ) {
                    Text("Salva")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            if (routePreview != null) {
                TopAppBar(
                    title = { Text("Percorso") },
                    navigationIcon = {
                        IconButton(onClick = onBackToSearch) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                        }
                    },
                    actions = {
                        TextButton(onClick = onBackToSearch) {
                            Text("Cambia", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("MOTO GPS") },
                    actions = {
                        IconButton(onClick = { showGatewayDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Impostazioni Gateway")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (routePreview != null) {
                val selectedRoute = routeOptions.getOrNull(selectedRouteIndex)
                val geoPoints = remember(selectedRoute, currentLocation, routePreview) {
                    extractGeoPoints(selectedRoute, currentLocation, routePreview)
                }

                RouteMapView(
                    geoPoints = geoPoints,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                HorizontalDivider()

                // Bottom Half: Seleziona percorso
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        text = "Seleziona percorso (OSM)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )

                    if (isLoading) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (routeOptions.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            itemsIndexed(routeOptions) { index, option ->
                                val isSelected = index == selectedRouteIndex
                                val mins = option.durationSeconds / 60
                                val km = String.format("%.1f", option.distanceMeters / 1000.0)
                                ListItem(
                                    headlineContent = { Text(option.name) },
                                    supportingContent = { Text("$mins min · $km km") },
                                    trailingContent = {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Selezionato",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    modifier = Modifier.clickable { onRouteOptionSelect(index) }
                                )
                                HorizontalDivider()
                            }
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Calcolo del percorso in corso...")
                        }
                    }

                    Button(
                        onClick = { onStartNavigation(routePreview) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(56.dp)
                    ) {
                        Text("Avvia navigazione", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Cerca destinazione") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        onSearch(searchQuery)
                    })
                )

                ListItem(
                    headlineContent = { Text("Il mio display") },
                    supportingContent = { Text(connectionState) },
                    leadingContent = {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    },
                    modifier = Modifier.clickable { onConnectRequest() }
                )

                HorizontalDivider()

                if (isLoading) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (searchResults.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(searchResults) { place ->
                            ListItem(
                                headlineContent = { Text(place.name) },
                                supportingContent = {
                                    val subtitle = listOf(place.displayArea, place.address).filter { it.isNotBlank() }.joinToString(" · ")
                                    Text(subtitle)
                                },
                                modifier = Modifier.clickable {
                                    onRouteSelected(place)
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                } else {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Seleziona destinazione", style = MaterialTheme.typography.titleMedium)
                            Text("Cerca un luogo o un indirizzo, scegli il percorso e parti.")
                        }
                    }
                }
            }
        }
    }
}
