package com.example.minifinancieraapp.ui.screens

import com.example.minifinancieraapp.ui.models.SolicitudModel
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolicitudesAdminScreen(
    navController: NavController,
    uid: String = "",
    rol: String = "admin"
) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var solicitudes by remember { mutableStateOf(listOf<SolicitudModel>()) }
    var isProcessing by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val snapshot = db.collection("solicitudes_prestamo")
                .whereEqualTo("estado", "pendiente")
                .get().await()

            val nuevasSolicitudes = snapshot.documents.mapNotNull { doc ->
                doc.data?.plus("id" to doc.id)?.toSolicitudModel()
            }
            solicitudes = nuevasSolicitudes
            isLoading = false
        } catch (e: Exception) {
            Toast.makeText(context, "Error cargando solicitudes: ${e.message}", Toast.LENGTH_SHORT).show()
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Solicitudes de Préstamo",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Cargando solicitudes...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (solicitudes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "No hay solicitudes pendientes",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Todas las solicitudes han sido procesadas",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(solicitudes) { solicitud ->
                        SolicitudCard(
                            solicitud = solicitud,
                            navController = navController,
                            db = db,
                            context = context,
                            scope = scope,
                            isProcessing = isProcessing,
                            onProcessingChange = { isProcessing = it },
                            onSolicitudProcesada = { solicitudId ->
                                solicitudes = solicitudes.filterNot { it.id == solicitudId }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SolicitudCard(
    solicitud: SolicitudModel,
    navController: NavController,
    db: FirebaseFirestore,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    isProcessing: Boolean,
    onProcessingChange: (Boolean) -> Unit,
    onSolicitudProcesada: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (solicitud.id.isNotBlank()) {
                    navController.navigate("detalleSolicitud/${solicitud.id}")
                } else {
                    Toast.makeText(context, "Error: ID de solicitud no válido", Toast.LENGTH_SHORT).show()
                }
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Información básica
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = solicitud.cliente,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "ID: ${solicitud.clienteId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "L. ${"%.2f".format(solicitud.monto)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // Detalles del préstamo
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cuotas: ${solicitud.cuotas}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Plazo: ${solicitud.plazo}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Interés: ${"%.1f".format(solicitud.interesMensual)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Fecha: ${solicitud.fecha}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (solicitud.lugar.isNotBlank()) {
                Text(
                    text = "Lugar: ${solicitud.lugar}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (solicitud.observaciones.isNotBlank()) {
                Text(
                    text = "Observaciones: ${solicitud.observaciones}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Mostrar fotos si existen
            if (solicitud.fotos.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Fotos adjuntas (${solicitud.fotos.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(solicitud.fotos.take(3)) { url ->
                            Image(
                                painter = rememberAsyncImagePainter(url),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(60.dp)
                                    .clickable { },
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (solicitud.fotos.size > 3) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+${solicitud.fotos.size - 3}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // Botones de acción
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Botón Ver Detalles
                OutlinedButton(
                    onClick = {
                        if (solicitud.id.isNotBlank()) {
                            // Navegar a detalle de SOLICITUD, no de préstamo
                            navController.navigate("detalleSolicitud/${solicitud.id}")
                        } else {
                            Toast.makeText(context, "Error: ID de solicitud no válido", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = solicitud.id.isNotBlank()
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ver Detalles")
                }
            }

                // Botones de aprobación y rechazo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Botón Rechazar
                    Button(
                        onClick = {
                            if (!isProcessing) {
                                scope.launch {
                                    onProcessingChange(true)
                                    try {
                                        db.collection("solicitudes_prestamo")
                                            .document(solicitud.id)
                                            .update("estado", "rechazado")
                                            .await()

                                        onSolicitudProcesada(solicitud.id)
                                        Toast.makeText(
                                            context,
                                            "Solicitud rechazada",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Error al rechazar solicitud: ${e.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } finally {
                                        onProcessingChange(false)
                                    }
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE53935)
                        )
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rechazar")
                    }

                    // Botón Aprobar
                    Button(
                        onClick = {
                            if (!isProcessing) {
                                onProcessingChange(true)
                                scope.launch {
                                    try {
                                        aceptarSolicitudYGenerarRecibo(
                                            solicitud = solicitud,
                                            context = context,
                                            db = db,
                                            onSuccess = {
                                                onSolicitudProcesada(solicitud.id)
                                            }
                                        )
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Error al aprobar solicitud: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        onProcessingChange(false)
                                    }
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text("Aprobar")
                    }
                }
            }
        }
    }
