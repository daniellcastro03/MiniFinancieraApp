package com.example.capitalexpressapp.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

data class NotificacionCobro(
    val cliente: String,
    val monto: Double,
    val fecha: String,
    val tipo: String,
    val prestamoId: String,
    val plazo: String,
    val diferenciaDias: Int,
    val estado: String,
    val ultimoPago: String? = null,
    val numeroCuotaActual: Int = 1,
    val totalCuotas: Int = 1
)

// Colores (mantener igual)
object NotificationColors {
    val PrimaryBlue = Color(0xFF1565C0)
    val SecondaryBlue = Color(0xFF42A5F5)
    val AccentBlue = Color(0xFF0277BD)
    val LightBlue = Color(0xFFE3F2FD)
    val DarkBlue = Color(0xFF0D47A1)

    val SuccessGreen = Color(0xFF2E7D32)
    val WarningOrange = Color(0xFFEF6C00)
    val DangerRed = Color(0xFFD32F2F)
    val InfoBlue = Color(0xFF1976D2)

    val CardBackground = Color(0xFFFAFAFA)
    val SurfaceVariant = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFF757575)

    val BlueGradient = Brush.horizontalGradient(colors = listOf(PrimaryBlue, SecondaryBlue))
    val RedGradient = Brush.horizontalGradient(colors = listOf(Color(0xFFE53935), Color(0xFFD32F2F)))
    val GreenGradient = Brush.horizontalGradient(colors = listOf(Color(0xFF43A047), Color(0xFF2E7D32)))
    val OrangeGradient = Brush.horizontalGradient(colors = listOf(Color(0xFFFF7043), Color(0xFFEF6C00)))
}

// ===== FUNCIONES CORREGIDAS PARA PLAN DE CUOTAS REAL =====

/**
 * Función IDÉNTICA a CuotasPrestamoScreen para calcular fechas de cuotas
 * ESTO GARANTIZA CONSISTENCIA TOTAL
 */
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
            calendar.add(Calendar.MONTH, numeroCuota) // Default mensual
        }
    }

    return dateFormat.format(calendar.time)
}

/**
 * NUEVA FUNCIÓN CORREGIDA: Obtiene la próxima cuota sin pagar basada en el PLAN REAL de cuotas
 * Esto ignora completamente proximoPago y usa solo el plan de amortización + pagos registrados
 */
private suspend fun obtenerProximaCuotaSinPagarReal(
    db: FirebaseFirestore,
    prestamoId: String
): Triple<String?, Int, Int> {
    return try {
        // 1. Obtener datos del préstamo
        val doc = db.collection("prestamos").document(prestamoId).get().await()
        val fechaInicio = doc.getTimestamp("fecha")?.toDate() ?: doc.getDate("fecha") ?: Date()
        val plazo = doc.getString("plazo")?.lowercase() ?: "semanal"
        val cuotasNum = doc.getLong("cuotas")?.toInt() ?: 1

        Log.d("NotificacionesScreen", """
            === OBTENIENDO PRÓXIMA CUOTA REAL ===
            - Préstamo: $prestamoId
            - Fecha inicio: ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(fechaInicio)}
            - Plazo: $plazo
            - Total cuotas: $cuotasNum
        """.trimIndent())

        // 2. Obtener cuotas pagadas del historial de pagos
        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId)
            .get().await()

        val cuotasPagadasSet = mutableSetOf<Int>()
        for (pago in pagosSnapshot.documents) {
            val numeroCuota = when {
                pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                pago.contains("cuota") -> pago.getLong("cuota")?.toInt() ?: 1
                else -> 1
            }
            val cuotasCubiertas = pago.getLong("cuotasCubiertas")?.toInt() ?: 1

            // Marcar todas las cuotas cubiertas por este pago
            for (i in 0 until cuotasCubiertas) {
                cuotasPagadasSet.add(numeroCuota + i)
            }
        }

        Log.d("NotificacionesScreen", "Cuotas pagadas: ${cuotasPagadasSet.sorted()}")

        // 3. Generar el plan completo de cuotas y encontrar la primera sin pagar
        for (numeroCuota in 1..cuotasNum) {
            if (!cuotasPagadasSet.contains(numeroCuota)) {
                val fechaCuota = calcularFechaCuota(fechaInicio, plazo, numeroCuota)

                Log.d("NotificacionesScreen", """
                    ✅ PRÓXIMA CUOTA SIN PAGAR ENCONTRADA:
                    - Número: $numeroCuota de $cuotasNum
                    - Fecha programada: $fechaCuota
                    - Esta fecha es INAMOVIBLE independientemente de pagos tardíos
                """.trimIndent())

                return Triple(fechaCuota, numeroCuota, cuotasNum)
            }
        }

        // Si llegamos aquí, todas las cuotas están pagadas
        Log.d("NotificacionesScreen", "✅ PRÉSTAMO COMPLETAMENTE SALDADO")
        Triple("saldado", cuotasNum, cuotasNum)

    } catch (e: Exception) {
        Log.e("NotificacionesScreen", "❌ Error obteniendo próxima cuota: ${e.message}")
        Triple(null, 1, 1)
    }
}

/**
 * FUNCIÓN CORREGIDA: Calcula días hasta la fecha de cuota (respetando calendario fijo)
 */
fun calcularDiasHastaFechaCuota(fechaCuota: String): Int {
    if (fechaCuota == "saldado") return 0

    val formato = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val hoy = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    return try {
        val fechaCuotaCalendar = Calendar.getInstance().apply {
            time = formato.parse(fechaCuota) ?: Date()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val diferenciaDias = ((fechaCuotaCalendar.timeInMillis - hoy.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()

        Log.d("NotificacionesScreen", """
            === CÁLCULO DÍAS HASTA CUOTA ===
            - Fecha cuota: $fechaCuota
            - Fecha hoy: ${formato.format(hoy.time)}
            - Diferencia: $diferenciaDias días
            - ${if (diferenciaDias < 0) "VENCIDA" else if (diferenciaDias == 0) "HOY" else "PENDIENTE"}
        """.trimIndent())

        diferenciaDias
    } catch (e: Exception) {
        Log.e("NotificacionesScreen", "Error calculando días: ${e.message}")
        0
    }
}

/**
 * Obtiene último pago de manera segura
 */
fun obtenerUltimoPago(
    doc: com.google.firebase.firestore.DocumentSnapshot,
    formato: SimpleDateFormat
): String? {
    return try {
        when (val raw = doc.get("ultimoPago")) {
            is String -> if (raw.isNotBlank() && raw != "saldado") raw else null
            is com.google.firebase.Timestamp -> formato.format(raw.toDate())
            is Date -> formato.format(raw)
            else -> null
        }
    } catch (e: Exception) {
        Log.w("NotificacionesScreen", "Error obteniendo ultimoPago: ${e.message}")
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificacionesScreen(navController: NavHostController, uid: String, rol: String) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    val formato = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val notificaciones = remember { mutableStateListOf<NotificacionCobro>() }

    var filtroTipo by rememberSaveable { mutableStateOf("Todos") }
    var filtroEstado by rememberSaveable { mutableStateOf("Todos") }
    val filtrosTipo = listOf("Todos", "vencido", "hoy", "próximo")
    val filtrosEstado = listOf("Todos", "activo", "inactivo", "saldado", "mora")

    var mostrarDialogoMora by remember { mutableStateOf(false) }
    var clienteMora by remember { mutableStateOf("") }
    var prestamoIdMora by remember { mutableStateOf("") }
    var moraCalculada by remember { mutableStateOf("") }
    var diasMora by remember { mutableStateOf(0) }
    var montoPrestamo by remember { mutableStateOf(0.0) }
    var isLoading by remember { mutableStateOf(true) }

    // ===== FUNCIÓN COMPLETAMENTE CORREGIDA PARA CARGAR NOTIFICACIONES =====
    suspend fun cargarNotificaciones() {
        try {
            isLoading = true
            notificaciones.clear()

            val query = if (rol == "cobrador") {
                db.collection("prestamos")
                    .whereArrayContains("cobradoresAsignados", uid)
            } else {
                db.collection("prestamos")
            }

            val snapshot = query.get().await()

            val prestamosFiltrados = snapshot.documents.filter { doc ->
                val eliminado = doc.getBoolean("eliminado") ?: false
                !eliminado
            }

            Log.d("NotificacionesScreen", "=== INICIANDO CARGA DE ${prestamosFiltrados.size} PRÉSTAMOS ===")

            for (doc in prestamosFiltrados) {
                val cliente = doc.getString("cliente") ?: continue
                val prestamoId = doc.id
                val plazo = doc.getString("plazo") ?: "semanal"
                val totalPagar = doc.getDouble("totalPagar") ?: continue
                val montoPagado = doc.getDouble("montoPagado") ?: 0.0
                val saldoActual = doc.getDouble("saldo") ?: (totalPagar - montoPagado)
                val estadoDoc = doc.getString("estado") ?: "activo"
                val ultimoPagoStr = obtenerUltimoPago(doc, formato)

                Log.d("NotificacionesScreen", """
                    === PROCESANDO PRÉSTAMO ===
                    - Cliente: $cliente
                    - Préstamo ID: $prestamoId
                    - Saldo actual: L. ${String.format("%.2f", saldoActual)}
                    - Estado: $estadoDoc
                    - Último pago: $ultimoPagoStr
                    - Plazo: $plazo
                """.trimIndent())

                // Si está saldado o sin saldo
                if (saldoActual <= 0 || estadoDoc.equals("saldado", ignoreCase = true)) {
                    if ((filtroEstado == "Todos" || filtroEstado == "saldado") &&
                        (filtroTipo == "Todos")) {
                        notificaciones.add(
                            NotificacionCobro(
                                cliente = cliente,
                                monto = 0.0,
                                fecha = "saldado",
                                tipo = "saldado",
                                prestamoId = prestamoId,
                                plazo = plazo,
                                diferenciaDias = 0,
                                estado = "saldado",
                                ultimoPago = ultimoPagoStr
                            )
                        )
                    }
                    continue
                }

                // === CAMBIO CRÍTICO: Usar plan de cuotas real ===
                val (fechaProximaCuota, numeroCuota, totalCuotas) = obtenerProximaCuotaSinPagarReal(db, prestamoId)

                if (fechaProximaCuota == null) {
                    Log.w("NotificacionesScreen", "No se pudo obtener próxima cuota para $prestamoId")
                    continue
                }

                if (fechaProximaCuota == "saldado") {
                    // Actualizar estado si no se había detectado como saldado
                    if (!estadoDoc.equals("saldado", ignoreCase = true)) {
                        db.collection("prestamos").document(prestamoId)
                            .update(mapOf("estado" to "saldado", "proximoPago" to "saldado"))
                    }
                    continue
                }

                // === CÁLCULO CORREGIDO: Días hasta la próxima cuota (fecha fija del plan) ===
                val diasHastaProximo = calcularDiasHastaFechaCuota(fechaProximaCuota)

                // Determinar tipo y estado basado en días reales hasta la cuota programada
                val (tipo, estadoFinal) = when {
                    estadoDoc.equals("inactivo", ignoreCase = true) -> "inactivo" to "inactivo"
                    diasHastaProximo < -3 -> "vencido" to "mora" // Más de 3 días vencida = mora
                    diasHastaProximo < 0 -> "vencido" to "activo" // Vencida pero reciente
                    diasHastaProximo == 0 -> "hoy" to "activo" // Debe cobrarse hoy
                    diasHastaProximo <= 3 -> "próximo" to "activo" // Próximos 3 días
                    else -> "futuro" to "activo"
                }

                // Aplicar filtros
                val pasaFiltroTipo = filtroTipo == "Todos" || filtroTipo == tipo
                val pasaFiltroEstado = filtroEstado == "Todos" || filtroEstado == estadoFinal

                if (pasaFiltroTipo && pasaFiltroEstado) {
                    notificaciones.add(
                        NotificacionCobro(
                            cliente = cliente,
                            monto = saldoActual,
                            fecha = fechaProximaCuota,
                            tipo = tipo,
                            prestamoId = prestamoId,
                            plazo = plazo,
                            diferenciaDias = diasHastaProximo,
                            estado = estadoFinal,
                            ultimoPago = ultimoPagoStr,
                            numeroCuotaActual = numeroCuota,
                            totalCuotas = totalCuotas
                        )
                    )

                    Log.d("NotificacionesScreen", """
                        ✅ NOTIFICACIÓN AGREGADA:
                        - Cliente: $cliente
                        - Cuota $numeroCuota de $totalCuotas
                        - Fecha programada (INAMOVIBLE): $fechaProximaCuota
                        - Días hasta cuota: $diasHastaProximo
                        - Tipo: $tipo | Estado: $estadoFinal
                        - IMPORTANTE: Esta fecha NO cambia aunque se pague tarde
                    """.trimIndent())
                }
            }

            // Ordenar por urgencia
            notificaciones.sortWith(compareBy<NotificacionCobro> {
                when (it.tipo) {
                    "vencido" -> 0
                    "hoy" -> 1
                    "próximo" -> 2
                    "futuro" -> 3
                    "saldado" -> 4
                    else -> 5
                }
            }.thenBy { it.diferenciaDias })

            Log.d("NotificacionesScreen", "✅ CARGA COMPLETADA: ${notificaciones.size} notificaciones generadas")

        } catch (e: Exception) {
            Log.e("NotificacionesScreen", "❌ Error cargando notificaciones: ${e.message}", e)
            Toast.makeText(context, "Error al cargar: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(filtroTipo, filtroEstado) {
        scope.launch { cargarNotificaciones() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "🔔 Notificaciones",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NotificationColors.PrimaryBlue
                ),
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch { cargarNotificaciones() }
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Actualizar",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { padding ->

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                NotificationColors.LightBlue,
                                Color.White
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = NotificationColors.PrimaryBlue,
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Cargando notificaciones...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NotificationColors.TextSecondary
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                NotificationColors.LightBlue.copy(alpha = 0.3f),
                                Color.White
                            )
                        )
                    )
                    .padding(16.dp)
            ) {

                // Estadísticas
                val vencidos = notificaciones.count { it.tipo == "vencido" }
                val hoyCount = notificaciones.count { it.tipo == "hoy" }
                val proximos = notificaciones.count { it.tipo == "próximo" }

                AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically() + fadeIn()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(8.dp, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Dashboard,
                                    contentDescription = null,
                                    tint = NotificationColors.PrimaryBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Resumen del día",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = NotificationColors.DarkBlue
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatCard(
                                    label = "Vencidos",
                                    count = vencidos,
                                    color = NotificationColors.DangerRed,
                                    icon = Icons.Default.Warning
                                )
                                StatCard(
                                    label = "Hoy",
                                    count = hoyCount,
                                    color = NotificationColors.WarningOrange,
                                    icon = Icons.Default.Today
                                )
                                StatCard(
                                    label = "Próximos",
                                    count = proximos,
                                    color = NotificationColors.SuccessGreen,
                                    icon = Icons.Default.Schedule
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = NotificationColors.LightBlue
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        "📋 Total: ${notificaciones.size}",
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = NotificationColors.DarkBlue
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Filtros
                AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(4.dp, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = null,
                                    tint = NotificationColors.PrimaryBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "🎯 Filtros",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = NotificationColors.DarkBlue
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Filtro de urgencia
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Urgencia:",
                                        fontSize = 12.sp,
                                        color = NotificationColors.TextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(filtrosTipo) { filtro ->
                                            FilterChip(
                                                selected = filtroTipo == filtro,
                                                onClick = { filtroTipo = filtro },
                                                label = {
                                                    Text(
                                                        when(filtro) {
                                                            "Todos" -> "Todo"
                                                            "vencido" -> "Venc"
                                                            "hoy" -> "Hoy"
                                                            "próximo" -> "Prox"
                                                            else -> filtro
                                                        },
                                                        fontSize = 11.sp,
                                                        fontWeight = if (filtroEstado == filtro) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                modifier = Modifier.height(32.dp),
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = NotificationColors.SecondaryBlue,
                                                    selectedLabelColor = Color.White,
                                                    containerColor = Color.Transparent,
                                                    labelColor = NotificationColors.SecondaryBlue
                                                ),
                                                border = FilterChipDefaults.filterChipBorder(
                                                    enabled = true,
                                                    selected = filtroEstado == filtro,
                                                    borderColor = NotificationColors.SecondaryBlue,
                                                    selectedBorderColor = NotificationColors.SecondaryBlue,
                                                    borderWidth = 1.dp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Lista de notificaciones
                if (notificaciones.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = NotificationColors.TextSecondary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No hay notificaciones",
                                style = MaterialTheme.typography.headlineSmall,
                                color = NotificationColors.TextSecondary
                            )
                            Text(
                                "¡Todo está al día!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NotificationColors.TextSecondary
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = notificaciones,
                            key = { it.prestamoId }
                        ) { notif ->
                            AnimatedVisibility(
                                visible = true,
                                enter = slideInVertically() + fadeIn(),
                                exit = slideOutVertically() + fadeOut()
                            ) {
                                NotificacionCard(
                                    notif = notif,
                                    rol = rol,
                                    uid = uid,
                                    context = context,
                                    navController = navController,
                                    db = db,
                                    onAplicarMora = { prestamoId, cliente, moraSugerida ->
                                        prestamoIdMora = prestamoId
                                        clienteMora = cliente
                                        moraCalculada = moraSugerida
                                        diasMora = -notif.diferenciaDias
                                        montoPrestamo = notif.monto
                                        mostrarDialogoMora = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Diálogo de mora mejorado
    if (mostrarDialogoMora) {
        DialogoAplicarMora(
            cliente = clienteMora,
            moraSugerida = moraCalculada,
            diasMora = diasMora,
            montoPrestamo = montoPrestamo,
            onDismiss = { mostrarDialogoMora = false },
            onConfirmar = { montoMora ->
                scope.launch {
                    try {
                        val ref = db.collection("prestamos").document(prestamoIdMora)
                        val doc = ref.get().await()

                        val eliminado = doc.getBoolean("eliminado") ?: false
                        if (eliminado) {
                            Toast.makeText(context, "No se puede aplicar mora a un préstamo eliminado", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        val saldoActual = doc.getDouble("saldo") ?: doc.getDouble("totalPagar") ?: 0.0

                        // === CORRECCIÓN: Obtener fecha de la cuota actual (basada en plan real) ===
                        val (fechaCuotaActual, numeroCuota, _) = obtenerProximaCuotaSinPagarReal(db, prestamoIdMora)

                        if (fechaCuotaActual == null || fechaCuotaActual == "saldado") {
                            Toast.makeText(context, "El préstamo no tiene cuotas pendientes", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        // Verificar si ya se aplicó mora para esa cuota específica
                        val morasAplicadas = (doc.get("morasAplicadas") as? List<*>)?.mapNotNull {
                            it as? String
                        } ?: emptyList()

                        val claveMora = "${fechaCuotaActual}_cuota_${numeroCuota}"

                        if (morasAplicadas.contains(claveMora)) {
                            Toast.makeText(context, "Ya se aplicó mora para la cuota $numeroCuota ($fechaCuotaActual)", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        // Aplicar mora y marcar que se aplicó para esa cuota específica
                        ref.update(
                            mapOf(
                                "saldo" to (saldoActual + montoMora),
                                "mora" to montoMora,
                                "morasAplicadas" to (morasAplicadas + claveMora),
                                "estado" to "mora",
                                "fechaUltimaActualizacion" to com.google.firebase.Timestamp.now()
                            )
                        ).await()

                        Toast.makeText(context, "Mora aplicada correctamente a cuota $numeroCuota", Toast.LENGTH_SHORT).show()
                        cargarNotificaciones()

                    } catch (e: Exception) {
                        Toast.makeText(context, "Error al aplicar mora: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        mostrarDialogoMora = false
                    }
                }
            }
        )
    }
}

// Componente de estadística mejorado
@Composable
fun StatCard(
    label: String,
    count: Int,
    color: Color,
    icon: ImageVector
) {
    Card(
        modifier = Modifier
            .width(100.dp)
            .height(80.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                count.toString(),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = color
            )
            Text(
                label,
                fontSize = 11.sp,
                color = color.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// Diálogo de mora mejorado
@Composable
fun DialogoAplicarMora(
    cliente: String,
    moraSugerida: String,
    diasMora: Int,
    montoPrestamo: Double,
    onDismiss: () -> Unit,
    onConfirmar: (Double) -> Unit
) {
    var moraTexto by remember { mutableStateOf(moraSugerida) }
    val calculoMora = (montoPrestamo * 0.005) * diasMora
    val detalle = "Cálculo: L. %.2f × 0.5%% × %d días = L. %.2f".format(montoPrestamo, diasMora, calculoMora)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = NotificationColors.DangerRed,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Aplicar mora",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = NotificationColors.LightBlue
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            "Cliente: $cliente",
                            fontWeight = FontWeight.Bold
                        )
                        Text("Monto del préstamo: L. %.2f".format(montoPrestamo))
                        Text("Días de mora: $diasMora")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "Cálculo sugerido:",
                    fontWeight = FontWeight.Bold,
                    color = NotificationColors.DarkBlue
                )
                Text(
                    detalle,
                    color = NotificationColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = moraTexto,
                    onValueChange = { moraTexto = it },
                    label = { Text("Monto de mora (L)") },
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            Icons.Default.AttachMoney,
                            contentDescription = null,
                            tint = NotificationColors.PrimaryBlue
                        )
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val monto = moraTexto.toDoubleOrNull()
                    if (monto != null && monto > 0) {
                        onConfirmar(monto)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NotificationColors.DangerRed
                )
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Aplicar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

// === CARD DE NOTIFICACIÓN CORREGIDA ===
@Composable
fun NotificacionCard(
    notif: NotificacionCobro,
    rol: String,
    uid: String,
    context: Context,
    navController: NavHostController,
    db: FirebaseFirestore,
    onAplicarMora: (String, String, String) -> Unit
) {
    val scope = rememberCoroutineScope()

    val (estadoColor, estadoGradient) = when (notif.estado) {
        "mora" -> NotificationColors.DangerRed to NotificationColors.RedGradient
        "saldado" -> NotificationColors.SuccessGreen to NotificationColors.GreenGradient
        "inactivo" -> NotificationColors.TextSecondary to Brush.horizontalGradient(listOf(Color.Gray, Color.DarkGray))
        else -> NotificationColors.InfoBlue to NotificationColors.BlueGradient
    }

    // === MENSAJES CORREGIDOS BASADOS EN FECHAS FIJAS DEL PLAN ===
    val (mensaje, urgenciaColor) = when {
        notif.estado == "saldado" -> "Préstamo saldado" to NotificationColors.SuccessGreen
        notif.diferenciaDias < -3 -> "Cuota ${notif.numeroCuotaActual} en mora: ${-notif.diferenciaDias} días" to NotificationColors.DangerRed
        notif.diferenciaDias < 0 -> "Cuota ${notif.numeroCuotaActual} venció hace ${-notif.diferenciaDias} días" to NotificationColors.WarningOrange
        notif.diferenciaDias == 0 -> "Cuota ${notif.numeroCuotaActual} debe cobrarse hoy" to NotificationColors.WarningOrange
        notif.diferenciaDias == 1 -> "Cuota ${notif.numeroCuotaActual}: falta 1 día" to NotificationColors.SuccessGreen
        notif.diferenciaDias <= 3 -> "Cuota ${notif.numeroCuotaActual}: faltan ${notif.diferenciaDias} días" to NotificationColors.SuccessGreen
        else -> "Cuota ${notif.numeroCuotaActual}: faltan ${notif.diferenciaDias} días" to NotificationColors.TextSecondary
    }

    // Calcular progreso de cuotas
    val progresoCuotas = if (notif.totalCuotas > 0) {
        (notif.numeroCuotaActual - 1).toFloat() / notif.totalCuotas.toFloat()
    } else 0f

    val colorProgreso = when {
        notif.estado == "saldado" -> NotificationColors.SuccessGreen
        notif.diferenciaDias < -3 -> NotificationColors.DangerRed
        notif.diferenciaDias < 0 -> NotificationColors.WarningOrange
        notif.diferenciaDias <= 3 -> NotificationColors.WarningOrange
        else -> NotificationColors.SuccessGreen
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column {
            // Header con gradiente
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(estadoGradient)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                notif.cliente,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (notif.estado != "saldado") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.AttachMoney,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "L. ${"%.2f".format(notif.monto)}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                notif.estado.uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${notif.numeroCuotaActual}/${notif.totalCuotas}",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Contenido principal
            Column(modifier = Modifier.padding(16.dp)) {
                // === INFORMACIÓN CORREGIDA DE FECHA (PLAN FIJO) ===
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = urgenciaColor.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CalendarToday,
                                        contentDescription = null,
                                        tint = urgenciaColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "Cuota ${notif.numeroCuotaActual} (FIJA):",
                                        fontSize = 13.sp,
                                        color = NotificationColors.TextSecondary
                                    )
                                }
                                Text(
                                    notif.fecha,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NotificationColors.DarkBlue
                                )
                                Text(
                                    "Esta fecha NO cambia",
                                    fontSize = 11.sp,
                                    color = urgenciaColor,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    mensaje,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = urgenciaColor,
                                    textAlign = TextAlign.End
                                )
                            }
                        }

                        // Mostrar último pago si existe
                        if (!notif.ultimoPago.isNullOrBlank() && notif.estado != "saldado") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = null,
                                    tint = NotificationColors.TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Último pago: ${notif.ultimoPago}",
                                    fontSize = 12.sp,
                                    color = NotificationColors.TextSecondary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Progreso de cuotas
                if (notif.estado != "saldado") {
                    Spacer(modifier = Modifier.height(12.dp))

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Progreso de cuotas:",
                                fontSize = 12.sp,
                                color = NotificationColors.TextSecondary
                            )
                            Text(
                                "${notif.numeroCuotaActual - 1} de ${notif.totalCuotas} pagadas",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorProgreso
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = progresoCuotas,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = colorProgreso,
                            trackColor = Color.LightGray.copy(alpha = 0.3f)
                        )
                    }
                }

                // Botones de acción
                if (notif.estado != "saldado" && notif.estado != "inactivo") {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Primera fila de botones
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val prestamoDoc = db.collection("prestamos").document(notif.prestamoId).get().await()
                                        val clienteId = prestamoDoc.getString("clienteId")

                                        if (!clienteId.isNullOrEmpty()) {
                                            navController.navigate("RegistrarPagoScreen/${clienteId}/${notif.prestamoId}/${notif.monto}/$rol")
                                        } else {
                                            Toast.makeText(context, "Cliente no encontrado en el préstamo", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error al cargar cliente: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NotificationColors.PrimaryBlue
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cobrar", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                navController.navigate("CuotasPrestamoScreen/${notif.prestamoId}/${uid}/${rol}")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, NotificationColors.PrimaryBlue)
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = NotificationColors.PrimaryBlue
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Historial",
                                fontSize = 13.sp,
                                color = NotificationColors.PrimaryBlue
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Segunda fila de botones
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    enviarMensajeWhatsAppConNumero(context, notif.cliente, db)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF25D366))
                        ) {
                            Icon(
                                Icons.Default.Message,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF25D366)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "WhatsApp",
                                fontSize = 13.sp,
                                color = Color(0xFF25D366)
                            )
                        }

                        // Botón de mora solo si está en mora real (más de 3 días)
                        if ((rol == "admin" || rol == "cobrador") && notif.estado == "mora" && notif.diferenciaDias < -3) {
                            val diasMoraReales = -notif.diferenciaDias
                            val moraSugerida = "%.2f".format(notif.monto * 0.005 * diasMoraReales)

                            Button(
                                onClick = {
                                    onAplicarMora(notif.prestamoId, notif.cliente, moraSugerida)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NotificationColors.DangerRed
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Mora", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// Función para enviar WhatsApp (mantener igual)
suspend fun enviarMensajeWhatsAppConNumero(
    context: Context,
    clienteNombre: String,
    db: FirebaseFirestore
) {
    try {
        val clienteDocs = db.collection("clientes")
            .whereEqualTo("nombre", clienteNombre)
            .whereEqualTo("eliminado", false)
            .get().await()

        val doc = clienteDocs.documents.firstOrNull()
        val eliminado = doc?.getBoolean("eliminado") ?: false
        if (eliminado) {
            Toast.makeText(context, "Cliente no disponible", Toast.LENGTH_SHORT).show()
            return
        }

        val numero = doc?.getString("telefono")?.filter { it.isDigit() } ?: ""
        val nombre = doc?.getString("nombre") ?: "cliente"

        if (numero.isBlank()) {
            Toast.makeText(context, "Número de teléfono no encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        val mensaje = "Estimado/a $nombre, le recordamos que tiene un pago pendiente con Capital Express. Por favor, comuníquese con nosotros para coordinar su pago. ¡Gracias!"
        val url = "https://wa.me/504$numero?text=${Uri.encode(mensaje)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show()
        }

    } catch (e: Exception) {
        Toast.makeText(context, "Error al enviar mensaje: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}