package com.motogps.android.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.viewinterop.AndroidView
import com.motogps.android.map.SavedMapInfo
import com.motogps.android.viewmodel.ITALIAN_REGIONS
import com.motogps.android.viewmodel.NavigationViewModel
import com.motogps.android.viewmodel.PlaceSearchResult
import com.motogps.android.viewmodel.RouteOption
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun RouteMapView(
    geoPoints: List<GeoPoint>,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clipToBounds()
    ) {
        AndroidView(
            factory = { context ->
                Configuration.getInstance().userAgentValue = context.packageName
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    clipChildren = true
                    clipToPadding = true
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
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun MainMapView(
    currentLocation: Pair<Double, Double>?,
    destinationLocation: Pair<Double, Double>?,
    centerTrigger: Int,
    onMapLongPress: (Double, Double) -> Unit,
    onMapViewReady: (MapView) -> Unit,
    modifier: Modifier = Modifier
) {
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    LaunchedEffect(centerTrigger) {
        if (centerTrigger > 0 && mapViewInstance != null && currentLocation != null) {
            val point = GeoPoint(currentLocation.first, currentLocation.second)
            mapViewInstance?.controller?.animateTo(point)
            mapViewInstance?.controller?.setZoom(15.0)
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clipToBounds()
    ) {
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    clipChildren = true
                    clipToPadding = true
                    controller.setZoom(15.0)

                    val startPoint = if (currentLocation != null && (currentLocation.first != 0.0 || currentLocation.second != 0.0)) {
                        GeoPoint(currentLocation.first, currentLocation.second)
                    } else {
                        GeoPoint(41.9028, 12.4964) // Rome default
                    }
                    controller.setCenter(startPoint)

                    val mapEventsReceiver = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            p?.let {
                                onMapLongPress(it.latitude, it.longitude)
                            }
                            return true
                        }
                    }
                    overlays.add(MapEventsOverlay(mapEventsReceiver))

                    mapViewInstance = this
                    onMapViewReady(this)
                }
            },
            update = { mapView ->
                mapViewInstance = mapView
                onMapViewReady(mapView)

                mapView.overlays.removeAll { it !is MapEventsOverlay }

                if (currentLocation != null && (currentLocation.first != 0.0 || currentLocation.second != 0.0)) {
                    val locMarker = Marker(mapView).apply {
                        position = GeoPoint(currentLocation.first, currentLocation.second)
                        title = "La mia posizione"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    mapView.overlays.add(locMarker)
                }

                if (destinationLocation != null) {
                    val destMarker = Marker(mapView).apply {
                        position = GeoPoint(destinationLocation.first, destinationLocation.second)
                        title = "Destinazione"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    mapView.overlays.add(destMarker)
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )
    }
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
    isNavigating: Boolean = false,
    currentLocation: Pair<Double, Double>? = null,
    viewModel: NavigationViewModel? = null,
    onRouteOptionSelect: (Int) -> Unit,
    onRouteSelected: (PlaceSearchResult) -> Unit,
    onStartNavigation: (PlaceSearchResult) -> Unit,
    onStopNavigation: () -> Unit = {},
    onBackToSearch: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var currentGateway by remember { mutableStateOf(initialGateway) }
    var showGatewayDialog by remember { mutableStateOf(initialGateway.isBlank()) }
    var tempGateway by remember { mutableStateOf(initialGateway) }
    var showOfflineMapsDialog by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    var centerOnLocationTrigger by remember { mutableStateOf(0) }
    var activeMapView by remember { mutableStateOf<MapView?>(null) }
    var longPressDestination by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val downloadStatus by viewModel?.downloadStatus?.collectAsState() ?: remember { mutableStateOf(null) }
    val isDownloadingFromVm by viewModel?.isDownloadingMap?.collectAsState() ?: remember { mutableStateOf(false) }
    val savedMaps by viewModel?.savedMaps?.collectAsState() ?: remember { mutableStateOf(emptyList()) }

    var backPressedTime by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = routePreview == null) {
        val now = System.currentTimeMillis()
        if (now - backPressedTime < 2000) {
            (context as? android.app.Activity)?.finish()
        } else {
            backPressedTime = now
            Toast.makeText(context, "Premi ancora per uscire", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel?.refreshSavedMaps()
    }

    LaunchedEffect(downloadStatus) {
        downloadStatus?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    if (showOfflineMapsDialog) {
        AlertDialog(
            onDismissRequest = { showOfflineMapsDialog = false },
            title = { Text("Gestione Mappe Offline") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                    if (downloadStatus != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isDownloadingFromVm) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = downloadStatus ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isDownloadingFromVm) {
                                    IconButton(
                                        onClick = { viewModel?.cancelDownload() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Annulla", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel?.downloadCurrentAreaMap(context.applicationContext) },
                            enabled = !isDownloadingFromVm,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Area Attuale (10 km)", style = MaterialTheme.typography.labelSmall)
                        }

                        Button(
                            onClick = { viewModel?.downloadRouteCorridorMap(context.applicationContext) },
                            enabled = !isDownloadingFromVm && (routePreview != null || isNavigating),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Corridoio Percorso", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("Mappe Salvate nel Telefono:", style = MaterialTheme.typography.titleSmall)
                    if (savedMaps.isEmpty()) {
                        Text("Nessuna mappa offline salvata", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 120.dp).fillMaxWidth()) {
                            items(savedMaps) { mapInfo ->
                                val sizeMb = String.format(java.util.Locale.US, "%.1f MB", mapInfo.sizeBytes / (1024.0 * 1024.0))
                                ListItem(
                                    headlineContent = { Text(mapInfo.displayName, style = MaterialTheme.typography.bodyMedium) },
                                    supportingContent = { Text(sizeMb, style = MaterialTheme.typography.bodySmall) },
                                    trailingContent = {
                                        IconButton(onClick = { viewModel?.deleteSavedMap(mapInfo.fileName) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Elimina", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("Scarica Regione Italiana (20 Regioni):", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(ITALIAN_REGIONS) { region ->
                            ListItem(
                                headlineContent = { Text(region.name) },
                                trailingContent = {
                                    Button(
                                        onClick = { viewModel?.downloadRegionMap(context.applicationContext, region) },
                                        enabled = !isDownloadingFromVm
                                    ) {
                                        Text("Scarica", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOfflineMapsDialog = false }) {
                    Text("Chiudi")
                }
            }
        )
    }

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
                        .fillMaxWidth()
                        .height(220.dp)
                )

                HorizontalDivider()

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

                    OutlinedButton(
                        onClick = { viewModel?.downloadRouteCorridorMap(context.applicationContext) },
                        enabled = !isDownloadingFromVm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        if (isDownloadingFromVm) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download in corso...", style = MaterialTheme.typography.labelSmall)
                        } else {
                            Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Salva Mappa Corridoio (10 km)", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (isNavigating) {
                        Button(
                            onClick = onStopNavigation,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .height(56.dp)
                        ) {
                            Text("Termina Navigazione", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        Button(
                            onClick = { onStartNavigation(routePreview) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .height(56.dp)
                        ) {
                            Text("Avvia navigazione", style = MaterialTheme.typography.titleMedium)
                        }
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
                    trailingIcon = {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            if (searchQuery.isNotBlank()) {
                                onSearch(searchQuery)
                            }
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Cerca")
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        if (searchQuery.isNotBlank()) {
                            onSearch(searchQuery)
                        }
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { centerOnLocationTrigger++ },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Mostra Mappa / Mia Posizione", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = {
                            showOfflineMapsDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isDownloadingFromVm) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download...", style = MaterialTheme.typography.labelSmall)
                        } else {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Mappe Offline", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (isLoading) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Box(modifier = Modifier.height(60.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (searchResults.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Risultati di ricerca:",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            LazyColumn(modifier = Modifier.heightIn(max = 180.dp).fillMaxWidth()) {
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
                        }
                    }
                }

                MainMapView(
                    currentLocation = currentLocation,
                    destinationLocation = longPressDestination,
                    centerTrigger = centerOnLocationTrigger,
                    onMapLongPress = { lat, lon ->
                        longPressDestination = Pair(lat, lon)
                        val place = PlaceSearchResult(
                            name = "Punto sulla mappa",
                            address = String.format("Lat: %.4f, Lon: %.4f", lat, lon),
                            displayArea = "Mappa",
                            latitude = lat,
                            longitude = lon
                        )
                        onRouteSelected(place)
                    },
                    onMapViewReady = { mapView ->
                        activeMapView = mapView
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .weight(1f)
                )
            }
        }
    }
}
