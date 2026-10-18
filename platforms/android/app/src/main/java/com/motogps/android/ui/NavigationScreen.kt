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
import com.motogps.android.viewmodel.PlaceSearchResult
import com.motogps.android.viewmodel.RouteOption

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
                // Top Half: Map View Placeholder
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Mappa: Vista Percorso (OSM)", style = MaterialTheme.typography.titleMedium)
                }

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
