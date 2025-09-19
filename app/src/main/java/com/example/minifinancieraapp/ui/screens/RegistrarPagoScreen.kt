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

// ===================== FUNCIONES CORREGIDAS PARA FECHAS FIJAS Y ABONOS PARCIALES =====================

// ✅ FUNCIÓN PARA CALCULAR FECHAS DE CUOTAS (IGUAL QUE EN CuotasPrestamoScreen)
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

// ✅ NUEVA FUNCIÓN: Obtener información detallada de cuotas con estados de pagos parciales
private suspend fun obtenerEstadoCuotasDetallado(
    db: FirebaseFirestore,
    prestamoId: String
): Map<Int, Double> {
    return try {
        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId)
            .get().await()

        val pagosPorCuota = mutableMapOf<Int, Double>()

        for (pago in pagosSnapshot.documents) {
            val numeroCuotaInicial = when {
                pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                pago.contains("cuota") -> pago.getLong("cuota")?.toInt() ?: 1
                else -> 1
            }

            val cuotasCubiertas = pago.getLong("cuotasCubiertas")?.toInt() ?: 1
            val montoPago = pago.getDouble("monto") ?: 0.0
            val moraPago = pago.getDouble("mora") ?: 0.0
            val montoTotal = montoPago + moraPago

            // Distribuir el pago entre las cuotas afectadas
            for (i in 0 until cuotasCubiertas) {
                val numCuota = numeroCuotaInicial + i
                pagosPorCuota[numCuota] = (pagosPorCuota[numCuota] ?: 0.0) + montoTotal
            }
        }

        pagosPorCuota
    } catch (e: Exception) {
        Log.e("EstadoCuotas", "Error obteniendo estado de cuotas: ${e.message}")
        emptyMap()
    }
}

// ✅ NUEVA FUNCIÓN CRÍTICA: Encontrar la verdadera cuota siguiente considerando parciales
private suspend fun encontrarCuotaSiguienteReal(
    db: FirebaseFirestore,
    prestamoId: String,
    cuotasTotales: Int,
    cuotaEstimada: Double
): Int {
    return try {
        val estadoCuotas = obtenerEstadoCuotasDetallado(db, prestamoId)

        // Buscar la primera cuota que NO esté completamente pagada
        for (i in 1..cuotasTotales) {
            val montoPagadoEnCuota = estadoCuotas[i] ?: 0.0
            if (montoPagadoEnCuota < cuotaEstimada - 0.01) { // Tolerancia de 1 centavo
                Log.d("CuotaSiguiente", "Cuota siguiente real encontrada: $i (pagado: L. ${String.format("%.2f", montoPagadoEnCuota)}, total: L. ${String.format("%.2f", cuotaEstimada)})")
                return i
            }
        }

        // Si todas están pagadas, la siguiente sería después de la última
        cuotasTotales + 1
    } catch (e: Exception) {
        Log.e("CuotaSiguiente", "Error encontrando cuota siguiente: ${e.message}")
        1 // Fallback
    }
}

// ✅ FUNCIÓN MEJORADA: Calcular cuotas cubiertas considerando la cuota real actual
fun calcularCuotasCubiertasDesdeRealInteligente(
    montoPagado: Double,
    cuotaEstimada: Double,
    saldoActual: Double,
    estadoCuotas: Map<Int, Double>,
    cuotaSiguienteReal: Int, // CAMBIO: Usar la cuota real, no estimada
    plazo: String
): Pair<Int, Boolean> {

    if (montoPagado >= saldoActual) {
        return Pair(Math.ceil(saldoActual / cuotaEstimada).toInt(), false)
    }

    // Verificar si la cuota siguiente real ya tiene abonos parciales
    val abonoPrevioEnCuotaSiguiente = estadoCuotas[cuotaSiguienteReal] ?: 0.0
    val tieneAbonoParcialPrevio = abonoPrevioEnCuotaSiguiente > 0.01 && abonoPrevioEnCuotaSiguiente < cuotaEstimada - 0.01

    if (tieneAbonoParcialPrevio) {
        // Si la cuota siguiente ya tiene abono parcial, verificar si este pago la completa
        val montoRestanteCuotaSiguiente = cuotaEstimada - abonoPrevioEnCuotaSiguiente
        if (montoPagado >= montoRestanteCuotaSiguiente - 0.01) {
            // Completa la cuota parcial y posiblemente más
            val sobrante = montoPagado - montoRestanteCuotaSiguiente
            val cuotasAdicionales = if (sobrante > 0) (sobrante / cuotaEstimada).toInt() else 0
            return Pair(1 + cuotasAdicionales, true)
        } else {
            // Solo abona más a la cuota parcial existente, no la completa
            return Pair(0, true)
        }
    }

    // Lógica normal si no hay abonos parciales previos
    val cuotasCubiertas = (montoPagado / cuotaEstimada).toInt()
    val porcentajeCuota = montoPagado / cuotaEstimada

    val resultado = when {
        cuotasCubiertas == 0 && porcentajeCuota >= 0.5 -> 1
        cuotasCubiertas == 0 -> 0
        else -> cuotasCubiertas
    }

    return Pair(resultado, false)
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

// ✅ FUNCIÓN CORREGIDA: Obtener próxima fecha programada usando el sistema de fechas fijas
private suspend fun obtenerProximaFechaProgramadaFija(
    db: FirebaseFirestore,
    prestamoId: String,
    cuotasIncremento: Int,
    esAbonoParcialNuevo: Boolean = false
): String {
    return try {
        // Si es un abono parcial que no completa la cuota, no cambiar la fecha programada
        if (esAbonoParcialNuevo && cuotasIncremento == 0) {
            val fechaProgramadaActual = obtenerFechaProgramadaActual(db, prestamoId)
            return fechaProgramadaActual ?: "pendiente"
        }

        // 1. Obtener la fecha programada actual del préstamo
        val fechaProgramadaActual = obtenerFechaProgramadaActual(db, prestamoId)

        if (!fechaProgramadaActual.isNullOrEmpty() && fechaProgramadaActual != "saldado") {
            // Si hay fecha programada y vamos a cubrir cuotas, avanzar desde esa fecha fija
            if (cuotasIncremento > 0) {
                val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
                val plazo = prestamoDoc.getString("plazo") ?: "semanal"
                return calcularProximaFechaDesdeAnclaje(fechaProgramadaActual, plazo, cuotasIncremento)
            } else {
                return fechaProgramadaActual
            }
        }

        // 2. Si no hay fecha programada, calcular desde el sistema de cuotas
        val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
        val cuotasTotales = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
        val plazo = prestamoDoc.getString("plazo") ?: "semanal"
        val fechaInicio = prestamoDoc.getTimestamp("fecha")?.toDate() ?: Date()

        // Encontrar cuántas cuotas se han pagado completamente
        val estadoCuotas = obtenerEstadoCuotasDetallado(db, prestamoId)
        val cuotaEstimada = prestamoDoc.getDouble("cuota") ?: 0.0

        var cuotasCompletasPagadas = 0
        for (i in 1..cuotasTotales) {
            val montoPagadoEnCuota = estadoCuotas[i] ?: 0.0
            if (montoPagadoEnCuota >= cuotaEstimada - 0.01) {
                cuotasCompletasPagadas++
            } else {
                break // Parar en la primera cuota no completamente pagada
            }
        }

        val proximaCuota = cuotasCompletasPagadas + 1 + cuotasIncremento
        return if (proximaCuota <= cuotasTotales) {
            calcularFechaCuota(fechaInicio, plazo, proximaCuota)
        } else {
            "saldado"
        }

    } catch (e: Exception) {
        Log.e("FechasFijas", "Error obteniendo próxima fecha fija: ${e.message}")
        return "pendiente"
    }
}

// ✅ FUNCIÓN AUXILIAR: Calcular próxima fecha desde un punto de anclaje fijo
private fun calcularProximaFechaDesdeAnclaje(
    fechaAnclaje: String,
    plazo: String,
    cuotasAAvanzar: Int
): String {
    return try {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val fechaBase = Calendar.getInstance().apply {
            time = dateFormat.parse(fechaAnclaje) ?: Date()
        }

        // Avanzar desde el punto de anclaje fijo
        when (plazo.lowercase()) {
            "diario" -> {
                fechaBase.add(Calendar.DAY_OF_YEAR, cuotasAAvanzar)
            }
            "lunes a sábado" -> {
                repeat(cuotasAAvanzar) {
                    do {
                        fechaBase.add(Calendar.DAY_OF_YEAR, 1)
                    } while (fechaBase.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                }
            }
            "semanal" -> {
                fechaBase.add(Calendar.DAY_OF_YEAR, cuotasAAvanzar * 7)
            }
            "quincenal" -> {
                fechaBase.add(Calendar.DAY_OF_YEAR, cuotasAAvanzar * 15)
            }
            "mensual" -> {
                fechaBase.add(Calendar.MONTH, cuotasAAvanzar)
            }
            "bimestral" -> {
                fechaBase.add(Calendar.MONTH, cuotasAAvanzar * 2)
            }
            else -> {
                fechaBase.add(Calendar.MONTH, cuotasAAvanzar)
            }
        }

        dateFormat.format(fechaBase.time)

    } catch (e: Exception) {
        Log.e("FechasFijas", "Error calculando desde anclaje: ${e.message}")
        fechaAnclaje // Retornar la fecha original como fallback
    }
}

// ✅ FUNCIÓN PARA ACTUALIZAR PRÓXIMO PAGO PROGRAMADO CON SOPORTE PARA ABONOS PARCIALES
suspend fun actualizarProximoPagoProgramado(
    db: FirebaseFirestore,
    prestamoId: String,
    cuotasIncremento: Int,
    esAbonoParcialNuevo: Boolean = false
) {
    try {
        val doc = db.collection("prestamos").document(prestamoId).get().await()
        val estado = (doc.getString("estado") ?: "activo").lowercase()
        if (estado == "saldado") return

        // Si es un abono parcial que no completa cuota, no cambiar fecha
        if (esAbonoParcialNuevo && cuotasIncremento == 0) {
            Log.d("ActualizarFecha", "Abono parcial - no se actualiza fecha programada")
            return
        }

        val proximaFecha = obtenerProximaFechaProgramadaFija(db, prestamoId, cuotasIncremento, esAbonoParcialNuevo)

        db.collection("prestamos").document(prestamoId).update(
            mapOf(
                "proximoPago" to proximaFecha,
                "estado" to "activo"
            )
        ).await()

        Log.d("ActualizarFecha", "Fecha programada actualizada a: $proximaFecha")

    } catch (e: Exception) {
        Log.e("ActualizarFecha", "Error actualizando próximo pago: ${e.message}")
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

    // ✅ VARIABLES CRÍTICAS PARA CÁLCULOS CORRECTOS CON ABONOS PARCIALES
    var montoPagadoActual by remember { mutableStateOf(0.0) }
    var saldoActualizado by remember { mutableStateOf(saldoActual) }
    var estadoCuotas by remember { mutableStateOf(mapOf<Int, Double>()) }

    // ✅ NUEVA VARIABLE PARA VISTA PREVIA DE PRÓXIMA FECHA FIJA CON ABONOS PARCIALES
    var proximaFechaVistaPrevia by remember { mutableStateOf("Calculando...") }
    var tieneAbonoParcialPrevio by remember { mutableStateOf(false) }
    var montoRestanteCuotaParcial by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        Log.d("RegistrarPagoScreen", "UID del cobrador recibido: $cobrador")

        if (cobrador.isEmpty()) {
            Toast.makeText(
                context,
                "Error: UID del cobrador no válido. Inicie sesión de nuevo.",
                Toast.LENGTH_LONG
            ).show()
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
                    Toast.makeText(
                        context,
                        "Error: No se pudo crear el documento del cliente",
                        Toast.LENGTH_LONG
                    ).show()
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

            // ✅ OBTENER FECHA PROGRAMADA ACTUAL
            proximoPagoActual = obtenerFechaProgramadaActual(db, prestamoId)

            // ✅ OBTENER ESTADO DETALLADO DE CUOTAS
            estadoCuotas = obtenerEstadoCuotasDetallado(db, prestamoId)

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

            // ✅ CAMBIO CRÍTICO: Usar la función nueva para encontrar la cuota siguiente real
            cuotaSiguiente = encontrarCuotaSiguienteReal(db, prestamoId, cuotasTotales, cuotaEstimada)

            // ✅ VERIFICAR SI LA CUOTA SIGUIENTE REAL TIENE ABONO PARCIAL
            val abonoPrevioEnCuotaSiguiente = estadoCuotas[cuotaSiguiente] ?: 0.0
            tieneAbonoParcialPrevio = abonoPrevioEnCuotaSiguiente > 0.01 && abonoPrevioEnCuotaSiguiente < cuotaEstimada - 0.01
            montoRestanteCuotaParcial = if (tieneAbonoParcialPrevio) cuotaEstimada - abonoPrevioEnCuotaSiguiente else 0.0

            // ✅ LOG MEJORADO PARA VERIFICAR CÁLCULOS
            Log.d("RegistrarPagoScreen", """
                === DATOS DEL PRÉSTAMO CORREGIDOS ===
                - Capital prestado: L. ${String.format("%.2f", montoPrestamo)}
                - Interés total: L. ${String.format("%.2f", interesTotal)}
                - TOTAL A PAGAR: L. ${String.format("%.2f", totalAPagar)}
                - Monto ya pagado: L. ${String.format("%.2f", montoPagadoActual)}
                - SALDO PENDIENTE: L. ${String.format("%.2f", saldoActualizado)}
                - Próximo pago programado: $proximoPagoActual
                - Cuota siguiente REAL: $cuotaSiguiente (no estimada)
                - Tiene abono parcial previo: $tieneAbonoParcialPrevio
                - Monto restante cuota parcial: L. ${String.format("%.2f", montoRestanteCuotaParcial)}
            """.trimIndent())

        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Error al cargar datos del préstamo: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
            Log.e("RegistrarPagoScreen", "Error al cargar datos del préstamo", e)
        }
    }

    // ✅ CALCULAR VISTA PREVIA DE PRÓXIMA FECHA CUANDO CAMBIE EL ABONO (ACTUALIZADO)
    LaunchedEffect(
        montoAbono,
        proximoPagoActual,
        tieneAbonoParcialPrevio,
        montoRestanteCuotaParcial
    ) {
        val abono = montoAbono.toDoubleOrNull() ?: 0.0
        if (abono > 0.0 && proximoPagoActual != null) {
            val (cuotasCubiertas, _) = if (cuotaEstimada > 0) {
                // ✅ USAR LA FUNCIÓN CORREGIDA
                calcularCuotasCubiertasDesdeRealInteligente(
                    abono, cuotaEstimada, saldoActualizado, estadoCuotas, cuotaSiguiente, plazo
                )
            } else {
                Pair(1, false)
            }

            // Si es abono parcial nuevo (no completa cuota), mantener fecha actual
            val esAbonoParcialNuevo = cuotasCubiertas == 0 && abono > 0.01

            if (esAbonoParcialNuevo) {
                proximaFechaVistaPrevia = proximoPagoActual ?: "Sin cambios"
            } else if (cuotasCubiertas > 0) {
                proximaFechaVistaPrevia = obtenerProximaFechaProgramadaFija(db, prestamoId, cuotasCubiertas, false)
            } else {
                proximaFechaVistaPrevia = proximoPagoActual ?: "No disponible"
            }
        } else {
            proximaFechaVistaPrevia = proximoPagoActual ?: "No disponible"
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
            // ✅ INFORMACIÓN DEL PRÉSTAMO MEJORADA CON ABONOS PARCIALES
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
                    Text("Estado: $estadoPrestamo")
                    Text("Plazo: $plazo")
                    Text("Cuota estimada: L. ${"%.2f".format(cuotaEstimada)}")
                    Text("Cuota #: $cuotaSiguiente de $cuotasTotales")
                    proximoPagoActual?.let {
                        Text(
                            "Próximo pago programado: $it",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2)
                        )
                    }

                    // ✅ NUEVA INFORMACIÓN SOBRE ABONOS PARCIALES
                    if (tieneAbonoParcialPrevio) {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            "⚠️ CUOTA $cuotaSiguiente TIENE ABONO PARCIAL",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9800)
                        )
                        val abonoPrevio = estadoCuotas[cuotaSiguiente] ?: 0.0
                        Text(
                            "Ya abonado: L. ${"%.2f".format(abonoPrevio)}",
                            color = Color(0xFF4CAF50)
                        )
                        Text(
                            "Resta por pagar: L. ${"%.2f".format(montoRestanteCuotaParcial)}",
                            color = Color(0xFFFF9800),
                            fontWeight = FontWeight.Bold
                        )
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
            PrimaryTextField(
                value = firmaPrestamista,
                onValueChange = { firmaPrestamista = it },
                label = "Firma"
            )

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

            val (cuotasCubiertas, completaCuotaParcial) = if (cuotaEstimada > 0) {
                calcularCuotasCubiertasDesdeRealInteligente(
                    abono, cuotaEstimada, saldoActualizado, estadoCuotas, cuotaSiguiente, plazo
                )
            } else {
                Pair(1, false)
            }

            // ✅ RESUMEN DEL PAGO MEJORADO CON INFORMACIÓN DE ABONOS PARCIALES
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Resumen del Pago", fontWeight = FontWeight.Bold)
                    Text("Monto a abonar: L. ${"%.2f".format(abono)}")
                    Text("Pagado anteriormente: L. ${"%.2f".format(montoPagadoActual)}")
                    Text(
                        "Nuevo total pagado: L. ${"%.2f".format(nuevoMontoPagado)}",
                        color = Color(0xFF4CAF50)
                    )
                    Text(
                        "Nuevo saldo pendiente: L. ${"%.2f".format(nuevoSaldoPendiente)}",
                        color = Color(0xFFFF5722)
                    )

                    // ✅ INFORMACIÓN MEJORADA SOBRE CUOTAS CUBIERTAS Y ABONOS PARCIALES
                    if (tieneAbonoParcialPrevio && abono > 0) {
                        if (completaCuotaParcial && cuotasCubiertas > 0) {
                            Text(
                                "✅ Completa cuota $cuotaSiguiente + ${cuotasCubiertas - 1} cuotas adicionales",
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold
                            )
                        } else if (completaCuotaParcial && cuotasCubiertas == 1) {
                            Text(
                                "✅ Completa cuota parcial $cuotaSiguiente",
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            val nuevoAbonoEnCuota = (estadoCuotas[cuotaSiguiente] ?: 0.0) + abono
                            Text(
                                "📝 Abono adicional a cuota $cuotaSiguiente (Total: L. ${"%.2f".format(nuevoAbonoEnCuota)})",
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        if (cuotasCubiertas > 0) {
                            Text("Cuotas cubiertas con este pago: $cuotasCubiertas")
                        } else if (abono > 0.01) {
                            Text(
                                "📝 Abono parcial a cuota $cuotaSiguiente",
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (nuevoSaldoPendiente == 0.0) {
                        Text(
                            "¡PRÉSTAMO SALDADO!",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    } else {
                        // ✅ MOSTRAR PRÓXIMA FECHA FIJA CON LÓGICA DE ABONOS PARCIALES
                        val esAbonoParcialNuevo = cuotasCubiertas == 0 && abono > 0.01 && !completaCuotaParcial
                        if (esAbonoParcialNuevo) {
                            Text(
                                "Próxima fecha programada: $proximaFechaVistaPrevia (SIN CAMBIO - abono parcial)",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF9800)
                            )
                            Text(
                                "(La fecha se actualiza solo cuando se completa una cuota)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF666666)
                            )
                        } else {
                            Text(
                                "Próxima fecha programada (fija): $proximaFechaVistaPrevia",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1976D2)
                            )
                            Text(
                                "(Las fechas son fijas e inamovibles)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF666666)
                            )
                        }
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
                        Toast.makeText(
                            context,
                            "Error: Sesión no válida. Por favor, reinicie la app.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@PrimaryButton
                    }

                    botonHabilitado = false
                    val fechaActual = Timestamp.now()
                    val fechaFormateada = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(fechaActual.toDate())

                    scope.launch {
                        try {
                            if (!verificarDocumentoExiste(db, "clientes", clienteId)) {
                                if (!crearDocumentoSiNoExiste(db, clienteId, nombreCliente)) {
                                    Toast.makeText(
                                        context,
                                        "❌ Error: No se pudo crear el documento del cliente",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    botonHabilitado = true
                                    return@launch
                                }
                            }

                            // ✅ RECALCULAR CUOTAS CUBIERTAS CON LÓGICA CORREGIDA
                            val (cuotasCubiertasFinal, completaCuotaParcialFinal) = calcularCuotasCubiertasDesdeRealInteligente(
                                abono,
                                cuotaEstimada,
                                saldoActualizado,
                                estadoCuotas,
                                cuotaSiguiente, // USAR LA CUOTA REAL
                                plazo
                            )

                            // ✅ DETERMINAR SI ES UN ABONO PARCIAL QUE NO COMPLETA CUOTA
                            val esAbonoParcialNuevo = cuotasCubiertasFinal == 0 && abono > 0.01 && !completaCuotaParcialFinal

                            // ✅ 1) CALCULAR PRÓXIMA FECHA USANDO EL SISTEMA DE FECHAS FIJAS CON LÓGICA DE PARCIALES
                            val proximaProgramadaFija = if (nuevoSaldoPendiente > 0.0) {
                                obtenerProximaFechaProgramadaFija(
                                    db,
                                    prestamoId,
                                    cuotasCubiertasFinal,
                                    esAbonoParcialNuevo
                                )
                            } else {
                                "saldado"
                            }

                            Log.d("RegistrarPago", """
                                === CÁLCULO DE PRÓXIMA FECHA CORREGIDO CON ABONOS PARCIALES ===
                                - Plazo: $plazo
                                - Fecha actual programada: $proximoPagoActual
                                - Cuotas cubiertas: $cuotasCubiertasFinal
                                - Completa cuota parcial: $completaCuotaParcialFinal
                                - Es abono parcial nuevo: $esAbonoParcialNuevo
                                - PRÓXIMA FECHA FIJA: $proximaProgramadaFija
                                - Sistema: Fechas fijas con soporte para abonos parciales
                            """.trimIndent())

                            // ✅ DETERMINAR SI EL PAGO ES TARDÍO
                            val pagoTardio: Boolean = run {
                                val prog = proximoPagoActual?.trim()
                                if (!prog.isNullOrEmpty() && prog.lowercase() != "saldado") {
                                    val f = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                    val fechaProg = runCatching { f.parse(prog) }.getOrNull()
                                    val hoy = Timestamp.now().toDate()

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
                                        calA.time.after(calB.time)
                                    } else false
                                } else false
                            }

                            if (pagoTardio) {
                                Log.d("PagoTardio", "⚠️ PAGO TARDÍO detectado - Fecha programada: $proximoPagoActual")
                            } else {
                                Log.d("PagoTardio", "✅ Pago a tiempo - Fecha programada: $proximoPagoActual")
                            }

                            // ✅ 2) GENERAR RECIBO CON FECHA FIJA Y LÓGICA DE ABONOS PARCIALES
                            val etiquetaCuota = when {
                                esAbonoParcialNuevo -> "$cuotaSiguiente (Abono Parcial)"
                                completaCuotaParcialFinal && cuotasCubiertasFinal > 0 -> "$cuotaSiguiente (Completa Parcial) + ${cuotasCubiertasFinal - 1} más"
                                completaCuotaParcialFinal && cuotasCubiertasFinal == 1 -> "$cuotaSiguiente (Completa Parcial)"
                                tieneAbonoParcialPrevio && !completaCuotaParcialFinal -> "$cuotaSiguiente (Abono Adicional)"
                                else -> cuotaSiguiente.toString()
                            }

                            val pdfFile = ReciboHelper.generarReciboPDF(
                                context = context,
                                cliente = nombreCliente,
                                prestamoId = "Préstamo Nº $numeroPrestamo",
                                fecha = fechaFormateada,
                                montoPagado = abono.toString(),
                                saldoAnterior = saldoActualizado,
                                proximoPago = proximaProgramadaFija, // ✅ USAR FECHA FIJA
                                cuota = etiquetaCuota, // ✅ ETIQUETA DESCRIPTIVA PARA ABONOS PARCIALES
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
                                    Toast.makeText(context, "✅ Recibo impreso correctamente", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    pdfImpreso = false
                                    Toast.makeText(context, "⚠️ Error al imprimir. Compartiendo...", Toast.LENGTH_SHORT).show()
                                }

                                ReciboHelper.compartirReciboPDF(context, pdfFile!!)
                            } else {
                                Toast.makeText(context, "❌ No se pudo generar el recibo", Toast.LENGTH_LONG).show()
                            }

                            // ✅ 3) REGISTRAR EL PAGO CON INFORMACIÓN DE ABONOS PARCIALES
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
                                "cuota" to cuotaSiguiente,
                                "numeroCuota" to cuotaSiguiente,
                                "cuotasCubiertas" to cuotasCubiertasFinal,
                                "saldoRestante" to nuevoSaldoPendiente,
                                "lugar" to lugar,
                                "firma" to firmaPrestamista,
                                "metodoPago" to metodoPago,
                                "plazo" to plazo,
                                "pdfGenerado" to pdfGenerado,
                                "pdfImpreso" to pdfImpreso,
                                "fechaProgramadaOriginal" to (proximoPagoActual ?: ""),
                                "proximaFechaProgramada" to proximaProgramadaFija, // ✅ FECHA FIJA
                                "pagoTardio" to pagoTardio,
                                // ✅ NUEVOS CAMPOS PARA ABONOS PARCIALES
                                "esAbonoParcial" to esAbonoParcialNuevo,
                                "completaCuotaParcial" to completaCuotaParcialFinal,
                                "teniaAbonoParcialPrevio" to tieneAbonoParcialPrevio,
                                "etiquetaCuota" to etiquetaCuota,
                                "observaciones" to when {
                                    esAbonoParcialNuevo -> "Abono parcial a cuota $cuotaSiguiente"
                                    completaCuotaParcialFinal -> "Completa cuota parcial $cuotaSiguiente"
                                    tieneAbonoParcialPrevio -> "Abono adicional a cuota parcial $cuotaSiguiente"
                                    else -> "Pago normal"
                                }
                            )

                            if (isInternetAvailable(context)) {
                                db.collection("pagos").add(abonoData).await()

                                // ✅ 4) ACTUALIZAR EL PRÉSTAMO CON LÓGICA DE ABONOS PARCIALES
                                if (nuevoSaldoPendiente == 0.0) {
                                    val actualizacionPrestamoSaldado = mutableMapOf<String, Any>(
                                        "saldo" to nuevoSaldoPendiente,
                                        "montoPagado" to nuevoMontoPagado,
                                        "estado" to "saldado",
                                        "proximoPago" to "saldado",
                                        "fechaUltimaActualizacion" to fechaActual,
                                        "ultimoPago" to fechaFormateada
                                    )

                                    if (pagoTardio) {
                                        actualizacionPrestamoSaldado["tienePagosTarde"] = true
                                        actualizacionPrestamoSaldado["ultimoPagoTarde"] = fechaActual
                                        actualizacionPrestamoSaldado["pagosTardeCount"] = FieldValue.increment(1)
                                    }

                                    db.collection("prestamos").document(prestamoId).update(actualizacionPrestamoSaldado).await()

                                } else {
                                    // ✅ ACTUALIZAR CON FECHA FIJA CONSIDERANDO ABONOS PARCIALES
                                    actualizarProximoPagoProgramado(db, prestamoId, cuotasCubiertasFinal, esAbonoParcialNuevo)
                                    val proximoProgramadoAnclado = obtenerFechaProgramadaActual(db, prestamoId) ?: proximaProgramadaFija

                                    val actualizacionPrestamoActivo = mutableMapOf<String, Any>(
                                        "saldo" to nuevoSaldoPendiente,
                                        "montoPagado" to nuevoMontoPagado,
                                        "estado" to "activo",
                                        "proximoPago" to proximoProgramadoAnclado,
                                        "fechaUltimaActualizacion" to fechaActual,
                                        "ultimoPago" to fechaFormateada
                                    )

                                    if (pagoTardio) {
                                        actualizacionPrestamoActivo["tienePagosTarde"] = true
                                        actualizacionPrestamoActivo["ultimoPagoTarde"] = fechaActual
                                        actualizacionPrestamoActivo["pagosTardeCount"] = FieldValue.increment(1)
                                    }

                                    db.collection("prestamos").document(prestamoId).update(actualizacionPrestamoActivo).await()
                                }

                                // ✅ ACTUALIZAR CLIENTE
                                try {
                                    val actualizacionCliente = mutableMapOf<String, Any>(
                                        "ultimaActividad" to fechaActual,
                                        "fechaUltimaActualizacion" to fechaActual
                                    )

                                    if (pagoTardio) {
                                        actualizacionCliente["tienePagosTarde"] = true
                                        actualizacionCliente["ultimoPagoTarde"] = fechaActual
                                        actualizacionCliente["pagosTardeCount"] = FieldValue.increment(1)
                                    }

                                    db.collection("clientes").document(clienteId).update(actualizacionCliente).await()
                                } catch (e: Exception) {
                                    Log.e("ActualizacionCliente", "Error actualizando cliente: ${e.message}")
                                }

                                // ✅ MOSTRAR MENSAJE APROPIADO SEGÚN EL TIPO DE PAGO
                                val mensajeExito = when {
                                    esAbonoParcialNuevo -> "✅ Abono parcial registrado correctamente"
                                    completaCuotaParcialFinal -> "✅ Cuota parcial completada correctamente"
                                    else -> "✅ Pago registrado correctamente"
                                }
                                Toast.makeText(context, mensajeExito, Toast.LENGTH_SHORT).show()

                                if (pagoTardio) {
                                    Toast.makeText(context, "⚠️ Pago registrado como TARDÍO", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                guardarAbonoPendiente(context, abonoData)
                                Toast.makeText(context, "📂 Abono guardado offline", Toast.LENGTH_LONG).show()
                            }

                            // ✅ ACTUALIZAR VARIABLES LOCALES CON NUEVA INFORMACIÓN DE ABONOS PARCIALES
                            montoPagadoActual = nuevoMontoPagado
                            saldoActualizado = nuevoSaldoPendiente
                            proximoPagoActual = if (nuevoSaldoPendiente == 0.0) "saldado" else obtenerFechaProgramadaActual(db, prestamoId)

                            // ✅ RECALCULAR LA CUOTA SIGUIENTE REAL DESPUÉS DEL PAGO
                            cuotaSiguiente = encontrarCuotaSiguienteReal(db, prestamoId, cuotasTotales, cuotaEstimada)

                            // Actualizar estado de cuotas
                            estadoCuotas = obtenerEstadoCuotasDetallado(db, prestamoId)

                            // Verificar nueva cuota parcial
                            val nuevoAbonoParcial = estadoCuotas[cuotaSiguiente] ?: 0.0
                            tieneAbonoParcialPrevio = nuevoAbonoParcial > 0.01 && nuevoAbonoParcial < cuotaEstimada - 0.01
                            montoRestanteCuotaParcial = if (tieneAbonoParcialPrevio) cuotaEstimada - nuevoAbonoParcial else 0.0

                            montoAbono = ""

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