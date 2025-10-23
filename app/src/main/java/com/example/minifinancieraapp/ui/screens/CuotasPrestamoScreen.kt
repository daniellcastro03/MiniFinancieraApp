package com.example.minifinancieraapp.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.AttachMoney
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
    val estaCompleta: Boolean get() = montoPagado >= total - 0.01
}

// ===================== FUNCIONES PARA NUEVA LÓGICA DE CASCADA =====================

// Función para calcular fechas (mantenida igual)
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

// Nueva función adaptada para leer la estructura de pagos en cascada
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

        Log.d("EstadoCuotasCascada", "Procesando ${pagosSnapshot.documents.size} pagos")

        for (pago in pagosSnapshot.documents) {
            val fechaPagoStr: String? = when (val fp = pago.get("fechaPago")) {
                is Timestamp -> fmtPago.format(fp.toDate())
                is Date -> fmtPago.format(fp)
                is String -> fp
                else -> null
            }

            // Nueva estructura con pagos en cascada
            val cuotasCubiertas = pago.get("cuotasCubiertas") as? List<*>

            if (cuotasCubiertas != null && cuotasCubiertas.isNotEmpty()) {
                // Estructura nueva: lista de cuotas cubiertas
                cuotasCubiertas.forEach { cuotaData ->
                    if (cuotaData is Map<*, *>) {
                        val numeroCuota = (cuotaData["numeroCuota"] as? Number)?.toInt() ?: 0
                        val montoAplicado = (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0

                        if (numeroCuota > 0 && montoAplicado > 0) {
                            informacionCuotas.getOrPut(numeroCuota) { mutableListOf() }
                                .add(Pair(montoAplicado, fechaPagoStr ?: "Sin fecha"))

                            Log.d("EstadoCuotasCascada", "Cuota $numeroCuota: +L. ${String.format("%.2f", montoAplicado)}")
                        }
                    }
                }
            } else {
                // Compatibilidad con estructura anterior
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

                    Log.d("EstadoCuotasCascada", "Cuota $numeroCuota (legacy): +L. ${String.format("%.2f", montoTotal)}")
                }
            }
        }

        // Consolidar información por cuota
        val resultadoFinal = mutableMapOf<Int, CuotaInfo>()
        informacionCuotas.forEach { (numeroCuota, pagos) ->
            val montoTotalPagado = pagos.sumOf { it.first }
            val fechasHistorial = pagos.map { it.second }.distinct()
            val ultimaFechaPago = fechasHistorial.lastOrNull()

            // Crear CuotaInfo temporal (se completará con datos del préstamo después)
            resultadoFinal[numeroCuota] = CuotaInfo(
                numero = numeroCuota,
                fecha = "", // Se llenará después
                capital = 0.0, // Se llenará después
                interes = 0.0, // Se llenará después
                total = 0.0, // Se llenará después
                montoPagado = montoTotalPagado,
                fechaPago = ultimaFechaPago,
                historialPagos = fechasHistorial
            )
        }

        Log.d("EstadoCuotasCascada", "Procesadas ${resultadoFinal.size} cuotas con pagos")
        resultadoFinal

    } catch (e: Exception) {
        Log.e("EstadoCuotasCascada", "Error obteniendo estado de cuotas: ${e.message}", e)
        emptyMap()
    }
}

// Función para generar plan de cuotas base y aplicar estado de pagos
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
        // Generar plan base de cuotas
        val capitalPorCuota = if (cuotasTotales > 0) montoPrestamo / cuotasTotales else 0.0
        val interesPorCuota = if (cuotasTotales > 0) interesTotal / cuotasTotales else 0.0

        val capitalEntero = capitalPorCuota.toInt()
        val capitalResiduo = montoPrestamo - (capitalEntero * cuotasTotales)
        val interesEntero = interesPorCuota.toInt()
        val interesResiduo = interesTotal - (interesEntero * cuotasTotales)

        val planBase = mutableListOf<CuotaInfo>()
        for (i in 0 until cuotasTotales) {
            val capitalCuota = if (i == cuotasTotales - 1) capitalEntero + capitalResiduo else capitalEntero.toDouble()
            val interesCuota = if (i == cuotasTotales - 1) interesEntero + interesResiduo else interesEntero.toDouble()
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

        // Obtener estado de pagos y aplicarlo
        val estadoPagos = obtenerEstadoCuotasConCascada(db, prestamoId)

        // Combinar plan base con estado de pagos
        planBase.map { cuotaBase ->
            val estadoPago = estadoPagos[cuotaBase.numero]
            if (estadoPago != null) {
                cuotaBase.copy(
                    montoPagado = estadoPago.montoPagado,
                    pagada = estadoPago.montoPagado >= cuotaBase.total - 0.01,
                    fechaPago = estadoPago.fechaPago,
                    historialPagos = estadoPago.historialPagos
                )
            } else {
                cuotaBase
            }
        }

    } catch (e: Exception) {
        Log.e("GenerarPlanCuotas", "Error generando plan de cuotas: ${e.message}", e)
        emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuotasPrestamoScreen(prestamoId: String, navController: NavController, uid: String, rol: String) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val dec = DecimalFormat("#,##0.00")

    // Estados principales
    var cuotas by remember { mutableStateOf(listOf<CuotaInfo>()) }
    var cargando by remember { mutableStateOf(true) }
    var esActivo by remember { mutableStateOf(true) }
    var estaSaldado by remember { mutableStateOf(false) }
    var errorCarga by remember { mutableStateOf<String?>(null) }

    // Estados del préstamo
    var totalCapital by remember { mutableStateOf(0.0) }
    var totalInteres by remember { mutableStateOf(0.0) }
    var moraAplicada by remember { mutableStateOf(0.0) }
    var nombreCobrador by remember { mutableStateOf("") }
    var nombreCliente by remember { mutableStateOf("") }
    var descripcionPlazo by remember { mutableStateOf("") }
    var proximoPagoProgramado by remember { mutableStateOf<String?>(null) }
    var numeroPrestamo by remember { mutableStateOf(0) }

    // Función de recarga simplificada para nueva lógica
    suspend fun recargarDatosCompletos() {
        try {
            Log.d("CuotasScreenCascada", "=== RECARGANDO CON NUEVA LÓGICA DE CASCADA ===")

            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()

            val monto = prestamoDoc.getDouble("monto") ?: 0.0
            val cuotasNum = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
            val plazo = prestamoDoc.getString("plazo") ?: "Mensual"
            val fechaTimestamp = prestamoDoc.getTimestamp("fecha")
            val fechaInicio = fechaTimestamp?.toDate() ?: Date()
            val interesTotal = prestamoDoc.getDouble("interesTotal") ?: prestamoDoc.getDouble("interes") ?: 0.0

            totalCapital = monto
            totalInteres = interesTotal

            // Normalizar descripción del plazo
            descripcionPlazo = when (plazo.lowercase()) {
                "diario" -> "Diario (incluye domingos)"
                "lunes a sábado" -> "Lunes a Sábado (sin domingos)"
                "semanal" -> "Semanal (cada 7 días)"
                "quincenal" -> "Quincenal (cada 15 días)"
                "mensual" -> "Mensual (cada mes calendario)"
                "bimestral" -> "Bimestral (cada 2 meses calendario)"
                else -> plazo
            }

            // Generar plan de cuotas con nueva lógica
            cuotas = generarPlanCuotasConEstado(
                db = db,
                prestamoId = prestamoId,
                montoPrestamo = monto,
                interesTotal = interesTotal,
                cuotasTotales = cuotasNum,
                fechaInicio = fechaInicio,
                plazo = plazo.lowercase()
            )

            // Obtener fecha programada actual (manejar diferentes tipos)
            proximoPagoProgramado = when (val proximoPago = prestamoDoc.get("proximoPago")) {
                is Timestamp -> formatter.format(proximoPago.toDate())
                is Date -> formatter.format(proximoPago)
                is String -> proximoPago
                else -> null
            }

            // Manejar mora (simplificado)
            val moraValor = prestamoDoc.getDouble("mora") ?: 0.0
            val moraActiva = moraValor > 0.0
            moraAplicada = if (moraActiva) moraValor else 0.0

            if (moraActiva) {
                cuotas = cuotas + CuotaInfo(
                    numero = cuotas.size + 1,
                    fecha = "Aplicada (mora)",
                    capital = 0.0,
                    interes = 0.0,
                    total = moraValor,
                    descripcion = "Mora",
                    pagada = false // La mora siempre aparece como pendiente hasta que se pague
                )
            }

            // Verificar si está saldado (simplificado)
            val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
            val todasPagadas = cuotasNormales.all { it.estaCompleta }
            val moraCobrada = moraAplicada == 0.0 || cuotas.find { it.descripcion == "Mora" }?.estaCompleta == true
            estaSaldado = todasPagadas && moraCobrada

            Log.d("CuotasScreenCascada", """
                ✅ DATOS RECARGADOS CON LÓGICA DE CASCADA:
                - Cuotas totales: ${cuotasNormales.size}
                - Completamente pagadas: ${cuotasNormales.count { it.estaCompleta }}
                - Próxima fecha programada: $proximoPagoProgramado
                - Estado saldado: $estaSaldado
                - Sistema: Pagos en cascada sin abonos parciales
            """.trimIndent())

        } catch (e: Exception) {
            Log.e("CuotasScreenCascada", "Error recargando datos: ${e.message}", e)
            throw e
        }
    }

    // Carga inicial
    LaunchedEffect(prestamoId) {
        cargando = true
        errorCarga = null

        try {
            Log.d("CuotasScreenCascada", "=== INICIANDO CARGA CON LÓGICA DE CASCADA ===")

            // Verificar que el préstamo existe
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()

            if (!prestamoDoc.exists()) {
                throw Exception("El préstamo no existe o no tienes permisos para verlo")
            }

            val eliminado = prestamoDoc.getBoolean("eliminado") ?: false
            if (eliminado) {
                throw Exception("Este préstamo ha sido eliminado")
            }

            // Verificar permisos según rol
            if (rol == "cobrador") {
                val cobradoresAsignados = prestamoDoc.get("cobradoresAsignados") as? List<*> ?: emptyList<String>()
                val cobradoresString = cobradoresAsignados.mapNotNull { it as? String }

                if (!cobradoresString.contains(uid)) {
                    throw Exception("No tienes permisos para ver este préstamo")
                }
            }

            val estado = prestamoDoc.getString("estado") ?: "activo"
            esActivo = estado == "activo"

            nombreCliente = prestamoDoc.getString("cliente") ?: "Cliente"
            numeroPrestamo = prestamoDoc.getLong("numeroPrestamo")?.toInt() ?: 0

            // Obtener nombre del usuario
            val usuarioDoc = db.collection("usuarios").document(uid).get().await()
            nombreCobrador = usuarioDoc.getString("nombre") ?: uid

            // Cargar datos completos
            recargarDatosCompletos()

        } catch (e: Exception) {
            Log.e("CuotasScreenCascada", "Error al cargar datos: ${e.message}", e)
            errorCarga = e.message ?: "Error desconocido"
        } finally {
            cargando = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Cuotas - Sistema Cascada",
                        color = Color.White,
                        fontSize = 18.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0061A7))
            )
        }
    ) { padding ->
        when {
            cargando -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Error",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = Color(0xFFD32F2F)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                errorCarga!!,
                                color = Color(0xFFD32F2F),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { navController.popBackStack() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                            ) {
                                Text("Volver", color = Color.White)
                            }
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    // INFORMACIÓN GENERAL DEL PRÉSTAMO
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                // Título de la sección
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Color(0xFF0061A7)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Información del Préstamo",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0061A7)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Información en dos columnas
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    // Columna izquierda
                                    Column(modifier = Modifier.weight(1f)) {
                                        InfoRow(Icons.Default.Person, "Cliente", nombreCliente)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        InfoRow(Icons.Default.DateRange, "Modalidad", descripcionPlazo)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        InfoRow(Icons.Default.AttachMoney, "Capital", "L. ${dec.format(totalCapital)}")
                                    }

                                    Spacer(modifier = Modifier.width(16.dp))

                                    // Columna derecha
                                    Column(modifier = Modifier.weight(1f)) {
                                        InfoRow(Icons.Default.AttachMoney, "Interés", "L. ${dec.format(totalInteres)}")
                                        Spacer(modifier = Modifier.height(8.dp))

                                        val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                                        InfoRow(Icons.Default.DateRange, "Cuotas", "${cuotasNormales.size}")

                                        Spacer(modifier = Modifier.height(8.dp))
                                        proximoPagoProgramado?.let { fecha ->
                                            Text(
                                                "Próximo pago: $fecha",
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF1976D2),
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }

                                if (moraAplicada > 0.0) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                                    ) {
                                        Text(
                                            "Mora aplicada: L. ${dec.format(moraAplicada)}",
                                            modifier = Modifier.padding(12.dp),
                                            color = Color.Red,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = Color(0xFFE0E0E0))
                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    "Total a pagar: L. ${dec.format(totalCapital + totalInteres + moraAplicada)}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = Color(0xFF0061A7)
                                )
                            }
                        }
                    }

                    // PROGRESO DEL PRÉSTAMO
                    item {
                        val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                        val cuotasPagadas = cuotasNormales.count { it.estaCompleta }
                        val totalCuotas = cuotasNormales.size
                        val progreso = if (totalCuotas > 0) cuotasPagadas.toFloat() / totalCuotas else 0f

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (estaSaldado) Color(0xFFE8F5E8) else Color.White
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    if (estaSaldado) "Préstamo Saldado ✓" else "Progreso de Pagos",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (estaSaldado) Color(0xFF4CAF50) else Color(0xFF0061A7)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                if (!estaSaldado) {
                                    LinearProgressIndicator(
                                        progress = progreso,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(12.dp),
                                        color = Color(0xFF4CAF50),
                                        trackColor = Color(0xFFE0E0E0),
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                Text(
                                    "$cuotasPagadas de $totalCuotas cuotas completadas (${String.format("%.0f", progreso * 100)}%)",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (estaSaldado) Color(0xFF388E3C) else Color(0xFF666666)
                                )
                            }
                        }
                    }

                    // INFORMACIÓN DEL SISTEMA
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Sistema de Pagos en Cascada",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE65100),
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Los pagos se distribuyen automáticamente completando cuotas en orden secuencial. No hay abonos parciales.",
                                    color = Color(0xFFBF360C),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // BOTÓN EXPORTAR PDF
                    item {
                        Button(
                            onClick = {
                                val pdfFile = ReciboHelper.generarCuotasPDF(
                                    context = context,
                                    cliente = nombreCliente,
                                    prestamoId = prestamoId,
                                    cuotas.map { cuota ->
                                        mapOf(
                                            "numero" to cuota.numero,
                                            "fecha" to cuota.fecha,
                                            "capital" to cuota.capital,
                                            "interes" to cuota.interes,
                                            "total" to cuota.total,
                                            "pagado" to cuota.estaCompleta,
                                            "montoPagado" to cuota.montoPagado,
                                            "fechaPago" to (cuota.fechaPago ?: "")
                                        )
                                    },
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
                            Text("📄 Exportar Cuotas en PDF", color = Color.White, fontSize = 16.sp)
                        }
                    }

                    // LISTA DE CUOTAS
                    items(cuotas) { cuota ->
                        CuotaCard(
                            cuota = cuota,
                            dec = dec,
                            esActivo = esActivo,
                            rol = rol,
                            onMarcarPagada = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            // Crear pago manual usando la nueva estructura de cascada
                                            val abonoManual = mapOf(
                                                "prestamoId" to prestamoId,
                                                "monto" to cuota.total,
                                                "mora" to 0.0,
                                                "fechaPago" to Timestamp.now(),
                                                "registradoPor" to uid,
                                                "nombreCobrador" to nombreCobrador,
                                                "clienteNombre" to nombreCliente,
                                                "metodoPago" to "Manual (Admin)",
                                                "sistemaPagoEnCascada" to true,
                                                "cuotasCubiertas" to listOf(
                                                    mapOf(
                                                        "numeroCuota" to cuota.numero,
                                                        "montoAplicado" to cuota.total,
                                                        "completada" to true
                                                    )
                                                ),
                                                "observaciones" to "Marcado manualmente por administrador"
                                            )

                                            db.collection("pagos").add(abonoManual).await()

                                            // Actualizar préstamo
                                            val prestamoRef = db.collection("prestamos").document(prestamoId)
                                            val prestamoSnap = prestamoRef.get().await()
                                            val saldoActual = prestamoSnap.getDouble("saldo") ?: 0.0
                                            val montoPagadoActual = prestamoSnap.getDouble("montoPagado") ?: 0.0

                                            val nuevoSaldo = (saldoActual - cuota.total).coerceAtLeast(0.0)
                                            val nuevoMontoPagado = montoPagadoActual + cuota.total

                                            prestamoRef.update(mapOf(
                                                "saldo" to nuevoSaldo,
                                                "montoPagado" to nuevoMontoPagado,
                                                "fechaUltimaActualizacion" to Timestamp.now()
                                            )).await()
                                        }

                                        Toast.makeText(context, "Cuota marcada como pagada", Toast.LENGTH_SHORT).show()
                                        recargarDatosCompletos()

                                    } catch (e: Exception) {
                                        Log.e("CuotasScreenCascada", "Error al marcar pago: ${e.message}", e)
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }

                    // RESUMEN FINAL
                    item {
                        val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                        val cuotasCompletas = cuotasNormales.count { it.estaCompleta }
                        val cuotasConPagoParcial = cuotasNormales.count { it.montoPagado > 0 && !it.estaCompleta }
                        val cuotasPendientes = cuotasNormales.count { it.montoPagado == 0.0 }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    "Resumen del Sistema",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = Color(0xFF7B1FA2)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                ResumenRow("Completadas", cuotasCompletas, Color(0xFF388E3C))
                                ResumenRow("Con pago parcial", cuotasConPagoParcial, Color(0xFFFF9800))
                                ResumenRow("Pendientes", cuotasPendientes, Color(0xFF757575))

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = Color(0xFFE1BEE7))
                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    "El sistema de cascada elimina los abonos parciales distribuyendo automáticamente los pagos para completar cuotas en orden secuencial.",
                                    color = Color(0xFF4A148C),
                                    fontSize = 14.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }
                    }

                    // BOTONES DE NAVEGACIÓN
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF0061A7)
                                )
                            ) {
                                Text("← Regresar")
                            }

                            Button(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A7))
                            ) {
                                Text("Ir a Registrar Pago →", color = Color.White)
                            }
                        }
                    }

                    // INFORMACIÓN DEL USUARIO
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))
                        ) {
                            Text(
                                "Usuario: $nombreCobrador ($rol) | Préstamo #$numeroPrestamo",
                                modifier = Modifier.padding(12.dp),
                                fontSize = 12.sp,
                                color = Color.Gray,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===================== COMPOSABLES AUXILIARES =====================

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color(0xFF666666),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "$label: ",
            fontSize = 14.sp,
            color = Color(0xFF666666)
        )
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF333333)
        )
    }
}

@Composable
private fun CuotaCard(
    cuota: CuotaInfo,
    dec: DecimalFormat,
    esActivo: Boolean,
    rol: String,
    onMarcarPagada: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                cuota.estaCompleta -> Color(0xFFE8F5E8) // Verde suave
                cuota.montoPagado > 0 -> Color(0xFFFFF3E0) // Naranja muy suave
                else -> Color.White
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ENCABEZADO DE LA CUOTA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (cuota.descripcion == "Mora") "MORA" else "Cuota ${cuota.numero}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = if (cuota.descripcion == "Mora") Color.Red else Color(0xFF0061A7)
                    )

                    if (cuota.descripcion == "Mora") {
                        Spacer(modifier = Modifier.width(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Red)
                        ) {
                            Text(
                                "MORA",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // INDICADOR DE ESTADO
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (cuota.estaCompleta) Icons.Default.CheckCircle else Icons.Default.HourglassBottom,
                        contentDescription = null,
                        tint = if (cuota.estaCompleta) Color(0xFF388E3C) else Color(0xFF757575),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when {
                            cuota.estaCompleta -> "Completa"
                            cuota.montoPagado > 0 -> "Parcial (${String.format("%.0f", cuota.porcentajePagado)}%)"
                            else -> "Pendiente"
                        },
                        color = when {
                            cuota.estaCompleta -> Color(0xFF388E3C)
                            cuota.montoPagado > 0 -> Color(0xFFFF9800)
                            else -> Color(0xFF757575)
                        },
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // INFORMACIÓN DE FECHAS
            if (cuota.descripcion != "Mora") {
                Text(
                    "Fecha programada: ${cuota.fecha}",
                    fontSize = 14.sp,
                    color = Color(0xFF666666)
                )
            }

            // INFORMACIÓN DE PAGOS REALIZADOS
            if (cuota.estaCompleta && cuota.fechaPago != null) {
                Text(
                    "✓ Pagada el: ${cuota.fechaPago}",
                    color = Color(0xFF388E3C),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            } else if (cuota.montoPagado > 0 && cuota.historialPagos.isNotEmpty()) {
                if (cuota.historialPagos.size == 1) {
                    Text(
                        "Pago parcial el: ${cuota.historialPagos.first()}",
                        color = Color(0xFFFF9800),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                } else {
                    Text(
                        "Pagos múltiples:",
                        color = Color(0xFFFF9800),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    cuota.historialPagos.forEachIndexed { index, fecha ->
                        Text(
                            "  • Pago ${index + 1}: $fecha",
                            color = Color(0xFFFF9800),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // INFORMACIÓN FINANCIERA
            if (cuota.capital > 0 || cuota.interes > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (cuota.capital > 0) {
                        Text(
                            "Capital: L. ${dec.format(cuota.capital)}",
                            fontSize = 14.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    if (cuota.interes > 0) {
                        Text(
                            "Interés: L. ${dec.format(cuota.interes)}",
                            fontSize = 14.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // TOTAL Y MONTOS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Total: L. ${dec.format(cuota.total)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (cuota.descripcion == "Mora") Color.Red else Color(0xFF333333)
                )

                if (cuota.montoPagado > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "Pagado: L. ${dec.format(cuota.montoPagado)}",
                            color = Color(0xFF388E3C),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        if (!cuota.estaCompleta) {
                            val montoRestante = cuota.total - cuota.montoPagado
                            Text(
                                "Resta: L. ${dec.format(montoRestante)}",
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // BOTONES DE ACCIÓN
            if (!cuota.estaCompleta && esActivo && cuota.descripcion != "Mora") {
                Spacer(modifier = Modifier.height(12.dp))

                when (rol) {
                    "admin" -> {
                        Button(
                            onClick = onMarcarPagada,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A7))
                        ) {
                            Text("Marcar como Pagada (Admin)", color = Color.White)
                        }
                    }
                    "cobrador" -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
                        ) {
                            Text(
                                "💡 Para registrar pagos, usa la pantalla 'Registrar Pago'. Los pagos se distribuirán automáticamente.",
                                modifier = Modifier.padding(12.dp),
                                color = Color(0xFFE65100),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResumenRow(
    label: String,
    cantidad: Int,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = Color(0xFF666666)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                cantidad.toString(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = color, shape = androidx.compose.foundation.shape.CircleShape)
            )
        }
    }}