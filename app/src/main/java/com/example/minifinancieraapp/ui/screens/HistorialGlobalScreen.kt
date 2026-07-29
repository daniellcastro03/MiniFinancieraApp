package com.example.minifinancieraapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.core.formatearLempiras
import com.example.capitalexpressapp.ui.theme.CEColors
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import com.example.minifinancieraapp.ui.models.PagoItem
import java.text.SimpleDateFormat
import java.util.*

data class PagoGlobal(
    val clienteNombre: String = "",
    val monto: Double = 0.0,
    val fechaPago: Date? = null,
    val cobradorAsignado: String = "",
    val metodoPago: String = "",
    val lugar: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialGlobalScreen(navController: NavController) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    var pagos by remember { mutableStateOf(listOf<PagoGlobal>()) }
    var pagosFiltrados by remember { mutableStateOf(listOf<PagoGlobal>()) }
    var cargando by remember { mutableStateOf(true) }

    val formato = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val formatoHora = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formatoMoneda = remember { NumberFormat.getCurrencyInstance(Locale("es", "HN")) }

    val opciones = listOf("Todos", "Hoy", "Semana", "Mes", "Año")
    // Por defecto "Hoy" para no renderizar todo el historial de golpe
    var opcionSeleccionada by remember { mutableStateOf("Hoy") }

    fun aplicarFiltro() {
        val hoy = Calendar.getInstance()
        pagosFiltrados = when (opcionSeleccionada) {
            "Hoy" -> pagos.filter {
                it.fechaPago?.let {
                    val fecha = Calendar.getInstance().apply { time = it }
                    fecha.get(Calendar.YEAR) == hoy.get(Calendar.YEAR) &&
                            fecha.get(Calendar.DAY_OF_YEAR) == hoy.get(Calendar.DAY_OF_YEAR)
                } ?: false
            }
            "Semana" -> {
                val hace7dias = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.time
                pagos.filter { it.fechaPago != null && it.fechaPago.after(hace7dias) }
            }
            "Mes" -> pagos.filter {
                it.fechaPago?.let {
                    val fecha = Calendar.getInstance().apply { time = it }
                    fecha.get(Calendar.YEAR) == hoy.get(Calendar.YEAR) &&
                            fecha.get(Calendar.MONTH) == hoy.get(Calendar.MONTH)
                } ?: false
            }
            "Año" -> pagos.filter {
                it.fechaPago?.let {
                    val fecha = Calendar.getInstance().apply { time = it }
                    fecha.get(Calendar.YEAR) == hoy.get(Calendar.YEAR)
                } ?: false
            }
            else -> pagos
        }
    }

    LaunchedEffect(Unit) {
        try {
            val snapshot = db.collection("pagos").get().await()
            val lista = snapshot.documents.mapNotNull { doc ->
                val fecha = try {
                    when (val rawFecha = doc.get("fechaPago")) {
                        is Timestamp -> rawFecha.toDate()
                        is String -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(rawFecha)
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }

                PagoGlobal(
                    clienteNombre = doc.getString("clienteNombre") ?: "Cliente Desconocido",
                    monto = doc.getDouble("monto") ?: 0.0,
                    fechaPago = fecha,
                    cobradorAsignado = doc.getString("cobradorAsignado") ?: "Sin asignar",
                    metodoPago = doc.getString("metodoPago") ?: "Efectivo",
                    lugar = doc.getString("lugar") ?: ""
                )
            }.sortedByDescending { it.fechaPago }

            pagos = lista
            aplicarFiltro()
        } catch (e: Exception) {
            pagos = emptyList()
            pagosFiltrados = emptyList()
        } finally {
            cargando = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Historial Global",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CEColors.Primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        // Un solo LazyColumn (header, filtros y lista) para que el scroll mueva
        // toda la pantalla, sin controles fijos arriba.
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (cargando) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = CEColors.Primary,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Cargando historial...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray
                            )
                        }
                    }
                }
            } else {
                item {
                    // Header con estadísticas
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CEColors.Primary),
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Resumen de Pagos",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "${pagosFiltrados.size}",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Pagos",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        formatearLempiras(pagosFiltrados.sumOf { it.monto }),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Total",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    // Filtros
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                Icon(
                                    Icons.Default.CalendarToday,
                                    contentDescription = null,
                                    tint = CEColors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Filtrar por período",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(opciones) { opcion ->
                                    FilterChip(
                                        selected = opcion == opcionSeleccionada,
                                        onClick = {
                                            opcionSeleccionada = opcion
                                            aplicarFiltro()
                                        },
                                        label = { Text(opcion) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = CEColors.Primary,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }

                // Lista de pagos
                if (pagosFiltrados.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Assignment,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = Color(0xFFBDBDBD)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "No hay pagos registrados",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.Gray
                                )
                                Text(
                                    "en el período seleccionado",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                } else {
                    items(pagosFiltrados) { pago ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                elevation = CardDefaults.cardElevation(3.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    // Header del pago
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = CEColors.Primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    pago.clienteNombre,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = CEColors.Primary.copy(alpha = 0.1f)
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.AttachMoney,
                                                    contentDescription = null,
                                                    tint = CEColors.Primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    formatearLempiras(pago.monto),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = CEColors.Primary
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Detalles del pago
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Fecha:",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.Gray
                                            )
                                            Text(
                                                pago.fechaPago?.let { formato.format(it) } ?: "Sin fecha",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )

                                            if (pago.fechaPago != null) {
                                                Text(
                                                    formatoHora.format(pago.fechaPago),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.Gray
                                                )
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Cobrador:",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.Gray
                                            )
                                            Text(
                                                pago.cobradorAsignado,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }

                                    if (pago.metodoPago.isNotEmpty() || pago.lugar.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            if (pago.metodoPago.isNotEmpty()) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "Método:",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.Gray
                                                    )
                                                    Text(
                                                        pago.metodoPago,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                }
                                            }

                                            if (pago.lugar.isNotEmpty()) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "Lugar:",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.Gray
                                                    )
                                                    Text(
                                                        pago.lugar,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Espaciado final
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }