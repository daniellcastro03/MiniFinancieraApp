package com.example.capitalexpressapp.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.capitalexpressapp.util.NetworkUtils.isInternetAvailable
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

// Data class para pago individual
data class PagoDetalle(
    val docId: String,
    val cliente: String,
    val prestamoId: String,
    val fecha: String,
    val monto: Double,
    val mora: Double = 0.0,
    val interesTotal: Double = 0.0,
    val cuota: String,
    val cobrador: String,
    val lugar: String = "",
    val firma: String = "",
    val tipoPago: String = "Efectivo",
    val saldoRestante: Double = 0.0,
    val numeroPrestamo: Int = 0,
    val metodoPago: String = "Efectivo",
    val observaciones: String = ""
)

// Data class para información del préstamo
data class PrestamoInfo(
    val cliente: String,
    val monto: Double,
    val fechaInicio: String,
    val numeroPrestamo: Int,
    val saldoActual: Double,
    val estado: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialPagosPrestamoScreen(
    navController: NavController,
    prestamoId: String,
    clienteId: String,
    rol: String
) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    var pagos by remember { mutableStateOf(listOf<PagoDetalle>()) }
    var prestamoInfo by remember { mutableStateOf<PrestamoInfo?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var pagoAEliminar by remember { mutableStateOf<PagoDetalle?>(null) }

    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    LaunchedEffect(prestamoId) {
        try {
            val prefs = context.getSharedPreferences("offline_data", Context.MODE_PRIVATE)

            if (isInternetAvailable(context)) {
                // Cargar información del préstamo
                val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
                if (prestamoDoc.exists()) {
                    val cliente = prestamoDoc.getString("cliente") ?: "Cliente desconocido"
                    val monto = prestamoDoc.getDouble("monto") ?: 0.0
                    val fechaInicio = try {
                        val fecha = prestamoDoc.get("fechaInicio")
                        when (fecha) {
                            is Timestamp -> formatter.format(fecha.toDate())
                            is String -> fecha
                            else -> "Fecha desconocida"
                        }
                    } catch (e: Exception) {
                        "Fecha desconocida"
                    }
                    val numeroPrestamo = (prestamoDoc.getLong("numeroPrestamo") ?: 0L).toInt()
                    val saldoActual = prestamoDoc.getDouble("saldoActual") ?: 0.0
                    val estado = prestamoDoc.getString("estado") ?: "Activo"

                    prestamoInfo = PrestamoInfo(
                        cliente = cliente,
                        monto = monto,
                        fechaInicio = fechaInicio,
                        numeroPrestamo = numeroPrestamo,
                        saldoActual = saldoActual,
                        estado = estado
                    )
                }

                // Cargar usuarios para obtener nombres de cobradores
                val usuarios = db.collection("usuarios").get().await()
                    .associateBy({ it.id }, { it.getString("nombre") ?: it.id })

                // Cargar pagos del préstamo específico
                val snapshot = db.collection("pagos")
                    .whereEqualTo("prestamoId", prestamoId)
                    .get()
                    .await()

                val listaPagos = snapshot.documents.mapNotNull { doc ->
                    try {
                        val cliente = doc.getString("clienteNombre") ?: doc.getString("cliente") ?: return@mapNotNull null
                        val monto = doc.getDouble("monto") ?: doc.getDouble("montoPagado") ?: return@mapNotNull null
                        val mora = doc.getDouble("mora") ?: 0.0
                        val interesTotal = doc.getDouble("interesTotal") ?: doc.getDouble("interes") ?: 0.0
                        val cobradorId = doc.getString("registradoPor") ?: doc.getString("cobrador") ?: "Desconocido"
                        val cuota = doc.getString("cuotas") ?: doc.getString("cuota") ?: "1"
                        val lugar = doc.getString("lugar") ?: ""
                        val firma = doc.getString("firma") ?: ""
                        val tipoPago = doc.getString("tipoPago") ?: "Efectivo"
                        val saldoRestante = doc.getDouble("saldoRestante") ?: 0.0
                        val metodoPago = doc.getString("metodoPago") ?: "Efectivo"
                        val observaciones = doc.getString("observaciones") ?: ""

                        // Manejo de fechas
                        val fecha = try {
                            when (val fechaRaw = doc.get("fechaPago") ?: doc.get("fecha")) {
                                is Timestamp -> formatter.format(fechaRaw.toDate())
                                is String -> {
                                    if (fechaRaw.matches(Regex("\\d{2}/\\d{2}/\\d{4}"))) {
                                        fechaRaw
                                    } else {
                                        try {
                                            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(fechaRaw)
                                            formatter.format(date ?: Date())
                                        } catch (e: Exception) {
                                            fechaRaw
                                        }
                                    }
                                }
                                else -> formatter.format(Date())
                            }
                        } catch (e: Exception) {
                            formatter.format(Date())
                        }

                        val nombreCobrador = usuarios[cobradorId] ?: cobradorId
                        val numeroPrestamo = (prestamoInfo?.numeroPrestamo ?: 0)

                        PagoDetalle(
                            docId = doc.id,
                            cliente = cliente,
                            prestamoId = prestamoId,
                            fecha = fecha,
                            monto = monto,
                            mora = mora,
                            interesTotal = interesTotal,
                            cuota = cuota,
                            cobrador = nombreCobrador,
                            lugar = lugar,
                            firma = firma,
                            tipoPago = tipoPago,
                            saldoRestante = saldoRestante,
                            numeroPrestamo = numeroPrestamo,
                            metodoPago = metodoPago,
                            observaciones = observaciones
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                // Guardar en cache offline
                val cacheKey = "pagos_prestamo_$prestamoId"
                prefs.edit().putString(cacheKey, gson.toJson(listaPagos)).apply()

                pagos = listaPagos.sortedByDescending {
                    try {
                        formatter.parse(it.fecha.split(" ")[0])?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }
            } else {
                // Modo offline
                val cacheKey = "pagos_prestamo_$prestamoId"
                val json = prefs.getString(cacheKey, "[]") ?: "[]"
                pagos = try {
                    gson.fromJson(json, Array<PagoDetalle>::class.java).toList()
                } catch (e: Exception) {
                    emptyList()
                }
                Toast.makeText(context, "📴 Modo offline", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error cargando pagos: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            cargando = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Historial de Pagos")
                        prestamoInfo?.let { info ->
                            Text(
                                "Préstamo #${info.numeroPrestamo} - ${info.cliente}",
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0061A7),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        if (cargando) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Información del préstamo
                prestamoInfo?.let { info ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Información del Préstamo",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("👤 Cliente: ${info.cliente}")
                            Text("📋 Número: #${info.numeroPrestamo}")
                            Text("💰 Monto inicial: L. %.2f".format(info.monto))
                            Text("📅 Fecha inicio: ${info.fechaInicio}")
                            Text("💳 Saldo actual: L. %.2f".format(info.saldoActual))
                            Text(
                                "📊 Estado: ${info.estado}",
                                color = if (info.estado == "Activo") Color(0xFF2E7D32) else Color(0xFFD32F2F)
                            )
                        }
                    }
                }

                // Resumen de pagos
                if (pagos.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Resumen de Pagos", fontWeight = FontWeight.Bold)
                            Text("Total pagos realizados: ${pagos.size}")
                            Text("Monto total pagado: L. %.2f".format(pagos.sumOf { it.monto }))
                            val totalMora = pagos.sumOf { it.mora }
                            if (totalMora > 0) {
                                Text("Total mora pagada: L. %.2f".format(totalMora))
                            }
                            val totalInteres = pagos.sumOf { it.interesTotal }
                            if (totalInteres > 0) {
                                Text("Total interés pagado: L. %.2f".format(totalInteres))
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Lista de pagos
                if (pagos.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📋 No hay pagos registrados", fontSize = 16.sp)
                            Text("Este préstamo aún no tiene pagos", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                } else {
                    pagos.forEachIndexed { index, pago ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Pago #${pagos.size - index}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = Color(0xFF0061A7)
                                    )
                                    Text(
                                        "Cuota: ${pago.cuota}",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    "💰 Total pagado: L. %.2f".format(pago.monto),
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32),
                                    fontSize = 16.sp
                                )

                                val montoBase = pago.monto - pago.mora
                                if (montoBase > 0 && pago.mora > 0) {
                                    Text("💵 Monto base: L. %.2f".format(montoBase))
                                }

                                if (pago.mora > 0.0) {
                                    Text(
                                        "⚠️ Mora: L. %.2f".format(pago.mora),
                                        color = Color.Red,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                if (pago.interesTotal > 0.0) {
                                    Text(
                                        "📈 Interés: L. %.2f".format(pago.interesTotal),
                                        color = Color(0xFF6A1B9A)
                                    )
                                }

                                if (pago.saldoRestante > 0) {
                                    Text(
                                        "💳 Saldo restante: L. %.2f".format(pago.saldoRestante),
                                        color = Color(0xFF1976D2)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Información adicional
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("📅 Fecha: ${pago.fecha}", fontSize = 12.sp)
                                        Text("👨‍💼 Cobrador: ${pago.cobrador}", fontSize = 12.sp)
                                        if (pago.lugar.isNotBlank()) {
                                            Text("📍 Lugar: ${pago.lugar}", fontSize = 12.sp)
                                        }
                                        if (pago.observaciones.isNotBlank()) {
                                            Text("📝 Notas: ${pago.observaciones}", fontSize = 12.sp)
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            pago.metodoPago,
                                            fontSize = 12.sp,
                                            color = if (pago.metodoPago == "Efectivo") Color(0xFF4CAF50) else Color(0xFF2196F3),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Botones de acción
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    val saldoAnterior = pago.saldoRestante + pago.monto
                                                    val proximoPago = if (pago.saldoRestante > 0) {
                                                        "L. %.2f".format(pago.saldoRestante)
                                                    } else {
                                                        "Préstamo cancelado"
                                                    }

                                                    val file = ReciboHelper.generarReciboPDF(
                                                        context = context,
                                                        cliente = pago.cliente,
                                                        prestamoId = if (pago.numeroPrestamo > 0) pago.numeroPrestamo.toString() else pago.prestamoId,
                                                        fecha = pago.fecha,
                                                        montoPagado = pago.monto.toString(),
                                                        saldoAnterior = saldoAnterior,
                                                        proximoPago = proximoPago,
                                                        cuota = pago.cuota,
                                                        cobrador = pago.cobrador,
                                                        lugar = pago.lugar,
                                                        firma = pago.firma.ifBlank { pago.cobrador },
                                                        tipoPago = pago.metodoPago,
                                                        mora = pago.mora
                                                    )

                                                    if (file != null) {
                                                        ReciboHelper.imprimirPDF(context, file)
                                                    } else {
                                                        Toast.makeText(context, "Error al generar PDF", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                                    ) {
                                        Text("🖨️ Reimprimir", color = Color.White, fontSize = 11.sp)
                                    }

                                    if (rol == "admin") {
                                        Button(
                                            onClick = { pagoAEliminar = pago },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Eliminar",
                                                tint = Color.White
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

    // Diálogo de confirmación para eliminar
    pagoAEliminar?.let { pago ->
        AlertDialog(
            onDismissRequest = { pagoAEliminar = null },
            title = { Text("¿Eliminar pago?", color = MaterialTheme.colorScheme.error) },
            text = {
                Column {
                    Text("¿Estás seguro de que deseas eliminar este pago?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Monto: L. %.2f".format(pago.monto), fontWeight = FontWeight.Bold)
                    Text("Fecha: ${pago.fecha}")
                    Text("Cobrador: ${pago.cobrador}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Esta acción no se puede deshacer.", color = Color.Red)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                db.collection("pagos").document(pago.docId).delete().await()
                                pagos = pagos.filterNot { it.docId == pago.docId }
                                Toast.makeText(context, "Pago eliminado correctamente", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al eliminar: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                pagoAEliminar = null
                            }
                        }
                    }
                ) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pagoAEliminar = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}