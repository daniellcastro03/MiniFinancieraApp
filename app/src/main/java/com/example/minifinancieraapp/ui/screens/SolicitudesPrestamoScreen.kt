package com.example.capitalexpressapp.ui.screens

import com.example.minifinancieraapp.ui.models.SolicitudModel
import com.example.capitalexpressapp.core.formatearLempiras
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolicitudesPrestamoScreen(navController: NavController) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    var solicitudes by remember { mutableStateOf(listOf<SolicitudModel>()) }
    var isLoading by remember { mutableStateOf(false) }

    // Función para cargar solicitudes
    fun cargarSolicitudes() {
        scope.launch {
            try {
                isLoading = true
                val snapshot = db.collection("solicitudes_prestamo").get().await()
                solicitudes = snapshot.documents.mapNotNull { doc ->
                    try {
                        val id = doc.id
                        val cliente = doc.getString("cliente") ?: return@mapNotNull null
                        val clienteId = doc.getString("clienteId") ?: doc.getString("cliente_id") ?: ""

                        // Manejar fechas - puede ser String o Timestamp
                        val fechaField = doc.get("fecha")
                        val fechaString = when (fechaField) {
                            is Timestamp -> formatter.format(fechaField.toDate())
                            is String -> fechaField
                            else -> formatter.format(Date())
                        }

                        SolicitudModel(
                            id = id,
                            cliente = cliente,
                            clienteId = clienteId,
                            monto = doc.getDouble("monto") ?: 0.0,
                            interes = doc.getDouble("interes") ?: 0.0,
                            interesMensual = doc.getDouble("interesMensual") ?: 0.0,
                            interesTotal = doc.getDouble("interesTotal") ?: 0.0,
                            totalPagar = doc.getDouble("totalPagar") ?: 0.0,
                            cuota = doc.getDouble("cuota") ?: 0.0,
                            cuotas = doc.getLong("cuotas")?.toInt() ?: 1,
                            plazo = doc.getString("plazo") ?: "",
                            fecha = fechaString,
                            lugar = doc.getString("lugar") ?: "",
                            firma = doc.getString("firma") ?: "",
                            observaciones = doc.getString("observaciones") ?: "",
                            cobradorSolicitante = doc.getString("cobradorSolicitante") ?: "",
                            fotos = doc.get("fotos") as? List<String> ?: emptyList(),
                            diasEfectivos = doc.getLong("diasEfectivos")?.toInt() ?: 0,
                            estado = doc.getString("estado") ?: "",
                            garantia = doc.getString("garantia") ?: "",
                            cobrador = doc.getString("cobrador") ?: "",
                            cobradorUid = doc.getString("cobradorUid"),
                            mora = doc.getDouble("mora") ?: 0.0
                        )
                    } catch (e: Exception) {
                        println("Error al procesar solicitud: ${e.message}")
                        null
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error al cargar solicitudes: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    // Función para aprobar solicitud desde la lista (opcional)
    fun aprobarSolicitudRapida(solicitud: SolicitudModel) {
        scope.launch {
            try {
                isLoading = true
                val docRef = db.collection("prestamos").document()
                val prestamoId = docRef.id

                // Convertir la fecha string a Calendar
                val fechaCalendar = Calendar.getInstance()
                try {
                    fechaCalendar.time = formatter.parse(solicitud.fecha) ?: Date()
                } catch (e: Exception) {
                    fechaCalendar.time = Date()
                }

                // Calcular la próxima fecha de pago
                val proximoCal = fechaCalendar.clone() as Calendar
                when (solicitud.plazo) {
                    "Diario" -> proximoCal.add(Calendar.DAY_OF_YEAR, 1)
                    "Lunes a Sábado" -> {
                        do {
                            proximoCal.add(Calendar.DAY_OF_YEAR, 1)
                        } while (proximoCal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                    }
                    "Semanal" -> proximoCal.add(Calendar.DAY_OF_YEAR, 7)
                    "Quincenal" -> proximoCal.add(Calendar.DAY_OF_YEAR, 15)
                    "Mensual" -> proximoCal.add(Calendar.DAY_OF_YEAR, 30)
                    "Bimestral" -> proximoCal.add(Calendar.DAY_OF_YEAR, 60)
                    else -> proximoCal.add(Calendar.DAY_OF_YEAR, 30)
                }

                val proximoPagoTimestamp = Timestamp(proximoCal.time)

                val prestamo = hashMapOf(
                    "id" to prestamoId,
                    "prestamoId" to prestamoId,
                    "cliente" to solicitud.cliente,
                    "clienteId" to solicitud.clienteId,
                    "monto" to solicitud.monto,
                    "interes" to solicitud.interes,
                    "interesMensual" to solicitud.interesMensual,
                    "interesTotal" to solicitud.interesTotal,
                    "totalPagar" to solicitud.totalPagar,
                    "cuota" to solicitud.cuota,
                    "cuotas" to solicitud.cuotas,
                    "plazo" to solicitud.plazo,
                    "fecha" to Timestamp(fechaCalendar.time),
                    "fechaCreacion" to Timestamp(Date()),
                    "lugar" to solicitud.lugar,
                    "firma" to solicitud.firma,
                    "cobrador" to "Administrador",
                    "cobradorAsignado" to (solicitud.cobradorUid ?: ""),
                    "proximoPago" to proximoPagoTimestamp,
                    "montoPagado" to 0.0,
                    "saldoAnterior" to solicitud.monto,
                    "saldo" to solicitud.totalPagar,
                    "estado" to "activo",
                    "observaciones" to solicitud.observaciones,
                    "fotos" to solicitud.fotos,
                    "diasEfectivos" to solicitud.diasEfectivos,
                    "garantia" to solicitud.garantia,
                    "mora" to solicitud.mora
                )

                docRef.set(prestamo).await()
                db.collection("solicitudes_prestamo").document(solicitud.id).delete().await()

                Toast.makeText(context, "Préstamo aprobado correctamente", Toast.LENGTH_LONG).show()
                cargarSolicitudes()

            } catch (e: Exception) {
                Toast.makeText(context, "Error al aprobar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
            }
        }
    }

    // Función para rechazar solicitud
    fun rechazarSolicitud(solicitud: SolicitudModel) {
        scope.launch {
            try {
                isLoading = true
                db.collection("solicitudes_prestamo").document(solicitud.id).delete().await()
                Toast.makeText(context, "Solicitud rechazada", Toast.LENGTH_SHORT).show()
                cargarSolicitudes()
            } catch (e: Exception) {
                Toast.makeText(context, "Error al rechazar: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    // Cargar solicitudes al inicio
    LaunchedEffect(Unit) {
        cargarSolicitudes()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Solicitudes de Préstamo") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = { cargarSolicitudes() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                    }
                }
            )
        }
    ) { padding ->
        Box(
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
                    CircularProgressIndicator()
                }
            } else if (solicitudes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Assignment,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No hay solicitudes pendientes",
                            color = Color.Gray,
                            fontSize = 18.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(solicitudes, key = { it.id }) { solicitud ->
                        SolicitudCard(
                            solicitud = solicitud,
                            onVerDetalle = {
                                // 🔥 CAMBIO PRINCIPAL: Navegar con solo el ID
                                navController.navigate("detalleSolicitud/${solicitud.id}")
                            },
                            onAprobar = { aprobarSolicitudRapida(solicitud) },
                            onRechazar = { rechazarSolicitud(solicitud) },
                            isLoading = isLoading
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
    onVerDetalle: () -> Unit,
    onAprobar: () -> Unit,
    onRechazar: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Encabezado
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = solicitud.cliente,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = solicitud.fecha,
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Detalles financieros
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Monto:", fontWeight = FontWeight.Bold)
                            Text(formatearLempiras(solicitud.monto))
                        }
                        Column {
                            Text("Interés:", fontWeight = FontWeight.Bold)
                            Text("%.1f%%".format(solicitud.interesMensual))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Total a pagar:", fontWeight = FontWeight.Bold)
                            Text(formatearLempiras(solicitud.totalPagar))
                        }
                        Column {
                            Text("Cuota:", fontWeight = FontWeight.Bold)
                            Text(formatearLempiras(solicitud.cuota))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Detalles adicionales
            Text("Cuotas: ${solicitud.cuotas} (${solicitud.plazo})")
            Text("Lugar: ${solicitud.lugar}")
            if (solicitud.cobradorSolicitante.isNotBlank()) {
                Text("Cobrador: ${solicitud.cobradorSolicitante}")
            }

            if (solicitud.observaciones.isNotBlank()) {
                Text("Observaciones: ${solicitud.observaciones}")
            }

            if (solicitud.diasEfectivos > 0) {
                Text("Días efectivos: ${solicitud.diasEfectivos}")
            }

            // Mostrar fotos si existen
            if (solicitud.fotos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Fotos adjuntas (${solicitud.fotos.size}):", fontWeight = FontWeight.Bold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    items(solicitud.fotos, key = { it }) { fotoUri ->
                        val fotoContext = LocalContext.current
                        Card(
                            modifier = Modifier.size(60.dp)
                        ) {
                            Image(
                                painter = rememberAsyncImagePainter(
                                    model = coil.request.ImageRequest.Builder(fotoContext)
                                        .data(Uri.parse(fotoUri))
                                        .size(150, 150)
                                        .build()
                                ),
                                contentDescription = "Foto adjunta",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Botones de acción
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(
                    onClick = onVerDetalle,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ver Detalle")
                }

                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

suspend fun confirmDialog(
    context: Context,
    mensaje: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    withContext(Dispatchers.Main) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setMessage(mensaje)
            .setPositiveButton("Sí") { _, _ -> onConfirm() }
            .setNegativeButton("No") { _, _ -> onDismiss() }
            .setCancelable(false)
            .show()
    }
}