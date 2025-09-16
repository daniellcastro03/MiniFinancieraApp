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
import com.example.capitalexpressapp.util.ReciboHelper.compartirReciboPDF
import com.example.capitalexpressapp.util.ReciboHelper.generarReciboPDF

// ===================== FUNCIONES UNIFICADAS (IGUALES A CuotasPrestamoScreen) =====================

// ✅ FUNCIÓN PARA CALCULAR LA PRÓXIMA FECHA SOLO PARA MOSTRAR (SIN TOCAR BD)
private fun calcularProximaFechaSoloParaPDF(
    fechaProgramadaActual: String?,
    plazo: String,
    cuotasCubiertas: Int,
    fechaInicio: Date?
): String {
    if (cuotasCubiertas == 0) {
        return fechaProgramadaActual ?: SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
    }

    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val fechaBase = Calendar.getInstance()

    // Prioridad: usar la fecha programada actual
    when {
        !fechaProgramadaActual.isNullOrEmpty() && fechaProgramadaActual != "saldado" -> {
            try {
                fechaBase.time = dateFormat.parse(fechaProgramadaActual)!!
                Log.d("PDFHelper", "✅ Usando fecha programada actual: $fechaProgramadaActual")
            } catch (e: Exception) {
                fechaInicio?.let { fechaBase.time = it }
                Log.w("PDFHelper", "⚠️ Error parseando fecha actual, usando fecha inicio")
            }
        }
        fechaInicio != null -> {
            fechaBase.time = fechaInicio
            Log.d("PDFHelper", "✅ Usando fecha de inicio del préstamo")
        }
        else -> {
            Log.w("PDFHelper", "⚠️ Usando fecha actual como fallback")
        }
    }

    // Calcular la próxima fecha según el plazo
    when (plazo.lowercase()) {
        "diario" -> {
            fechaBase.add(Calendar.DAY_OF_YEAR, cuotasCubiertas)
        }
        "lunes a sábado" -> {
            repeat(cuotasCubiertas) {
                do {
                    fechaBase.add(Calendar.DAY_OF_YEAR, 1)
                } while (fechaBase.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
        }
        "semanal" -> {
            fechaBase.add(Calendar.DAY_OF_YEAR, cuotasCubiertas * 7)
        }
        "quincenal" -> {
            fechaBase.add(Calendar.DAY_OF_YEAR, cuotasCubiertas * 15)
        }
        "mensual" -> {
            fechaBase.add(Calendar.MONTH, cuotasCubiertas)
        }
        "bimestral" -> {
            fechaBase.add(Calendar.MONTH, cuotasCubiertas * 2)
        }
        else -> {
            fechaBase.add(Calendar.MONTH, cuotasCubiertas)
        }
    }

    return dateFormat.format(fechaBase.time)
}

fun calcularCuotasCubiertasInteligente(
    montoPagado: Double,
    cuotaEstimada: Double,
    saldoActual: Double,
    plazo: String
): Int {
    if (montoPagado >= saldoActual) {
        return Math.ceil(saldoActual / cuotaEstimada).toInt()
    }

    val cuotasCubiertas = (montoPagado / cuotaEstimada).toInt()
    val porcentajeCuota = montoPagado / cuotaEstimada

    return when {
        cuotasCubiertas == 0 && porcentajeCuota >= 0.5 -> 1
        cuotasCubiertas == 0 -> 0
        else -> cuotasCubiertas
    }
}

// ✅ FUNCIÓN PARA VERIFICAR SI EL DOCUMENTO EXISTE
suspend fun verificarDocumentoExiste(db: FirebaseFirestore, collection: String, documentId: String): Boolean {
    return try {
        val doc = db.collection(collection).document(documentId).get().await()
        doc.exists()
    } catch (e: Exception) {
        Log.e("FirestoreCheck", "Error verificando documento $collection/$documentId: ${e.message}")
        false
    }
}

// ✅ FUNCIÓN PARA CREAR DOCUMENTO SI NO EXISTE
suspend fun crearDocumentoSiNoExiste(db: FirebaseFirestore, clienteId: String, nombreCliente: String): Boolean {
    return try {
        if (!verificarDocumentoExiste(db, "clientes", clienteId)) {
            val clienteData = mapOf<String, Any>(
                "nombre" to nombreCliente,
                "fechaCreacion" to Timestamp.now(),
                "ultimaActividad" to Timestamp.now(),
                "estado" to "activo"
            )
            db.collection("clientes").document(clienteId).set(clienteData).await()
            Log.d("FirestoreCheck", "✅ Documento cliente creado: $clienteId")
            true
        } else {
            true
        }
    } catch (e: Exception) {
        Log.e("FirestoreCheck", "❌ Error creando documento cliente: ${e.message}")
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
    var cuotaSiguiente by remember { mutableStateOf(1) }
    var lugar by remember { mutableStateOf("El Paraíso, Danlí") }
    var firmaPrestamista by remember { mutableStateOf("") }
    var archivoPDF by remember { mutableStateOf<File?>(null) }
    var plazo by remember { mutableStateOf("Semanal") }
    var numeroPrestamo by remember { mutableStateOf(0) }
    var metodoPago by remember { mutableStateOf("Efectivo") }
    val opcionesMetodoPago = listOf("Efectivo", "Transferencia")
    var expandedMetodoPago by remember { mutableStateOf(false) }
    var nombreCobrador by remember { mutableStateOf(cobrador) }
    var estadoPrestamo by remember { mutableStateOf("activo") }
    var botonHabilitado by remember { mutableStateOf(true) }

    // Variables para datos del préstamo
    var montoPrestamo by remember { mutableStateOf(0.0) }
    var interesMensual by remember { mutableStateOf(0.0) }
    var interesTotal by remember { mutableStateOf(0.0) }
    var totalAPagar by remember { mutableStateOf(0.0) }
    var cuotaEstimada by remember { mutableStateOf(0.0) }
    var cuotasTotales by remember { mutableStateOf(1) }
    var fechaInicio by remember { mutableStateOf<Date?>(null) }
    var diasEfectivos by remember { mutableStateOf(0) }
    var proximoPagoActual by remember { mutableStateOf<String?>(null) }

    // ✅ VARIABLES CRÍTICAS PARA CÁLCULOS CORRECTOS
    var montoPagadoActual by remember { mutableStateOf(0.0) }
    var saldoActualizado by remember { mutableStateOf(saldoActual) }

    LaunchedEffect(Unit) {
        Log.d("RegistrarPagoScreen", "UID del cobrador recibido: $cobrador")

        if (cobrador.isEmpty()) {
            Toast.makeText(context, "Error: UID del cobrador no válido. Inicie sesión de nuevo.", Toast.LENGTH_LONG).show()
            navController.popBackStack()
            return@LaunchedEffect
        }

        try {
            // ✅ VERIFICAR Y CREAR DOCUMENTO CLIENTE SI NO EXISTE
            val clienteDoc = db.collection("clientes").document(clienteId).get().await()
            if (clienteDoc.exists()) {
                nombreCliente = clienteDoc.getString("nombre") ?: "Cliente"
            } else {
                Log.w("RegistrarPagoScreen", "⚠️ Documento cliente no existe, intentando crear...")
                nombreCliente = "Cliente $clienteId"
                if (!crearDocumentoSiNoExiste(db, clienteId, nombreCliente)) {
                    Toast.makeText(context, "Error: No se pudo crear el documento del cliente", Toast.LENGTH_LONG).show()
                    navController.popBackStack()
                    return@LaunchedEffect
                }
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

            estadoPrestamo = prestamoDoc.getString("estado") ?: "activo"
            plazo = prestamoDoc.getString("plazo") ?: "Semanal"
            numeroPrestamo = prestamoDoc.getLong("numeroPrestamo")?.toInt() ?: 0

            // ✅ OBTENER DATOS DEL PRÉSTAMO CORRECTAMENTE
            montoPrestamo = prestamoDoc.getDouble("monto") ?: 0.0
            interesMensual = prestamoDoc.getDouble("interesMensual") ?: 0.0
            interesTotal = prestamoDoc.getDouble("interes") ?: prestamoDoc.getDouble("interesTotal") ?: 0.0
            totalAPagar = montoPrestamo + interesTotal
            cuotaEstimada = prestamoDoc.getDouble("cuota") ?: 0.0
            cuotasTotales = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
            diasEfectivos = prestamoDoc.getLong("diasEfectivos")?.toInt() ?: 0

            // ✅ OBTENER MONTO PAGADO ACTUAL
            montoPagadoActual = prestamoDoc.getDouble("montoPagado") ?: 0.0

            // ✅ CALCULAR SALDO PENDIENTE CORRECTAMENTE
            saldoActualizado = (totalAPagar - montoPagadoActual).coerceAtLeast(0.0)

            // ✅ LOG PARA VERIFICAR CÁLCULOS
            Log.d("RegistrarPagoScreen", """
                === DATOS DEL PRÉSTAMO ===
                - Capital prestado: L. ${String.format("%.2f", montoPrestamo)}
                - Interés total: L. ${String.format("%.2f", interesTotal)}
                - TOTAL A PAGAR: L. ${String.format("%.2f", totalAPagar)}
                - Monto ya pagado: L. ${String.format("%.2f", montoPagadoActual)}
                - SALDO PENDIENTE: L. ${String.format("%.2f", saldoActualizado)}
            """.trimIndent())

            // ✅ USAR LA FUNCIÓN UNIFICADA PARA OBTENER FECHA PROGRAMADA
            proximoPagoActual = obtenerFechaProgramadaActual(db, prestamoId)

            fechaInicio = try {
                val fechaTimestamp = prestamoDoc.getTimestamp("fecha")
                fechaTimestamp?.toDate()
            } catch (e: Exception) {
                try {
                    val fechaString = prestamoDoc.getString("fecha")
                    if (fechaString != null) {
                        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        dateFormat.parse(fechaString)
                    } else {
                        null
                    }
                } catch (parseException: Exception) {
                    Date()
                }
            } ?: Date()

            val pagos = db.collection("pagos")
                .whereEqualTo("prestamoId", prestamoId)
                .get().await()

            cuotaSiguiente = pagos.size() + 1

        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar datos del préstamo: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("RegistrarPagoScreen", "Error al cargar datos del préstamo", e)
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
            // ✅ INFORMACIÓN DEL PRÉSTAMO CORREGIDA
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Información del Préstamo", fontWeight = FontWeight.Bold)
                    Text("Cliente: $nombreCliente", fontWeight = FontWeight.Bold)
                    Text("Capital prestado: L. ${"%.2f".format(montoPrestamo)}")
                    Text("Interés total: L. ${"%.2f".format(interesTotal)}")
                    Text("Total a pagar: L. ${"%.2f".format(totalAPagar)}", fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                    Text("Ya pagado: L. ${"%.2f".format(montoPagadoActual)}", color = Color(0xFF4CAF50))
                    Text("Saldo pendiente: L. ${"%.2f".format(saldoActualizado)}", fontWeight = FontWeight.Bold, color = Color(0xFFFF5722))
                    Text("Estado: $estadoPrestamo")
                    Text("Plazo: $plazo")
                    Text("Cuota estimada: L. ${"%.2f".format(cuotaEstimada)}")
                    Text("Cuota #: $cuotaSiguiente de $cuotasTotales")
                    proximoPagoActual?.let {
                        Text("Próximo pago programado: $it", fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                    }
                    if (diasEfectivos > 0) {
                        Text("Días efectivos: $diasEfectivos")
                    }
                }
            }

            PrimaryTextField(
                value = montoAbono,
                onValueChange = { montoAbono = it },
                label = "Monto del abono",
                keyboardType = KeyboardType.Number
            )

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

            val abono = montoAbono.toDoubleOrNull() ?: 0.0
            val nuevoSaldoPendiente = (saldoActualizado - abono).coerceAtLeast(0.0)
            val nuevoMontoPagado = montoPagadoActual + abono

            val cuotasCubiertas = if (cuotaEstimada > 0) {
                calcularCuotasCubiertasInteligente(abono, cuotaEstimada, saldoActualizado, plazo)
            } else {
                1
            }

            // ✅ RESUMEN DEL PAGO CORREGIDO
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Resumen del Pago", fontWeight = FontWeight.Bold)
                    Text("Monto a abonar: L. ${"%.2f".format(abono)}")
                    Text("Pagado anteriormente: L. ${"%.2f".format(montoPagadoActual)}")
                    Text("Nuevo total pagado: L. ${"%.2f".format(nuevoMontoPagado)}", color = Color(0xFF4CAF50))
                    Text("Nuevo saldo pendiente: L. ${"%.2f".format(nuevoSaldoPendiente)}", color = Color(0xFFFF5722))
                    Text("Cuotas cubiertas con este pago: $cuotasCubiertas")
                    if (nuevoSaldoPendiente == 0.0) {
                        Text("¡PRÉSTAMO SALDADO!", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    } else {
                        // ✅ MOSTRAR PRÓXIMA FECHA CALCULADA (SOLO PARA VISTA PREVIA)
                        val proximaFechaParaPDF = calcularProximaFechaSoloParaPDF(proximoPagoActual, plazo, cuotasCubiertas, fechaInicio)
                        Text("Próxima fecha de pago: $proximaFechaParaPDF", fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                        Text("(Las fechas son fijas e inamovibles)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
                    }
                }
            }

            PrimaryButton(
                text = "Registrar Abono",
                onClick = {
                    if (!botonHabilitado) return@PrimaryButton
                    if (abono <= 0.0) {
                        Toast.makeText(context, "Ingresa un monto válido", Toast.LENGTH_SHORT).show()
                        return@PrimaryButton
                    }

                    val uidActual = session.getUid()
                    if (uidActual.isNullOrEmpty()) {
                        Toast.makeText(context, "Error: Sesión no válida. Por favor, reinicie la app.", Toast.LENGTH_LONG).show()
                        return@PrimaryButton
                    }

                    botonHabilitado = false
                    val fechaActual = Timestamp.now()
                    val fechaFormateada = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(fechaActual.toDate())

                    scope.launch {
                        try {
                            if (!verificarDocumentoExiste(db, "clientes", clienteId)) {
                                if (!crearDocumentoSiNoExiste(db, clienteId, nombreCliente)) {
                                    Toast.makeText(context, "❌ Error: No se pudo crear el documento del cliente", Toast.LENGTH_LONG).show()
                                    botonHabilitado = true
                                    return@launch
                                }
                            }

                            // ✅ 1) CALCULAR PRÓXIMA FECHA SOLO PARA EL PDF (SIN TOCAR BD)
                            val proximaProgramadaSoloParaPDF = if (nuevoSaldoPendiente > 0.0) {
                                calcularProximaFechaSoloParaPDF(proximoPagoActual, plazo, cuotasCubiertas, fechaInicio)
                            } else {
                                "saldado"
                            }

                            Log.d("RegistrarPago", """
                                === CÁLCULO DE PRÓXIMA FECHA ===
                                - Plazo: $plazo
                                - Fecha actual programada: $proximoPagoActual
                                - Cuotas cubiertas: $cuotasCubiertas
                                - Fecha de inicio: ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(fechaInicio ?: Date())}
                                - PRÓXIMA FECHA (solo PDF): $proximaProgramadaSoloParaPDF
                            """.trimIndent())

                            // ✅ DETERMINAR SI EL PAGO ES TARDÍO (hoy > fecha programada actual)
                            val pagoTardio: Boolean = run {
                                val prog = proximoPagoActual?.trim()
                                if (!prog.isNullOrEmpty() && prog.lowercase() != "saldado") {
                                    val f = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                    val fechaProg = runCatching { f.parse(prog) }.getOrNull()
                                    val hoy = Timestamp.now().toDate()

                                    // Comparación por fecha (ignorando hora)
                                    if (fechaProg != null) {
                                        val calA = Calendar.getInstance().apply {
                                            time = hoy
                                            set(Calendar.HOUR_OF_DAY, 0)
                                            set(Calendar.MINUTE, 0)
                                            set(Calendar.SECOND, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }
                                        val calB = Calendar.getInstance().apply {
                                            time = fechaProg
                                            set(Calendar.HOUR_OF_DAY, 0)
                                            set(Calendar.MINUTE, 0)
                                            set(Calendar.SECOND, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }
                                        calA.time.after(calB.time) // hoy > programada ⇒ tardío
                                    } else false
                                } else false
                            }

                            // Log para debugging
                            if (pagoTardio) {
                                Log.d("PagoTardio", "⚠️ PAGO TARDÍO detectado - Fecha programada: $proximoPagoActual")
                            } else {
                                Log.d("PagoTardio", "✅ Pago a tiempo - Fecha programada: $proximoPagoActual")
                            }

                            // ✅ 2) GENERAR RECIBO CON SALDO ANTERIOR CORRECTO
                            val pdfFile = ReciboHelper.generarReciboPDF(
                                context = context,
                                cliente = nombreCliente,
                                prestamoId = "Préstamo Nº $numeroPrestamo",
                                fecha = fechaFormateada,
                                montoPagado = abono.toString(),
                                saldoAnterior = saldoActualizado, // ✅ CORRECCIÓN: usar saldoActualizado directamente
                                proximoPago = proximaProgramadaSoloParaPDF,
                                cuota = cuotaSiguiente.toString(),
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

                                // ✅ Imprimir
                                try {
                                    ReciboHelper.imprimirPDF(context, pdfFile!!)
                                    pdfImpreso = true
                                    Toast.makeText(context, "✅ Recibo impreso correctamente", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    pdfImpreso = false
                                    Toast.makeText(context, "⚠️ Error al imprimir. Compartiendo...", Toast.LENGTH_SHORT).show()
                                }

                                // ✅ Compartir
                                ReciboHelper.compartirReciboPDF(context, pdfFile!!)
                            } else {
                                Toast.makeText(context, "❌ No se pudo generar el recibo", Toast.LENGTH_LONG).show()
                            }

                            // ✅ 3) REGISTRAR EL PAGO EN "PAGOS"
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
                                "cuota" to cuotaSiguiente,           // ✅ GUARDAMOS COMO "cuota" (compatible)
                                "numeroCuota" to cuotaSiguiente,    // ✅ TAMBIÉN COMO "numeroCuota" (compatibilidad total)
                                "saldoRestante" to nuevoSaldoPendiente,
                                "lugar" to lugar,
                                "firma" to firmaPrestamista,
                                "metodoPago" to metodoPago,
                                "cuotasCubiertas" to cuotasCubiertas,
                                "plazo" to plazo,
                                "pdfGenerado" to pdfGenerado,
                                "pdfImpreso" to pdfImpreso,
                                "fechaProgramadaOriginal" to (proximoPagoActual ?: ""), // ✅ GUARDAR LA FECHA ORIGINAL
                                "proximaFechaProgramada" to proximaProgramadaSoloParaPDF,     // ✅ GUARDAR LA PRÓXIMA FECHA
                                "pagoTardio" to pagoTardio // ✅ MARCAR SI ES PAGO TARDÍO
                            )

                            if (isInternetAvailable(context)) {
                                db.collection("pagos").add(abonoData).await()

                                // ✅ 4) ACTUALIZAR EL PRÉSTAMO CON SISTEMA DE FECHAS ANCLADAS
                                if (nuevoSaldoPendiente == 0.0) {
                                    // Si saldó, marca saldado y fija proximoPago = "saldado"
                                    val actualizacionPrestamoSaldado = mutableMapOf<String, Any>(
                                        "saldo" to nuevoSaldoPendiente,
                                        "montoPagado" to nuevoMontoPagado,
                                        "estado" to "saldado",
                                        "proximoPago" to "saldado",
                                        "fechaUltimaActualizacion" to fechaActual,
                                        "ultimoPago" to fechaFormateada
                                    )

                                    // ✅ Si el pago fue tardío, marca y acumula en el préstamo
                                    if (pagoTardio) {
                                        actualizacionPrestamoSaldado["tienePagosTarde"] = true
                                        actualizacionPrestamoSaldado["ultimoPagoTarde"] = fechaActual
                                        actualizacionPrestamoSaldado["pagosTardeCount"] = FieldValue.increment(1)
                                    }

                                    db.collection("prestamos").document(prestamoId).update(actualizacionPrestamoSaldado).await()

                                } else {
                                    // ✅ AVANZA LA FECHA PROGRAMADA ANCLADA EN +cuotasCubiertas
                                    actualizarProximoPagoProgramado(db, prestamoId, cuotasCubiertas)

                                    // ✅ REFRESCAR EL VALOR YA ANCLADO PARA GUARDARLO JUNTO CON OTROS CAMPOS
                                    val proximoProgramadoAnclado = obtenerFechaProgramadaActual(db, prestamoId) ?: proximaProgramadaSoloParaPDF

                                    val actualizacionPrestamoActivo = mutableMapOf<String, Any>(
                                        "saldo" to nuevoSaldoPendiente,
                                        "montoPagado" to nuevoMontoPagado,
                                        "estado" to "activo",
                                        "proximoPago" to proximoProgramadoAnclado,
                                        "fechaUltimaActualizacion" to fechaActual,
                                        "ultimoPago" to fechaFormateada
                                    )

                                    // ✅ Si el pago fue tardío, marca y acumula en el préstamo
                                    if (pagoTardio) {
                                        actualizacionPrestamoActivo["tienePagosTarde"] = true
                                        actualizacionPrestamoActivo["ultimoPagoTarde"] = fechaActual
                                        actualizacionPrestamoActivo["pagosTardeCount"] = FieldValue.increment(1)
                                        Log.d("PagoTardio", "📊 Actualizando contadores de préstamo para pago tardío")
                                    }

                                    db.collection("prestamos").document(prestamoId).update(actualizacionPrestamoActivo).await()
                                }

                                // ✅ ACTUALIZACIÓN DEL CLIENTE CON SISTEMA DE PAGOS TARDÍOS
                                try {
                                    val actualizacionCliente = mutableMapOf<String, Any>(
                                        "ultimaActividad" to fechaActual,
                                        "fechaUltimaActualizacion" to fechaActual
                                    )

                                    // ✅ Si fue tardío, marca en el cliente también
                                    if (pagoTardio) {
                                        actualizacionCliente["tienePagosTarde"] = true
                                        actualizacionCliente["ultimoPagoTarde"] = fechaActual
                                        actualizacionCliente["pagosTardeCount"] = FieldValue.increment(1)
                                        Log.d("PagoTardio", "📊 Actualizando contadores de cliente para pago tardío")
                                    }

                                    db.collection("clientes").document(clienteId).update(actualizacionCliente).await()
                                } catch (e: Exception) {
                                    Log.e("ActualizacionCliente", "Error actualizando cliente: ${e.message}")
                                }

                                Toast.makeText(context, "✅ Pago registrado correctamente", Toast.LENGTH_SHORT).show()

                                // ✅ MENSAJE ADICIONAL CUANDO HAY PAGO TARDÍO
                                if (pagoTardio) {
                                    Toast.makeText(context, "⚠️ Pago registrado como TARDÍO", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                guardarAbonoPendiente(context, abonoData)
                                Toast.makeText(context, "📂 Abono guardado offline", Toast.LENGTH_LONG).show()
                            }

                            // ✅ LIMPIAR Y ACTUALIZAR
                            montoPagadoActual = nuevoMontoPagado
                            saldoActualizado = nuevoSaldoPendiente
                            proximoPagoActual = if (nuevoSaldoPendiente == 0.0) "saldado" else obtenerFechaProgramadaActual(db, prestamoId)
                            montoAbono = ""
                            cuotaSiguiente += cuotasCubiertas

                        } catch (e: Exception) {
                            Toast.makeText(context, "❌ Error al registrar el pago: ${e.message}", Toast.LENGTH_LONG).show()
                            Log.e("RegistrarPago", "Error completo: ", e)
                        } finally {
                            botonHabilitado = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = botonHabilitado
            )

            if (archivoPDF != null) {
                OutlinedButton(
                    onClick = {
                        archivoPDF?.let {
                            try {
                                ReciboHelper.imprimirPDF(context, it)
                                Toast.makeText(context, "✅ Recibo reenviado a impresora", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "❌ Error al reimprimir: ${e.message}", Toast.LENGTH_SHORT).show()
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

            OutlinedButton(
                onClick = {
                    val uid = clienteId
                    val rol = "cobrador"
                    val route = "CuotasPrestamoScreen/$prestamoId/$uid/$rol"
                    navController.navigate(route)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Ver Tabla de Cuotas") }

            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Regresar") }
        }
    }
}