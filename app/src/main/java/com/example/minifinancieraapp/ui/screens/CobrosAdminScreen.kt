package com.example.capitalexpressapp.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.core.formatearLempiras
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

data class PrestamoResumen(
    val id: String,
    val clienteId: String, // ✅ NUEVO
    val cliente: String,
    val monto: Double,
    val totalPagar: Double,
    val montoPagado: Double,
    val saldo: Double,
    val cobrador: String,
    val fecha: String,
    val proximoPago: String,
    val plazo: String,
    val cuota: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CobrosAdminScreen(navController: NavController, adminUid: String) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    var prestamos by remember { mutableStateOf<List<PrestamoResumen>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }

    fun cargarPrestamos() {
        scope.launch {
            isLoading = true
            errorMessage = ""
            try {
                val snapshot = db.collection("prestamos")
                    .whereEqualTo("estado", "activo")
                    .get()
                    .await()

                prestamos = snapshot.documents.mapNotNull { doc ->
                    try {
                        val id = doc.id
                        val cliente = doc.getString("cliente") ?: return@mapNotNull null
                        val clienteId = doc.getString("clienteId") ?: return@mapNotNull null // ✅
                        val monto = doc.getDouble("monto") ?: 0.0
                        val totalPagar = doc.getDouble("totalPagar") ?: monto
                        val montoPagado = doc.getDouble("montoPagado") ?: 0.0
                        val saldo = totalPagar - montoPagado
                        val cobrador = doc.getString("cobrador") ?: "Sin asignar"
                        val plazo = doc.getString("plazo") ?: "N/A"
                        val cuota = doc.getDouble("cuota") ?: 0.0

                        val fecha = when (val fechaData = doc.get("fecha")) {
                            is Timestamp -> formatter.format(fechaData.toDate())
                            is String -> fechaData
                            else -> "-"
                        }

                        val proximoPago = when (val proximoData = doc.get("proximoPago")) {
                            is Timestamp -> formatter.format(proximoData.toDate())
                            is String -> proximoData
                            else -> "-"
                        }

                        PrestamoResumen(
                            id = id,
                            clienteId = clienteId, // ✅
                            cliente = cliente,
                            monto = monto,
                            totalPagar = totalPagar,
                            montoPagado = montoPagado,
                            saldo = saldo,
                            cobrador = cobrador,
                            fecha = fecha,
                            proximoPago = proximoPago,
                            plazo = plazo,
                            cuota = cuota
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                errorMessage = "Error al cargar préstamos: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        cargarPrestamos()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cobros - Administrador") },
                actions = {
                    IconButton(onClick = { cargarPrestamos() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                errorMessage.isNotEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { cargarPrestamos() }) {
                            Text("Reintentar")
                        }
                    }
                }

                prestamos.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No hay préstamos activos",
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { cargarPrestamos() }) {
                                Text("Actualizar")
                            }
                        }
                    }
                }

                else -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Assessment, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Resumen General", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Total préstamos: ${prestamos.size}")
                            Text("Monto prestado: ${formatearLempiras(prestamos.sumOf { it.monto })}")
                            Text("Total a cobrar: ${formatearLempiras(prestamos.sumOf { it.totalPagar })}")
                            Text("Ya pagado: ${formatearLempiras(prestamos.sumOf { it.montoPagado })}")
                            Text("Saldo pendiente: ${formatearLempiras(prestamos.sumOf { it.saldo })}")
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(prestamos, key = { it.id }) { prestamo ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val ruta = "RegistrarPagoScreen/" +
                                                "${prestamo.clienteId}/" + // ✅ ID correcto
                                                "${prestamo.id}/" +
                                                "${prestamo.saldo}/" +
                                                adminUid
                                        navController.navigate(ruta)
                                    },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        prestamo.cliente,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1976D2)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Monto: ${formatearLempiras(prestamo.monto)}")
                                            Text("Total: ${formatearLempiras(prestamo.totalPagar)}")
                                            Text("Pagado: ${formatearLempiras(prestamo.montoPagado)}")
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Plazo: ${prestamo.plazo}")
                                            Text("Cuota: ${formatearLempiras(prestamo.cuota)}")
                                            Text("Cobrador: ${prestamo.cobrador}")
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Fecha: ${prestamo.fecha}")
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Próximo pago: ${prestamo.proximoPago}")
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (prestamo.saldo > 0) Color(0xFFFFEBEE) else Color(0xFFE8F5E8)
                                        )
                                    ) {
                                        Text(
                                            "Saldo: ${formatearLempiras(prestamo.saldo)}",
                                            modifier = Modifier.padding(8.dp),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (prestamo.saldo > 0) Color(0xFFD32F2F) else Color(0xFF388E3C)
                                        )
                                    }
                                    if (prestamo.totalPagar > 0) {
                                        val progreso = (prestamo.montoPagado / prestamo.totalPagar).toFloat()
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = progreso,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            "Progreso: ${(progreso * 100).toInt()}%",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
