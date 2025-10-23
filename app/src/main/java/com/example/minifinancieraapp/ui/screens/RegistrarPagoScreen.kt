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
    // 1) Intentar con pagos reales (admin u otros roles con permiso)
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
                            val numeroCuota = (cuotaData["numeroCuota"] as? Number)?.toInt() ?: return@forEach
                            val montoAplicado = (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0
                            if (numeroCuota > 0 && montoAplicado > 0) {
                                pagosPorCuota[numeroCuota] = (pagosPorCuota[numeroCuota] ?: 0.0) + montoAplicado
                            }
                        }
                    }
                } else {
                    // Backward-compat: pagos antiguos con un solo campo de cuota
                    val numeroCuota = when {
                        pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                        pago.contains("cuota") -> pago.getLong("cuota")?.toInt() ?: 1
                        else -> 1
                    }
                    val montoPago = pago.getDouble("monto") ?: 0.0
                    if (montoPago > 0) {
                        pagosPorCuota[numeroCuota] = (pagosPorCuota[numeroCuota] ?: 0.0) + montoPago
                    }
                }
            }

            if (pagosPorCuota.isNotEmpty()) return pagosPorCuota
        }
    } catch (e: Exception) {
        // Es normal que falle aquí por reglas si el rol no es admin
        Log.w("EstadoCuotas", "Lectura de pagos no disponible para este rol: ${e.message}")
    }

    // 2) Fallback: calcular avance sólo con el documento del préstamo
    return try {
        val p = db.collection("prestamos").document(prestamoId).get().await()
        val cuota = p.getDouble("cuota") ?: 0.0
        val cuotasTotales = p.getLong("cuotas")?.toInt() ?: 1
        // Preferir "montoPagado"; si no, calcular desde total/ saldo
        val montoPagado = p.getDouble("montoPagado")
            ?: run {
                val monto = p.getDouble("monto") ?: 0.0
                val interesTotal = p.getDouble("interesTotal") ?: p.getDouble("interes") ?: 0.0
                val totalPagar = p.getDouble("totalPagar") ?: (monto + interesTotal)
                val saldo = p.getDouble("saldo") ?: (totalPagar)
                (totalPagar - saldo).coerceAtLeast(0.0)
            }

        if (cuota <= 0.0 || montoPagado <= 0.0) return emptyMap()

        val completas = kotlin.math.floor(montoPagado / cuota).toInt().coerceAtMost(cuotasTotales)
        val parcial = (montoPagado - (completas * cuota)).coerceAtLeast(0.0)

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
            val montoRestanteCuota = (cuotaEstimada - montoPagadoEnCuota).coerceAtLeast(0.0)

            if (montoRestanteCuota > 0.01) {
                val montoAAplicar = minOf(montoRestante, montoRestanteCuota)
                val cuotaCompleta = montoAAplicar >= montoRestanteCuota - 0.01

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
            estadoActualizado[cuota.numeroCuota] = (estadoActualizado[cuota.numeroCuota] ?: 0.0) + cuota.montoAplicado
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

suspend fun verificarYCrearCliente(db: FirebaseFirestore, clienteId: String, nombreCliente: String): Boolean {
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
    var numeroPrestamo by remember { mutableStateOf(0) }
    var montoPagadoActual by remember { mutableStateOf(0.0) }
    var saldoActualizado by remember { mutableStateOf(saldoActual) }
    var proximaCuotaPendiente by remember { mutableStateOf(1) }
    var fechaProximoPago by remember { mutableStateOf("") }

    var vistaPrevia by remember { mutableStateOf<ResultadoDistribucion?>(null) }

    LaunchedEffect(Unit) {
        if (cobrador.isEmpty()) {
            Toast.makeText(context, "Error: UID del cobrador no válido", Toast.LENGTH_LONG).show()
            navController.popBackStack()
            return@LaunchedEffect
        }

        try {
            val clienteDoc = db.collection("clientes").document(clienteId).get().await()
            nombreCliente = if (clienteDoc.exists()) {
                clienteDoc.getString("nombre") ?: "Cliente"
            } else {
                "Cliente $clienteId"
            }

            val usuarioDoc = db.collection("usuarios").document(cobrador).get().await()
            nombreCobrador = usuarioDoc.getString("nombre") ?: "Cobrador Desconocido"
            firmaPrestamista = nombreCobrador

            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
            if (!prestamoDoc.exists()) {
                Toast.makeText(context, "Error: El préstamo no existe", Toast.LENGTH_LONG).show()
                navController.popBackStack()
                return@LaunchedEffect
            }

            montoPrestamo = prestamoDoc.getDouble("monto") ?: 0.0
            interesTotal = prestamoDoc.getDouble("interesTotal") ?: prestamoDoc.getDouble("interes") ?: 0.0
            totalAPagar = prestamoDoc.getDouble("totalPagar") ?: (montoPrestamo + interesTotal)
            cuotaEstimada = prestamoDoc.getDouble("cuota") ?: 0.0
            cuotasTotales = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
            plazo = prestamoDoc.getString("plazo") ?: "Semanal"
            numeroPrestamo = prestamoDoc.getLong("numeroPrestamo")?.toInt() ?: 0
            montoPagadoActual = prestamoDoc.getDouble("montoPagado") ?: 0.0
            saldoActualizado = (totalAPagar - montoPagadoActual).coerceAtLeast(0.0)

            val resultado = distribuirPagoEnCascada(db, prestamoId, 0.0, cuotaEstimada, cuotasTotales)
            proximaCuotaPendiente = resultado.proximaCuotaPendiente
            fechaProximoPago = resultado.fechaProximoPago

        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("RegistrarPagoScreen", "Error: ", e)
        }
    }

    LaunchedEffect(montoAbono) {
        val abono = montoAbono.toDoubleOrNull() ?: 0.0
        if (abono > 0.0) {
            vistaPrevia = distribuirPagoEnCascada(db, prestamoId, abono, cuotaEstimada, cuotasTotales)
        } else {
            vistaPrevia = null
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
                    Text("Cliente: $nombreCliente", fontWeight = FontWeight.Bold)
                    Text("Capital: L. ${"%.2f".format(montoPrestamo)}")
                    Text("Interés: L. ${"%.2f".format(interesTotal)}")
                    Text("Total: L. ${"%.2f".format(totalAPagar)}", fontWeight = FontWeight.Bold)
                    Text("Pagado: L. ${"%.2f".format(montoPagadoActual)}", color = Color(0xFF4CAF50))
                    Text("Saldo: L. ${"%.2f".format(saldoActualizado)}", fontWeight = FontWeight.Bold, color = Color(0xFFFF5722))
                    Text("Cuota: L. ${"%.2f".format(cuotaEstimada)}")
                    Text("Próxima: #$proximaCuotaPendiente de $cuotasTotales")
                    Text("Fecha: $fechaProximoPago", fontWeight = FontWeight.Bold)
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
                        if ((saldoActualizado - (montoAbono.toDoubleOrNull() ?: 0.0)) > 0) {
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

                    val uidActual = session.getUid()
                    if (uidActual.isNullOrEmpty()) {
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

                            // ✅ CALCULAR DISTRIBUCIÓN FRESCA JUSTO ANTES DE USAR
                            val distribucion = distribuirPagoEnCascada(db, prestamoId, abono, cuotaEstimada, cuotasTotales)

                            // ✅ LOG DETALLADO PARA DEBUG
                            Log.d("RegistrarPago", "========================================")
                            Log.d("RegistrarPago", "DISTRIBUCIÓN CALCULADA:")
                            Log.d("RegistrarPago", "Total cuotas cubiertas: ${distribucion.cuotasCubiertas.size}")
                            distribucion.cuotasCubiertas.forEachIndexed { index, cuota ->
                                Log.d("RegistrarPago", "  [$index] Cuota #${cuota.numeroCuota}: L.${String.format("%.2f", cuota.montoAplicado)} ${if(cuota.completada) "✓ COMPLETA" else "⚠ PARCIAL"}")
                            }
                            Log.d("RegistrarPago", "Próxima cuota pendiente: #${distribucion.proximaCuotaPendiente}")
                            Log.d("RegistrarPago", "Fecha próximo pago: ${distribucion.fechaProximoPago}")
                            Log.d("RegistrarPago", "========================================")

                            val fechaActual = Timestamp.now()
                            val fechaFormateada = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(fechaActual.toDate())
                            val nuevoMontoPagado = montoPagadoActual + abono
                            val nuevoSaldo = (saldoActualizado - abono).coerceAtLeast(0.0)

                            // ✅ DESCRIPCIÓN MEJORADA PARA EL RECIBO (calculada DESPUÉS de obtener distribución)
                            val descripcionDetallada = when {
                                distribucion.cuotasCubiertas.isEmpty() -> {
                                    Log.w("RegistrarPago", "⚠️ NO SE CUBRIERON CUOTAS")
                                    "Sin cuotas"
                                }
                                distribucion.cuotasCubiertas.size == 1 -> {
                                    val c = distribucion.cuotasCubiertas.first()
                                    val desc = if (c.completada) "Cuota #${c.numeroCuota}" else "Cuota #${c.numeroCuota} parcial"
                                    Log.d("RegistrarPago", "📝 Descripción: $desc")
                                    desc
                                }
                                else -> {
                                    val nums = distribucion.cuotasCubiertas.map { it.numeroCuota }
                                    val desc = if (nums.size <= 3) {
                                        "Cuotas ${nums.joinToString(", ") { "#$it" }}"
                                    } else {
                                        "Cuotas #${nums.first()} a #${nums.last()}"
                                    }
                                    Log.d("RegistrarPago", "📝 Descripción: $desc")
                                    desc
                                }
                            }

                            val descripcionCorta = when {
                                distribucion.cuotasCubiertas.isEmpty() -> "N/A"
                                distribucion.cuotasCubiertas.size == 1 -> {
                                    val c = distribucion.cuotasCubiertas.first()
                                    if (c.completada) "#${c.numeroCuota}" else "#${c.numeroCuota}*"
                                }
                                else -> {
                                    val nums = distribucion.cuotasCubiertas.map { it.numeroCuota }
                                    if (nums.size <= 3) nums.joinToString(", ") { "#$it" }
                                    else "#${nums.first()}-#${nums.last()}"
                                }
                            }

                            Log.d("RegistrarPago", "📋 Descripción detallada para PDF: '$descripcionDetallada'")
                            Log.d("RegistrarPago", "📋 Descripción corta para BD: '$descripcionCorta'")

                            // ✅ GENERAR PDF CON LA DESCRIPCIÓN CORRECTA
                            val pdfFile = ReciboHelper.generarReciboPDF(
                                context = context,
                                cliente = nombreCliente,
                                prestamoId = "Préstamo Nº $numeroPrestamo",
                                fecha = fechaFormateada,
                                montoPagado = abono.toString(),
                                saldoAnterior = saldoActualizado,
                                proximoPago = distribucion.fechaProximoPago,
                                cuota = descripcionDetallada, // ✅ USA LA DESCRIPCIÓN RECIÉN CALCULADA
                                cobrador = nombreCobrador,
                                lugar = lugar,
                                firma = firmaPrestamista,
                                tipoPago = metodoPago,
                                mora = 0.0
                            )

                            val pdfGenerado = pdfFile != null && pdfFile.exists()
                            var pdfImpreso = false

                            if (pdfGenerado) {
                                archivoPDF = pdfFile
                                try {
                                    ReciboHelper.imprimirPDF(context, pdfFile!!)
                                    pdfImpreso = true
                                    Log.d("RegistrarPago", "✅ PDF impreso correctamente")
                                } catch (e: Exception) {
                                    Log.e("ImprimirPDF", "❌ Error al imprimir: ${e.message}")
                                }
                                ReciboHelper.compartirReciboPDF(context, pdfFile!!)
                            }

                            // ✅ GUARDAR EN FIREBASE
                            val abonoData = mapOf(
                                "clienteId" to clienteId,
                                "clienteNombre" to nombreCliente,
                                "prestamoId" to prestamoId,
                                "numeroPrestamo" to numeroPrestamo,
                                "monto" to abono,
                                "mora" to 0.0,
                                "fechaPago" to fechaActual,
                                "registradoPor" to uidActual,
                                "nombreCobrador" to nombreCobrador,
                                "saldoRestante" to nuevoSaldo,
                                "lugar" to lugar,
                                "firma" to firmaPrestamista,
                                "metodoPago" to metodoPago,
                                "plazo" to plazo,
                                "pdfGenerado" to pdfGenerado,
                                "pdfImpreso" to pdfImpreso,
                                "proximaFechaProgramada" to distribucion.fechaProximoPago,
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
                                Log.d("RegistrarPago", "✅ Pago guardado en Firebase")

                                // ✅ ACTUALIZAR PRÉSTAMO
                                val actualizacionPrestamo = if (nuevoSaldo <= 0.01) {
                                    mapOf<String, Any>(
                                        "saldo" to 0.0,
                                        "montoPagado" to totalAPagar,
                                        "estado" to "saldado",
                                        "proximoPago" to "saldado",
                                        "fechaUltimaActualizacion" to fechaActual,
                                        "ultimoPago" to fechaFormateada,
                                        "fechaSaldado" to fechaActual,
                                        "totalPagar" to totalAPagar
                                    )
                                } else {
                                    mapOf<String, Any>(
                                        "saldo" to nuevoSaldo,
                                        "montoPagado" to nuevoMontoPagado,
                                        "estado" to "activo",
                                        "proximoPago" to distribucion.fechaProximoPago,
                                        "fechaUltimaActualizacion" to fechaActual,
                                        "ultimoPago" to fechaFormateada
                                    )
                                }

                                db.collection("prestamos").document(prestamoId).update(actualizacionPrestamo).await()
                                Log.d("RegistrarPago", "✅ Préstamo actualizado")

                                try {
                                    db.collection("clientes").document(clienteId).update(
                                        mapOf(
                                            "ultimaActividad" to fechaActual,
                                            "fechaUltimaActualizacion" to fechaActual
                                        )
                                    ).await()
                                } catch (e: Exception) {
                                    Log.e("ActualizarCliente", "Error: ${e.message}")
                                }

                                val msg = if (nuevoSaldo <= 0.01) "¡PRÉSTAMO SALDADO! ✅"
                                else "Pago registrado. ${distribucion.totalCuotasCompletas} cuotas completas"

                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

                            } else {
                                guardarAbonoPendiente(context, abonoData)
                                Toast.makeText(context, "Pago guardado offline", Toast.LENGTH_LONG).show()
                            }

                            // ✅ ACTUALIZAR UI
                            montoPagadoActual = nuevoMontoPagado
                            saldoActualizado = nuevoSaldo
                            proximaCuotaPendiente = distribucion.proximaCuotaPendiente
                            fechaProximoPago = distribucion.fechaProximoPago
                            montoAbono = ""

                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            Log.e("RegistrarPago", "❌ Error general: ", e)
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
                    onClick = {
                        archivoPDF?.let { ReciboHelper.compartirReciboPDF(context, it) }
                    },
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