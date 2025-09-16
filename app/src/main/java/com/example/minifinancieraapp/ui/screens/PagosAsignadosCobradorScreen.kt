package com.example.minifinancieraapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Money
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.minifinancieraapp.util.SessionManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagosAsignadosCobradorScreen(navController: NavController) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val session = remember { SessionManager(context) }

    val uidCobrador = session.getUid()
    var pagos by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        Log.d("PagosCobrador", "UID del cobrador logueado (para filtro): $uidCobrador")

        if (uidCobrador.isNullOrEmpty()) {
            Toast.makeText(context, "Error: No se pudo obtener la sesión del cobrador.", Toast.LENGTH_LONG).show()
            Log.e("PagosCobrador", "UID del cobrador es nulo o vacío. No se pueden cargar pagos.")
            isLoading = false
            return@LaunchedEffect
        }

        try {
            val snapshot = db.collection("pagos")
                .whereEqualTo("registradoPor", uidCobrador)
                .get()
                .await()

            pagos = snapshot.documents.mapNotNull { it.data }
            Log.d("PagosCobrador", "Pagos cargados para UID $uidCobrador: ${pagos.size}")
        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar pagos: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("PagosCobrador", "Error al cargar pagos", e)
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Mis Pagos Registrados",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0061A7)
                )
            )
        }
    ) { padding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF5F7FA),
                            Color(0xFFE8EEF5)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                // Header con estadísticas
                if (pagos.isNotEmpty()) {
                    StatisticsCard(pagos = pagos)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF0061A7),
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Cargando pagos...",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    pagos.isEmpty() -> {
                        EmptyStateCard()
                    }

                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            items(pagos) { pago ->
                                PagoCard(
                                    pago = pago,
                                    session = session,
                                    context = context
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatisticsCard(pagos: List<Map<String, Any>>) {
    val totalPagos = pagos.size
    val montoTotal = pagos.sumOf { (it["monto"] as? Number)?.toDouble() ?: 0.0 }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0061A7)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatisticItem(
                icon = Icons.Default.Assignment,
                value = totalPagos.toString(),
                label = "Total Pagos",
                color = Color.White
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(Color.White.copy(alpha = 0.3f))
            )

            StatisticItem(
                icon = Icons.Default.Money,
                value = "L. %.0f".format(montoTotal),
                label = "Monto Total",
                color = Color.White
            )
        }
    }
}

@Composable
fun StatisticItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = color.copy(alpha = 0.8f),
            fontSize = 12.sp
        )
    }
}

@Composable
fun EmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Assignment,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No hay pagos registrados",
                style = MaterialTheme.typography.titleMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Los pagos que registres aparecerán aquí",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PagoCard(
    pago: Map<String, Any>,
    session: SessionManager,
    context: android.content.Context
) {
    val cliente = pago["clienteNombre"]?.toString() ?: "-"
    val monto = (pago["monto"] as? Number)?.toDouble() ?: 0.0
    val cuota = pago["numeroCuota"]?.toString() ?: pago["cuotas"]?.toString() ?: "-"
    val prestamoId = pago["prestamoId"]?.toString() ?: "-"
    val numeroPrestamo = pago["numeroPrestamo"]?.toString() ?: "-"
    val fechaTimestamp = pago["fechaPago"] as? Timestamp
    val metodo = pago["metodoPago"]?.toString() ?: "-"
    val saldo = (pago["saldoRestante"] as? Number)?.toDouble() ?: 0.0
    val nombreCobrador = pago["nombreCobrador"]?.toString() ?: session.getNombre()

    val fechaFormateada = fechaTimestamp?.toDate()?.let {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(it)
    } ?: "-"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Header del pago
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pago #$cuota",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0061A7)
                )

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF4CAF50).copy(alpha = 0.1f)
                ) {
                    Text(
                        text = "L. %.2f".format(monto),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Información del cliente y préstamo
            InfoRow(
                icon = Icons.Default.Person,
                label = "Cliente",
                value = cliente
            )

            InfoRow(
                icon = Icons.Default.AccountBalance,
                label = "Préstamo",
                value = "#$numeroPrestamo"
            )

            InfoRow(
                icon = Icons.Default.Money,
                label = "Saldo restante",
                value = "L. %.2f".format(saldo)
            )

            InfoRow(
                icon = Icons.Default.CalendarToday,
                label = "Fecha y hora",
                value = fechaFormateada
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Información del método de pago
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF5F7FA)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Método:",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = metodo,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF0061A7)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Botón de reimprimir
            FilledTonalButton(
                onClick = {
                    val file = ReciboHelper.generarReciboPDF(
                        context = context,
                        cliente = cliente,
                        prestamoId = prestamoId,
                        fecha = fechaFormateada,
                        montoPagado = monto.toString(),
                        saldoAnterior = monto + saldo,
                        proximoPago = "Próxima no disponible",
                        cuota = cuota,
                        cobrador = nombreCobrador,
                        lugar = pago["lugar"]?.toString() ?: "-",
                        firma = pago["firma"]?.toString() ?: "-",
                        tipoPago = metodo,
                        mora = (pago["mora"] as? Number)?.toDouble() ?: 0.0
                    )
                    file?.let { ReciboHelper.imprimirPDF(context, it) }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF0061A7).copy(alpha = 0.1f),
                    contentColor = Color(0xFF0061A7)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Print,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Reimprimir Recibo",
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "$label:",
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF333333)
        )
    }
}