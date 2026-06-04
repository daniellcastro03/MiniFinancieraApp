package com.example.minifinancieraapp.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.util.ReciboHelper
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

// ===================== MODELOS SIMPLIFICADOS PARA NUEVA LÓGICA =====================

data class CuotaInfo(
    val numero: Int,
    val fecha: String,
    val capital: Double,
    val interes: Double,
    val total: Double,
    val descripcion: String = "",
    val pagada: Boolean = false,
    val montoPagado: Double = 0.0,
    val fechaPago: String? = null,
    val historialPagos: List<String> = emptyList()
) {
    val porcentajePagado: Double get() = if (total > 0) (montoPagado / total) * 100 else 0.0
    val estaCompleta: Boolean get() = montoPagado >= total - 0.90
}

// ===================== FUNCIONES PARA NUEVA LÓGICA DE CASCADA =====================

private fun calcularFechaCuota(fechaInicio: Date, plazo: String, numeroCuota: Int): String {
    val calendar = Calendar.getInstance().apply { time = fechaInicio }
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    when (plazo.lowercase()) {
        "diario" -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota)
        "lunes a sábado" -> {
            repeat(numeroCuota) {
                do {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
        }
        "semanal" -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 7)
        "quincenal" -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 15)
        "mensual" -> calendar.add(Calendar.MONTH, numeroCuota)
        "bimestral" -> calendar.add(Calendar.MONTH, numeroCuota * 2)
        else -> calendar.add(Calendar.MONTH, numeroCuota)
    }

    return dateFormat.format(calendar.time)
}

fun DocumentSnapshot.getNumeroPrestamoSafeCuotas(): String {
    return try {
        this.getString("numeroPrestamo")?.takeIf { it.isNotBlank() }
            ?: this.getLong("numeroPrestamo")?.toString()
            ?: "N/D"
    } catch (e: Exception) {
        Log.w("CuotasHelper", "Error al obtener numeroPrestamo en ${this.id}: ${e.message}")
        "N/D"
    }
}

private suspend fun obtenerEstadoCuotasConCascada(
    db: FirebaseFirestore,
    prestamoId: String
): Map<Int, CuotaInfo> {
    return try {
        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId)
            .get().await()

        val informacionCuotas = mutableMapOf<Int, MutableList<Pair<Double, String>>>()
        val fmtPago = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        for (pago in pagosSnapshot.documents) {
            val fechaPagoStr: String? = when (val fp = pago.get("fechaPago")) {
                is Timestamp -> fmtPago.format(fp.toDate())
                is Date -> fmtPago.format(fp)
                is String -> fp
                else -> null
            }

            val cuotasCubiertas = pago.get("cuotasCubiertas") as? List<*>

            if (cuotasCubiertas != null && cuotasCubiertas.isNotEmpty()) {
                cuotasCubiertas.forEach { cuotaData ->
                    if (cuotaData is Map<*, *>) {
                        val numeroCuota = (cuotaData["numeroCuota"] as? Number)?.toInt() ?: 0
                        val montoAplicado = (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0

                        if (numeroCuota > 0 && montoAplicado > 0) {
                            informacionCuotas.getOrPut(numeroCuota) { mutableListOf() }
                                .add(Pair(montoAplicado, fechaPagoStr ?: "Sin fecha"))
                        }
                    }
                }
            } else {
                val numeroCuota = when {
                    pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                    pago.contains("cuota") -> pago.getLong("cuota")?.toInt() ?: 1
                    else -> 1
                }

                val montoPago = pago.getDouble("monto") ?: 0.0
                val moraPago = pago.getDouble("mora") ?: 0.0
                val montoTotal = montoPago + moraPago

                if (montoTotal > 0) {
                    informacionCuotas.getOrPut(numeroCuota) { mutableListOf() }
                        .add(Pair(montoTotal, fechaPagoStr ?: "Sin fecha"))
                }
            }
        }

        val resultadoFinal = mutableMapOf<Int, CuotaInfo>()
        informacionCuotas.forEach { (numeroCuota, pagos) ->
            val montoTotalPagado = pagos.sumOf { it.first }
            val fechasHistorial = pagos.map { it.second }.distinct()
            val ultimaFechaPago = fechasHistorial.lastOrNull()

            resultadoFinal[numeroCuota] = CuotaInfo(
                numero = numeroCuota,
                fecha = "",
                capital = 0.0,
                interes = 0.0,
                total = 0.0,
                montoPagado = montoTotalPagado,
                fechaPago = ultimaFechaPago,
                historialPagos = fechasHistorial
            )
        }

        resultadoFinal

    } catch (e: Exception) {
        Log.e("EstadoCuotasCascada", "Error: ${e.message}", e)
        emptyMap()
    }
}

private suspend fun generarPlanCuotasConEstado(
    db: FirebaseFirestore,
    prestamoId: String,
    montoPrestamo: Double,
    interesTotal: Double,
    cuotasTotales: Int,
    fechaInicio: Date,
    plazo: String
): List<CuotaInfo> {
    return try {
        val capitalPorCuota = if (cuotasTotales > 0) montoPrestamo / cuotasTotales else 0.0
        val interesPorCuota = if (cuotasTotales > 0) interesTotal / cuotasTotales else 0.0

        val capitalRedondeado = kotlin.math.round(capitalPorCuota).toInt()
        val capitalResiduo = montoPrestamo - (capitalRedondeado * cuotasTotales)
        val interesRedondeado = kotlin.math.round(interesPorCuota).toInt()
        val interesResiduo = interesTotal - (interesRedondeado * cuotasTotales)

        val planBase = mutableListOf<CuotaInfo>()
        for (i in 0 until cuotasTotales) {
            val capitalCuota = if (i == cuotasTotales - 1) capitalRedondeado + capitalResiduo else capitalRedondeado.toDouble()
            val interesCuota = if (i == cuotasTotales - 1) interesRedondeado + interesResiduo else interesRedondeado.toDouble()
            val fechaCuota = calcularFechaCuota(fechaInicio, plazo, i + 1)

            planBase.add(
                CuotaInfo(
                    numero = i + 1,
                    fecha = fechaCuota,
                    capital = capitalCuota,
                    interes = interesCuota,
                    total = capitalCuota + interesCuota
                )
            )
        }

        val estadoPagos = obtenerEstadoCuotasConCascada(db, prestamoId)

        planBase.map { cuotaBase ->
            val estadoPago = estadoPagos[cuotaBase.numero]
            if (estadoPago != null) {
                cuotaBase.copy(
                    montoPagado = estadoPago.montoPagado,
                    pagada = estadoPago.montoPagado >= cuotaBase.total - 0.90,
                    fechaPago = estadoPago.fechaPago,
                    historialPagos = estadoPago.historialPagos
                )
            } else {
                cuotaBase
            }
        }

    } catch (e: Exception) {
        Log.e("GenerarPlanCuotas", "Error: ${e.message}", e)
        emptyList()
    }
}

// ─────────────────────────────────────────────
//  COLORES COMPARTIDOS  (igual que NC en NotificacionesScreen)
// ─────────────────────────────────────────────
private val CNavy    = Color(0xFF0A1628)
private val CBlue    = Color(0xFF1A56DB)
private val CRed     = Color(0xFFEF4444)
private val CRedSoft = Color(0xFFFEF2F2)
private val CGreen   = Color(0xFF10B981)
private val CBorder  = Color(0xFFE2E8F0)
private val CTextSec = Color(0xFF64748B)
private val CAmber   = Color(0xFFF59E0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuotasPrestamoScreen(prestamoId: String, navController: NavController, uid: String, rol: String) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val dec = DecimalFormat("#,##0.00")

    var cuotas by remember { mutableStateOf(listOf<CuotaInfo>()) }
    var cargando by remember { mutableStateOf(true) }
    var esActivo by remember { mutableStateOf(true) }
    var estaSaldado by remember { mutableStateOf(false) }
    var errorCarga by remember { mutableStateOf<String?>(null) }

    var totalCapital by remember { mutableStateOf(0.0) }
    var totalInteres by remember { mutableStateOf(0.0) }
    var moraAplicada by remember { mutableStateOf(0.0) }
    var nombreCobrador by remember { mutableStateOf("") }
    var nombreCliente by remember { mutableStateOf("") }
    var descripcionPlazo by remember { mutableStateOf("") }
    var proximoPagoProgramado by remember { mutableStateOf<String?>(null) }
    var numeroPrestamo by remember { mutableStateOf("") }
    var numeroPrestamoDisplay by remember { mutableStateOf("") }

    // ── NUEVO: diálogo confirmar cancelar mora ──────────────────────────
    var mostrarDialogoCancelarMora by remember { mutableStateOf(false) }

    suspend fun recargarDatosCompletos() {
        try {
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()

            val monto = prestamoDoc.getDouble("monto") ?: 0.0
            val cuotasNum = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
            val plazo = prestamoDoc.getString("plazo") ?: "Mensual"
            val fechaTimestamp = prestamoDoc.getTimestamp("fecha")
            val fechaInicio = fechaTimestamp?.toDate() ?: Date()
            val interesTotal = prestamoDoc.getDouble("interesTotal") ?: prestamoDoc.getDouble("interes") ?: 0.0

            totalCapital = monto
            totalInteres = interesTotal

            numeroPrestamo = prestamoDoc.getNumeroPrestamoSafeCuotas()
            numeroPrestamoDisplay = if (numeroPrestamo != "N/D") "Préstamo N° $numeroPrestamo" else "Préstamo ID: $prestamoId"

            descripcionPlazo = when (plazo.lowercase()) {
                "diario"         -> "Diario"
                "lunes a sábado" -> "Lun–Sáb"
                "semanal"        -> "Semanal"
                "quincenal"      -> "Quincenal"
                "mensual"        -> "Mensual"
                "bimestral"      -> "Bimestral"
                else             -> plazo
            }

            cuotas = generarPlanCuotasConEstado(
                db = db,
                prestamoId = prestamoId,
                montoPrestamo = monto,
                interesTotal = interesTotal,
                cuotasTotales = cuotasNum,
                fechaInicio = fechaInicio,
                plazo = plazo.lowercase()
            )

            proximoPagoProgramado = when (val proximoPago = prestamoDoc.get("proximoPago")) {
                is Timestamp -> formatter.format(proximoPago.toDate())
                is Date -> formatter.format(proximoPago)
                is String -> proximoPago
                else -> null
            }

            val moraValor = prestamoDoc.getDouble("mora") ?: 0.0
            val moraActiva = moraValor > 0.0
            moraAplicada = if (moraActiva) moraValor else 0.0

            if (moraActiva) {
                val pagosSnapshot = db.collection("pagos")
                    .whereEqualTo("prestamoId", prestamoId)
                    .get().await()

                var montoMoraPagado = 0.0
                for (pago in pagosSnapshot.documents) {
                    val moraPago = pago.getDouble("mora") ?: 0.0
                    if (moraPago > 0) montoMoraPagado += moraPago
                }

                val moraPagada = montoMoraPagado >= moraValor - 0.90

                if (!moraPagada) {
                    cuotas = cuotas + CuotaInfo(
                        numero = cuotas.size + 1,
                        fecha = "Aplicada (mora)",
                        capital = 0.0, interes = 0.0,
                        total = moraValor,
                        descripcion = "Mora",
                        pagada = false,
                        montoPagado = montoMoraPagado
                    )
                } else {
                    cuotas = cuotas + CuotaInfo(
                        numero = cuotas.size + 1,
                        fecha = "Aplicada (mora)",
                        capital = 0.0, interes = 0.0,
                        total = moraValor,
                        descripcion = "Mora",
                        pagada = true,
                        montoPagado = montoMoraPagado,
                        fechaPago = "Pagada"
                    )
                }
            }

            val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
            val todasPagadas = cuotasNormales.all { it.estaCompleta }
            val cuotaMora = cuotas.find { it.descripcion == "Mora" }
            val moraCobrada = moraAplicada == 0.0 || cuotaMora?.estaCompleta == true
            estaSaldado = todasPagadas && moraCobrada

        } catch (e: Exception) {
            Log.e("CuotasScreenCascada", "Error recargando: ${e.message}", e)
            throw e
        }
    }

    LaunchedEffect(prestamoId) {
        cargando = true
        errorCarga = null

        try {
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()

            if (!prestamoDoc.exists()) throw Exception("El préstamo no existe")
            if (prestamoDoc.getBoolean("eliminado") == true) throw Exception("Este préstamo fue eliminado")

            if (rol == "cobrador") {
                val cobradoresAsignados = prestamoDoc.get("cobradoresAsignados") as? List<*> ?: emptyList<String>()
                if (!cobradoresAsignados.mapNotNull { it as? String }.contains(uid)) {
                    throw Exception("Sin permisos para ver este préstamo")
                }
            }

            esActivo = (prestamoDoc.getString("estado") ?: "activo") == "activo"
            nombreCliente = prestamoDoc.getString("cliente") ?: "Cliente"

            val usuarioDoc = db.collection("usuarios").document(uid).get().await()
            nombreCobrador = usuarioDoc.getString("nombre") ?: uid

            recargarDatosCompletos()

        } catch (e: Exception) {
            errorCarga = e.message ?: "Error desconocido"
        } finally {
            cargando = false
        }
    }

    // ── NUEVO: diálogo confirmar cancelar mora ──────────────────────────
    if (mostrarDialogoCancelarMora) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoCancelarMora = false },
            containerColor   = Color.White,
            shape            = RoundedCornerShape(20.dp),
            title = {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null,
                        tint = CRed, modifier = Modifier.size(22.dp))
                    Text("Cancelar mora", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CRedSoft),
                        shape  = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Se eliminará la mora de L. ${dec.format(moraAplicada)} del saldo pendiente.",
                                color      = CRed,
                                fontWeight = FontWeight.SemiBold,
                                fontSize   = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "El saldo volverá a su valor sin mora. Esta acción no se puede deshacer.",
                                color    = Color(0xFFB91C1C),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Text(
                        "¿Confirmas la cancelación de la mora?",
                        fontSize   = 14.sp,
                        color      = Color(0xFF374151),
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        mostrarDialogoCancelarMora = false
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val ref  = db.collection("prestamos").document(prestamoId)
                                    val snap = ref.get().await()

                                    val moraGuardada = snap.getDouble("mora") ?: 0.0

                                    if (moraGuardada <= 0.0) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "No hay mora activa", Toast.LENGTH_SHORT).show()
                                        }
                                        return@withContext
                                    }

                                    // ✅ FIX: recalcular saldo REAL desde colección de pagos
                                    // En vez de confiar en snap.saldo (puede estar inconsistente),
                                    // sumamos todos los pagos reales y calculamos contra totalPagar SIN mora
                                    val pagosSnap = db.collection("pagos")
                                        .whereEqualTo("prestamoId", prestamoId)
                                        .get().await()

                                    var totalRealPagado = 0.0
                                    for (pago in pagosSnap.documents) {
                                        totalRealPagado += (pago.getDouble("monto") ?: 0.0) +
                                                (pago.getDouble("mora") ?: 0.0)
                                    }

                                    // totalPagarSinMora: restaurar totalPagar al valor sin mora
                                    val totalPagarConMora = snap.getDouble("totalPagar") ?: 0.0
                                    val totalPagarSinMora = (totalPagarConMora - moraGuardada).coerceAtLeast(0.0)

                                    // Saldo real = lo que queda por pagar SIN mora
                                    val nuevoSaldo = (totalPagarSinMora - totalRealPagado).coerceAtLeast(0.0)

                                    val morasAplicadasActual = (snap.get("morasAplicadas") as? List<*>)
                                        ?.mapNotNull { it as? String } ?: emptyList()
                                    val morasActualizadas = if (morasAplicadasActual.isNotEmpty())
                                        morasAplicadasActual.dropLast(1) else emptyList<String>()

                                    // Si ya no quedan moras → estado activo; si quedan → mora
                                    val nuevoEstado = if (morasActualizadas.isEmpty()) "activo" else "mora"

                                    val updateData = mutableMapOf<String, Any>(
                                        "mora"                     to 0.0,
                                        "saldo"                    to nuevoSaldo,
                                        "totalPagar"               to totalPagarSinMora, // ✅ restaurar totalPagar
                                        "morasAplicadas"           to morasActualizadas,
                                        "estado"                   to nuevoEstado,
                                        "fechaUltimaActualizacion" to Timestamp.now(),
                                        "fechaUltimaMora"          to com.google.firebase.firestore.FieldValue.delete()
                                    )
                                    // ✅ FIX: limpiar saldoOriginal para que la próxima mora
                                    // use el saldo correcto como base, no uno desactualizado
                                    if (morasActualizadas.isEmpty()) {
                                        updateData["saldoOriginal"] = com.google.firebase.firestore.FieldValue.delete()
                                    }

                                    ref.update(updateData).await()

                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            "✅ Mora de L. ${dec.format(moraGuardada)} cancelada. Nuevo saldo: L. ${dec.format(nuevoSaldo)}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                recargarDatosCompletos()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CRed),
                    shape  = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cancelar mora", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { mostrarDialogoCancelarMora = false },
                    shape   = RoundedCornerShape(10.dp)
                ) { Text("Volver", color = CTextSec) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Sistema de Cuotas",
                            color      = Color.White,
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (numeroPrestamoDisplay.isNotEmpty()) {
                            Text(
                                numeroPrestamoDisplay,
                                color    = Color.White.copy(alpha = 0.9f),
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0061A7))
            )
        }
    ) { padding ->
        when {
            cargando -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF0061A7))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Cargando información...", color = Color.Gray)
                    }
                }
            }

            errorCarga != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.padding(16.dp),
                        colors   = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Error", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFFD32F2F))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(errorCarga!!, color = Color(0xFFD32F2F), textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { navController.popBackStack() },
                                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                            ) { Text("Volver", color = Color.White) }
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
                ) {

                    // ── TARJETA INFO PRÉSTAMO (responsive) ──────────────────────
                    item {
                        Card(
                            modifier  = Modifier.fillMaxWidth(),
                            colors    = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {

                                // Título
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = null,
                                        tint = Color(0xFF0061A7))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            "Información del Préstamo",
                                            fontSize   = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color      = Color(0xFF0061A7)
                                        )
                                        if (numeroPrestamo != "N/D") {
                                            Text(
                                                "N° $numeroPrestamo",
                                                fontSize   = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color      = Color(0xFF1976D2)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // ── RESPONSIVE: columna única en lugar de dos columnas fijas ──
                                // Wrap automático según ancho
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Fila 1
                                    Row(
                                        modifier              = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        InfoChip(
                                            icon   = Icons.Default.Person,
                                            label  = "Cliente",
                                            value  = nombreCliente,
                                            modifier = Modifier.weight(1f)
                                        )
                                        InfoChip(
                                            icon   = Icons.Default.DateRange,
                                            label  = "Modalidad",
                                            value  = descripcionPlazo,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    // Fila 2
                                    Row(
                                        modifier              = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        InfoChip(
                                            icon   = Icons.Default.AttachMoney,
                                            label  = "Capital",
                                            value  = "L. ${dec.format(totalCapital)}",
                                            modifier = Modifier.weight(1f)
                                        )
                                        InfoChip(
                                            icon   = Icons.Default.AttachMoney,
                                            label  = "Interés",
                                            value  = "L. ${dec.format(totalInteres)}",
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    // Fila 3: cuotas + próximo pago
                                    val cuotasNorm = cuotas.filter { it.descripcion != "Mora" }
                                    Row(
                                        modifier              = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        InfoChip(
                                            icon   = Icons.Default.DateRange,
                                            label  = "Cuotas",
                                            value  = "${cuotasNorm.size}",
                                            modifier = Modifier.weight(1f)
                                        )
                                        proximoPagoProgramado?.let { fecha ->
                                            InfoChip(
                                                icon      = Icons.Default.DateRange,
                                                label     = "Próximo pago",
                                                value     = fecha,
                                                valueColor = Color(0xFF1976D2),
                                                modifier  = Modifier.weight(1f)
                                            )
                                        } ?: Spacer(modifier = Modifier.weight(1f))
                                    }
                                }

                                // ── Mora activa ──────────────────────────────────────────────
                                if (moraAplicada > 0.0) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    val cuotaMora = cuotas.find { it.descripcion == "Mora" }
                                    val moraEstaPagada = cuotaMora?.estaCompleta == true

                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (moraEstaPagada)
                                                Color(0xFFE8F5E8) else Color(0xFFFFEBEE)
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier              = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment     = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    if (moraEstaPagada) "Mora pagada: L. ${dec.format(moraAplicada)}"
                                                    else "Mora activa: L. ${dec.format(moraAplicada)}",
                                                    color      = if (moraEstaPagada) Color(0xFF388E3C) else CRed,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize   = 14.sp
                                                )
                                                if (cuotaMora != null && cuotaMora.montoPagado > 0 && !moraEstaPagada) {
                                                    Text(
                                                        "Pagado: L. ${dec.format(cuotaMora.montoPagado)}",
                                                        color    = Color(0xFFFF9800),
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }

                                            // ── NUEVO: botón cancelar mora ────────────────────────
                                            if (!moraEstaPagada && (rol == "admin")) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                OutlinedButton(
                                                    onClick        = { mostrarDialogoCancelarMora = true },
                                                    shape          = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                    colors         = ButtonDefaults.outlinedButtonColors(
                                                        contentColor = CRed
                                                    ),
                                                    border = androidx.compose.foundation.BorderStroke(
                                                        1.dp, CRed.copy(alpha = 0.5f)
                                                    )
                                                ) {
                                                    Icon(
                                                        Icons.Default.Cancel,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                        "Cancelar mora",
                                                        fontSize   = 12.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = Color(0xFFE0E0E0))
                                Spacer(modifier = Modifier.height(12.dp))

                                // Total a pagar
                                val cuotaMoraFinal   = cuotas.find { it.descripcion == "Mora" }
                                val moraAunPendiente = cuotaMoraFinal != null && !cuotaMoraFinal.estaCompleta
                                val moraEnTotal      = if (moraAunPendiente) moraAplicada else 0.0

                                Column {
                                    Text(
                                        "Total a pagar: L. ${dec.format(totalCapital + totalInteres + moraEnTotal)}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize   = 17.sp,
                                        color      = Color(0xFF0061A7)
                                    )
                                    if (moraAunPendiente && moraAplicada > 0.0) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "↳ Incluye mora pendiente: L. ${dec.format(moraAplicada)}",
                                            fontSize   = 12.sp,
                                            color      = CRed,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── PROGRESO ─────────────────────────────────────────────────
                    item {
                        val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                        val cuotasPagadas  = cuotasNormales.count { it.estaCompleta }
                        val totalCuotas    = cuotasNormales.size
                        val progreso       = if (totalCuotas > 0) cuotasPagadas.toFloat() / totalCuotas else 0f

                        Card(
                            modifier  = Modifier.fillMaxWidth(),
                            colors    = CardDefaults.cardColors(
                                containerColor = if (estaSaldado) Color(0xFFE8F5E8) else Color.White
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    if (estaSaldado) "Préstamo Saldado ✓" else "Progreso de Pagos",
                                    fontSize   = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color      = if (estaSaldado) Color(0xFF4CAF50) else Color(0xFF0061A7)
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                if (!estaSaldado) {
                                    LinearProgressIndicator(
                                        progress   = progreso,
                                        modifier   = Modifier.fillMaxWidth().height(10.dp),
                                        color      = Color(0xFF4CAF50),
                                        trackColor = Color(0xFFE0E0E0)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                }

                                Text(
                                    "$cuotasPagadas de $totalCuotas cuotas completadas (${String.format("%.0f", progreso * 100)}%)",
                                    fontSize   = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color      = if (estaSaldado) Color(0xFF388E3C) else Color(0xFF666666)
                                )
                            }
                        }
                    }

                    // ── AVISO SISTEMA CASCADA ────────────────────────────────────
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "Sistema de Pagos en Cascada",
                                    fontWeight = FontWeight.Bold,
                                    color      = Color(0xFFE65100),
                                    fontSize   = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Los pagos se distribuyen automáticamente completando cuotas en orden secuencial.",
                                    color    = Color(0xFFBF360C),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // ── EXPORTAR PDF ─────────────────────────────────────────────
                    item {
                        Button(
                            onClick = {
                                val pdfFile = ReciboHelper.generarCuotasPDF(
                                    context = context,
                                    cliente = nombreCliente,
                                    prestamoId = if (numeroPrestamo != "N/D") "Préstamo N° $numeroPrestamo" else prestamoId,
                                    cuotas.map { cuota ->
                                        mapOf(
                                            "numero"      to cuota.numero,
                                            "fecha"       to cuota.fecha,
                                            "capital"     to cuota.capital,
                                            "interes"     to cuota.interes,
                                            "total"       to cuota.total,
                                            "pagado"      to cuota.estaCompleta,
                                            "montoPagado" to cuota.montoPagado,
                                            "fechaPago"   to (cuota.fechaPago ?: "")
                                        )
                                    },
                                    totalCapital     = totalCapital,
                                    totalInteres     = totalInteres,
                                    mora             = moraAplicada,
                                    fechaExportacion = formatter.format(Date())
                                )
                                if (pdfFile != null) {
                                    ReciboHelper.compartirReciboPDF(context, pdfFile)
                                    Toast.makeText(context, "PDF generado correctamente", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error al generar PDF", Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                        ) {
                            Text("📄 Exportar Cuotas en PDF", color = Color.White, fontSize = 15.sp)
                        }
                    }

                    // ── LISTA DE CUOTAS ──────────────────────────────────────────
                    items(cuotas) { cuota ->
                        CuotaCard(
                            cuota         = cuota,
                            dec           = dec,
                            esActivo      = esActivo,
                            rol           = rol,
                            onMarcarPagada = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val prestamoRef  = db.collection("prestamos").document(prestamoId)
                                            val prestamoSnap = prestamoRef.get().await()

                                            val monto              = prestamoSnap.getDouble("monto") ?: 0.0
                                            val interesTotal       = prestamoSnap.getDouble("interesTotal")
                                                ?: prestamoSnap.getDouble("interes") ?: 0.0
                                            val totalPagar         = prestamoSnap.getDouble("totalPagar")
                                                ?: (monto + interesTotal)
                                            val moraActual         = prestamoSnap.getDouble("mora") ?: 0.0
                                            val numeroPrestamoActual = prestamoSnap.getNumeroPrestamoSafeCuotas()
                                            val plazo              = prestamoSnap.getString("plazo") ?: "semanal"
                                            val fechaInicio        = prestamoSnap.getTimestamp("fecha")?.toDate() ?: Date()

                                            val pagosSnapshot = db.collection("pagos")
                                                .whereEqualTo("prestamoId", prestamoId)
                                                .get().await()

                                            var totalRealmentePagado = 0.0
                                            for (pago in pagosSnapshot.documents) {
                                                totalRealmentePagado += (pago.getDouble("monto") ?: 0.0) +
                                                        (pago.getDouble("mora") ?: 0.0)
                                            }

                                            val totalConMora    = totalPagar + moraActual
                                            val nuevoMontoPagado = totalRealmentePagado + cuota.total
                                            val nuevoSaldo       = (totalConMora - nuevoMontoPagado).coerceAtLeast(0.0)

                                            val cuotasPagadas = mutableSetOf<Int>()
                                            for (pago in pagosSnapshot.documents) {
                                                val cuotasCubiertas = pago.get("cuotasCubiertas") as? List<*>
                                                cuotasCubiertas?.forEach { cd ->
                                                    if (cd is Map<*, *>) {
                                                        (cd["numeroCuota"] as? Number)?.toInt()?.let { cuotasPagadas.add(it) }
                                                    }
                                                }
                                            }
                                            cuotasPagadas.add(cuota.numero)

                                            val cuotasNum = prestamoSnap.getLong("cuotas")?.toInt() ?: 1
                                            var proximaCuotaPendiente: Int? = null
                                            for (i in 1..cuotasNum) {
                                                if (!cuotasPagadas.contains(i)) { proximaCuotaPendiente = i; break }
                                            }

                                            val proximoPagoStr = if (proximaCuotaPendiente != null)
                                                calcularFechaCuota(fechaInicio, plazo, proximaCuotaPendiente)
                                            else "saldado"

                                            val fechaActual     = Timestamp.now()
                                            val fechaFormateada = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                                .format(fechaActual.toDate())

                                            val abonoManual = mapOf(
                                                "prestamoId"            to prestamoId,
                                                "numeroPrestamo"        to numeroPrestamoActual,
                                                "monto"                 to if (cuota.descripcion == "Mora") 0.0 else cuota.total,
                                                "mora"                  to if (cuota.descripcion == "Mora") cuota.total else 0.0,
                                                "fechaPago"             to fechaActual,
                                                "registradoPor"         to uid,
                                                "nombreCobrador"        to nombreCobrador,
                                                "clienteNombre"         to nombreCliente,
                                                "metodoPago"            to "Manual (Admin)",
                                                "sistemaPagoEnCascada"  to true,
                                                "saldoRestante"         to nuevoSaldo,
                                                "proximaFechaProgramada" to proximoPagoStr,
                                                "cuotasCubiertas"       to listOf(
                                                    mapOf(
                                                        "numeroCuota"    to cuota.numero,
                                                        "montoAplicado"  to cuota.total,
                                                        "completada"     to true
                                                    )
                                                ),
                                                "observaciones" to "Marcado manualmente por administrador"
                                            )

                                            db.collection("pagos").add(abonoManual).await()

                                            val saldoAnterior = (totalConMora - totalRealmentePagado).coerceAtLeast(0.0)

                                            val pdfFile = ReciboHelper.generarReciboPDF(
                                                context       = context,
                                                cliente       = nombreCliente,
                                                prestamoId    = if (numeroPrestamoActual != "N/D") "Préstamo N° $numeroPrestamoActual" else "ID: $prestamoId",
                                                fecha         = fechaFormateada,
                                                montoPagado   = cuota.total.toString(),
                                                saldoAnterior = saldoAnterior,
                                                proximoPago   = proximoPagoStr,
                                                cuota         = if (cuota.descripcion == "Mora") "MORA" else "Cuota #${cuota.numero}",
                                                cobrador      = nombreCobrador,
                                                lugar         = "Registro Manual",
                                                firma         = nombreCobrador,
                                                tipoPago      = "Manual (Admin)",
                                                mora          = if (cuota.descripcion == "Mora") cuota.total else 0.0,
                                                saldoNuevoFijo = nuevoSaldo
                                            )
                                            if (pdfFile != null && pdfFile.exists()) {
                                                ReciboHelper.compartirReciboPDF(context, pdfFile)
                                            }

                                            val actualizacionPrestamo = if (nuevoSaldo <= 0.90) {
                                                mapOf<String, Any>(
                                                    "saldo"                    to 0.0,
                                                    "montoPagado"              to totalConMora,
                                                    "estado"                   to "saldado",
                                                    "proximoPago"              to "saldado",
                                                    "fechaUltimaActualizacion" to fechaActual,
                                                    "ultimoPago"               to fechaFormateada,
                                                    "fechaSaldado"             to fechaActual,
                                                    "fechaCancelacion"         to fechaActual,
                                                    "mora"                     to 0.0
                                                )
                                            } else {
                                                mapOf<String, Any>(
                                                    "saldo"                    to nuevoSaldo,
                                                    "montoPagado"              to nuevoMontoPagado,
                                                    "estado"                   to "activo",
                                                    "proximoPago"              to proximoPagoStr,
                                                    "fechaUltimaActualizacion" to fechaActual,
                                                    "ultimoPago"               to fechaFormateada
                                                )
                                            }
                                            prestamoRef.update(actualizacionPrestamo).await()

                                            withContext(Dispatchers.Main) {
                                                val mensaje = if (nuevoSaldo <= 0.90)
                                                    "¡PRÉSTAMO N° $numeroPrestamoActual SALDADO! ✅"
                                                else
                                                    "Cuota pagada. Saldo: L. ${dec.format(nuevoSaldo)}"
                                                Toast.makeText(context, mensaje, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        recargarDatosCompletos()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }

                    // ── RESUMEN ──────────────────────────────────────────────────
                    item {
                        val cuotasNormales         = cuotas.filter { it.descripcion != "Mora" }
                        val cuotasCompletas        = cuotasNormales.count { it.estaCompleta }
                        val cuotasConPagoParcial   = cuotasNormales.count { it.montoPagado > 0 && !it.estaCompleta }
                        val cuotasPendientes       = cuotasNormales.count { it.montoPagado == 0.0 }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors   = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Resumen", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF7B1FA2))
                                Spacer(modifier = Modifier.height(10.dp))
                                ResumenFilaCuotas("Completadas",       cuotasCompletas,      Color(0xFF388E3C))
                                ResumenFilaCuotas("Con pago parcial",  cuotasConPagoParcial, Color(0xFFFF9800))
                                ResumenFilaCuotas("Pendientes",        cuotasPendientes,     Color(0xFF757575))
                            }
                        }
                    }

                    // ── BOTONES NAVEGACIÓN ───────────────────────────────────────
                    item {
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick  = { navController.popBackStack() },
                                modifier = Modifier.weight(1f),
                                colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0061A7))
                            ) { Text("← Regresar") }

                            Button(
                                onClick  = { navController.popBackStack() },
                                modifier = Modifier.weight(1f),
                                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A7))
                            ) { Text("Registrar Pago →", color = Color.White) }
                        }
                    }

                    // ── PIE ──────────────────────────────────────────────────────
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))) {
                            Text(
                                "Usuario: $nombreCobrador ($rol) · ${if (numeroPrestamo != "N/D") "N° $numeroPrestamo" else prestamoId}",
                                modifier  = Modifier.padding(12.dp).fillMaxWidth(),
                                fontSize  = 11.sp,
                                color     = Color.Gray,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
//  CHIP INFO (responsive)
// ─────────────────────────────────────────────
@Composable
private fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color(0xFF333333)
) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = Color.White),
        shape    = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(icon, contentDescription = null,
                    tint     = Color(0xFF666666),
                    modifier = Modifier.size(13.dp))
                Text(label, fontSize = 11.sp, color = Color(0xFF666666))
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                value,
                fontSize   = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color      = valueColor,
                maxLines   = 2
            )
        }
    }
}

// ─────────────────────────────────────────────
//  TARJETA DE CUOTA
// ─────────────────────────────────────────────
@Composable
private fun CuotaCard(
    cuota: CuotaInfo,
    dec: DecimalFormat,
    esActivo: Boolean,
    rol: String,
    onMarcarPagada: () -> Unit
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(
            containerColor = when {
                cuota.estaCompleta   -> Color(0xFFE8F5E8)
                cuota.montoPagado > 0 -> Color(0xFFFFF3E0)
                else                 -> Color.White
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            // Encabezado
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (cuota.descripcion == "Mora") "MORA" else "Cuota ${cuota.numero}",
                        fontWeight = FontWeight.Bold,
                        fontSize   = 17.sp,
                        color      = if (cuota.descripcion == "Mora") CRed else Color(0xFF0061A7)
                    )
                    if (cuota.descripcion == "Mora") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = CRed)) {
                            Text(
                                "MORA",
                                modifier  = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                color     = Color.White,
                                fontSize  = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (cuota.estaCompleta) Icons.Default.CheckCircle else Icons.Default.HourglassBottom,
                        contentDescription = null,
                        tint     = if (cuota.estaCompleta) Color(0xFF388E3C) else Color(0xFF757575),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        when {
                            cuota.estaCompleta     -> "Completa"
                            cuota.montoPagado > 0  -> "${String.format("%.0f", cuota.porcentajePagado)}%"
                            else                   -> "Pendiente"
                        },
                        color      = when {
                            cuota.estaCompleta     -> Color(0xFF388E3C)
                            cuota.montoPagado > 0  -> Color(0xFFFF9800)
                            else                   -> Color(0xFF757575)
                        },
                        fontWeight = FontWeight.Medium,
                        fontSize   = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (cuota.descripcion != "Mora") {
                Text("Fecha: ${cuota.fecha}", fontSize = 13.sp, color = Color(0xFF666666))
            }

            if (cuota.estaCompleta && cuota.fechaPago != null) {
                Text("✓ Pagada: ${cuota.fechaPago}", color = Color(0xFF388E3C),
                    fontWeight = FontWeight.Medium, fontSize = 13.sp)
            } else if (cuota.montoPagado > 0 && cuota.historialPagos.isNotEmpty()) {
                if (cuota.historialPagos.size == 1) {
                    Text("Pago parcial: ${cuota.historialPagos.first()}",
                        color = Color(0xFFFF9800), fontSize = 13.sp)
                } else {
                    Text("Pagos múltiples:", color = Color(0xFFFF9800),
                        fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    cuota.historialPagos.forEachIndexed { i, fecha ->
                        Text("  • Pago ${i + 1}: $fecha", color = Color(0xFFFF9800), fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Capital + Interés
            if (cuota.capital > 0 || cuota.interes > 0) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (cuota.capital > 0) Text("Capital: L. ${dec.format(cuota.capital)}",
                        fontSize = 13.sp, color = Color(0xFF666666))
                    if (cuota.interes > 0) Text("Interés: L. ${dec.format(cuota.interes)}",
                        fontSize = 13.sp, color = Color(0xFF666666))
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Total + pagado
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "Total: L. ${dec.format(cuota.total)}",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp,
                    color      = if (cuota.descripcion == "Mora") CRed else Color(0xFF333333)
                )

                if (cuota.montoPagado > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Pagado: L. ${dec.format(cuota.montoPagado)}",
                            color = Color(0xFF388E3C), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        if (!cuota.estaCompleta) {
                            Text("Resta: L. ${dec.format(cuota.total - cuota.montoPagado)}",
                                color = Color(0xFFFF9800), fontSize = 12.sp)
                        }
                    }
                }
            }

            // Botones acción
            if (!cuota.estaCompleta && esActivo) {
                Spacer(modifier = Modifier.height(10.dp))
                when (rol) {
                    "admin" -> {
                        Button(
                            onClick  = onMarcarPagada,
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A7))
                        ) {
                            Text(
                                if (cuota.descripcion == "Mora") "Marcar Mora como Pagada (Admin)"
                                else "Marcar como Pagada (Admin)",
                                color = Color.White
                            )
                        }
                    }
                    "cobrador" -> {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
                            Text(
                                "💡 Para registrar pagos usa la pantalla 'Registrar Pago'. Los pagos se distribuyen automáticamente.",
                                modifier = Modifier.padding(10.dp),
                                color    = Color(0xFFE65100),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
//  FILA RESUMEN
// ─────────────────────────────────────────────
@Composable
private fun ResumenFilaCuotas(label: String, cantidad: Int, color: Color) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Color(0xFF666666))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cantidad.toString(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = color,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
        }
    }
}