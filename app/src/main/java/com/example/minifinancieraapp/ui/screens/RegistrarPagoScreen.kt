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
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

// ===================== NUEVAS ESTRUCTURAS DE DATOS PARA PAGOS EN CASCADA =====================

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

// ===================== FUNCIONES PARA LA NUEVA LÓGICA DE PAGOS EN CASCADA =====================

// Función para calcular fechas de cuotas (mantenida igual)
private fun calcularFechaCuota(fechaInicio: Date, plazo: String, numeroCuota: Int): String {
    val calendar = Calendar.getInstance().apply { time = fechaInicio }
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    when (plazo.lowercase()) {
        "diario" -> {
            calendar.add(Calendar.DAY_OF_YEAR, numeroCuota)
        }
        "lunes a sábado" -> {
            repeat(numeroCuota) {
                do {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
        }
        "semanal" -> {
            calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 7)
        }
        "quincenal" -> {
            calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 15)
        }
        "mensual" -> {
            calendar.add(Calendar.MONTH, numeroCuota)
        }
        "bimestral" -> {
            calendar.add(Calendar.MONTH, numeroCuota * 2)
        }
        else -> {
            calendar.add(Calendar.MONTH, numeroCuota)
        }
    }

    return dateFormat.format(calendar.time)
}

// NUEVA FUNCIÓN PRINCIPAL: Distribución de pagos en cascada
private suspend fun distribuirPagoEnCascada(
    db: FirebaseFirestore,
    prestamoId: String,
    montoPagado: Double,
    cuotaEstimada: Double,
    cuotasTotales: Int
): ResultadoDistribucion {
    return try {
        // Obtener estado actual de todas las cuotas
        val estadoCuotas = obtenerEstadoCuotasCompleto(db, prestamoId)

        var montoRestante = montoPagado
        val cuotasCubiertas = mutableListOf<CuotaCubierta>()
        var totalCuotasCompletas = 0

        Log.d("DistribucionCascada", "=== INICIANDO DISTRIBUCIÓN EN CASCADA ===")
        Log.d("DistribucionCascada", "Monto a distribuir: L. ${String.format("%.2f", montoPagado)}")
        Log.d("DistribucionCascada", "Cuota estimada: L. ${String.format("%.2f", cuotaEstimada)}")

        // Distribuir el dinero secuencialmente desde la cuota 1
        for (i in 1..cuotasTotales) {
            if (montoRestante <= 0.01) break // Parar si ya no hay dinero

            val montoPagadoEnCuota = estadoCuotas[i] ?: 0.0
            val montoRestanteCuota = (cuotaEstimada - montoPagadoEnCuota).coerceAtLeast(0.0)

            if (montoRestanteCuota > 0.01) { // Solo si la cuota no está completa
                val montoAAplicar = minOf(montoRestante, montoRestanteCuota)
                val cuotaCompleta = montoAAplicar >= montoRestanteCuota - 0.01

                cuotasCubiertas.add(
                    CuotaCubierta(
                        numeroCuota = i,
                        montoAplicado = montoAAplicar,
                        completada = cuotaCompleta
                    )
                )

                if (cuotaCompleta) {
                    totalCuotasCompletas++
                }

                montoRestante -= montoAAplicar

                Log.d("DistribucionCascada", "Cuota $i: aplicado L. ${String.format("%.2f", montoAAplicar)}, completa: $cuotaCompleta")
            }
        }

        // Encontrar próxima cuota pendiente
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

        // Calcular fecha de próximo pago
        val fechaProximoPago = if (proximaCuotaPendiente <= cuotasTotales) {
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
            val fechaInicio = prestamoDoc.getTimestamp("fecha")?.toDate() ?: Date()
            val plazo = prestamoDoc.getString("plazo") ?: "semanal"
            calcularFechaCuota(fechaInicio, plazo, proximaCuotaPendiente)
        } else {
            "saldado"
        }

        Log.d("DistribucionCascada", "=== RESULTADO DISTRIBUCIÓN ===")
        Log.d("DistribucionCascada", "Cuotas afectadas: ${cuotasCubiertas.size}")
        Log.d("DistribucionCascada", "Cuotas completadas: $totalCuotasCompletas")
        Log.d("DistribucionCascada", "Próxima cuota pendiente: $proximaCuotaPendiente")
        Log.d("DistribucionCascada", "Fecha próximo pago: $fechaProximoPago")

        ResultadoDistribucion(
            cuotasCubiertas = cuotasCubiertas,
            proximaCuotaPendiente = proximaCuotaPendiente,
            fechaProximoPago = fechaProximoPago,
            totalCuotasCompletas = totalCuotasCompletas
        )

    } catch (e: Exception) {
        Log.e("DistribucionCascada", "Error en distribución en cascada: ${e.message}", e)
        // Fallback: aplicar todo a la primera cuota disponible
        ResultadoDistribucion(
            cuotasCubiertas = listOf(CuotaCubierta(1, montoPagado, false)),
            proximaCuotaPendiente = 1,
            fechaProximoPago = "pendiente",
            totalCuotasCompletas = 0
        )
    }
}

// Función auxiliar para obtener estado completo de cuotas
private suspend fun obtenerEstadoCuotasCompleto(
    db: FirebaseFirestore,
    prestamoId: String
): Map<Int, Double> {
    return try {
        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId)
            .get().await()

        val pagosPorCuota = mutableMapOf<Int, Double>()

        for (pago in pagosSnapshot.documents) {
            val cuotasCubiertas = pago.get("cuotasCubiertas") as? List<*> ?: emptyList<Map<String, Any>>()

            if (cuotasCubiertas.isNotEmpty()) {
                // Nueva estructura con cuotas múltiples
                cuotasCubiertas.forEach { cuotaData ->
                    if (cuotaData is Map<*, *>) {
                        val numeroCuota = (cuotaData["numeroCuota"] as? Number)?.toInt() ?: 0
                        val montoAplicado = (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0

                        if (numeroCuota > 0) {
                            pagosPorCuota[numeroCuota] = (pagosPorCuota[numeroCuota] ?: 0.0) + montoAplicado
                        }
                    }
                }
            } else {
                // Compatibilidad con estructura antigua
                val numeroCuota = when {
                    pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                    pago.contains("cuota") -> pago.getLong("cuota")?.toInt() ?: 1
                    else -> 1
                }

                val montoPago = pago.getDouble("monto") ?: 0.0
                val moraPago = pago.getDouble("mora") ?: 0.0
                val montoTotal = montoPago + moraPago

                pagosPorCuota[numeroCuota] = (pagosPorCuota[numeroCuota] ?: 0.0) + montoTotal
            }
        }

        pagosPorCuota
    } catch (e: Exception) {
        Log.e("EstadoCuotas", "Error obteniendo estado de cuotas: ${e.message}")
        emptyMap()
    }
}

// Función para crear o verificar documento cliente
suspend fun verificarYCrearCliente(db: FirebaseFirestore, clienteId: String, nombreCliente: String): Boolean {
    return try {
        val clienteDoc = db.collection("clientes").document(clienteId).get().await()
        if (!clienteDoc.exists()) {
            val clienteData = mapOf<String, Any>(
                "nombre" to nombreCliente,
                "fechaCreacion" to Timestamp.now(),
                "ultimaActividad" to Timestamp.now(),
                "estado" to "activo"
            )
            db.collection("clientes").document(clienteId).set(clienteData).await()
            Log.d("CrearCliente", "Cliente creado: $clienteId")
        }
        true
    } catch (e: Exception) {
        Log.e("CrearCliente", "Error creando cliente: ${e.message}")
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

    // Estados principales
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

    // Variables del préstamo
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

    // NUEVA: Vista previa de distribución en cascada
    var vistaPrevia by remember { mutableStateOf<ResultadoDistribucion?>(null) }

    // Carga inicial de datos
    LaunchedEffect(Unit) {
        Log.d("RegistrarPagoScreen", "=== INICIANDO NUEVA LÓGICA DE PAGOS EN CASCADA ===")

        if (cobrador.isEmpty()) {
            Toast.makeText(context, "Error: UID del cobrador no válido", Toast.LENGTH_LONG).show()
            navController.popBackStack()
            return@LaunchedEffect
        }

        try {
            // Cargar datos del cliente
            val clienteDoc = db.collection("clientes").document(clienteId).get().await()
            nombreCliente = if (clienteDoc.exists()) {
                clienteDoc.getString("nombre") ?: "Cliente"
            } else {
                "Cliente $clienteId"
            }

            // Cargar datos del cobrador
            val usuarioDoc = db.collection("usuarios").document(cobrador).get().await()
            nombreCobrador = usuarioDoc.getString("nombre") ?: "Cobrador Desconocido"
            firmaPrestamista = nombreCobrador

            // Cargar datos del préstamo
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
            if (!prestamoDoc.exists()) {
                Toast.makeText(context, "Error: El préstamo no existe", Toast.LENGTH_LONG).show()
                navController.popBackStack()
                return@LaunchedEffect
            }

            // Extraer información del préstamo
            montoPrestamo = prestamoDoc.getDouble("monto") ?: 0.0
            interesTotal = prestamoDoc.getDouble("interes") ?: prestamoDoc.getDouble("interesTotal") ?: 0.0
            totalAPagar = montoPrestamo + interesTotal
            cuotaEstimada = prestamoDoc.getDouble("cuota") ?: 0.0
            cuotasTotales = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
            plazo = prestamoDoc.getString("plazo") ?: "Semanal"
            numeroPrestamo = prestamoDoc.getLong("numeroPrestamo")?.toInt() ?: 0
            montoPagadoActual = prestamoDoc.getDouble("montoPagado") ?: 0.0
            saldoActualizado = (totalAPagar - montoPagadoActual).coerceAtLeast(0.0)

            // Calcular cuota y fecha pendiente con nueva lógica
            val resultado = distribuirPagoEnCascada(db, prestamoId, 0.0, cuotaEstimada, cuotasTotales)
            proximaCuotaPendiente = resultado.proximaCuotaPendiente
            fechaProximoPago = resultado.fechaProximoPago

            Log.d("RegistrarPagoScreen", """
                === DATOS CARGADOS CON NUEVA LÓGICA ===
                - Capital: L. ${String.format("%.2f", montoPrestamo)}
                - Interés: L. ${String.format("%.2f", interesTotal)}
                - Total a pagar: L. ${String.format("%.2f", totalAPagar)}
                - Ya pagado: L. ${String.format("%.2f", montoPagadoActual)}
                - Saldo pendiente: L. ${String.format("%.2f", saldoActualizado)}
                - Próxima cuota: $proximaCuotaPendiente de $cuotasTotales
                - Fecha próximo pago: $fechaProximoPago
            """.trimIndent())

        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar datos: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("RegistrarPagoScreen", "Error al cargar datos", e)
        }
    }

    // Calcular vista previa cuando cambie el monto
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
                title = { Text("Registrar Pago - Nueva Lógica", color = MaterialTheme.colorScheme.primary) },
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
            // Información del préstamo
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Información del Préstamo", fontWeight = FontWeight.Bold)
                    Text("Cliente: $nombreCliente", fontWeight = FontWeight.Bold)
                    Text("Capital prestado: L. ${"%.2f".format(montoPrestamo)}")
                    Text("Interés total: L. ${"%.2f".format(interesTotal)}")
                    Text(
                        "Total a pagar: L. ${"%.2f".format(totalAPagar)}",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E88E5)
                    )
                    Text(
                        "Ya pagado: L. ${"%.2f".format(montoPagadoActual)}",
                        color = Color(0xFF4CAF50)
                    )
                    Text(
                        "Saldo pendiente: L. ${"%.2f".format(saldoActualizado)}",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5722)
                    )
                    Text("Plazo: $plazo")
                    Text("Cuota estimada: L. ${"%.2f".format(cuotaEstimada)}")
                    Text("Próxima cuota pendiente: #$proximaCuotaPendiente de $cuotasTotales")
                    Text(
                        "Fecha próximo pago: $fechaProximoPago",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1976D2)
                    )
                }
            }

            // NUEVA: Explicación del sistema
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nueva Lógica de Pagos", fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                    Text(
                        "El dinero se distribuirá automáticamente llenando cuotas completas en orden secuencial. " +
                                "No hay abonos parciales: si pagas L. 2000 y la cuota es L. 1330, se llenará la cuota 1 (L. 1330) " +
                                "y se aplicarán L. 670 a la cuota 2.",
                        color = Color(0xFFE65100)
                    )
                }
            }

            // Campo de monto
            PrimaryTextField(
                value = montoAbono,
                onValueChange = { montoAbono = it },
                label = "Monto recibido",
                keyboardType = KeyboardType.Number
            )

            // NUEVA: Vista previa de distribución en cascada
            vistaPrevia?.let { preview ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Vista Previa de Distribución", fontWeight = FontWeight.Bold)

                        if (preview.cuotasCubiertas.isNotEmpty()) {
                            Text("Cuotas que se llenarán:", fontWeight = FontWeight.Medium)
                            preview.cuotasCubiertas.forEach { cuota ->
                                val estado = if (cuota.completada) "COMPLETA" else "PARCIAL"
                                Text(
                                    "• Cuota ${cuota.numeroCuota}: L. ${"%.2f".format(cuota.montoAplicado)} ($estado)",
                                    color = if (cuota.completada) Color(0xFF4CAF50) else Color(0xFFFF9800)
                                )
                            }

                            Text(
                                "Total de cuotas completadas: ${preview.totalCuotasCompletas}",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50)
                            )

                            val nuevoSaldo = saldoActualizado - (montoAbono.toDoubleOrNull() ?: 0.0)
                            if (nuevoSaldo <= 0) {
                                Text(
                                    "¡PRÉSTAMO SALDADO COMPLETAMENTE!",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                            } else {
                                Text("Próxima cuota pendiente: #${preview.proximaCuotaPendiente}")
                                Text(
                                    "Nueva fecha programada: ${preview.fechaProximoPago}",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1976D2)
                                )
                            }
                        }
                    }
                }
            }

            // Campos adicionales
            PrimaryTextField(value = lugar, onValueChange = { lugar = it }, label = "Lugar")
            PrimaryTextField(
                value = firmaPrestamista,
                onValueChange = { firmaPrestamista = it },
                label = "Firma"
            )

            // Selector de método de pago
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

            // Botón principal
            PrimaryButton(
                text = "Registrar Pago en Cascada",
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
                            // Verificar/crear cliente
                            if (!verificarYCrearCliente(db, clienteId, nombreCliente)) {
                                Toast.makeText(context, "Error al verificar cliente", Toast.LENGTH_LONG).show()
                                botonHabilitado = true
                                return@launch
                            }

                            // Calcular distribución final
                            val distribucion = distribuirPagoEnCascada(db, prestamoId, abono, cuotaEstimada, cuotasTotales)

                            val fechaActual = Timestamp.now()
                            val fechaFormateada = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(fechaActual.toDate())
                            val nuevoMontoPagado = montoPagadoActual + abono
                            val nuevoSaldo = (saldoActualizado - abono).coerceAtLeast(0.0)

                            // Generar descripción para el recibo
                            val descripcionCuotas = distribucion.cuotasCubiertas.joinToString(", ") { cuota ->
                                val estado = if (cuota.completada) "completa" else "parcial"
                                "#${cuota.numeroCuota} ($estado)"
                            }

                            // Generar PDF
                            val pdfFile = ReciboHelper.generarReciboPDF(
                                context = context,
                                cliente = nombreCliente,
                                prestamoId = "Préstamo Nº $numeroPrestamo",
                                fecha = fechaFormateada,
                                montoPagado = abono.toString(),
                                saldoAnterior = saldoActualizado,
                                proximoPago = distribucion.fechaProximoPago,
                                cuota = "Cuotas: $descripcionCuotas",
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
                                    Toast.makeText(context, "Recibo impreso correctamente", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    pdfImpreso = false
                                    Toast.makeText(context, "Error al imprimir. Compartiendo...", Toast.LENGTH_SHORT).show()
                                }
                                ReciboHelper.compartirReciboPDF(context, pdfFile!!)
                            }

                            // Registrar pago con nueva estructura
                            val abonoData = mapOf<String, Any>(
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
                                // NUEVA ESTRUCTURA: Lista de cuotas cubiertas
                                "cuotasCubiertas" to distribucion.cuotasCubiertas.map { cuota ->
                                    mapOf(
                                        "numeroCuota" to cuota.numeroCuota,
                                        "montoAplicado" to cuota.montoAplicado,
                                        "completada" to cuota.completada
                                    )
                                },
                                "sistemaPagoEnCascada" to true,
                                "observaciones" to "Pago distribuido automáticamente en cascada: $descripcionCuotas"
                            )

                            if (isInternetAvailable(context)) {
                                // Guardar en Firestore
                                db.collection("pagos").add(abonoData).await()

                                // Actualizar préstamo
                                val actualizacionPrestamo = if (nuevoSaldo <= 0.01) {
                                    mapOf<String, Any>(
                                        "saldo" to 0.0,
                                        "montoPagado" to nuevoMontoPagado,
                                        "estado" to "saldado",
                                        "proximoPago" to "saldado",
                                        "fechaUltimaActualizacion" to fechaActual,
                                        "ultimoPago" to fechaFormateada,
                                        "fechaSaldado" to fechaActual
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

                                // Actualizar cliente
                                try {
                                    db.collection("clientes").document(clienteId).update(
                                        mapOf(
                                            "ultimaActividad" to fechaActual,
                                            "fechaUltimaActualizacion" to fechaActual
                                        )
                                    ).await()
                                } catch (e: Exception) {
                                    Log.e("ActualizarCliente", "Error actualizando cliente: ${e.message}")
                                }

                                val mensajeExito = if (nuevoSaldo <= 0.01) {
                                    "¡PRÉSTAMO SALDADO COMPLETAMENTE! ${distribucion.totalCuotasCompletas} cuotas completadas"
                                } else {
                                    "Pago registrado correctamente. ${distribucion.totalCuotasCompletas} cuotas completadas"
                                }

                                Toast.makeText(context, mensajeExito, Toast.LENGTH_LONG).show()

                            } else {
                                guardarAbonoPendiente(context, abonoData)
                                Toast.makeText(context, "Pago guardado offline", Toast.LENGTH_LONG).show()
                            }

                            // Actualizar variables locales
                            montoPagadoActual = nuevoMontoPagado
                            saldoActualizado = nuevoSaldo
                            proximaCuotaPendiente = distribucion.proximaCuotaPendiente
                            fechaProximoPago = distribucion.fechaProximoPago
                            montoAbono = ""

                            Log.d("RegistrarPago", """
                                === PAGO EN CASCADA REGISTRADO EXITOSAMENTE ===
                                - Monto pagado: L. ${String.format("%.2f", abono)}
                                - Cuotas afectadas: ${distribucion.cuotasCubiertas.size}
                                - Cuotas completadas: ${distribucion.totalCuotasCompletas}
                                - Nuevo saldo: L. ${String.format("%.2f", nuevoSaldo)}
                                - Próxima cuota: ${distribucion.proximaCuotaPendiente}
                                - Fecha próximo pago: ${distribucion.fechaProximoPago}
                            """.trimIndent())

                        } catch (e: Exception) {
                            Toast.makeText(context, "Error al registrar el pago: ${e.message}", Toast.LENGTH_LONG).show()
                            Log.e("RegistrarPago", "Error registrando pago en cascada: ", e)
                        } finally {
                            botonHabilitado = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = botonHabilitado && (montoAbono.toDoubleOrNull() ?: 0.0) > 0
            )

            // Botones adicionales si hay PDF
            if (archivoPDF != null) {
                OutlinedButton(
                    onClick = {
                        archivoPDF?.let {
                            try {
                                ReciboHelper.imprimirPDF(context, it)
                                Toast.makeText(context, "Recibo reenviado a impresora", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al reimprimir: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reimprimir Recibo") }

                OutlinedButton(
                    onClick = {
                        archivoPDF?.let {
                            ReciboHelper.compartirReciboPDF(context, it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Compartir Recibo") }
            }

            // Botón para volver
            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Regresar")
            }
        }
    }
}