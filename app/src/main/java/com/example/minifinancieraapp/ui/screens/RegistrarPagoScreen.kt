package com.example.minifinancieraapp.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.capitalexpressapp.theme.PrimaryButton
import com.example.capitalexpressapp.ui.components.PrimaryTextField
import com.example.capitalexpressapp.util.NetworkUtils.isInternetAvailable
import com.example.capitalexpressapp.util.NetworkUtils.guardarAbonoPendiente
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.minifinancieraapp.util.SessionManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

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

// ✅✅✅ FUNCIÓN HELPER PARA OBTENER numeroPrestamo DE FORMA SEGURA ✅✅✅
fun obtenerNumeroPrestamoSafe(doc: com.google.firebase.firestore.DocumentSnapshot): String {
    return try {
        when (val campo = doc.get("numeroPrestamo")) {
            is String -> campo.takeIf { it.isNotBlank() } ?: "0"
            is Long -> campo.toString()
            is Int -> campo.toString()
            is Number -> campo.toLong().toString()
            null -> "0"
            else -> {
                Log.w("NumeroPrestamo", "Tipo inesperado: ${campo::class.java.simpleName}")
                "0"
            }
        }
    } catch (e: Exception) {
        Log.e("NumeroPrestamo", "Error al obtener numeroPrestamo: ${e.message}", e)
        "0"
    }
}

private suspend fun resolverNombreCobrador(
    context: Context,
    db: FirebaseFirestore,
    uidParam: String
): String {
    fun getCachedName(ctx: Context): String? {
        val sp = ctx.getSharedPreferences("recibo_cache", Context.MODE_PRIVATE)
        return sp.getString("nombreCobrador", null)
    }
    fun setCachedName(ctx: Context, value: String) {
        val sp = ctx.getSharedPreferences("recibo_cache", Context.MODE_PRIVATE)
        sp.edit().putString("nombreCobrador", value).apply()
    }

    if (uidParam.contains(" ") && uidParam.length < 40 &&
        !uidParam.equals("COBRADOR", ignoreCase = true) &&
        !uidParam.equals("Sin asignar", ignoreCase = true)
    ) {
        val nice = uidParam.trim()
        setCachedName(context, nice)
        Log.d("NombreCobrador", "Usando nombre directo recibido: '$nice'")
        return nice
    }

    val cached = getCachedName(context)
    if (!cached.isNullOrBlank()) {
        return cached.trim()
    }

    try {
        val snap = db.collection("usuarios").document(uidParam).get().await()
        if (snap.exists()) {
            val nombre = snap.getString("nombre")?.trim()
            if (!nombre.isNullOrBlank()) {
                setCachedName(context, nombre)
                Log.d("NombreCobrador", "Resuelto por documentId: '$nombre'")
                return nombre
            }
        } else {
            Log.w("NombreCobrador", "usuarios/$uidParam no existe como documentId")
        }
    } catch (e: Exception) {
        Log.w("NombreCobrador", "Error leyendo usuarios/$uidParam: ${e.message}")
    }

    val campos = listOf("codigo", "identidad", "telefono", "nombre")
    for (campo in campos) {
        try {
            val q = db.collection("usuarios")
                .whereEqualTo(campo, uidParam)
                .limit(1)
                .get()
                .await()

            if (!q.isEmpty) {
                val doc = q.documents.first()
                val nombre = doc.getString("nombre")?.trim()
                if (!nombre.isNullOrBlank()) {
                    setCachedName(context, nombre)
                    Log.d("NombreCobrador", "Resuelto por $campo='$uidParam': '$nombre'")
                    return nombre
                }
            }
        } catch (e: Exception) {
            Log.w("NombreCobrador", "Error buscando por $campo='$uidParam': ${e.message}")
        }
    }

    try {
        val authName = com.google.firebase.auth.FirebaseAuth
            .getInstance()
            .currentUser
            ?.displayName
            ?.trim()
        if (!authName.isNullOrBlank()) {
            setCachedName(context, authName)
            Log.d("NombreCobrador", "Resuelto por FirebaseAuth: '$authName'")
            return authName
        }
    } catch (_: Exception) { }

    val tail = if (uidParam.length >= 6) uidParam.takeLast(6) else uidParam
    val fallback = "Cobrador-$tail"
    Log.w("NombreCobrador", "Usando fallback: '$fallback'")
    return fallback
}

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

private suspend fun obtenerEstadoCuotasCompleto(
    db: FirebaseFirestore,
    prestamoId: String
): Map<Int, Double> {
    try {
        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId)
            .get().await()

        if (!pagosSnapshot.isEmpty) {
            val pagosPorCuota = mutableMapOf<Int, Double>()

            for (pago in pagosSnapshot.documents) {
                val cuotasCubiertas = pago.get("cuotasCubiertas") as? List<*>
                if (!cuotasCubiertas.isNullOrEmpty()) {
                    cuotasCubiertas.forEach { cuotaData ->
                        if (cuotaData is Map<*, *>) {
                            val numeroCuota =
                                (cuotaData["numeroCuota"] as? Number)?.toInt() ?: return@forEach
                            val montoAplicado =
                                (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0
                            if (numeroCuota > 0 && montoAplicado > 0) {
                                pagosPorCuota[numeroCuota] =
                                    (pagosPorCuota[numeroCuota] ?: 0.0) + montoAplicado
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
                    if (montoPago > 0) {
                        pagosPorCuota[numeroCuota] =
                            (pagosPorCuota[numeroCuota] ?: 0.0) + montoPago
                    }
                }
            }

            if (pagosPorCuota.isNotEmpty()) return pagosPorCuota
        }
    } catch (e: Exception) {
        Log.w("EstadoCuotas", "Lectura de pagos no disponible: ${e.message}")
    }

    return try {
        val p = db.collection("prestamos").document(prestamoId).get().await()
        val cuota = p.getDouble("cuota") ?: 0.0
        val cuotasTotales = p.getLong("cuotas")?.toInt() ?: 1

        val montoPagado = p.getDouble("montoPagado")
            ?: run {
                val monto = p.getDouble("monto") ?: 0.0
                val interesTotal = p.getDouble("interesTotal") ?: p.getDouble("interes") ?: 0.0
                val totalPagar = p.getDouble("totalPagar") ?: (monto + interesTotal)
                val saldo = p.getDouble("saldo") ?: (totalPagar)
                (totalPagar - saldo).coerceAtLeast(0.0)
            }

        if (cuota <= 0.0 || montoPagado <= 0.0) return emptyMap()

        val completas = kotlin.math.floor(montoPagado / cuota).toInt()
            .coerceAtMost(cuotasTotales)
        val parcial =
            (montoPagado - (completas * cuota)).coerceAtLeast(0.0)

        val mapa = mutableMapOf<Int, Double>()
        for (i in 1..completas) mapa[i] = cuota
        if (parcial > 0.01 && completas + 1 <= cuotasTotales) mapa[completas + 1] = parcial

        Log.d("EstadoCuotas", "Fallback por préstamo: completas=$completas, parcial=$parcial")
        mapa
    } catch (e: Exception) {
        Log.e("EstadoCuotas", "Fallback falló: ${e.message}")
        emptyMap()
    }
}

private suspend fun distribuirPagoEnCascada(
    db: FirebaseFirestore,
    prestamoId: String,
    montoPagado: Double,
    cuotaEstimada: Double,
    cuotasTotales: Int
): ResultadoDistribucion {
    return try {
        val estadoCuotas = obtenerEstadoCuotasCompleto(db, prestamoId)

        var montoRestante = montoPagado
        val cuotasCubiertas = mutableListOf<CuotaCubierta>()
        var totalCuotasCompletas = 0

        for (i in 1..cuotasTotales) {
            if (montoRestante <= 0.01) break

            val montoPagadoEnCuota = estadoCuotas[i] ?: 0.0
            val montoRestanteCuota =
                (cuotaEstimada - montoPagadoEnCuota).coerceAtLeast(0.0)

            if (montoRestanteCuota > 0.01) {
                val montoAAplicar = minOf(montoRestante, montoRestanteCuota)
                val cuotaCompleta =
                    montoAAplicar >= montoRestanteCuota - 0.01

                cuotasCubiertas.add(
                    CuotaCubierta(
                        numeroCuota = i,
                        montoAplicado = montoAAplicar,
                        completada = cuotaCompleta
                    )
                )

                if (cuotaCompleta) totalCuotasCompletas++
                montoRestante -= montoAAplicar
            }
        }

        val estadoActualizado = estadoCuotas.toMutableMap()
        cuotasCubiertas.forEach { cuota ->
            estadoActualizado[cuota.numeroCuota] =
                (estadoActualizado[cuota.numeroCuota] ?: 0.0) + cuota.montoAplicado
        }

        var proximaCuotaPendiente = cuotasTotales + 1
        for (i in 1..cuotasTotales) {
            val montoPagadoTotal = estadoActualizado[i] ?: 0.0
            if (montoPagadoTotal < cuotaEstimada - 0.01) {
                proximaCuotaPendiente = i
                break
            }
        }

        val fechaProximoPago = if (proximaCuotaPendiente <= cuotasTotales) {
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
            val fechaInicio = prestamoDoc.getTimestamp("fecha")?.toDate() ?: Date()
            val plazo = prestamoDoc.getString("plazo") ?: "semanal"
            calcularFechaCuota(fechaInicio, plazo, proximaCuotaPendiente)
        } else {
            "saldado"
        }

        ResultadoDistribucion(
            cuotasCubiertas = cuotasCubiertas,
            proximaCuotaPendiente = proximaCuotaPendiente,
            fechaProximoPago = fechaProximoPago,
            totalCuotasCompletas = totalCuotasCompletas
        )

    } catch (e: Exception) {
        Log.e("DistribucionCascada", "Error: ${e.message}", e)
        ResultadoDistribucion(
            cuotasCubiertas = listOf(CuotaCubierta(1, montoPagado, false)),
            proximaCuotaPendiente = 1,
            fechaProximoPago = "pendiente",
            totalCuotasCompletas = 0
        )
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
                    "nombre" to nombreCliente,
                    "fechaCreacion" to Timestamp.now(),
                    "ultimaActividad" to Timestamp.now(),
                    "estado" to "activo"
                )
            ).await()
        }
        true
    } catch (e: Exception) {
        Log.e("CrearCliente", "Error: ${e.message}")
        false
    }
}

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
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    val session = remember { SessionManager(context) }

    var nombreCliente by remember { mutableStateOf("") }
    var montoAbono by remember { mutableStateOf("") }
    var lugar by remember { mutableStateOf("El Paraíso, Danlí") }
    var firmaPrestamista by remember { mutableStateOf("") }
    var archivoPDF by remember { mutableStateOf<File?>(null) }
    var metodoPago by remember { mutableStateOf("Efectivo") }
    val opcionesMetodoPago = listOf("Efectivo", "Transferencia")
    var expandedMetodoPago by remember { mutableStateOf(false) }

    var nombreCobrador by remember { mutableStateOf(cobrador) }

    var botonHabilitado by remember { mutableStateOf(true) }

    var montoPrestamo by remember { mutableStateOf(0.0) }
    var interesTotal by remember { mutableStateOf(0.0) }
    var totalAPagar by remember { mutableStateOf(0.0) }
    var cuotaEstimada by remember { mutableStateOf(0.0) }
    var cuotasTotales by remember { mutableStateOf(1) }
    var plazo by remember { mutableStateOf("Semanal") }
    var numeroPrestamo by remember { mutableStateOf("") }
    var montoPagadoActual by remember { mutableStateOf(0.0) }
    var saldoActualizado by remember { mutableStateOf(saldoActual) }
    var proximaCuotaPendiente by remember { mutableStateOf(1) }
    var fechaProximoPago by remember { mutableStateOf("") }

    var vistaPrevia by remember { mutableStateOf<ResultadoDistribucion?>(null) }

    LaunchedEffect(Unit) {
        try {
            val uidActualSesion = session.getUid()
            if (uidActualSesion.isNullOrEmpty()) {
                Toast.makeText(context, "Error: Sesión no válida", Toast.LENGTH_LONG).show()
                navController.popBackStack()
                return@LaunchedEffect
            }

            val uidParaRecibo = when {
                cobrador.isNotBlank() &&
                        !cobrador.equals("COBRADOR", true) &&
                        !cobrador.equals("Sin asignar", true) ->
                    cobrador.trim()
                else -> uidActualSesion
            }

            val nombreCobradorLimpio = resolverNombreCobrador(context, db, uidParaRecibo)
            nombreCobrador = nombreCobradorLimpio
            firmaPrestamista = nombreCobradorLimpio

            val clienteDoc = db.collection("clientes").document(clienteId).get().await()
            nombreCliente = if (clienteDoc.exists()) {
                clienteDoc.getString("nombre") ?: "Cliente"
            } else {
                "Cliente $clienteId"
            }

            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
            if (!prestamoDoc.exists()) {
                Toast.makeText(context, "Error: El préstamo no existe", Toast.LENGTH_LONG).show()
                navController.popBackStack()
                return@LaunchedEffect
            }

            montoPrestamo = prestamoDoc.getDouble("monto") ?: 0.0
            interesTotal = prestamoDoc.getDouble("interesTotal")
                ?: prestamoDoc.getDouble("interes") ?: 0.0
            totalAPagar = prestamoDoc.getDouble("totalPagar")
                ?: (montoPrestamo + interesTotal)
            cuotaEstimada = prestamoDoc.getDouble("cuota") ?: 0.0
            cuotasTotales = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
            plazo = prestamoDoc.getString("plazo") ?: "Semanal"

            // ✅ LEER NÚMERO DE PRÉSTAMO DE FORMA SEGURA
            numeroPrestamo = obtenerNumeroPrestamoSafe(prestamoDoc)

            val pagosSnapshot = db.collection("pagos")
                .whereEqualTo("prestamoId", prestamoId)
                .get().await()

            var totalRealmentePagado = 0.0
            for (pago in pagosSnapshot.documents) {
                val montoPago = pago.getDouble("monto") ?: 0.0
                val moraPago = pago.getDouble("mora") ?: 0.0
                totalRealmentePagado += montoPago + moraPago
            }

            val moraActual = prestamoDoc.getDouble("mora") ?: 0.0
            val totalConMora = totalAPagar + moraActual

            montoPagadoActual = totalRealmentePagado
            saldoActualizado = (totalConMora - totalRealmentePagado).coerceAtLeast(0.0)

            Log.d("RegistrarPagoScreen", """
                📊 CARGA INICIAL CORREGIDA:
                - Número de préstamo: $numeroPrestamo
                - Total a pagar: L. $totalAPagar
                - Mora: L. $moraActual
                - Total con mora: L. $totalConMora
                - Total REAL pagado: L. $totalRealmentePagado
                - Saldo REAL: L. $saldoActualizado
            """.trimIndent())

            val resultado = distribuirPagoEnCascada(
                db,
                prestamoId,
                0.0,
                cuotaEstimada,
                cuotasTotales
            )
            proximaCuotaPendiente = resultado.proximaCuotaPendiente
            fechaProximoPago = resultado.fechaProximoPago

        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("RegistrarPagoScreen", "Error init: ", e)
        }
    }

    LaunchedEffect(montoAbono) {
        val abono = montoAbono.toDoubleOrNull() ?: 0.0
        vistaPrevia = if (abono > 0.0) {
            distribuirPagoEnCascada(db, prestamoId, abono, cuotaEstimada, cuotasTotales)
        } else {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registrar Pago", color = MaterialTheme.colorScheme.primary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Información del Préstamo", fontWeight = FontWeight.Bold)
                    // ⭐ MOSTRAR NÚMERO DE PRÉSTAMO
                    if (numeroPrestamo.isNotEmpty() && numeroPrestamo != "0") {
                        Text("Préstamo N° $numeroPrestamo", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    }
                    Text("Cliente: $nombreCliente", fontWeight = FontWeight.Bold)
                    Text("Capital: L. ${"%.2f".format(montoPrestamo)}")
                    Text("Interés: L. ${"%.2f".format(interesTotal)}")
                    Text("Total: L. ${"%.2f".format(totalAPagar)}", fontWeight = FontWeight.Bold)
                    Text("Pagado: L. ${"%.2f".format(montoPagadoActual)}", color = Color(0xFF4CAF50))
                    Text(
                        "Saldo: L. ${"%.2f".format(saldoActualizado)}",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5722)
                    )
                    Text("Cuota: L. ${"%.2f".format(cuotaEstimada)}")
                    Text("Próxima: #$proximaCuotaPendiente de $cuotasTotales")
                    Text("Fecha: $fechaProximoPago", fontWeight = FontWeight.Bold)
                    Text("Cobrador actual: $nombreCobrador", color = Color(0xFF1565C0))
                }
            }

            PrimaryTextField(
                value = montoAbono,
                onValueChange = { montoAbono = it },
                label = "Monto recibido",
                keyboardType = KeyboardType.Number
            )

            vistaPrevia?.let { preview ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Vista Previa", fontWeight = FontWeight.Bold)
                        preview.cuotasCubiertas.forEach { cuota ->
                            Text(
                                "• Cuota ${cuota.numeroCuota}: L. ${"%.2f".format(cuota.montoAplicado)} ${if (cuota.completada) "✓" else "parcial"}",
                                color = if (cuota.completada) Color(0xFF4CAF50) else Color(0xFFFF9800)
                            )
                        }
                        Text("Completas: ${preview.totalCuotasCompletas}", fontWeight = FontWeight.Bold)

                        if ((saldoActualizado - (montoAbono.toDoubleOrNull() ?: 0.0)) > 0.01) {
                            Text("Próxima: #${preview.proximaCuotaPendiente} - ${preview.fechaProximoPago}")
                        } else {
                            Text("¡SALDADO!", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            PrimaryTextField(value = lugar, onValueChange = { lugar = it }, label = "Lugar")
            PrimaryTextField(value = firmaPrestamista, onValueChange = { firmaPrestamista = it }, label = "Firma")

            ExposedDropdownMenuBox(
                expanded = expandedMetodoPago,
                onExpandedChange = { expandedMetodoPago = !expandedMetodoPago }
            ) {
                OutlinedTextField(
                    value = metodoPago,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Método de pago") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedMetodoPago) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedMetodoPago,
                    onDismissRequest = { expandedMetodoPago = false }
                ) {
                    opcionesMetodoPago.forEach {
                        DropdownMenuItem(
                            text = { Text(it) },
                            onClick = {
                                metodoPago = it
                                expandedMetodoPago = false
                            }
                        )
                    }
                }
            }

            PrimaryButton(
                text = "Registrar Pago",
                onClick = {
                    if (!botonHabilitado) return@PrimaryButton

                    val abono = montoAbono.toDoubleOrNull()
                    if (abono == null || abono <= 0.0) {
                        Toast.makeText(context, "Ingresa un monto válido", Toast.LENGTH_SHORT).show()
                        return@PrimaryButton
                    }

                    val uidActualSesion = session.getUid()
                    if (uidActualSesion.isNullOrEmpty()) {
                        Toast.makeText(context, "Error: Sesión no válida", Toast.LENGTH_LONG).show()
                        return@PrimaryButton
                    }

                    botonHabilitado = false

                    scope.launch {
                        try {
                            if (!verificarYCrearCliente(db, clienteId, nombreCliente)) {
                                Toast.makeText(context, "Error al verificar cliente", Toast.LENGTH_LONG).show()
                                botonHabilitado = true
                                return@launch
                            }

                            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()

                            val montoBase = prestamoDoc.getDouble("monto") ?: 0.0
                            val interesTotalBase = prestamoDoc.getDouble("interesTotal")
                                ?: prestamoDoc.getDouble("interes") ?: 0.0
                            val totalPagarBase = prestamoDoc.getDouble("totalPagar")
                                ?: (montoBase + interesTotalBase)
                            val moraActual = prestamoDoc.getDouble("mora") ?: 0.0

                            // ✅ LEER NÚMERO DE PRÉSTAMO DE FORMA SEGURA
                            val numeroPrestamoActual = obtenerNumeroPrestamoSafe(prestamoDoc)

                            val pagosSnapshot = db.collection("pagos")
                                .whereEqualTo("prestamoId", prestamoId)
                                .get().await()

                            var totalRealmentePagado = 0.0
                            for (pago in pagosSnapshot.documents) {
                                val montoPago = pago.getDouble("monto") ?: 0.0
                                val moraPago = pago.getDouble("mora") ?: 0.0
                                totalRealmentePagado += montoPago + moraPago
                            }

                            Log.d("RegistrarPago", """
                                🔍 RECÁLCULO DESDE FIRESTORE:
                                - Número préstamo: $numeroPrestamoActual
                                - Total a pagar (base): L. $totalPagarBase
                                - Mora actual: L. $moraActual
                                - Total con mora: L. ${totalPagarBase + moraActual}
                                - Total REALMENTE pagado (desde pagos): L. $totalRealmentePagado
                                - Pago actual: L. $abono
                                - Nuevo total pagado: L. ${totalRealmentePagado + abono}
                            """.trimIndent())

                            val distribucion = distribuirPagoEnCascada(
                                db,
                                prestamoId,
                                abono,
                                cuotaEstimada,
                                cuotasTotales
                            )

                            val fechaActual = Timestamp.now()
                            val fechaFormateada = SimpleDateFormat(
                                "dd/MM/yyyy HH:mm",
                                Locale.getDefault()
                            ).format(fechaActual.toDate())

                            val totalConMora = totalPagarBase + moraActual
                            val nuevoMontoPagado = totalRealmentePagado + abono
                            val nuevoSaldo = (totalConMora - nuevoMontoPagado).coerceAtLeast(0.0)

                            Log.d("RegistrarPago", """
                                ✅ SALDOS CORRECTOS:
                                - Saldo ANTERIOR correcto: L. ${totalConMora - totalRealmentePagado}
                                - Pago actual: L. $abono
                                - Saldo NUEVO correcto: L. $nuevoSaldo
                            """.trimIndent())

                            val proximoPagoValidado = when {
                                nuevoSaldo <= 0.01 -> {
                                    Log.d("RegistrarPago", "✅ PRÉSTAMO SALDADO")
                                    "saldado"
                                }
                                distribucion.fechaProximoPago.equals("saldado", ignoreCase = true) && nuevoSaldo > 0.01 -> {
                                    Log.w("RegistrarPago", "⚠️ INCONSISTENCIA DETECTADA - Recalculando fecha")
                                    try {
                                        val fechaInicio = prestamoDoc.getTimestamp("fecha")?.toDate() ?: Date()
                                        val plazoActual = prestamoDoc.getString("plazo") ?: "semanal"
                                        calcularFechaCuota(fechaInicio, plazoActual, distribucion.proximaCuotaPendiente)
                                    } catch (e: Exception) {
                                        "Pendiente"
                                    }
                                }
                                else -> distribucion.fechaProximoPago
                            }

                            val descripcionDetallada = when {
                                distribucion.cuotasCubiertas.isEmpty() -> {
                                    "Abono a cuota #${distribucion.proximaCuotaPendiente}"
                                }
                                distribucion.cuotasCubiertas.size == 1 -> {
                                    val c = distribucion.cuotasCubiertas.first()
                                    if (c.completada) "Cuota #${c.numeroCuota}"
                                    else "Cuota #${c.numeroCuota} parcial (L. ${"%.2f".format(c.montoAplicado)})"
                                }
                                else -> {
                                    val nums = distribucion.cuotasCubiertas.map { it.numeroCuota }
                                    if (nums.size <= 3)
                                        "Cuotas ${nums.joinToString(", ") { "#$it" }}"
                                    else
                                        "Cuotas #${nums.first()} a #${nums.last()} (${nums.size} cuotas)"
                                }
                            }

                            val descripcionCorta = when {
                                distribucion.cuotasCubiertas.isEmpty() -> {
                                    "#${distribucion.proximaCuotaPendiente}*"
                                }
                                distribucion.cuotasCubiertas.size == 1 -> {
                                    val c = distribucion.cuotasCubiertas.first()
                                    if (c.completada) "#${c.numeroCuota}" else "#${c.numeroCuota}*"
                                }
                                else -> {
                                    val nums = distribucion.cuotasCubiertas.map { it.numeroCuota }
                                    if (nums.size <= 3)
                                        nums.joinToString(", ") { "#$it" }
                                    else
                                        "#${nums.first()}-#${nums.last()}"
                                }
                            }

                            val uidCobradorActivo = when {
                                cobrador.isNotBlank() &&
                                        !cobrador.equals("COBRADOR", true) &&
                                        !cobrador.equals("Sin asignar", true) ->
                                    cobrador.trim()
                                else -> uidActualSesion
                            }

                            val nombreCobradorActivo = resolverNombreCobrador(context, db, uidCobradorActivo)

                            val saldoAnteriorCorrecto = (totalConMora - totalRealmentePagado).coerceAtLeast(0.0)

                            Log.d("RegistrarPago", """
                                💰 VERIFICACIÓN FINAL PARA PDF:
                                - Número préstamo: $numeroPrestamoActual
                                - Saldo anterior: L. $saldoAnteriorCorrecto
                                - Pago actual: L. $abono
                                - Saldo nuevo: L. $nuevoSaldo
                                - Próximo pago: $proximoPagoValidado
                            """.trimIndent())

                            // ⭐ PDF CON NÚMERO DE PRÉSTAMO CORRECTO
                            val prestamoIdParaPDF = if (numeroPrestamoActual.isNotEmpty() && numeroPrestamoActual != "0") {
                                "Préstamo N° $numeroPrestamoActual"
                            } else {
                                "Préstamo"
                            }

                            val pdfFile = ReciboHelper.generarReciboPDF(
                                context = context,
                                cliente = nombreCliente,
                                prestamoId = prestamoIdParaPDF,
                                fecha = fechaFormateada,
                                montoPagado = abono.toString(),
                                saldoAnterior = saldoAnteriorCorrecto,
                                proximoPago = proximoPagoValidado,
                                cuota = descripcionDetallada,
                                cobrador = nombreCobradorActivo,
                                lugar = lugar,
                                firma = firmaPrestamista,
                                tipoPago = metodoPago,
                                mora = 0.0,
                                saldoNuevoFijo = nuevoSaldo
                            )

                            val pdfGenerado = pdfFile != null && pdfFile.exists()
                            var pdfImpreso = false

                            if (pdfGenerado) {
                                archivoPDF = pdfFile
                                try {
                                    ReciboHelper.imprimirPDF(context, pdfFile!!)
                                    pdfImpreso = true
                                } catch (e: Exception) {
                                    Log.e("ImprimirPDF", "Error al imprimir: ${e.message}")
                                }
                                ReciboHelper.compartirReciboPDF(context, pdfFile!!)
                            }

                            val abonoData = mapOf(
                                "clienteId" to clienteId,
                                "clienteNombre" to nombreCliente,
                                "prestamoId" to prestamoId,
                                "numeroPrestamo" to numeroPrestamoActual,
                                "monto" to abono,
                                "mora" to 0.0,
                                "fechaPago" to fechaActual,
                                "registradoPor" to uidCobradorActivo,
                                "nombreCobrador" to nombreCobradorActivo,
                                "saldoRestante" to nuevoSaldo,
                                "lugar" to lugar,
                                "firma" to firmaPrestamista,
                                "metodoPago" to metodoPago,
                                "plazo" to plazo,
                                "pdfGenerado" to pdfGenerado,
                                "pdfImpreso" to pdfImpreso,
                                "proximaFechaProgramada" to proximoPagoValidado,
                                "totalCuotasCompletas" to distribucion.totalCuotasCompletas,
                                "cuotasCubiertas" to distribucion.cuotasCubiertas.map {
                                    mapOf(
                                        "numeroCuota" to it.numeroCuota,
                                        "montoAplicado" to it.montoAplicado,
                                        "completada" to it.completada
                                    )
                                },
                                "descripcionCuotas" to descripcionCorta,
                                "sistemaPagoEnCascada" to true
                            )

                            if (isInternetAvailable(context)) {
                                db.collection("pagos").add(abonoData).await()

                                val actualizacionPrestamo =
                                    if (nuevoSaldo <= 0.01) {
                                        mapOf<String, Any>(
                                            "saldo" to 0.0,
                                            "montoPagado" to totalConMora,
                                            "estado" to "saldado",
                                            "proximoPago" to "saldado",
                                            "fechaUltimaActualizacion" to fechaActual,
                                            "ultimoPago" to fechaFormateada,
                                            "fechaSaldado" to fechaActual,
                                            "fechaCancelacion" to fechaActual,
                                            "totalPagar" to totalPagarBase,
                                            "mora" to 0.0
                                        )
                                    } else {
                                        mapOf<String, Any>(
                                            "saldo" to nuevoSaldo,
                                            "montoPagado" to nuevoMontoPagado,
                                            "estado" to "activo",
                                            "proximoPago" to proximoPagoValidado,
                                            "fechaUltimaActualizacion" to fechaActual,
                                            "ultimoPago" to fechaFormateada
                                        )
                                    }

                                db.collection("prestamos").document(prestamoId)
                                    .update(actualizacionPrestamo)
                                    .await()

                                runCatching {
                                    db.collection("clientes").document(clienteId).update(
                                        mapOf(
                                            "ultimaActividad" to fechaActual,
                                            "fechaUltimaActualizacion" to fechaActual
                                        )
                                    ).await()
                                }

                                val msg =
                                    if (nuevoSaldo <= 0.01) "¡PRÉSTAMO N° $numeroPrestamoActual SALDADO! ✅"
                                    else "Pago registrado. Saldo: L. ${"%.2f".format(nuevoSaldo)}"

                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            } else {
                                guardarAbonoPendiente(context, abonoData)
                                Toast.makeText(context, "Pago guardado offline", Toast.LENGTH_LONG).show()
                            }

                            montoPagadoActual = nuevoMontoPagado
                            saldoActualizado = nuevoSaldo
                            proximaCuotaPendiente = distribucion.proximaCuotaPendiente
                            fechaProximoPago = proximoPagoValidado
                            montoAbono = ""

                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            Log.e("RegistrarPago", "Error general: ", e)
                        } finally {
                            botonHabilitado = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = botonHabilitado && (montoAbono.toDoubleOrNull() ?: 0.0) > 0
            )

            if (archivoPDF != null) {
                OutlinedButton(
                    onClick = {
                        archivoPDF?.let {
                            try {
                                ReciboHelper.imprimirPDF(context, it)
                                Toast.makeText(context, "Recibo reenviado", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al reimprimir", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reimprimir Recibo") }

                OutlinedButton(
                    onClick = { archivoPDF?.let { ReciboHelper.compartirReciboPDF(context, it) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Compartir Recibo") }
            }

            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Regresar") }
        }
    }
}