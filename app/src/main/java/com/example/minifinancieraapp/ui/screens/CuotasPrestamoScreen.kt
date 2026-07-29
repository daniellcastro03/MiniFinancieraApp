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
import androidx.compose.ui.draw.clip
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

// ===================== MODELOS =====================

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

// ===================== FUNCIONES CASCADA =====================

private fun calcularFechaCuota(fechaInicio: Date, plazo: String, numeroCuota: Int): String {
    val calendar = Calendar.getInstance().apply { time = fechaInicio }
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    when (plazo.lowercase()) {
        "diario"         -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota)
        "lunes a sábado" -> repeat(numeroCuota) {
            do { calendar.add(Calendar.DAY_OF_YEAR, 1) }
            while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
        }
        "semanal"        -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 7)
        "quincenal"      -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 15)
        "mensual"        -> calendar.add(Calendar.MONTH, numeroCuota)
        "bimestral"      -> calendar.add(Calendar.MONTH, numeroCuota * 2)
        else             -> calendar.add(Calendar.MONTH, numeroCuota)
    }
    return dateFormat.format(calendar.time)
}

fun DocumentSnapshot.getNumeroPrestamoSafeCuotas(): String {
    return try {
        this.getString("numeroPrestamo")?.takeIf { it.isNotBlank() }
            ?: this.getLong("numeroPrestamo")?.toString()
            ?: "N/D"
    } catch (e: Exception) {
        Log.w("CuotasHelper", "Error en ${this.id}: ${e.message}")
        "N/D"
    }
}

private suspend fun obtenerEstadoCuotasConCascada(
    db: FirebaseFirestore,
    prestamoId: String
): Map<Int, CuotaInfo> {
    return try {
        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId).get().await()

        val informacionCuotas = mutableMapOf<Int, MutableList<Pair<Double, String>>>()
        val fmtPago = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        for (pago in pagosSnapshot.documents) {
            val fechaPagoStr: String? = when (val fp = pago.get("fechaPago")) {
                is Timestamp -> fmtPago.format(fp.toDate())
                is Date      -> fmtPago.format(fp)
                is String    -> fp
                else         -> null
            }
            val cuotasCubiertas = pago.get("cuotasCubiertas") as? List<*>
            if (cuotasCubiertas != null && cuotasCubiertas.isNotEmpty()) {
                cuotasCubiertas.forEach { cuotaData ->
                    if (cuotaData is Map<*, *>) {
                        val numeroCuota   = (cuotaData["numeroCuota"]  as? Number)?.toInt()    ?: 0
                        val montoAplicado = (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0
                        if (numeroCuota > 0 && montoAplicado > 0)
                            informacionCuotas.getOrPut(numeroCuota) { mutableListOf() }
                                .add(Pair(montoAplicado, fechaPagoStr ?: "Sin fecha"))
                    }
                }
            } else {
                val numeroCuota = when {
                    pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                    pago.contains("cuota")       -> pago.getLong("cuota")?.toInt() ?: 1
                    else -> 1
                }
                val montoTotal = (pago.getDouble("monto") ?: 0.0) + (pago.getDouble("mora") ?: 0.0)
                if (montoTotal > 0)
                    informacionCuotas.getOrPut(numeroCuota) { mutableListOf() }
                        .add(Pair(montoTotal, fechaPagoStr ?: "Sin fecha"))
            }
        }

        val resultadoFinal = mutableMapOf<Int, CuotaInfo>()
        informacionCuotas.forEach { (numeroCuota, pagos) ->
            val montoTotalPagado = pagos.sumOf { it.first }
            val fechasHistorial  = pagos.map { it.second }.distinct()
            resultadoFinal[numeroCuota] = CuotaInfo(
                numero         = numeroCuota,
                fecha          = "",
                capital        = 0.0,
                interes        = 0.0,
                total          = 0.0,
                montoPagado    = montoTotalPagado,
                fechaPago      = fechasHistorial.lastOrNull(),
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
        val capitalPorCuota   = if (cuotasTotales > 0) montoPrestamo / cuotasTotales else 0.0
        val interesPorCuota   = if (cuotasTotales > 0) interesTotal  / cuotasTotales else 0.0
        val capitalRedondeado = kotlin.math.round(capitalPorCuota).toInt()
        val capitalResiduo    = montoPrestamo - (capitalRedondeado * cuotasTotales)
        val interesRedondeado = kotlin.math.round(interesPorCuota).toInt()
        val interesResiduo    = interesTotal  - (interesRedondeado * cuotasTotales)

        val planBase = mutableListOf<CuotaInfo>()
        for (i in 0 until cuotasTotales) {
            val capitalCuota = if (i == cuotasTotales - 1)
                capitalRedondeado + capitalResiduo else capitalRedondeado.toDouble()
            val interesCuota = if (i == cuotasTotales - 1)
                interesRedondeado + interesResiduo else interesRedondeado.toDouble()
            planBase.add(
                CuotaInfo(
                    numero  = i + 1,
                    fecha   = calcularFechaCuota(fechaInicio, plazo, i + 1),
                    capital = capitalCuota,
                    interes = interesCuota,
                    total   = capitalCuota + interesCuota
                )
            )
        }

        val estadoPagos = obtenerEstadoCuotasConCascada(db, prestamoId)
        planBase.map { cuotaBase ->
            val estadoPago = estadoPagos[cuotaBase.numero]
            if (estadoPago != null) cuotaBase.copy(
                montoPagado    = estadoPago.montoPagado,
                pagada         = estadoPago.montoPagado >= cuotaBase.total - 0.90,
                fechaPago      = estadoPago.fechaPago,
                historialPagos = estadoPago.historialPagos
            ) else cuotaBase
        }
    } catch (e: Exception) {
        Log.e("GenerarPlanCuotas", "Error: ${e.message}", e)
        emptyList()
    }
}

// ─────────────────────────────────────────────
//  PALETA
// ─────────────────────────────────────────────
private val CNavy      = Color(0xFF0A192F)
private val CBlue      = Color(0xFF007AFF)
private val CRed       = Color(0xFFEF4444)
private val CRedSoft   = Color(0xFFFEF2F2)
private val CGreen     = Color(0xFF10B981)
private val CGreenSoft = Color(0xFFECFDF5)
private val CBlueSoft  = Color(0xFFEFF6FF)
private val CBorder    = Color(0xFFE2E8F0)
private val CTextSec   = Color(0xFF64748B)
private val CTextMuted = Color(0xFF94A3B8)
private val CAmber     = Color(0xFFF59E0B)
private val CSurface   = Color(0xFFF8FAFC)

// ─────────────────────────────────────────────
//  PANTALLA
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuotasPrestamoScreen(
    prestamoId: String,
    navController: NavController,
    uid: String,
    rol: String
) {
    val db        = FirebaseFirestore.getInstance()
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val dec       = DecimalFormat("#,##0.00")

    var cuotas                    by remember { mutableStateOf(listOf<CuotaInfo>()) }
    var cargando                  by remember { mutableStateOf(true) }
    var esActivo                  by remember { mutableStateOf(true) }
    var estaSaldado               by remember { mutableStateOf(false) }
    var errorCarga                by remember { mutableStateOf<String?>(null) }
    var totalCapital              by remember { mutableStateOf(0.0) }
    var totalInteres              by remember { mutableStateOf(0.0) }
    var moraAplicada              by remember { mutableStateOf(0.0) }
    var saldoRestante             by remember { mutableStateOf(0.0) }
    var nombreCobrador            by remember { mutableStateOf("") }
    var nombreCliente             by remember { mutableStateOf("") }
    var descripcionPlazo          by remember { mutableStateOf("") }
    var proximoPagoProgramado     by remember { mutableStateOf<String?>(null) }
    var numeroPrestamo            by remember { mutableStateOf("") }
    var numeroPrestamoDisplay     by remember { mutableStateOf("") }
    var mostrarDialogoCancelarMora by remember { mutableStateOf(false) }

    // ── RECARGA ──────────────────────────────────────────────────────────
    suspend fun recargarDatosCompletos() {
        val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()

        val monto        = prestamoDoc.getDouble("monto") ?: 0.0
        val cuotasNum    = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
        val plazo        = prestamoDoc.getString("plazo") ?: "Mensual"
        val fechaInicio  = prestamoDoc.getTimestamp("fecha")?.toDate() ?: Date()
        val interesTotal = prestamoDoc.getDouble("interesTotal")
            ?: prestamoDoc.getDouble("interes") ?: 0.0

        totalCapital = monto
        totalInteres = interesTotal
        numeroPrestamo        = prestamoDoc.getNumeroPrestamoSafeCuotas()
        numeroPrestamoDisplay = if (numeroPrestamo != "N/D")
            "Préstamo N° $numeroPrestamo" else "Préstamo ID: $prestamoId"

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
            db            = db,
            prestamoId    = prestamoId,
            montoPrestamo = monto,
            interesTotal  = interesTotal,
            cuotasTotales = cuotasNum,
            fechaInicio   = fechaInicio,
            plazo         = plazo.lowercase()
        )

        proximoPagoProgramado = when (val pp = prestamoDoc.get("proximoPago")) {
            is Timestamp -> formatter.format(pp.toDate())
            is Date      -> formatter.format(pp)
            is String    -> pp
            else         -> null
        }

        val moraValor  = prestamoDoc.getDouble("mora") ?: 0.0
        val moraActiva = moraValor > 0.0

        // ── Saldo real desde colección pagos ─────────────────────────────
        val pagosSnap = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId).get().await()

        var totalCuotasPagadas = 0.0
        var totalMoraPagada    = 0.0
        for (pago in pagosSnap.documents) {
            totalCuotasPagadas += pago.getDouble("monto") ?: 0.0
            totalMoraPagada    += pago.getDouble("mora")  ?: 0.0
        }

        var moraHistorica = if (moraActiva) moraValor else 0.0
        if (moraActiva && moraValor < totalMoraPagada) {
            moraHistorica = totalMoraPagada + moraValor
        }
        moraAplicada = moraHistorica

        val totalPagarDoc = prestamoDoc.getDouble("totalPagar") ?: (monto + interesTotal)
        val moraPendiente = (moraAplicada - totalMoraPagada).coerceAtLeast(0.0)
        saldoRestante = (totalPagarDoc - totalCuotasPagadas + moraPendiente).coerceAtLeast(0.0)

        // ── Cuota mora ───────────────────────────────────────────────────
        if (moraActiva) {
            val moraPagada = moraPendiente <= 0.01
            cuotas = cuotas + CuotaInfo(
                numero      = cuotas.size + 1,
                fecha       = "Aplicada (mora)",
                capital     = 0.0,
                interes     = 0.0,
                total       = moraAplicada,
                descripcion = "Mora",
                pagada      = moraPagada,
                montoPagado = totalMoraPagada,
                fechaPago   = if (moraPagada) "Pagada" else null
            )
        }

        val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
        val cuotaMora      = cuotas.find { it.descripcion == "Mora" }
        estaSaldado = cuotasNormales.all { it.estaCompleta } &&
                (moraAplicada == 0.0 || cuotaMora?.estaCompleta == true)
    }

    // ── CARGA INICIAL ────────────────────────────────────────────────────
    LaunchedEffect(prestamoId) {
        cargando   = true
        errorCarga = null
        try {
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
            if (!prestamoDoc.exists())
                throw Exception("El préstamo no existe")
            if (prestamoDoc.getBoolean("eliminado") == true)
                throw Exception("Préstamo eliminado")
            if (rol == "cobrador") {
                val asignados = prestamoDoc.get("cobradoresAsignados") as? List<*> ?: emptyList<String>()
                if (!asignados.mapNotNull { it as? String }.contains(uid))
                    throw Exception("Sin permisos para ver este préstamo")
            }
            esActivo      = (prestamoDoc.getString("estado") ?: "activo") == "activo"
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

    // ── DIÁLOGO CANCELAR MORA ────────────────────────────────────────────
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
                                "Se eliminará la mora de L.${dec.format(moraAplicada)} del saldo pendiente.",
                                color = CRed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "El saldo volverá a su valor sin mora. Esta acción no se puede deshacer.",
                                color = Color(0xFFB91C1C), fontSize = 12.sp
                            )
                        }
                    }
                    Text(
                        "¿Confirmas la cancelación de la mora?",
                        fontSize = 14.sp, color = Color(0xFF374151),
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
                                            Toast.makeText(context, "No hay mora activa",
                                                Toast.LENGTH_SHORT).show()
                                        }
                                        return@withContext
                                    }
                                    val pagosSnapCancelar = db.collection("pagos")
                                        .whereEqualTo("prestamoId", prestamoId).get().await()
                                    var totalCuotasPagadas = 0.0
                                    var totalMoraPagada = 0.0
                                    for (pago in pagosSnapCancelar.documents) {
                                        totalCuotasPagadas += pago.getDouble("monto") ?: 0.0
                                        totalMoraPagada    += pago.getDouble("mora")  ?: 0.0
                                    }

                                    // ✅ FIX: "totalPagar" NUNCA incluye la mora (es la base fija
                                    // monto + interés). Al cancelar la mora, fijamos la mora
                                    // histórica igual a lo ya pagado para que moraPendiente = 0,
                                    // y el saldo se recalcula descontando SOLO el capital pagado.
                                    val totalPagarBase = snap.getDouble("totalPagar") ?: 0.0
                                    val nuevoSaldo = (totalPagarBase - totalCuotasPagadas).coerceAtLeast(0.0)

                                    val morasAplicadasActual = (snap.get("morasAplicadas") as? List<*>)
                                        ?.mapNotNull { it as? String } ?: emptyList()
                                    val morasActualizadas = if (morasAplicadasActual.isNotEmpty())
                                        morasAplicadasActual.dropLast(1) else emptyList<String>()

                                    val nuevoEstado = if (nuevoSaldo <= 0.01) "saldado" else "activo"

                                    val updateData = mutableMapOf<String, Any>(
                                        "mora"                     to totalMoraPagada,
                                        "saldo"                    to nuevoSaldo,
                                        "morasAplicadas"           to morasActualizadas,
                                        "estado"                   to nuevoEstado,
                                        "fechaUltimaActualizacion" to Timestamp.now(),
                                        "fechaUltimaMora"          to com.google.firebase.firestore.FieldValue.delete()
                                    )
                                    if (nuevoEstado == "saldado") {
                                        updateData["fechaSaldado"]     = Timestamp.now()
                                        updateData["fechaCancelacion"] = Timestamp.now()
                                    }
                                    if (morasActualizadas.isEmpty())
                                        updateData["saldoOriginal"] =
                                            com.google.firebase.firestore.FieldValue.delete()
                                    ref.update(updateData).await()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            "✅ Mora cancelada. Nuevo saldo: L.${dec.format(nuevoSaldo)}",
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
                    Icon(Icons.Default.Cancel, contentDescription = null,
                        modifier = Modifier.size(16.dp))
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

    // ── SCAFFOLD ─────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sistema de cuotas", color = Color.White,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (numeroPrestamoDisplay.isNotEmpty())
                            Text(numeroPrestamoDisplay,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 13.sp, maxLines = 1)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CNavy)
            )
        }
    ) { padding ->

        when {
            cargando -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = CBlue)
                    Spacer(Modifier.height(16.dp))
                    Text("Cargando información...", color = CTextSec)
                }
            }

            errorCarga != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(16.dp),
                    colors   = CardDefaults.cardColors(containerColor = CRedSoft)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Error", fontWeight = FontWeight.Bold,
                            fontSize = 20.sp, color = CRed)
                        Spacer(Modifier.height(12.dp))
                        Text(errorCarga!!, color = CRed, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { navController.popBackStack() },
                            colors  = ButtonDefaults.buttonColors(containerColor = CBlue)
                        ) { Text("Volver", color = Color.White) }
                    }
                }
            }

            else -> LazyColumn(
                modifier            = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
            ) {

                // ── CARD INFO PRÉSTAMO ────────────────────────────────────
                item {
                    Card(
                        modifier  = Modifier.fillMaxWidth(),
                        shape     = RoundedCornerShape(20.dp),
                        colors    = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .background(CBlue)
                            )
                            Column(modifier = Modifier.padding(16.dp)) {

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, contentDescription = null,
                                        tint = CBlue, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("Información del préstamo",
                                            fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                            color = CBlue)
                                        if (numeroPrestamo != "N/D")
                                            Text("N° $numeroPrestamo",
                                                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                                color = CBlue.copy(alpha = 0.75f))
                                    }
                                }

                                Spacer(Modifier.height(14.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        InfoChip(Icons.Default.Person, "Cliente",
                                            nombreCliente, modifier = Modifier.weight(1f))
                                        InfoChip(Icons.Default.DateRange, "Modalidad",
                                            descripcionPlazo, modifier = Modifier.weight(1f))
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        InfoChip(Icons.Default.AttachMoney, "Capital",
                                            "L.${dec.format(totalCapital)}",
                                            modifier = Modifier.weight(1f))
                                        InfoChip(Icons.Default.AttachMoney, "Interés",
                                            "L.${dec.format(totalInteres)}",
                                            modifier = Modifier.weight(1f))
                                    }
                                    val cuotasNorm = cuotas.filter { it.descripcion != "Mora" }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        InfoChip(Icons.Default.DateRange, "Cuotas",
                                            "${cuotasNorm.size}",
                                            modifier = Modifier.weight(1f))
                                        proximoPagoProgramado?.let { fecha ->
                                            InfoChip(Icons.Default.DateRange, "Próximo pago",
                                                fecha, valueColor = CBlue,
                                                modifier = Modifier.weight(1f))
                                        } ?: Spacer(Modifier.weight(1f))
                                    }
                                }

                                // ── Mora activa ───────────────────────────
                                if (moraAplicada > 0.0) {
                                    Spacer(Modifier.height(12.dp))
                                    val cuotaMora      = cuotas.find { it.descripcion == "Mora" }
                                    val moraEstaPagada = cuotaMora?.estaCompleta == true
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (moraEstaPagada) CGreenSoft else CRedSoft
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment     = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    if (moraEstaPagada)
                                                        "Mora pagada: L.${dec.format(moraAplicada)}"
                                                    else
                                                        "Mora activa: L.${dec.format(moraAplicada)}",
                                                    color = if (moraEstaPagada) CGreen else CRed,
                                                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                                                )
                                                if (cuotaMora != null &&
                                                    cuotaMora.montoPagado > 0 && !moraEstaPagada)
                                                    Text(
                                                        "Pagado: L.${dec.format(cuotaMora.montoPagado)}",
                                                        color = CAmber, fontSize = 12.sp
                                                    )
                                            }
                                            if (!moraEstaPagada && rol == "admin") {
                                                Spacer(Modifier.width(8.dp))
                                                OutlinedButton(
                                                    onClick = { mostrarDialogoCancelarMora = true },
                                                    shape   = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(
                                                        horizontal = 12.dp, vertical = 6.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(
                                                        contentColor = CRed),
                                                    border = androidx.compose.foundation.BorderStroke(
                                                        1.dp, CRed.copy(alpha = 0.5f))
                                                ) {
                                                    Icon(Icons.Default.Cancel,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Cancelar mora",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(color = CBorder, thickness = 0.5.dp)
                                Spacer(Modifier.height(12.dp))

                                // ── TOTAL A PAGAR + SALDO RESTANTE ────────
                                val cuotaMoraFinal   = cuotas.find { it.descripcion == "Mora" }
                                val moraAunPendiente = cuotaMoraFinal != null &&
                                        !cuotaMoraFinal.estaCompleta
                                val moraEnTotal      = moraAplicada

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Total a pagar
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(CBlueSoft)
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            "Total a pagar",
                                            fontSize = 11.sp,
                                            color = CBlue.copy(alpha = 0.75f),
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            "L.${dec.format(totalCapital + totalInteres + moraEnTotal)}",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = CBlue
                                        )
                                        if (moraAunPendiente && moraAplicada > 0.0)
                                            Text(
                                                "↳ mora acumulada: L.${dec.format(moraAplicada)}",
                                                fontSize = 10.sp,
                                                color = CRed,
                                                fontWeight = FontWeight.Medium
                                            )
                                    }

                                    // Saldo restante
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (saldoRestante <= 0.01) CGreenSoft else CRedSoft
                                            )
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            "Saldo restante",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (saldoRestante <= 0.01)
                                                CGreen.copy(alpha = 0.75f)
                                            else
                                                CRed.copy(alpha = 0.75f)
                                        )
                                        Text(
                                            if (saldoRestante <= 0.01) "L.0.00 ✓"
                                            else "L.${dec.format(saldoRestante)}",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (saldoRestante <= 0.01) CGreen else CRed
                                        )
                                        if (saldoRestante <= 0.01)
                                            Text(
                                                "Préstamo saldado",
                                                fontSize = 10.sp,
                                                color = CGreen,
                                                fontWeight = FontWeight.Medium
                                            )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── PROGRESO ──────────────────────────────────────────────
                item {
                    val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                    val cuotasPagadas  = cuotasNormales.count { it.estaCompleta }
                    val totalCuotas    = cuotasNormales.size
                    val progreso       = if (totalCuotas > 0)
                        cuotasPagadas.toFloat() / totalCuotas else 0f

                    Card(
                        modifier  = Modifier.fillMaxWidth(),
                        shape     = RoundedCornerShape(20.dp),
                        colors    = CardDefaults.cardColors(
                            containerColor = if (estaSaldado) CGreenSoft else Color.White
                        ),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                if (estaSaldado) "Préstamo saldado ✓" else "Progreso de pagos",
                                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                color = if (estaSaldado) CGreen else CBlue
                            )
                            Spacer(Modifier.height(12.dp))
                            if (!estaSaldado) {
                                LinearProgressIndicator(
                                    progress   = progreso,
                                    modifier   = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color      = CGreen,
                                    trackColor = CBorder
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                            Text(
                                "$cuotasPagadas de $totalCuotas cuotas completadas " +
                                        "(${String.format("%.0f", progreso * 100)}%)",
                                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = if (estaSaldado) CGreen else CTextSec
                            )
                        }
                    }
                }

                // ── AVISO CASCADA ─────────────────────────────────────────
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(14.dp),
                        colors   = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Sistema de pagos en cascada",
                                fontWeight = FontWeight.Bold, color = CAmber, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Los pagos se distribuyen automáticamente completando " +
                                        "cuotas en orden secuencial.",
                                color = Color(0xFFB45309), fontSize = 12.sp
                            )
                        }
                    }
                }

                // ── EXPORTAR PDF ──────────────────────────────────────────
                item {
                    Button(
                        onClick = {
                            val pdfFile = ReciboHelper.generarCuotasPDF(
                                context    = context,
                                cliente    = nombreCliente,
                                prestamoId = if (numeroPrestamo != "N/D")
                                    "Préstamo N° $numeroPrestamo" else prestamoId,
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
                                Toast.makeText(context, "PDF generado correctamente",
                                    Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Error al generar PDF",
                                    Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = CBlue)
                    ) {
                        Text("Exportar cuotas en PDF", color = Color.White,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // ── LISTA CUOTAS ──────────────────────────────────────────
                items(cuotas, key = { it.numero }) { cuota ->
                    CuotaCard(
                        cuota          = cuota,
                        dec            = dec,
                        esActivo       = esActivo,
                        rol            = rol,
                        onMarcarPagada = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val prestamoRef  = db.collection("prestamos").document(prestamoId)
                                        val prestamoSnap = prestamoRef.get().await()

                                        val monto        = prestamoSnap.getDouble("monto") ?: 0.0
                                        val interesTotal = prestamoSnap.getDouble("interesTotal")
                                            ?: prestamoSnap.getDouble("interes") ?: 0.0
                                        val totalPagar   = prestamoSnap.getDouble("totalPagar")
                                            ?: (monto + interesTotal)
                                        val moraActivaSnap = prestamoSnap.getDouble("mora") ?: 0.0
                                        val numPrestamo  = prestamoSnap.getNumeroPrestamoSafeCuotas()
                                        val plazo        = prestamoSnap.getString("plazo") ?: "semanal"
                                        val fechaInicio  = prestamoSnap.getTimestamp("fecha")?.toDate()
                                            ?: Date()

                                        val pagosSnapshot = db.collection("pagos")
                                            .whereEqualTo("prestamoId", prestamoId).get().await()

                                        var totalRealmentePagado  = 0.0
                                        var totalMoraYaPagadaSnap = 0.0
                                        var totalCuotasYaPagadasSnap = 0.0
                                        for (pago in pagosSnapshot.documents) {
                                            val pm = pago.getDouble("monto") ?: 0.0
                                            val mm = pago.getDouble("mora")  ?: 0.0
                                            totalRealmentePagado     += pm + mm
                                            totalMoraYaPagadaSnap    += mm
                                            totalCuotasYaPagadasSnap += pm
                                        }

                                        val nuevoMontoPagado = totalRealmentePagado + cuota.total

                                        // ✅ FIX: Saldo correcto separando mora de cuotas.
                                        // Si es cuota normal: va a totalCuotasPagadas
                                        // Si es mora manual: va a totalMoraPagada
                                        val esCuotaMora = cuota.descripcion == "Mora"
                                        val cuotasAcumuladasSnap = totalCuotasYaPagadasSnap +
                                            if (!esCuotaMora) cuota.total else 0.0
                                        val moraAplicadaSnap     = totalMoraYaPagadaSnap +
                                            if (esCuotaMora) cuota.total else 0.0
                                        val moraPendienteSnap    = (moraActivaSnap - moraAplicadaSnap).coerceAtLeast(0.0)
                                        val nuevoSaldo = (totalPagar - cuotasAcumuladasSnap + moraPendienteSnap).coerceAtLeast(0.0)

                                        val cuotasPagadas = mutableSetOf<Int>()
                                        for (pago in pagosSnapshot.documents) {
                                            val cc = pago.get("cuotasCubiertas") as? List<*>
                                            cc?.forEach { cd ->
                                                if (cd is Map<*, *>)
                                                    (cd["numeroCuota"] as? Number)?.toInt()
                                                        ?.let { cuotasPagadas.add(it) }
                                            }
                                        }
                                        cuotasPagadas.add(cuota.numero)

                                        val cuotasNum = prestamoSnap.getLong("cuotas")?.toInt() ?: 1
                                        var proximaCuotaPendiente: Int? = null
                                        for (i in 1..cuotasNum)
                                            if (!cuotasPagadas.contains(i)) {
                                                proximaCuotaPendiente = i; break
                                            }

                                        val proximoPagoStr = if (proximaCuotaPendiente != null)
                                            calcularFechaCuota(fechaInicio, plazo,
                                                proximaCuotaPendiente)
                                        else "saldado"

                                        val fechaActual = Timestamp.now()
                                        val fechaFormateada = SimpleDateFormat(
                                            "dd/MM/yyyy HH:mm", Locale.getDefault())
                                            .format(fechaActual.toDate())

                                        val abonoManual = mapOf(
                                            "prestamoId"             to prestamoId,
                                            "numeroPrestamo"         to numPrestamo,
                                            "monto"                  to if (cuota.descripcion == "Mora") 0.0 else cuota.total,
                                            "mora"                   to if (cuota.descripcion == "Mora") cuota.total else 0.0,
                                            "fechaPago"              to fechaActual,
                                            "registradoPor"          to uid,
                                            "nombreCobrador"         to nombreCobrador,
                                            "clienteNombre"          to nombreCliente,
                                            "metodoPago"             to "Manual (Admin)",
                                            "sistemaPagoEnCascada"   to true,
                                            "saldoRestante"          to nuevoSaldo,
                                            "proximaFechaProgramada" to proximoPagoStr,
                                            "cuotasCubiertas" to listOf(
                                                mapOf(
                                                    "numeroCuota"   to cuota.numero,
                                                    "montoAplicado" to cuota.total,
                                                    "completada"    to true
                                                )
                                            ),
                                            "observaciones" to "Marcado manualmente por administrador"
                                        )
                                        db.collection("pagos").add(abonoManual).await()

                                        val saldoAnterior = (totalPagar - totalRealmentePagado)
                                            .coerceAtLeast(0.0)
                                        val pdfFile = ReciboHelper.generarReciboPDF(
                                            context        = context,
                                            cliente        = nombreCliente,
                                            prestamoId     = if (numPrestamo != "N/D")
                                                "Préstamo N° $numPrestamo" else "ID: $prestamoId",
                                            fecha          = fechaFormateada,
                                            montoPagado    = cuota.total.toString(),
                                            saldoAnterior  = saldoAnterior,
                                            proximoPago    = proximoPagoStr,
                                            cuota          = if (cuota.descripcion == "Mora")
                                                "MORA" else "Cuota #${cuota.numero}",
                                            cobrador       = nombreCobrador,
                                            lugar          = "Registro Manual",
                                            firma          = nombreCobrador,
                                            tipoPago       = "Manual (Admin)",
                                            mora           = if (cuota.descripcion == "Mora")
                                                cuota.total else 0.0,
                                            saldoNuevoFijo = nuevoSaldo
                                        )
                                        if (pdfFile != null && pdfFile.exists())
                                            ReciboHelper.compartirReciboPDF(context, pdfFile)

                                        val actualizacion = if (nuevoSaldo <= 0.90) {
                                            mapOf<String, Any>(
                                                "saldo"                    to 0.0,
                                                "montoPagado"              to totalPagar,
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
                                        prestamoRef.update(actualizacion).await()

                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                if (nuevoSaldo <= 0.90)
                                                    "¡PRÉSTAMO N° $numPrestamo SALDADO! ✅"
                                                else
                                                    "Cuota pagada. Saldo: L.${dec.format(nuevoSaldo)}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                    recargarDatosCompletos()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}",
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }

                // ── RESUMEN ───────────────────────────────────────────────
                item {
                    val cuotasNormales   = cuotas.filter { it.descripcion != "Mora" }
                    val cuotasCompletas  = cuotasNormales.count { it.estaCompleta }
                    val cuotasParciales  = cuotasNormales.count {
                        it.montoPagado > 0 && !it.estaCompleta }
                    val cuotasPendientes = cuotasNormales.count { it.montoPagado == 0.0 }

                    Card(
                        modifier  = Modifier.fillMaxWidth(),
                        shape     = RoundedCornerShape(20.dp),
                        colors    = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5)),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Resumen", fontWeight = FontWeight.Bold,
                                fontSize = 16.sp, color = Color(0xFF7B1FA2))
                            Spacer(Modifier.height(10.dp))
                            ResumenFilaCuotas("Completadas",      cuotasCompletas,  Color(0xFF388E3C))
                            ResumenFilaCuotas("Con pago parcial", cuotasParciales,  Color(0xFFFF9800))
                            ResumenFilaCuotas("Pendientes",       cuotasPendientes, Color(0xFF757575))
                        }
                    }
                }

                // ── BOTONES NAVEGACIÓN ────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick  = { navController.popBackStack() },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = CBlue)
                        ) { Text("← Regresar") }
                        Button(
                            onClick  = { navController.popBackStack() },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = CBlue)
                        ) { Text("Registrar pago →", color = Color.White) }
                    }
                }

                // ── PIE ───────────────────────────────────────────────────
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = CardDefaults.cardColors(containerColor = CSurface)
                    ) {
                        Text(
                            "Usuario: $nombreCobrador ($rol) · " +
                                    if (numeroPrestamo != "N/D") "N° $numeroPrestamo" else prestamoId,
                            modifier  = Modifier.padding(12.dp).fillMaxWidth(),
                            fontSize  = 11.sp,
                            color     = CTextMuted,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
//  CHIP INFO
// ─────────────────────────────────────────────
@Composable
private fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color(0xFF0F172A)
) {
    Card(
        modifier  = modifier,
        colors    = CardDefaults.cardColors(containerColor = CSurface),
        shape     = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(icon, contentDescription = null,
                    tint = CTextSec, modifier = Modifier.size(12.dp))
                Text(label, fontSize = 11.sp, color = CTextSec)
            }
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = valueColor, maxLines = 2)
        }
    }
}

// ─────────────────────────────────────────────
//  TARJETA CUOTA
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
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = when {
                cuota.estaCompleta    -> CGreenSoft
                cuota.montoPagado > 0 -> Color(0xFFFFF7ED)
                else                  -> Color.White
            }
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        when {
                            cuota.descripcion == "Mora" -> CRed
                            cuota.estaCompleta          -> CGreen
                            cuota.montoPagado > 0       -> CAmber
                            else                        -> CBorder
                        }
                    )
            )
            Column(modifier = Modifier.padding(14.dp)) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (cuota.descripcion == "Mora") "MORA" else "Cuota ${cuota.numero}",
                            fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            color = if (cuota.descripcion == "Mora") CRed else CBlue
                        )
                        if (cuota.descripcion == "Mora") {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CRed)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("MORA", color = Color.White,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (cuota.estaCompleta) Icons.Default.CheckCircle
                            else Icons.Default.HourglassBottom,
                            contentDescription = null,
                            tint = if (cuota.estaCompleta) CGreen else CTextSec,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when {
                                cuota.estaCompleta    -> "Completa"
                                cuota.montoPagado > 0 -> "${String.format("%.0f", cuota.porcentajePagado)}%"
                                else                  -> "Pendiente"
                            },
                            color = when {
                                cuota.estaCompleta    -> CGreen
                                cuota.montoPagado > 0 -> CAmber
                                else                  -> CTextSec
                            },
                            fontWeight = FontWeight.Medium, fontSize = 13.sp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (cuota.descripcion != "Mora")
                    Text("Fecha: ${cuota.fecha}", fontSize = 12.sp, color = CTextSec)

                if (cuota.estaCompleta && cuota.fechaPago != null) {
                    Text("✓ Pagada: ${cuota.fechaPago}",
                        color = CGreen, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                } else if (cuota.montoPagado > 0 && cuota.historialPagos.isNotEmpty()) {
                    if (cuota.historialPagos.size == 1)
                        Text("Pago parcial: ${cuota.historialPagos.first()}",
                            color = CAmber, fontSize = 12.sp)
                    else {
                        Text("Pagos múltiples:", color = CAmber,
                            fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        cuota.historialPagos.forEachIndexed { i, fecha ->
                            Text("  • Pago ${i + 1}: $fecha",
                                color = CAmber, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (cuota.capital > 0 || cuota.interes > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (cuota.capital > 0)
                            Text("Capital: L.${dec.format(cuota.capital)}",
                                fontSize = 12.sp, color = CTextSec)
                        if (cuota.interes > 0)
                            Text("Interés: L.${dec.format(cuota.interes)}",
                                fontSize = 12.sp, color = CTextSec)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        "Total: L.${dec.format(cuota.total)}",
                        fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        color = if (cuota.descripcion == "Mora") CRed else Color(0xFF0F172A)
                    )
                    if (cuota.montoPagado > 0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Pagado: L.${dec.format(cuota.montoPagado)}",
                                color = CGreen, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            if (!cuota.estaCompleta)
                                Text("Resta: L.${dec.format(cuota.total - cuota.montoPagado)}",
                                    color = CAmber, fontSize = 11.sp)
                        }
                    }
                }

                if (!cuota.estaCompleta && esActivo) {
                    Spacer(Modifier.height(10.dp))
                    when (rol) {
                        "admin" -> Button(
                            onClick  = onMarcarPagada,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape    = RoundedCornerShape(10.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = CBlue)
                        ) {
                            Text(
                                if (cuota.descripcion == "Mora") "Marcar mora como pagada (admin)"
                                else "Marcar como pagada (admin)",
                                color = Color.White, fontSize = 13.sp
                            )
                        }
                        "cobrador" -> Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                            shape  = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                "Para registrar pagos usa la pantalla Registrar Pago. " +
                                        "Los pagos se distribuyen automáticamente.",
                                modifier = Modifier.padding(10.dp),
                                color    = Color(0xFFB45309), fontSize = 11.sp
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = CTextSec)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cantidad.toString(), fontSize = 15.sp,
                fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}