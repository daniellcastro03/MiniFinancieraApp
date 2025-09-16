package com.example.minifinancieraapp.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassBottom
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
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

// ===================== MODELO =====================
data class CuotaAmortizacion(
    val numero: Int,
    val fecha: String,
    val capital: Double,
    val interes: Double,
    val total: Double,
    val descripcion: String = "",
    var pagado: Boolean = false
)

// ===================== FUNCIONES UNIFICADAS (IGUALES A RegistrarPago) =====================

/** Función unificada para calcular fechas de cuotas con el mismo algoritmo de RegistrarPago */
private fun calcularFechaCuota(fechaInicio: Date, plazo: String, numeroCuota: Int): String {
    val calendar = Calendar.getInstance().apply { time = fechaInicio }
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // Para la primera cuota, calcular desde la fecha de inicio
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
            calendar.add(Calendar.MONTH, numeroCuota) // Default mensual
        }
    }

    return dateFormat.format(calendar.time)
}

/** Función corregida: Lee proximoPago de manera más robusta */
private fun leerProximoPagoProgramado(
    doc: com.google.firebase.firestore.DocumentSnapshot
): String? {
    val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    return try {
        // Intentar leer como Timestamp primero (más común en Firestore)
        doc.getTimestamp("proximoPago")?.toDate()?.let {
            return fmt.format(it)
        }

        // Luego como Date
        doc.getDate("proximoPago")?.let {
            return fmt.format(it)
        }

        // Finalmente como String
        doc.getString("proximoPago")?.let {
            return it
        }

        null
    } catch (e: Exception) {
        Log.w("CuotasScreen", "Error leyendo proximoPago: ${e.message}")
        null
    }
}

// ===================== NUEVOS HELPERS REQUERIDOS =====================

// Función corregida: Lee la fecha programada "proximoPago" de manera más robusta
suspend fun obtenerFechaProgramadaActual(
    db: FirebaseFirestore,
    prestamoId: String
): String? {
    return try {
        val doc = db.collection("prestamos").document(prestamoId).get().await()
        leerProximoPagoProgramado(doc)
    } catch (e: Exception) {
        Log.e("CuotasScreen", "Error obteniendo fecha programada: ${e.message}")
        null
    }
}

// Función para encontrar la próxima cuota sin pagar
suspend fun encontrarProximaCuotaSinPagar(
    db: FirebaseFirestore,
    prestamoId: String,
    todasLasCuotas: List<CuotaAmortizacion>
): String? {
    return try {
        val cuotasSinPagar = todasLasCuotas.filter { !it.pagado && it.descripcion != "Mora" }
        if (cuotasSinPagar.isNotEmpty()) {
            val primeraCuotaSinPagar = cuotasSinPagar.minByOrNull { it.numero }
            primeraCuotaSinPagar?.fecha
        } else {
            "saldado"
        }
    } catch (e: Exception) {
        Log.e("CuotasScreen", "Error encontrando próxima cuota sin pagar: ${e.message}")
        null
    }
}

// Avanza/retrocede la fecha programada desde el valor ACTUAL (calendario anclado).
suspend fun actualizarProximoPagoProgramado(
    db: FirebaseFirestore,
    prestamoId: String,
    cuotasIncremento: Int
) {
    try {
        val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val doc = db.collection("prestamos").document(prestamoId).get().await()
        val estado = (doc.getString("estado") ?: "activo").lowercase()
        if (estado == "saldado") return

        val plazo = (doc.getString("plazo") ?: "semanal")
        val proximo = obtenerFechaProgramadaActual(db, prestamoId) ?: return
        if (proximo == "saldado") return

        // base = fecha programada actual (¡no hoy!)
        val base = Calendar.getInstance().apply {
            time = try {
                fmt.parse(proximo) ?: Date()
            } catch (e: Exception) {
                Date()
            }
        }

        fun addByPlazo(c: Calendar, periods: Int) {
            when (plazo.lowercase()) {
                "diario" -> c.add(Calendar.DAY_OF_YEAR, periods)
                "lunes a sábado" -> repeat(periods) {
                    do { c.add(Calendar.DAY_OF_YEAR, 1) }
                    while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                }
                "semanal" -> c.add(Calendar.DAY_OF_YEAR, periods * 7)
                "quincenal" -> c.add(Calendar.DAY_OF_YEAR, periods * 15)
                "mensual" -> c.add(Calendar.MONTH, periods)
                "bimestral" -> c.add(Calendar.MONTH, periods * 2)
                else -> c.add(Calendar.MONTH, periods)
            }
        }

        addByPlazo(base, cuotasIncremento)

        db.collection("prestamos").document(prestamoId).update(
            mapOf(
                "proximoPago" to fmt.format(base.time),
                "estado" to "activo"
            )
        ).await()
    } catch (e: Exception) {
        Log.e("CuotasScreen", "Error actualizando próximo pago: ${e.message}")
    }
}

// ===================== UI =====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuotasPrestamoScreen(prestamoId: String, navController: NavController, uid: String, rol: String) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val dec = DecimalFormat("#,##0.00")

    var cuotas by remember { mutableStateOf(listOf<CuotaAmortizacion>()) }
    var cargando by remember { mutableStateOf(true) }
    var esActivo by remember { mutableStateOf(true) }
    var estaSaldado by remember { mutableStateOf(false) }

    // Estado para fechas reales de pago por cuota
    var fechasPagoPorCuota by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    val fmtPago = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    var totalCapital by remember { mutableStateOf(0.0) }
    var totalInteres by remember { mutableStateOf(0.0) }
    var moraAplicada by remember { mutableStateOf(0.0) }
    var moraPagada by remember { mutableStateOf(false) }
    var nombreCobrador by remember { mutableStateOf("") }
    var nombreCliente by remember { mutableStateOf("") }
    var descripcionPlazo by remember { mutableStateOf("") }
    var proximoPagoProgramado by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(prestamoId) {
        cargando = true
        try {
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
            val estado = prestamoDoc.getString("estado") ?: "activo"
            esActivo = estado == "activo"

            nombreCliente = prestamoDoc.getString("cliente") ?: "Cliente"
            val monto = prestamoDoc.getDouble("monto") ?: 0.0
            val cuotasNum = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
            val plazo = prestamoDoc.getString("plazo") ?: "Mensual"
            val fechaTimestamp = prestamoDoc.getTimestamp("fecha")
            val fechaInicio = fechaTimestamp?.toDate() ?: Date()

            // Puede venir en distintos campos; normalizamos
            val interesTotal = prestamoDoc.getDouble("interes") ?: prestamoDoc.getDouble("interesTotal") ?: 0.0
            totalCapital = monto
            totalInteres = interesTotal

            // Normalizar texto de plazo para helpers (igual que RegistrarPago)
            val plazoNormalizado = plazo.lowercase()

            // Descripción coherente con el cálculo
            descripcionPlazo = when (plazoNormalizado) {
                "diario" -> "Diario (incluye domingos)"
                "lunes a sábado" -> "Lunes a Sábado (sin domingos)"
                "semanal" -> "Semanal (cada 7 días)"
                "quincenal" -> "Quincenal (cada 15 días)"
                "mensual" -> "Mensual (cada mes calendario)"
                "bimestral" -> "Bimestral (cada 2 meses calendario)"
                else -> plazo
            }

            // Usar la función corregida para obtener fecha programada
            proximoPagoProgramado = obtenerFechaProgramadaActual(db, prestamoId)

            Log.d("CuotasScreen", """
                === DATOS DEL PRÉSTAMO ===
                - Cliente: $nombreCliente
                - Capital: L. ${String.format("%.2f", monto)}
                - Interés total: L. ${String.format("%.2f", interesTotal)}
                - TOTAL A PAGAR: L. ${String.format("%.2f", monto + interesTotal)}
                - Plazo: $plazo ($plazoNormalizado)
                - Cuotas: $cuotasNum
                - Fecha inicio: ${formatter.format(fechaInicio)}
                - Próximo pago programado: $proximoPagoProgramado
            """.trimIndent())

            // Plan de cuotas usando la función unificada
            val capitalPorCuota = if (cuotasNum > 0) monto / cuotasNum else 0.0
            val interesPorCuota = if (cuotasNum > 0) totalInteres / cuotasNum else 0.0

            // Evitar errores por acumulación de decimales
            val capitalEntero = capitalPorCuota.toInt()
            val capitalResiduo = monto - (capitalEntero * cuotasNum)

            val interesEntero = interesPorCuota.toInt()
            val interesResiduo = totalInteres - (interesEntero * cuotasNum)

            val planCuotas = mutableListOf<CuotaAmortizacion>()
            for (i in 0 until cuotasNum) {
                val capitalCuota = if (i == cuotasNum - 1) capitalEntero + capitalResiduo else capitalEntero.toDouble()
                val interesCuota = if (i == cuotasNum - 1) interesEntero + interesResiduo else interesEntero.toDouble()

                // Usar la función unificada para calcular fechas
                val fechaCuota = calcularFechaCuota(fechaInicio, plazoNormalizado, i + 1)

                planCuotas.add(
                    CuotaAmortizacion(
                        numero = i + 1,
                        fecha = fechaCuota,
                        capital = capitalCuota,
                        interes = interesCuota,
                        total = capitalCuota + interesCuota
                    )
                )
            }

            // === Leer pagos y marcar cuotas pagadas (compatible con ambos campos) ===
            val pagosSnapshot = db.collection("pagos")
                .whereEqualTo("prestamoId", prestamoId)
                .get().await()

            val cuotasPagadasSet = mutableSetOf<Int>()
            val fechasPagoTemporal = mutableMapOf<Int, String>()

            for (pago in pagosSnapshot.documents) {
                // Compatible con ambos nombres de campo
                val numeroCuota = when {
                    pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                    pago.contains("cuota") -> pago.getLong("cuota")?.toInt() ?: 1
                    else -> 1
                }
                val cuotasCubiertas = pago.getLong("cuotasCubiertas")?.toInt() ?: 1

                // Extraer fecha real de pago y guardar en el mapa
                val fechaPagoStr: String? = when (val fp = pago.get("fechaPago")) {
                    is Timestamp -> fmtPago.format(fp.toDate())
                    is Date -> fmtPago.format(fp)
                    is String -> fp
                    else -> null
                }

                // Marcar todas las cuotas cubiertas por este pago
                for (i in 0 until cuotasCubiertas) {
                    val numCuota = numeroCuota + i
                    cuotasPagadasSet.add(numCuota)

                    // Registrar la misma fecha para todas las cuotas cubiertas por ese pago
                    fechaPagoStr?.let { f ->
                        fechasPagoTemporal[numCuota] = f
                    }
                }

                val mora = pago.getDouble("mora") ?: 0.0
                if (mora > 0.0) moraPagada = true
            }

            fechasPagoPorCuota = fechasPagoTemporal

            Log.d("CuotasScreen", "Cuotas marcadas como pagadas: ${cuotasPagadasSet.sorted()}")
            Log.d("CuotasScreen", "Fechas de pago registradas: $fechasPagoPorCuota")

            // Aplicar estado pagado
            cuotas = planCuotas.map { c -> c.copy(pagado = cuotasPagadasSet.contains(c.numero)) }

            // Manejo de mora (igual que antes)
            val moraValor = prestamoDoc.getDouble("mora") ?: 0.0
            val moraActiva = moraValor > 0.0 && !moraPagada
            moraAplicada = if (moraActiva) moraValor else 0.0

            if (moraActiva) {
                cuotas = cuotas + CuotaAmortizacion(
                    numero = cuotas.size + 1,
                    fecha = "Aplicada (mora)",
                    capital = 0.0,
                    interes = 0.0,
                    total = moraValor,
                    descripcion = "Mora",
                    pagado = moraPagada
                )
            }

            // Verificar si está saldado
            val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
            val todasPagadas = cuotasNormales.all { it.pagado }
            val moraCobrada = moraAplicada == 0.0 || moraPagada
            estaSaldado = todasPagadas && moraCobrada

            if (estaSaldado && esActivo) {
                db.collection("prestamos").document(prestamoId).update("estado", "saldado").await()
                esActivo = false
            }

            // Nombre del cobrador
            val usuarioDoc = db.collection("usuarios").document(uid).get().await()
            nombreCobrador = usuarioDoc.getString("nombre") ?: uid

            Log.d("CuotasScreen", """
                === RESUMEN FINAL ===
                - Cuotas totales: ${cuotas.filter { it.descripcion != "Mora" }.size}
                - Cuotas pagadas: ${cuotas.filter { it.descripcion != "Mora" && it.pagado }.size}
                - Mora aplicada: L. ${String.format("%.2f", moraAplicada)}
                - Mora pagada: $moraPagada
                - Estado saldado: $estaSaldado
            """.trimIndent())

        } catch (e: Exception) {
            Log.e("CuotasScreen", "Error al cargar datos: ${e.message}", e)
            Toast.makeText(context, "Error al cargar datos: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            cargando = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tabla de Amortización", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0061A7))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            if (cargando) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // ---- Cabecera ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Información del Préstamo", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Cliente: $nombreCliente")
                        Text("Tipo de plazo: $descripcionPlazo")
                        proximoPagoProgramado?.let {
                            Text(
                                "Próximo pago programado: $it",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1976D2)
                            )
                        }
                        Text("Número de cuotas: ${cuotas.filter { it.descripcion != "Mora" }.size}")
                        Text("Capital: L. ${dec.format(totalCapital)}")
                        Text("Interés Total: L. ${dec.format(totalInteres)}")

                        val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                        val cuotasPagadas = cuotasNormales.count { it.pagado }
                        val totalCuotas = cuotasNormales.size
                        Text(
                            "Progreso: $cuotasPagadas de $totalCuotas cuotas pagadas",
                            fontWeight = FontWeight.Medium,
                            color = if (cuotasPagadas == totalCuotas) Color(0xFF4CAF50) else Color(0xFF1976D2)
                        )

                        if (moraAplicada > 0.0) {
                            Text(
                                "Mora aplicada: L. ${dec.format(moraAplicada)}",
                                color = if (moraPagada) Color(0xFF4CAF50) else Color.Red,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            "Total a pagar: L. ${dec.format(totalCapital + totalInteres + moraAplicada)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ---- Exportar PDF ----
                Button(
                    onClick = {
                        val pdfFile = ReciboHelper.generarCuotasPDF(
                            context = context,
                            cliente = nombreCliente,
                            prestamoId = prestamoId,
                            cuotas = cuotas,
                            totalCapital = totalCapital,
                            totalInteres = totalInteres,
                            mora = moraAplicada,
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text("Exportar Cuotas en PDF", color = Color.White)
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (estaSaldado) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFD4F6D4))) {
                        Text(
                            "Este préstamo está completamente saldado",
                            modifier = Modifier.padding(16.dp),
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ---- Lista de cuotas ----
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cuotas) { cuota ->
                        var mostrarDialogo by remember { mutableStateOf(false) }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (cuota.pagado) Color(0xFFD0F0C0) else Color.White
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Cuota ${cuota.numero}" + if (cuota.descripcion.isNotEmpty()) " (${cuota.descripcion})" else "",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (cuota.pagado) Icons.Default.CheckCircle else Icons.Default.HourglassBottom,
                                            contentDescription = null,
                                            tint = if (cuota.pagado) Color(0xFF388E3C) else Color.Gray
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            if (cuota.pagado) "Pagado" else "Pendiente",
                                            color = if (cuota.pagado) Color(0xFF388E3C) else Color.Gray,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                if (cuota.descripcion != "Mora") Text("Fecha: ${cuota.fecha}")

                                // Mostrar fecha real de pago si la cuota está pagada
                                if (cuota.pagado) {
                                    val fechaReal = fechasPagoPorCuota[cuota.numero]
                                    if (!fechaReal.isNullOrBlank()) {
                                        Text(
                                            "Pagado el: $fechaReal",
                                            color = Color(0xFF388E3C),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                if (cuota.capital > 0) Text("Capital: L. ${dec.format(cuota.capital)}")
                                if (cuota.interes > 0) Text("Interés: L. ${dec.format(cuota.interes)}")
                                Text(
                                    "Total: L. ${dec.format(cuota.total)}",
                                    fontWeight = FontWeight.Bold,
                                    color = if (cuota.descripcion == "Mora") Color.Red else Color.Black
                                )

                                // ---- Marcar manualmente como pagada (Admin) ----
                                if (!cuota.pagado && esActivo && rol == "admin") {
                                    Button(
                                        onClick = { mostrarDialogo = true },
                                        modifier = Modifier.padding(top = 8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A7))
                                    ) { Text("Marcar como pagada (Admin)", color = Color.White) }

                                    if (mostrarDialogo) {
                                        AlertDialog(
                                            onDismissRequest = { mostrarDialogo = false },
                                            title = { Text("Confirmar acción") },
                                            text = { Text("¿Marcar esta cuota como pagada manualmente?\nSe registrará un pago 0.0 para control.") },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    mostrarDialogo = false

                                                    scope.launch {
                                                        try {
                                                            withContext(Dispatchers.IO) {
                                                                val abonoManual = mapOf(
                                                                    "prestamoId" to prestamoId,
                                                                    "monto" to 0.0,
                                                                    "mora" to if (cuota.descripcion == "Mora") cuota.total else 0.0,
                                                                    "fechaPago" to Timestamp.now(),
                                                                    "registradoPor" to nombreCobrador,
                                                                    // Guardamos con ambos nombres para máxima compatibilidad:
                                                                    "numeroCuota" to cuota.numero,
                                                                    "cuota" to cuota.numero,
                                                                    "cuotasCubiertas" to 1,
                                                                    "saldoRestante" to "manual",
                                                                    "lugar" to "Marcado manualmente",
                                                                    "firma" to nombreCobrador,
                                                                    "metodoPago" to "Manual (Admin)",
                                                                    "clienteNombre" to nombreCliente,
                                                                    "observaciones" to "Marcado manualmente por administrador"
                                                                )

                                                                val batch = db.batch()
                                                                val pagosRef = db.collection("pagos").document()
                                                                val historialRef = db.collection("historial").document()
                                                                val historialGlobalRef = db.collection("historialGlobal").document()

                                                                batch.set(pagosRef, abonoManual)
                                                                batch.set(historialRef, abonoManual)
                                                                batch.set(historialGlobalRef, abonoManual)

                                                                batch.commit().await()
                                                            }

                                                            Toast.makeText(context, "Cuota marcada como pagada", Toast.LENGTH_SHORT).show()
                                                            cuotas = cuotas.map { if (it.numero == cuota.numero) it.copy(pagado = true) else it }

                                                            // Registrar fecha de pago en UI y actualizar último pago
                                                            val hoyStr = fmtPago.format(Date())

                                                            // Reflejar en UI la fecha de pago de esa cuota marcada
                                                            fechasPagoPorCuota = fechasPagoPorCuota + mapOf(cuota.numero to hoyStr)

                                                            // Actualizar el campo ultimoPago del préstamo
                                                            scope.launch {
                                                                withContext(Dispatchers.IO) {
                                                                    db.collection("prestamos").document(prestamoId)
                                                                        .update("ultimoPago", hoyStr).await()

                                                                    // Encontrar la próxima cuota sin pagar
                                                                    val cuotasActualizadas = cuotas.map { if (it.numero == cuota.numero) it.copy(pagado = true) else it }
                                                                    val proximaFecha = encontrarProximaCuotaSinPagar(db, prestamoId, cuotasActualizadas)

                                                                    // Actualizar próximo pago
                                                                    if (proximaFecha != null) {
                                                                        db.collection("prestamos").document(prestamoId)
                                                                            .update("proximoPago", proximaFecha).await()
                                                                        proximoPagoProgramado = proximaFecha
                                                                    }
                                                                }
                                                            }

                                                            val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                                                            val todasPagadas = cuotasNormales.all { it.pagado }
                                                            val moraCobrada = moraAplicada == 0.0 || cuotas.any { it.descripcion == "Mora" && it.pagado }

                                                            if (todasPagadas && moraCobrada && esActivo) {
                                                                scope.launch {
                                                                    withContext(Dispatchers.IO) {
                                                                        db.collection("prestamos").document(prestamoId)
                                                                            .update("estado", "saldado").await()
                                                                    }
                                                                    estaSaldado = true
                                                                    esActivo = false
                                                                }
                                                            }

                                                        } catch (e: Exception) {
                                                            Log.e("CuotasScreen", "Error al marcar pago: ${e.message}", e)
                                                            Toast.makeText(context, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
                                                        }
                                                    }

                                                }) { Text("Confirmar", color = Color.Red) }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { mostrarDialogo = false }) { Text("Cancelar") }
                                            }
                                        )
                                    }
                                }

                                // ---- Deshacer pago (Admin) ----
                                if (cuota.pagado && esActivo && rol == "admin") {
                                    var mostrarDialogoDeshacer by remember { mutableStateOf(false) }

                                    Button(
                                        onClick = { mostrarDialogoDeshacer = true },
                                        modifier = Modifier.padding(top = 8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                                    ) { Text("Deshacer pago (Admin)", color = Color.White) }

                                    if (mostrarDialogoDeshacer) {
                                        AlertDialog(
                                            onDismissRequest = { mostrarDialogoDeshacer = false },
                                            title = { Text("Confirmar acción") },
                                            text = { Text("Esto eliminará los registros de pago de esta cuota y restaurará el saldo.") },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    mostrarDialogoDeshacer = false

                                                    scope.launch {
                                                        try {
                                                            withContext(Dispatchers.IO) {
                                                                // Buscar pagos por ambos nombres de campo para compatibilidad total
                                                                val pagosQuery1 = db.collection("pagos")
                                                                    .whereEqualTo("prestamoId", prestamoId)
                                                                    .whereEqualTo("numeroCuota", cuota.numero)
                                                                    .get().await()
                                                                val pagosQuery2 = db.collection("pagos")
                                                                    .whereEqualTo("prestamoId", prestamoId)
                                                                    .whereEqualTo("cuota", cuota.numero)
                                                                    .get().await()

                                                                val pagosAEliminar = (pagosQuery1.documents + pagosQuery2.documents).distinctBy { it.id }
                                                                val batch = db.batch()
                                                                var montoRestaurado = 0.0

                                                                pagosAEliminar.forEach { pago ->
                                                                    val monto = pago.getDouble("monto") ?: 0.0
                                                                    montoRestaurado += monto
                                                                    batch.delete(pago.reference)
                                                                    Log.d("CuotasScreen", "Eliminando pago: ${pago.id}, monto: $monto")
                                                                }

                                                                // Eliminar de historial e historialGlobal
                                                                val hist1 = db.collection("historial")
                                                                    .whereEqualTo("prestamoId", prestamoId)
                                                                    .whereEqualTo("numeroCuota", cuota.numero)
                                                                    .get().await()
                                                                val hist2 = db.collection("historial")
                                                                    .whereEqualTo("prestamoId", prestamoId)
                                                                    .whereEqualTo("cuota", cuota.numero)
                                                                    .get().await()
                                                                (hist1.documents + hist2.documents).distinctBy { it.id }.forEach { batch.delete(it.reference) }

                                                                val glob1 = db.collection("historialGlobal")
                                                                    .whereEqualTo("prestamoId", prestamoId)
                                                                    .whereEqualTo("numeroCuota", cuota.numero)
                                                                    .get().await()
                                                                val glob2 = db.collection("historialGlobal")
                                                                    .whereEqualTo("prestamoId", prestamoId)
                                                                    .whereEqualTo("cuota", cuota.numero)
                                                                    .get().await()
                                                                (glob1.documents + glob2.documents).distinctBy { it.id }.forEach { batch.delete(it.reference) }

                                                                // Restaurar saldo del préstamo y recalcular próxima fecha
                                                                val prestamoRef = db.collection("prestamos").document(prestamoId)
                                                                val prestamoSnap = prestamoRef.get().await()
                                                                val saldoActual = prestamoSnap.getDouble("saldo") ?: 0.0
                                                                val montoPagadoActual = prestamoSnap.getDouble("montoPagado") ?: 0.0

                                                                val nuevoSaldo = saldoActual + montoRestaurado
                                                                val nuevoMontoPagado = (montoPagadoActual - montoRestaurado).coerceAtLeast(0.0)

                                                                // Encontrar la próxima cuota sin pagar después de deshacer este pago
                                                                val cuotasActualizadas = cuotas.map { if (it.numero == cuota.numero) it.copy(pagado = false) else it }
                                                                val cuotasSinPagar = cuotasActualizadas.filter { !it.pagado && it.descripcion != "Mora" }
                                                                val nuevaProximaFecha = if (cuotasSinPagar.isNotEmpty()) {
                                                                    val primeraCuotaSinPagar = cuotasSinPagar.minByOrNull { it.numero }
                                                                    primeraCuotaSinPagar?.fecha ?: "pendiente"
                                                                } else {
                                                                    "saldado"
                                                                }

                                                                val actualizacionPrestamo = mapOf(
                                                                    "saldo" to nuevoSaldo,
                                                                    "montoPagado" to nuevoMontoPagado,
                                                                    "estado" to if (nuevoSaldo > 0.0) "activo" else "saldado",
                                                                    "proximoPago" to nuevaProximaFecha,
                                                                    "fechaUltimaActualizacion" to Timestamp.now()
                                                                )

                                                                batch.update(prestamoRef, actualizacionPrestamo)
                                                                batch.commit().await()

                                                                Log.d("CuotasScreen", """
                                                                    PAGO DESHECHO EXITOSAMENTE:
                                                                    - Monto restaurado: L. ${String.format("%.2f", montoRestaurado)}
                                                                    - Nuevo saldo: L. ${String.format("%.2f", nuevoSaldo)}
                                                                    - Nuevo monto pagado: L. ${String.format("%.2f", nuevoMontoPagado)}
                                                                    - Nueva próxima fecha: $nuevaProximaFecha
                                                                """.trimIndent())
                                                            }

                                                            // Limpiar fecha de pago de esa cuota del mapa
                                                            fechasPagoPorCuota = fechasPagoPorCuota - cuota.numero

                                                            // Actualizar próximo pago programado
                                                            scope.launch {
                                                                withContext(Dispatchers.IO) {
                                                                    proximoPagoProgramado = obtenerFechaProgramadaActual(db, prestamoId)
                                                                }
                                                            }

                                                            Toast.makeText(context, "Pago deshecho correctamente", Toast.LENGTH_SHORT).show()
                                                            cuotas = cuotas.map { if (it.numero == cuota.numero) it.copy(pagado = false) else it }
                                                            if (cuota.descripcion == "Mora") moraPagada = false

                                                            // Verificar si ya no está saldado
                                                            val cuotasActualizadas = cuotas.map { if (it.numero == cuota.numero) it.copy(pagado = false) else it }
                                                            val cuotasNormales = cuotasActualizadas.filter { it.descripcion != "Mora" }
                                                            val todasPagadasDespues = cuotasNormales.all { it.pagado }

                                                            if (estaSaldado && !todasPagadasDespues) {
                                                                estaSaldado = false
                                                                esActivo = true
                                                            }

                                                        } catch (e: Exception) {
                                                            Log.e("CuotasScreen", "Error al deshacer pago: ${e.message}", e)
                                                            Toast.makeText(context, "Error al deshacer: ${e.message}", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }) { Text("Sí, deshacer", color = Color.Red) }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { mostrarDialogoDeshacer = false }) { Text("Cancelar") }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ---- Botones de navegación ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Regresar")
                    }

                    Button(
                        onClick = {
                            // Navegar de vuelta a registrar pago si es necesario
                            navController.popBackStack()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) {
                        Text("Registrar Pago", color = Color.White)
                    }
                }
            }
        }
    }
}