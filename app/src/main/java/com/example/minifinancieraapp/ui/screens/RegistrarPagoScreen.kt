package com.example.minifinancieraapp.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.theme.PrimaryButton
import com.example.capitalexpressapp.util.NetworkUtils.guardarAbonoPendiente
import com.example.capitalexpressapp.util.NetworkUtils.isInternetAvailable
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.minifinancieraapp.util.SessionManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

// ─────────────────────────────────────────────
//  PALETA (igual que NotificacionesScreen)
// ─────────────────────────────────────────────
private object RP {
    val Navy      = Color(0xFF0A1628)
    val NavyMid   = Color(0xFF0F2044)
    val Blue      = Color(0xFF1A56DB)
    val BlueSoft  = Color(0xFF3B82F6)
    val BlueLight = Color(0xFFEFF6FF)

    val Red       = Color(0xFFEF4444)
    val RedSoft   = Color(0xFFFEF2F2)
    val Orange    = Color(0xFFF97316)
    val OrangeSoft = Color(0xFFFFF7ED)
    val Amber     = Color(0xFFF59E0B)
    val AmberSoft = Color(0xFFFFFBEB)
    val Green     = Color(0xFF10B981)
    val GreenSoft = Color(0xFFECFDF5)

    val Card      = Color(0xFFFFFFFF)
    val Surface   = Color(0xFFF8FAFC)
    val Border    = Color(0xFFE2E8F0)
    val TextPri   = Color(0xFF0F172A)
    val TextSec   = Color(0xFF64748B)
    val TextMuted = Color(0xFF94A3B8)

    val GradStart = Color(0xFF0A1628)
    val GradEnd   = Color(0xFF1A3A6B)
}

private val dec = DecimalFormat("#,##0.00")

// ─────────────────────────────────────────────
//  DATA CLASSES (se mantienen igual)
// ─────────────────────────────────────────────
data class CuotaCubierta(
    val numeroCuota: Int,
    val montoAplicado: Double,
    val completada: Boolean
)

data class ResultadoDistribucion(
    val cuotasCubiertas: List<CuotaCubierta>,
    val proximaCuotaPendiente: Int,
    val fechaProximoPago: String,
    val totalCuotasCompletas: Int
)

// ─────────────────────────────────────────────
//  HELPERS (sin cambios)
// ─────────────────────────────────────────────
fun obtenerNumeroPrestamoSafe(doc: com.google.firebase.firestore.DocumentSnapshot): String {
    return try {
        when (val campo = doc.get("numeroPrestamo")) {
            is String -> campo.takeIf { it.isNotBlank() } ?: "0"
            is Long   -> campo.toString()
            is Int    -> campo.toString()
            is Number -> campo.toLong().toString()
            null      -> "0"
            else      -> {
                Log.w("NumeroPrestamo", "Tipo inesperado: ${campo::class.java.simpleName}")
                "0"
            }
        }
    } catch (e: Exception) {
        Log.e("NumeroPrestamo", "Error: ${e.message}", e)
        "0"
    }
}

private suspend fun resolverNombreCobrador(
    context: Context,
    db: FirebaseFirestore,
    uidParam: String
): String {
    fun getCachedName(ctx: Context): String? =
        ctx.getSharedPreferences("recibo_cache", Context.MODE_PRIVATE)
            .getString("nombreCobrador", null)

    fun setCachedName(ctx: Context, value: String) =
        ctx.getSharedPreferences("recibo_cache", Context.MODE_PRIVATE)
            .edit().putString("nombreCobrador", value).apply()

    if (uidParam.contains(" ") && uidParam.length < 40 &&
        !uidParam.equals("COBRADOR", ignoreCase = true) &&
        !uidParam.equals("Sin asignar", ignoreCase = true)
    ) {
        val nice = uidParam.trim()
        setCachedName(context, nice)
        return nice
    }

    val cached = getCachedName(context)
    if (!cached.isNullOrBlank()) return cached.trim()

    try {
        val snap = db.collection("usuarios").document(uidParam).get().await()
        if (snap.exists()) {
            val nombre = snap.getString("nombre")?.trim()
            if (!nombre.isNullOrBlank()) { setCachedName(context, nombre); return nombre }
        }
    } catch (_: Exception) {}

    for (campo in listOf("codigo", "identidad", "telefono", "nombre")) {
        try {
            val q = db.collection("usuarios").whereEqualTo(campo, uidParam).limit(1).get().await()
            if (!q.isEmpty) {
                val nombre = q.documents.first().getString("nombre")?.trim()
                if (!nombre.isNullOrBlank()) { setCachedName(context, nombre); return nombre }
            }
        } catch (_: Exception) {}
    }

    return try {
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Cobrador-${uidParam.takeLast(6)}"
    } catch (_: Exception) { "Cobrador-${uidParam.takeLast(6)}" }
}

private fun calcularFechaCuota(fechaInicio: Date, plazo: String, numeroCuota: Int): String {
    val calendar = Calendar.getInstance().apply { time = fechaInicio }
    val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    when (plazo.lowercase()) {
        "diario"          -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota)
        "lunes a sábado"  -> repeat(numeroCuota) {
            do { calendar.add(Calendar.DAY_OF_YEAR, 1) }
            while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
        }
        "semanal"         -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 7)
        "quincenal"       -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 15)
        "mensual"         -> calendar.add(Calendar.MONTH, numeroCuota)
        "bimestral"       -> calendar.add(Calendar.MONTH, numeroCuota * 2)
        else              -> calendar.add(Calendar.MONTH, numeroCuota)
    }
    return fmt.format(calendar.time)
}

private suspend fun obtenerEstadoCuotasCompleto(
    db: FirebaseFirestore,
    prestamoId: String
): Map<Int, Double> {
    try {
        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId).get().await()
        if (!pagosSnapshot.isEmpty) {
            val pagosPorCuota = mutableMapOf<Int, Double>()
            for (pago in pagosSnapshot.documents) {
                val cuotasCubiertas = pago.get("cuotasCubiertas") as? List<*>
                if (!cuotasCubiertas.isNullOrEmpty()) {
                    cuotasCubiertas.forEach { cuotaData ->
                        if (cuotaData is Map<*, *>) {
                            val num = (cuotaData["numeroCuota"] as? Number)?.toInt() ?: return@forEach
                            val monto = (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0
                            if (num > 0 && monto > 0)
                                pagosPorCuota[num] = (pagosPorCuota[num] ?: 0.0) + monto
                        }
                    }
                } else {
                    val num = when {
                        pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                        pago.contains("cuota")       -> pago.getLong("cuota")?.toInt() ?: 1
                        else -> 1
                    }
                    val monto = pago.getDouble("monto") ?: 0.0
                    if (monto > 0) pagosPorCuota[num] = (pagosPorCuota[num] ?: 0.0) + monto
                }
            }
            if (pagosPorCuota.isNotEmpty()) return pagosPorCuota
        }
    } catch (e: Exception) {
        Log.w("EstadoCuotas", "Lectura de pagos no disponible: ${e.message}")
    }
    return emptyMap()
}

private suspend fun distribuirPagoConMoraYCascada(
    db: FirebaseFirestore,
    prestamoId: String,
    montoPagado: Double,
    cuotaEstimada: Double,
    cuotasTotales: Int
): ResultadoDistribucion {
    return try {
        val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
        val moraActiva  = prestamoDoc.getDouble("mora") ?: 0.0

        var montoRestante = montoPagado
        val cuotasCubiertas = mutableListOf<CuotaCubierta>()
        var totalCuotasCompletas = 0

        // 1. COBRAR MORA PRIMERO
        if (moraActiva > 0.0) {
            val pagosSnapshot = db.collection("pagos")
                .whereEqualTo("prestamoId", prestamoId).get().await()
            var moraPagadaAcumulada = 0.0
            for (pago in pagosSnapshot.documents)
                moraPagadaAcumulada += pago.getDouble("mora") ?: 0.0

            var moraPendiente = (moraActiva - moraPagadaAcumulada).coerceAtLeast(0.0)
            
            // Auto-fix for corrupted documents where mora was overwritten
            if (moraActiva > 0 && moraActiva < moraPagadaAcumulada) {
                val moraCorregida = moraPagadaAcumulada + moraActiva
                try { db.collection("prestamos").document(prestamoId).update("mora", moraCorregida) } catch (_: Exception) {}
                moraPendiente = (moraCorregida - moraPagadaAcumulada).coerceAtLeast(0.0)
            }

            if (moraPendiente > 0.01 && montoRestante > 0.01) {
                val montoAAplicarMora = minOf(montoRestante, moraPendiente)
                val moraCompleta = montoAAplicarMora >= moraPendiente - 0.01
                cuotasCubiertas.add(CuotaCubierta(0, montoAAplicarMora, moraCompleta))
                if (moraCompleta) totalCuotasCompletas++
                montoRestante -= montoAAplicarMora
            }
        }

        // 2. DISTRIBUIR EN CASCADA
        if (montoRestante > 0.01) {
            val estadoCuotas = obtenerEstadoCuotasCompleto(db, prestamoId)
            for (i in 1..cuotasTotales) {
                if (montoRestante <= 0.01) break
                val montoPagadoEnCuota  = estadoCuotas[i] ?: 0.0
                val montoRestanteCuota  = (cuotaEstimada - montoPagadoEnCuota).coerceAtLeast(0.0)
                if (montoRestanteCuota > 0.01) {
                    val montoAAplicar   = minOf(montoRestante, montoRestanteCuota)
                    val montoPagadoTotal = montoPagadoEnCuota + montoAAplicar
                    val cuotaCompleta   = montoPagadoTotal >= cuotaEstimada - 0.01
                    cuotasCubiertas.add(CuotaCubierta(i, montoAAplicar, cuotaCompleta))
                    if (cuotaCompleta) totalCuotasCompletas++
                    montoRestante -= montoAAplicar
                }
            }
        }

        // 3. PRÓXIMA CUOTA PENDIENTE
        val estadoActualizado = obtenerEstadoCuotasCompleto(db, prestamoId).toMutableMap()
        cuotasCubiertas.filter { it.numeroCuota > 0 }.forEach { cuota ->
            estadoActualizado[cuota.numeroCuota] =
                (estadoActualizado[cuota.numeroCuota] ?: 0.0) + cuota.montoAplicado
        }
        var proximaCuotaPendiente = cuotasTotales + 1
        for (i in 1..cuotasTotales) {
            if ((estadoActualizado[i] ?: 0.0) < cuotaEstimada - 0.01) {
                proximaCuotaPendiente = i; break
            }
        }

        val fechaProximoPago = if (proximaCuotaPendiente <= cuotasTotales) {
            val fechaInicio = prestamoDoc.getTimestamp("fecha")?.toDate() ?: Date()
            val plazo = prestamoDoc.getString("plazo") ?: "semanal"
            calcularFechaCuota(fechaInicio, plazo, proximaCuotaPendiente)
        } else "saldado"

        ResultadoDistribucion(cuotasCubiertas, proximaCuotaPendiente, fechaProximoPago, totalCuotasCompletas)

    } catch (e: Exception) {
        Log.e("DistribucionConMora", "Error: ${e.message}", e)
        ResultadoDistribucion(listOf(CuotaCubierta(1, montoPagado, false)), 1, "pendiente", 0)
    }
}

suspend fun verificarYCrearCliente(
    db: FirebaseFirestore,
    clienteId: String,
    nombreCliente: String
): Boolean {
    return try {
        val clienteDoc = db.collection("clientes").document(clienteId).get().await()
        if (!clienteDoc.exists()) {
            db.collection("clientes").document(clienteId).set(
                mapOf(
                    "nombre"          to nombreCliente,
                    "fechaCreacion"   to Timestamp.now(),
                    "ultimaActividad" to Timestamp.now(),
                    "estado"          to "activo"
                )
            ).await()
        }
        true
    } catch (e: Exception) { Log.e("CrearCliente", "Error: ${e.message}"); false }
}

// ─────────────────────────────────────────────
//  PANTALLA PRINCIPAL
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrarPagoScreen(
    navController: NavController,
    clienteId: String,
    prestamoId: String,
    saldoActual: Double,
    cobrador: String
) {
    val context = LocalContext.current
    val db      = FirebaseFirestore.getInstance()
    val scope   = rememberCoroutineScope()
    val session = remember { SessionManager(context) }

    var nombreCliente         by remember { mutableStateOf("") }
    var montoAbono            by remember { mutableStateOf("") }
    var lugar                 by remember { mutableStateOf("El Paraíso, Danlí") }
    var firmaPrestamista      by remember { mutableStateOf("") }
    var archivoPDF            by remember { mutableStateOf<File?>(null) }
    var metodoPago            by remember { mutableStateOf("Efectivo") }
    val opcionesMetodoPago    = listOf("Efectivo", "Transferencia")
    var expandedMetodoPago    by remember { mutableStateOf(false) }
    var nombreCobrador        by remember { mutableStateOf(cobrador) }
    var botonHabilitado       by remember { mutableStateOf(true) }

    // Datos del préstamo
    var montoPrestamo         by remember { mutableStateOf(0.0) }
    var interesTotal          by remember { mutableStateOf(0.0) }
    var totalAPagar           by remember { mutableStateOf(0.0) }
    var moraActiva            by remember { mutableStateOf(0.0) }
    var cuotaEstimada         by remember { mutableStateOf(0.0) }
    var cuotasTotales         by remember { mutableStateOf(1) }
    var plazo                 by remember { mutableStateOf("Semanal") }
    var numeroPrestamo        by remember { mutableStateOf("") }
    var montoPagadoActual     by remember { mutableStateOf(0.0) }
    var saldoActualizado      by remember { mutableStateOf(saldoActual) }
    var proximaCuotaPendiente by remember { mutableStateOf(1) }
    var fechaProximoPago      by remember { mutableStateOf("") }
    var vistaPrevia           by remember { mutableStateOf<ResultadoDistribucion?>(null) }

    // ── CARGA INICIAL ────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        try {
            val uidActualSesion = session.getUid()
            if (uidActualSesion.isNullOrEmpty()) {
                Toast.makeText(context, "Error: Sesión no válida", Toast.LENGTH_LONG).show()
                navController.popBackStack(); return@LaunchedEffect
            }
            val uidParaRecibo = when {
                cobrador.isNotBlank() &&
                        !cobrador.equals("COBRADOR", true) &&
                        !cobrador.equals("Sin asignar", true) -> cobrador.trim()
                else -> uidActualSesion
            }
            val nombreCobradorLimpio = resolverNombreCobrador(context, db, uidParaRecibo)
            nombreCobrador   = nombreCobradorLimpio
            firmaPrestamista = nombreCobradorLimpio

            val clienteDoc = db.collection("clientes").document(clienteId).get().await()
            nombreCliente = if (clienteDoc.exists()) clienteDoc.getString("nombre") ?: "Cliente"
            else "Cliente $clienteId"

            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
            if (!prestamoDoc.exists()) {
                Toast.makeText(context, "El préstamo no existe", Toast.LENGTH_LONG).show()
                navController.popBackStack(); return@LaunchedEffect
            }

            montoPrestamo = prestamoDoc.getDouble("monto") ?: 0.0
            interesTotal  = prestamoDoc.getDouble("interesTotal")
                ?: prestamoDoc.getDouble("interes") ?: 0.0
            totalAPagar   = prestamoDoc.getDouble("totalPagar") ?: (montoPrestamo + interesTotal)
            moraActiva    = prestamoDoc.getDouble("mora") ?: 0.0
            cuotaEstimada = prestamoDoc.getDouble("cuota") ?: 0.0
            cuotasTotales = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
            plazo         = prestamoDoc.getString("plazo") ?: "Semanal"
            numeroPrestamo = obtenerNumeroPrestamoSafe(prestamoDoc)

            val pagosSnapshot = db.collection("pagos")
                .whereEqualTo("prestamoId", prestamoId).get().await()
            var totalRealmentePagado = 0.0
            var totalMoraInit        = 0.0
            var totalCuotasInit      = 0.0
            for (pago in pagosSnapshot.documents) {
                val pm = pago.getDouble("monto") ?: 0.0
                val mm = pago.getDouble("mora")  ?: 0.0
                totalRealmentePagado += pm + mm
                totalMoraInit        += mm
                totalCuotasInit      += pm
            }

            montoPagadoActual = totalRealmentePagado
            // ✅ FIX: saldo display = base - cuotas pagadas + mora pendiente
            var moraHistoricaInit = moraActiva
            if (moraActiva > 0 && moraActiva < totalMoraInit) {
                moraHistoricaInit = totalMoraInit + moraActiva
            }
            val moraPendienteInit = (moraHistoricaInit - totalMoraInit).coerceAtLeast(0.0)
            saldoActualizado  = (totalAPagar - totalCuotasInit + moraPendienteInit).coerceAtLeast(0.0)

            val resultado = distribuirPagoConMoraYCascada(db, prestamoId, 0.0, cuotaEstimada, cuotasTotales)
            proximaCuotaPendiente = resultado.proximaCuotaPendiente
            fechaProximoPago      = resultado.fechaProximoPago

        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("RegistrarPagoScreen", "Error init: ", e)
        }
    }

    // ── VISTA PREVIA ─────────────────────────────────────────────────────
    LaunchedEffect(montoAbono) {
        val abono = montoAbono.toDoubleOrNull() ?: 0.0
        vistaPrevia = if (abono > 0.0)
            distribuirPagoConMoraYCascada(db, prestamoId, abono, cuotaEstimada, cuotasTotales)
        else null
    }

    // ── UI ───────────────────────────────────────────────────────────────
    Scaffold(
        containerColor = RP.Surface,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(RP.GradStart, RP.GradEnd)))
            ) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Regresar",
                                    tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text("Registrar pago",
                                    fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                                    color = Color.White, letterSpacing = (-0.5).sp)
                                if (numeroPrestamo.isNotEmpty() && numeroPrestamo != "0") {
                                    Text("Préstamo N° $numeroPrestamo",
                                        fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f))
                                }
                            }
                        }
                        // Avatar cliente
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = nombreCliente.take(1).uppercase(),
                                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // ── 1. RESUMEN FINANCIERO ─────────────────────────────────────
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = RP.Card),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column {
                    // Franja superior azul
                    Box(
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                            .background(Brush.horizontalGradient(listOf(RP.Blue, RP.BlueSoft)))
                    )
                    Column(modifier = Modifier.padding(16.dp)) {

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null,
                                tint = RP.Blue, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(nombreCliente,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = RP.TextPri)
                        }

                        Spacer(Modifier.height(14.dp))

                        // Cuatro métricas en 2×2
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MetricaChip("Capital", "L. ${dec.format(montoPrestamo)}",
                                RP.Blue, RP.BlueLight, Modifier.weight(1f))
                            MetricaChip("Interés", "L. ${dec.format(interesTotal)}",
                                RP.TextSec, RP.Surface, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MetricaChip("Pagado", "L. ${dec.format(montoPagadoActual)}",
                                RP.Green, RP.GreenSoft, Modifier.weight(1f))
                            MetricaChip("Cuota", "L. ${dec.format(cuotaEstimada)}",
                                RP.TextSec, RP.Surface, Modifier.weight(1f))
                        }

                        // ── MORA (si existe) ──────────────────────────────
                        if (moraActiva > 0.0) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(RP.RedSoft)
                                    .border(1.dp, RP.Red.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null,
                                        tint = RP.Red, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text("Mora activa",
                                            fontSize = 11.sp, color = RP.Red,
                                            fontWeight = FontWeight.SemiBold)
                                        Text("Se cobra primero",
                                            fontSize = 10.sp, color = RP.Red.copy(alpha = 0.7f))
                                    }
                                }
                                Text("L. ${dec.format(moraActiva)}",
                                    fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                                    color = RP.Red)
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Divider(color = RP.Border, thickness = 0.5.dp)
                        Spacer(Modifier.height(12.dp))

                        // ── SALDO TOTAL ───────────────────────────────────
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Saldo pendiente",
                                    fontSize = 12.sp, color = RP.TextMuted,
                                    fontWeight = FontWeight.Medium)
                                Text("L. ${dec.format(saldoActualizado)}",
                                    fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                                    color = RP.TextPri)
                                if (moraActiva > 0.0) {
                                    Text(
                                        "↳ Incluye mora: L. ${dec.format(moraActiva)}",
                                        fontSize = 11.sp, color = RP.Red,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Próxima cuota",
                                    fontSize = 11.sp, color = RP.TextMuted)
                                Text("#$proximaCuotaPendiente de $cuotasTotales",
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = RP.Blue)
                                Text(fechaProximoPago,
                                    fontSize = 12.sp, color = RP.TextSec)
                            }
                        }

                        // ── Info cobrador ─────────────────────────────────
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null,
                                tint = RP.TextMuted, modifier = Modifier.size(12.dp))
                            Text("Cobrador: $nombreCobrador",
                                fontSize = 11.sp, color = RP.TextMuted)
                        }
                    }
                }
            }

            // ── 2. CAMPO MONTO ────────────────────────────────────────────
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = RP.Card),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Monto a registrar",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RP.TextSec)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value         = montoAbono,
                        onValueChange = { montoAbono = it },
                        placeholder   = { Text("0.00", color = RP.TextMuted) },
                        leadingIcon   = {
                            Text("L.", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                color = RP.Blue, modifier = Modifier.padding(start = 12.dp))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine      = true,
                        modifier        = Modifier.fillMaxWidth(),
                        shape           = RoundedCornerShape(14.dp),
                        colors          = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = RP.Blue,
                            unfocusedBorderColor = RP.Border,
                            focusedLabelColor    = RP.Blue
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = RP.TextPri
                        )
                    )

                    // Acceso rápido: cuota exacta o saldo total
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (cuotaEstimada > 0.0) {
                            OutlinedButton(
                                onClick = { montoAbono = "%.2f".format(cuotaEstimada) },
                                shape   = RoundedCornerShape(10.dp),
                                border  = androidx.compose.foundation.BorderStroke(1.dp, RP.Blue.copy(alpha = 0.4f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Una cuota\nL. ${dec.format(cuotaEstimada)}",
                                    fontSize = 11.sp, color = RP.Blue,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center)
                            }
                        }
                        if (saldoActualizado > 0.0) {
                            OutlinedButton(
                                onClick = { montoAbono = "%.2f".format(saldoActualizado) },
                                shape   = RoundedCornerShape(10.dp),
                                border  = androidx.compose.foundation.BorderStroke(1.dp, RP.Green.copy(alpha = 0.4f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Saldar todo\nL. ${dec.format(saldoActualizado)}",
                                    fontSize = 11.sp, color = RP.Green,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }

            // ── 3. VISTA PREVIA DISTRIBUCIÓN ─────────────────────────────
            vistaPrevia?.let { preview ->
                val abono = montoAbono.toDoubleOrNull() ?: 0.0
                val saldoDespues = (saldoActualizado - abono).coerceAtLeast(0.0)
                val estaSaldando = saldoDespues <= 0.01

                Card(
                    modifier  = Modifier.fillMaxWidth(),
                    shape     = RoundedCornerShape(20.dp),
                    colors    = CardDefaults.cardColors(
                        containerColor = if (estaSaldando) RP.GreenSoft else RP.Card
                    ),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                                .background(if (estaSaldando) RP.Green else RP.Amber)
                        )
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    if (estaSaldando) Icons.Default.CheckCircle
                                    else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = if (estaSaldando) RP.Green else RP.Amber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    if (estaSaldando) "Préstamo saldado" else "Vista previa",
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    color = if (estaSaldando) RP.Green else RP.TextPri
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            preview.cuotasCubiertas.forEach { cuota ->
                                val esMora     = cuota.numeroCuota == 0
                                val etiqueta   = if (esMora) "Mora" else "Cuota #${cuota.numeroCuota}"
                                val colorFondo = when {
                                    esMora && cuota.completada  -> RP.GreenSoft
                                    esMora                      -> RP.RedSoft
                                    cuota.completada            -> RP.GreenSoft
                                    else                        -> RP.AmberSoft
                                }
                                val colorTexto = when {
                                    esMora && cuota.completada  -> RP.Green
                                    esMora                      -> RP.Red
                                    cuota.completada            -> RP.Green
                                    else                        -> RP.Amber
                                }
                                val icono: ImageVector = when {
                                    esMora      -> Icons.Default.Warning
                                    cuota.completada -> Icons.Default.CheckCircle
                                    else        -> Icons.Default.RadioButtonUnchecked
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colorFondo)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment     = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(icono, contentDescription = null,
                                            tint = colorTexto, modifier = Modifier.size(14.dp))
                                        Text(etiqueta, fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold, color = colorTexto)
                                        if (!cuota.completada) {
                                            Text("parcial", fontSize = 11.sp,
                                                color = colorTexto.copy(alpha = 0.7f))
                                        }
                                    }
                                    Text("L. ${dec.format(cuota.montoAplicado)}",
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        color = colorTexto)
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            Divider(color = RP.Border, thickness = 0.5.dp)
                            Spacer(Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Saldo después del pago",
                                    fontSize = 13.sp, color = RP.TextSec)
                                Text(
                                    if (estaSaldando) "L. 0.00 ✓"
                                    else "L. ${dec.format(saldoDespues)}",
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    color = if (estaSaldando) RP.Green else RP.TextPri
                                )
                            }
                            if (!estaSaldando) {
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Próxima cuota",
                                        fontSize = 12.sp, color = RP.TextMuted)
                                    Text("#${preview.proximaCuotaPendiente} — ${preview.fechaProximoPago}",
                                        fontSize = 12.sp, color = RP.Blue,
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            // ── 4. DETALLES PAGO ──────────────────────────────────────────
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = RP.Card),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    Text("Detalles del recibo",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RP.TextSec)

                    // Lugar
                    OutlinedTextField(
                        value = lugar, onValueChange = { lugar = it },
                        label = { Text("Lugar") },
                        leadingIcon = {
                            Icon(Icons.Default.LocationOn, contentDescription = null,
                                tint = RP.TextMuted, modifier = Modifier.size(18.dp))
                        },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RP.Blue, unfocusedBorderColor = RP.Border
                        )
                    )

                    // Firma
                    OutlinedTextField(
                        value = firmaPrestamista, onValueChange = { firmaPrestamista = it },
                        label = { Text("Firma / nombre cobrador") },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null,
                                tint = RP.TextMuted, modifier = Modifier.size(18.dp))
                        },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RP.Blue, unfocusedBorderColor = RP.Border
                        )
                    )

                    // Método de pago
                    ExposedDropdownMenuBox(
                        expanded = expandedMetodoPago,
                        onExpandedChange = { expandedMetodoPago = !expandedMetodoPago }
                    ) {
                        OutlinedTextField(
                            value = metodoPago, onValueChange = {},
                            readOnly = true, label = { Text("Método de pago") },
                            leadingIcon = {
                                Icon(
                                    if (metodoPago == "Efectivo") Icons.Default.AttachMoney
                                    else Icons.Default.SwapHoriz,
                                    contentDescription = null,
                                    tint = RP.TextMuted, modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedMetodoPago)
                            },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RP.Blue, unfocusedBorderColor = RP.Border
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = expandedMetodoPago,
                            onDismissRequest = { expandedMetodoPago = false }
                        ) {
                            opcionesMetodoPago.forEach {
                                DropdownMenuItem(
                                    text = { Text(it) },
                                    onClick = { metodoPago = it; expandedMetodoPago = false }
                                )
                            }
                        }
                    }
                }
            }

            // ── 5. BOTÓN REGISTRAR ────────────────────────────────────────
            val abonoValido = (montoAbono.toDoubleOrNull() ?: 0.0) > 0.0
            Button(
                onClick = {
                    if (!botonHabilitado) return@Button
                    val abono = montoAbono.toDoubleOrNull()
                    if (abono == null || abono <= 0.0) {
                        Toast.makeText(context, "Ingresa un monto válido", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val uidActualSesion = session.getUid()
                    if (uidActualSesion.isNullOrEmpty()) {
                        Toast.makeText(context, "Error: Sesión no válida", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    botonHabilitado = false

                    scope.launch {
                        try {
                            if (!verificarYCrearCliente(db, clienteId, nombreCliente)) {
                                Toast.makeText(context, "Error al verificar cliente", Toast.LENGTH_LONG).show()
                                botonHabilitado = true; return@launch
                            }

                            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
                            val montoBase        = prestamoDoc.getDouble("monto") ?: 0.0
                            val interesTotalBase = prestamoDoc.getDouble("interesTotal")
                                ?: prestamoDoc.getDouble("interes") ?: 0.0
                            val totalPagarDoc    = prestamoDoc.getDouble("totalPagar")
                                ?: (montoBase + interesTotalBase)
                            val moraActualDoc    = prestamoDoc.getDouble("mora") ?: 0.0
                            val numeroPrestamoActual = obtenerNumeroPrestamoSafe(prestamoDoc)

                            val pagosSnapshot = db.collection("pagos")
                                .whereEqualTo("prestamoId", prestamoId).get().await()
                            var totalRealmentePagado = 0.0
                            var totalMoraYaPagada    = 0.0
                            var totalCuotasYaPagadas = 0.0
                            for (pago in pagosSnapshot.documents) {
                                val pm = pago.getDouble("monto") ?: 0.0
                                val mm = pago.getDouble("mora")  ?: 0.0
                                totalRealmentePagado += pm + mm
                                totalMoraYaPagada    += mm
                                totalCuotasYaPagadas += pm
                            }

                            // ✅ FIX: Saldo anterior = base - cuotas pagadas + mora pendiente
                            var moraHistoricaDoc = moraActualDoc
                            if (moraActualDoc > 0 && moraActualDoc < totalMoraYaPagada) {
                                moraHistoricaDoc = totalMoraYaPagada + moraActualDoc
                            }
                            val moraPendienteActual   = (moraHistoricaDoc - totalMoraYaPagada).coerceAtLeast(0.0)
                            val saldoAnteriorCorrecto = (totalPagarDoc - totalCuotasYaPagadas + moraPendienteActual).coerceAtLeast(0.0)
                            val distribucion = distribuirPagoConMoraYCascada(
                                db, prestamoId, abono, cuotaEstimada, cuotasTotales
                            )

                            val fechaActual     = Timestamp.now()
                            val fechaFormateada = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                .format(fechaActual.toDate())

                            val nuevoMontoPagado  = totalRealmentePagado + abono

                            val cuotaMoraCubierta = distribucion.cuotasCubiertas.find { it.numeroCuota == 0 }
                            val montoPagoNormal   = abono - (cuotaMoraCubierta?.montoAplicado ?: 0.0)
                            val montoPagoMora     = cuotaMoraCubierta?.montoAplicado ?: 0.0

                            // ✅ FIX: Saldo correcto separando mora de cuotas.
                            // Fórmula: saldo = base - cuotasPagadas + moraPendiente
                            // Evita que la mora pagada (campo que se borra al pagar)
                            // se reste de una base que ya no la incluye.
                            val moraYaPagadaTotal       = (totalMoraYaPagada + montoPagoMora).coerceAtLeast(0.0)
                            val moraNuevamentePendiente = (moraHistoricaDoc - moraYaPagadaTotal).coerceAtLeast(0.0)
                            val cuotasAcumuladas        = totalCuotasYaPagadas + montoPagoNormal.coerceAtLeast(0.0)
                            val nuevoSaldo              = (totalPagarDoc - cuotasAcumuladas + moraNuevamentePendiente).coerceAtLeast(0.0)

                            val actualizacionMora: Map<String, Any> = when {
                                cuotaMoraCubierta != null && cuotaMoraCubierta.completada ->
                                    mapOf("estado" to if (nuevoSaldo <= 0.01) "saldado" else "activo")
                                cuotaMoraCubierta != null ->
                                    mapOf("estado" to "mora")
                                else ->
                                    mapOf("estado" to if (nuevoSaldo <= 0.01) "saldado" else "activo")
                            }

                            val proximoPagoValidado = when {
                                nuevoSaldo <= 0.01 -> "saldado"
                                distribucion.fechaProximoPago.equals("saldado", ignoreCase = true) && nuevoSaldo > 0.01 -> {
                                    try {
                                        val fechaInicio = prestamoDoc.getTimestamp("fecha")?.toDate() ?: Date()
                                        val plazoActual = prestamoDoc.getString("plazo") ?: "semanal"
                                        calcularFechaCuota(fechaInicio, plazoActual, distribucion.proximaCuotaPendiente)
                                    } catch (_: Exception) { "Pendiente" }
                                }
                                else -> distribucion.fechaProximoPago
                            }

                            val cuotasNormales = distribucion.cuotasCubiertas.filter { it.numeroCuota > 0 }
                            val descripcionDetallada = buildString {
                                if (cuotaMoraCubierta != null) {
                                    append("MORA (L. ${"%.2f".format(cuotaMoraCubierta.montoAplicado)}${if (cuotaMoraCubierta.completada) " ✓" else " parcial"})")
                                    if (cuotasNormales.isNotEmpty()) append(" + ")
                                }
                                when {
                                    cuotasNormales.isEmpty() -> Unit
                                    cuotasNormales.size == 1 -> {
                                        val c = cuotasNormales.first()
                                        append(if (c.completada) "Cuota #${c.numeroCuota}" else "Cuota #${c.numeroCuota} parcial")
                                    }
                                    else -> {
                                        val nums = cuotasNormales.map { it.numeroCuota }
                                        append("Cuotas #${nums.first()} a #${nums.last()}")
                                    }
                                }
                            }

                            val uidCobradorActivo = when {
                                cobrador.isNotBlank() &&
                                        !cobrador.equals("COBRADOR", true) &&
                                        !cobrador.equals("Sin asignar", true) -> cobrador.trim()
                                else -> uidActualSesion
                            }
                            val nombreCobradorActivo = resolverNombreCobrador(context, db, uidCobradorActivo)

                            val prestamoIdParaPDF = if (numeroPrestamoActual.isNotEmpty() && numeroPrestamoActual != "0")
                                "Préstamo N° $numeroPrestamoActual" else "Préstamo"

                            val pdfFile = ReciboHelper.generarReciboPDF(
                                context        = context,
                                cliente        = nombreCliente,
                                prestamoId     = prestamoIdParaPDF,
                                fecha          = fechaFormateada,
                                montoPagado    = abono.toString(),
                                saldoAnterior  = saldoAnteriorCorrecto,
                                proximoPago    = proximoPagoValidado,
                                cuota          = descripcionDetallada,
                                cobrador       = nombreCobradorActivo,
                                lugar          = lugar,
                                firma          = firmaPrestamista,
                                tipoPago       = metodoPago,
                                mora           = montoPagoMora,
                                saldoNuevoFijo = nuevoSaldo
                            )

                            val pdfGenerado = pdfFile != null && pdfFile.exists()
                            if (pdfGenerado) {
                                archivoPDF = pdfFile
                                try { ReciboHelper.imprimirPDF(context, pdfFile!!) } catch (_: Exception) {}
                                ReciboHelper.compartirReciboPDF(context, pdfFile!!)
                            }

                            val abonoData = mapOf(
                                "clienteId"              to clienteId,
                                "clienteNombre"          to nombreCliente,
                                "prestamoId"             to prestamoId,
                                "numeroPrestamo"         to numeroPrestamoActual,
                                "monto"                  to montoPagoNormal.coerceAtLeast(0.0),
                                "mora"                   to montoPagoMora,
                                "fechaPago"              to fechaActual,
                                "registradoPor"          to uidCobradorActivo,
                                "nombreCobrador"         to nombreCobradorActivo,
                                "saldoRestante"          to nuevoSaldo,
                                "lugar"                  to lugar,
                                "firma"                  to firmaPrestamista,
                                "metodoPago"             to metodoPago,
                                "plazo"                  to plazo,
                                "pdfGenerado"            to pdfGenerado,
                                "proximaFechaProgramada" to proximoPagoValidado,
                                "totalCuotasCompletas"   to distribucion.totalCuotasCompletas,
                                "cuotasCubiertas"        to distribucion.cuotasCubiertas
                                    .filter { it.numeroCuota > 0 }
                                    .map { mapOf("numeroCuota" to it.numeroCuota, "montoAplicado" to it.montoAplicado, "completada" to it.completada) },
                                "descripcionCuotas"      to descripcionDetallada,
                                "sistemaPagoEnCascada"   to true
                            )

                            if (isInternetAvailable(context)) {
                                db.collection("pagos").add(abonoData).await()

                                val camposBase = mutableMapOf<String, Any>(
                                    "saldo"                    to nuevoSaldo,
                                    "montoPagado"              to nuevoMontoPagado,
                                    "proximoPago"              to proximoPagoValidado,
                                    "fechaUltimaActualizacion" to fechaActual,
                                    "ultimoPago"               to fechaFormateada
                                )
                                camposBase.putAll(actualizacionMora)
                                if (nuevoSaldo <= 0.01) {
                                    camposBase["fechaSaldado"]     = fechaActual
                                    camposBase["fechaCancelacion"] = fechaActual
                                    camposBase["mora"]             = 0.0
                                }
                                db.collection("prestamos").document(prestamoId).update(camposBase).await()

                                runCatching {
                                    db.collection("clientes").document(clienteId).update(
                                        mapOf("ultimaActividad" to fechaActual, "fechaUltimaActualizacion" to fechaActual)
                                    ).await()
                                }

                                val msg = if (nuevoSaldo <= 0.01)
                                    "¡PRÉSTAMO N° $numeroPrestamoActual SALDADO! ✅"
                                else "Pago registrado. Saldo: L. ${"%.2f".format(nuevoSaldo)}"
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

                            } else {
                                guardarAbonoPendiente(context, abonoData)
                                Toast.makeText(context, "Pago guardado offline", Toast.LENGTH_LONG).show()
                            }

                            montoPagadoActual     = nuevoMontoPagado
                            saldoActualizado      = nuevoSaldo
                            moraActiva            = if (cuotaMoraCubierta?.completada == true) 0.0 else moraActiva
                            proximaCuotaPendiente = distribucion.proximaCuotaPendiente
                            fechaProximoPago      = proximoPagoValidado
                            montoAbono            = ""

                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            Log.e("RegistrarPago", "Error general: ", e)
                        } finally {
                            botonHabilitado = true
                        }
                    }
                },
                enabled   = botonHabilitado && abonoValido,
                modifier  = Modifier.fillMaxWidth().height(52.dp),
                shape     = RoundedCornerShape(14.dp),
                colors    = ButtonDefaults.buttonColors(
                    containerColor         = RP.Blue,
                    disabledContainerColor = RP.Blue.copy(alpha = 0.4f)
                )
            ) {
                if (!botonHabilitado) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White, strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Icon(Icons.Default.Payment, contentDescription = null,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Registrar pago",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            // ── 6. REIMPRIMIR PDF ─────────────────────────────────────────
            if (archivoPDF != null) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            archivoPDF?.let {
                                try { ReciboHelper.imprimirPDF(context, it) }
                                catch (_: Exception) {
                                    Toast.makeText(context, "Error al reimprimir", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        shape  = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RP.Border),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null,
                            modifier = Modifier.size(16.dp), tint = RP.TextSec)
                        Spacer(Modifier.width(6.dp))
                        Text("Reimprimir", color = RP.TextSec, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { archivoPDF?.let { ReciboHelper.compartirReciboPDF(context, it) } },
                        shape  = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RP.Border),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null,
                            modifier = Modifier.size(16.dp), tint = RP.TextSec)
                        Spacer(Modifier.width(6.dp))
                        Text("Compartir", color = RP.TextSec, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─────────────────────────────────────────────
//  COMPONENTE MÉTRICA
// ─────────────────────────────────────────────
@Composable
private fun MetricaChip(
    label: String,
    valor: String,
    color: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 11.sp, color = color.copy(alpha = 0.75f),
            fontWeight = FontWeight.Medium)
        Text(valor, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}