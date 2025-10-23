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
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class NotificacionCobroCascada(
    val cliente: String,
    val montoSaldoPendiente: Double,
    val fechaProximaCuota: String,
    val tipo: String,
    val prestamoId: String,
    val plazo: String,
    val diferenciaDias: Int,
    val estado: String,
    val ultimoPago: String? = null,
    val proximaCuotaNumero: Int = 1,
    val totalCuotas: Int = 1,
    val cuotasCompletadas: Int = 0
)

object NotificationColors {
    val PrimaryBlue = Color(0xFF1565C0)
    val SecondaryBlue = Color(0xFF42A5F5)
    val LightBlue = Color(0xFFE3F2FD)
    val DarkBlue = Color(0xFF0D47A1)
    val SuccessGreen = Color(0xFF2E7D32)
    val WarningOrange = Color(0xFFEF6C00)
    val DangerRed = Color(0xFFD32F2F)
    val InfoBlue = Color(0xFF1976D2)
    val TextSecondary = Color(0xFF757575)
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

private suspend fun obtenerEstadoCuotasSimplificado(
    db: FirebaseFirestore,
    prestamoId: String,
    cuotasTotales: Int,
    cuotaEstimada: Double
): Pair<Int, Int> {
    return try {
        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId)
            .get().await()

        val montoPorCuota = mutableMapOf<Int, Double>()

        for (pago in pagosSnapshot.documents) {
            val cuotasCubiertas = pago.get("cuotasCubiertas") as? List<*>

            if (cuotasCubiertas != null && cuotasCubiertas.isNotEmpty()) {
                cuotasCubiertas.forEach { cuotaData ->
                    if (cuotaData is Map<*, *>) {
                        val numeroCuota = (cuotaData["numeroCuota"] as? Number)?.toInt() ?: 0
                        val montoAplicado = (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0

                        if (numeroCuota > 0) {
                            montoPorCuota[numeroCuota] = (montoPorCuota[numeroCuota] ?: 0.0) + montoAplicado
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
                    montoPorCuota[numeroCuota] = (montoPorCuota[numeroCuota] ?: 0.0) + montoPago
                }
            }
        }

        var cuotasCompletadas = 0
        var proximaCuotaNumero = cuotasTotales + 1

        for (i in 1..cuotasTotales) {
            val montoPagado = montoPorCuota[i] ?: 0.0
            if (montoPagado >= cuotaEstimada - 0.01) {
                cuotasCompletadas++
            } else {
                if (proximaCuotaNumero > cuotasTotales) {
                    proximaCuotaNumero = i
                }
            }
        }

        Pair(cuotasCompletadas, proximaCuotaNumero)

    } catch (e: Exception) {
        Log.e("EstadoCuotas", "Error: ${e.message}")
        Pair(0, 1)
    }
}

suspend fun procesarPrestamoConCascada(
    doc: DocumentSnapshot,
    formato: SimpleDateFormat
): NotificacionCobroCascada? {
    return try {
        val cliente = doc.getString("cliente")
        val prestamoId = doc.id

        if (cliente.isNullOrBlank()) return null

        val eliminado = doc.getBoolean("eliminado") ?: false
        if (eliminado) return null

        val plazo = doc.getString("plazo") ?: "semanal"
        val saldoActual = doc.getDouble("saldo") ?: 0.0
        val estadoDoc = doc.getString("estado") ?: "activo"
        val cuotasNum = doc.getLong("cuotas")?.toInt() ?: 1
        val fechaInicio = doc.getTimestamp("fecha")?.toDate() ?: doc.getDate("fecha") ?: Date()
        val cuotaEstimada = doc.getDouble("cuota") ?: 0.0

        // ✅ FILTRAR PRÉSTAMOS SALDADOS
        if (saldoActual <= 0.0 || estadoDoc.equals("saldado", ignoreCase = true)) {
            return null
        }

        if (cuotasNum <= 0 || cuotasNum > 500 || cuotaEstimada <= 0) return null

        val db = FirebaseFirestore.getInstance()
        val (cuotasCompletadas, proximaCuotaNumero) = obtenerEstadoCuotasSimplificado(
            db, prestamoId, cuotasNum, cuotaEstimada
        )

        // ✅ FILTRAR SI TODAS LAS CUOTAS ESTÁN COMPLETADAS
        if (cuotasCompletadas >= cuotasNum) {
            return null
        }

        val fechaProximaCuota = if (proximaCuotaNumero <= cuotasNum) {
            calcularFechaCuota(fechaInicio, plazo, proximaCuotaNumero)
        } else {
            "saldado"
        }

        val diasHastaProximo = calcularDiasHastaFechaCuota(fechaProximaCuota)

        val (tipo, estadoFinal) = when {
            estadoDoc.equals("inactivo", ignoreCase = true) -> "inactivo" to "inactivo"
            diasHastaProximo < -3 -> "vencido" to "mora"
            diasHastaProximo < 0 -> "vencido" to "activo"
            diasHastaProximo == 0 -> "hoy" to "activo"
            diasHastaProximo <= 3 -> "próximo" to "activo"
            else -> "futuro" to "activo"
        }

        NotificacionCobroCascada(
            cliente = cliente,
            montoSaldoPendiente = saldoActual,
            fechaProximaCuota = fechaProximaCuota,
            tipo = tipo,
            prestamoId = prestamoId,
            plazo = plazo,
            diferenciaDias = diasHastaProximo,
            estado = estadoFinal,
            ultimoPago = obtenerUltimoPago(doc, formato),
            proximaCuotaNumero = proximaCuotaNumero,
            totalCuotas = cuotasNum,
            cuotasCompletadas = cuotasCompletadas
        )

    } catch (e: Exception) {
        Log.e("NotificacionesCascada", "Error: ${e.message}", e)
        null
    }
}

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

        ((fechaCuotaCalendar.timeInMillis - hoy.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
    } catch (e: Exception) {
        0
    }
}

fun obtenerUltimoPago(doc: DocumentSnapshot, formato: SimpleDateFormat): String? {
    return try {
        when (val raw = doc.get("ultimoPago")) {
            is String -> if (raw.isNotBlank() && raw != "saldado") raw else null
            is com.google.firebase.Timestamp -> formato.format(raw.toDate())
            is Date -> formato.format(raw)
            else -> null
        }
    } catch (e: Exception) {
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

    val notificaciones = remember { mutableStateListOf<NotificacionCobroCascada>() }
    val todasLasNotificaciones = remember { mutableStateListOf<NotificacionCobroCascada>() }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    var filtroTipo by rememberSaveable { mutableStateOf("Todos") }
    var filtroEstado by rememberSaveable { mutableStateOf("Todos") }
    var textoBusqueda by rememberSaveable { mutableStateOf("") }

    val filtrosTipo = listOf("Todos", "vencido", "hoy", "próximo")
    val filtrosEstado = listOf("Todos", "activo", "inactivo", "mora")

    var mostrarDialogoMora by remember { mutableStateOf(false) }
    var clienteMora by remember { mutableStateOf("") }
    var prestamoIdMora by remember { mutableStateOf("") }
    var moraCalculada by remember { mutableStateOf("") }
    var diasMora by remember { mutableStateOf(0) }
    var montoPrestamo by remember { mutableStateOf(0.0) }

    suspend fun cargarNotificacionesConCascada() {
        try {
            isLoading = true
            hasError = false
            errorMessage = ""
            todasLasNotificaciones.clear()

            val query = if (rol == "cobrador") {
                db.collection("prestamos")
                    .whereArrayContains("cobradoresAsignados", uid)
                    .whereGreaterThan("saldo", 0.0)
            } else {
                db.collection("prestamos")
                    .whereGreaterThan("saldo", 0.0)
            }

            val snapshot = query.get().await()
            val documentos = snapshot.documents

            if (documentos.isEmpty()) {
                return
            }

            val documentosValidos = documentos.filter { doc ->
                val eliminado = doc.getBoolean("eliminado") ?: false
                val cliente = doc.getString("cliente")
                val cuotas = doc.getLong("cuotas")?.toInt() ?: 0
                val saldo = doc.getDouble("saldo") ?: 0.0
                val estado = doc.getString("estado") ?: "activo"

                !eliminado &&
                        !cliente.isNullOrBlank() &&
                        cuotas > 0 &&
                        cuotas <= 500 &&
                        saldo > 0.0 &&
                        !estado.equals("saldado", ignoreCase = true)
            }

            val loteSize = 15
            for (lote in documentosValidos.chunked(loteSize)) {
                val notificacionesLote = withContext(Dispatchers.Default) {
                    lote.mapNotNull { doc ->
                        try {
                            procesarPrestamoConCascada(doc, formato)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }

                todasLasNotificaciones.addAll(notificacionesLote)
                delay(50)
            }

            todasLasNotificaciones.sortWith(compareBy<NotificacionCobroCascada> {
                when (it.tipo) {
                    "vencido" -> 0
                    "hoy" -> 1
                    "próximo" -> 2
                    "futuro" -> 3
                    else -> 4
                }
            }.thenBy { it.diferenciaDias })

        } catch (e: Exception) {
            hasError = true
            errorMessage = when {
                e.message?.contains("network", ignoreCase = true) == true ->
                    "Error de conexión"
                e.message?.contains("permission", ignoreCase = true) == true ->
                    "Sin permisos"
                else -> "Error: ${e.localizedMessage}"
            }
        } finally {
            isLoading = false
        }
    }

    fun aplicarFiltros() {
        notificaciones.clear()

        val filtradas = todasLasNotificaciones.asSequence()
            .filter { notif ->
                val pasaFiltroTipo = filtroTipo == "Todos" || filtroTipo == notif.tipo
                val pasaFiltroEstado = filtroEstado == "Todos" || filtroEstado == notif.estado
                val pasaBusqueda = textoBusqueda.isBlank() ||
                        notif.cliente.contains(textoBusqueda, ignoreCase = true)

                pasaFiltroTipo && pasaFiltroEstado && pasaBusqueda
            }
            .toList()

        notificaciones.addAll(filtradas)
    }

    LaunchedEffect(filtroTipo, filtroEstado, textoBusqueda, todasLasNotificaciones.size) {
        aplicarFiltros()
    }

    LaunchedEffect(Unit) {
        scope.launch {
            cargarNotificacionesConCascada()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Notificaciones",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NotificationColors.PrimaryBlue
                ),
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch { cargarNotificacionesConCascada() }
                        },
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Actualizar",
                                tint = Color.White
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->

        when {
            hasError -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.padding(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = NotificationColors.DangerRed
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                "Error al cargar",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = NotificationColors.DangerRed
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NotificationColors.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                            Button(
                                onClick = {
                                    scope.launch { cargarNotificacionesConCascada() }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NotificationColors.PrimaryBlue
                                )
                            ) {
                                Text("Reintentar")
                            }
                        }
                    }
                }
            }

            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            color = NotificationColors.PrimaryBlue
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Cargando notificaciones...")
                        Text(
                            "Sistema de cascada",
                            color = NotificationColors.TextSecondary
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    NotificationColors.LightBlue.copy(alpha = 0.1f),
                                    Color.White
                                )
                            )
                        ),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = 16.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        val vencidos = notificaciones.count { it.tipo == "vencido" }
                        val hoyCount = notificaciones.count { it.tipo == "hoy" }
                        val proximos = notificaciones.count { it.tipo == "próximo" }

                        ResumenDelDiaCompacto(
                            vencidos = vencidos,
                            hoyCount = hoyCount,
                            proximos = proximos,
                            total = notificaciones.size
                        )
                    }

                    item {
                        SistemaCascadaInfo()
                    }

                    item {
                        OutlinedTextField(
                            value = textoBusqueda,
                            onValueChange = { textoBusqueda = it },
                            label = { Text("Buscar cliente...", fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (textoBusqueda.isNotBlank()) {
                                    IconButton(onClick = { textoBusqueda = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    item {
                        FiltrosCompactos(
                            filtroTipo = filtroTipo,
                            onFiltroTipoChange = { filtroTipo = it },
                            filtroEstado = filtroEstado,
                            onFiltroEstadoChange = { filtroEstado = it }
                        )
                    }

                    if (notificaciones.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Notifications,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = NotificationColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No hay notificaciones", fontSize = 14.sp)
                                }
                            }
                        }
                    } else {
                        items(
                            items = notificaciones,
                            key = { it.prestamoId }
                        ) { notif ->
                            NotificacionCardCascada(
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
                                    montoPrestamo = notif.montoSaldoPendiente
                                    mostrarDialogoMora = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

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
                            Toast.makeText(context, "Préstamo eliminado", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        val saldoActual = doc.getDouble("saldo") ?: 0.0
                        val morasAplicadas = (doc.get("morasAplicadas") as? List<*>)?.mapNotNull {
                            it as? String
                        } ?: emptyList()

                        val claveMora = "${clienteMora}_${System.currentTimeMillis()}"

                        ref.update(
                            mapOf(
                                "saldo" to (saldoActual + montoMora),
                                "mora" to montoMora,
                                "morasAplicadas" to (morasAplicadas + claveMora),
                                "estado" to "mora",
                                "fechaUltimaActualizacion" to com.google.firebase.Timestamp.now()
                            )
                        ).await()

                        Toast.makeText(context, "Mora aplicada", Toast.LENGTH_SHORT).show()
                        cargarNotificacionesConCascada()

                    } catch (e: Exception) {
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        mostrarDialogoMora = false
                    }
                }
            }
        )
    }
}

@Composable
fun StatCard(label: String, count: Int, color: Color, icon: ImageVector) {
    Card(
        modifier = Modifier
            .width(100.dp)
            .height(80.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(count.toString(), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
            Text(
                label,
                fontSize = 11.sp,
                color = color.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ResumenDelDiaCompacto(vencidos: Int, hoyCount: Int, proximos: Int, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Resumen",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = NotificationColors.DarkBlue
                )
                Text(
                    "Total: $total",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = NotificationColors.PrimaryBlue
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatCard("Vencidos", vencidos, NotificationColors.DangerRed, Icons.Default.Warning)
                StatCard("Hoy", hoyCount, NotificationColors.WarningOrange, Icons.Default.Today)
                StatCard("Próximos", proximos, NotificationColors.SuccessGreen, Icons.Default.Schedule)
            }
        }
    }
}

@Composable
fun SistemaCascadaInfo() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = Color(0xFF7B1FA2),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Sistema Cascada: Pagos automáticos en orden",
                fontSize = 13.sp,
                color = Color(0xFF4A148C)
            )
        }
    }
}

@Composable
fun FiltrosCompactos(
    filtroTipo: String,
    onFiltroTipoChange: (String) -> Unit,
    filtroEstado: String,
    onFiltroEstadoChange: (String) -> Unit
) {
    val filtrosTipo = listOf("Todos", "vencido", "hoy", "próximo")
    val filtrosEstado = listOf("Todos", "activo", "inactivo", "mora")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Filtros", fontWeight = FontWeight.Bold, fontSize = 14.sp)

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text("Urgencia:", fontSize = 12.sp, color = NotificationColors.TextSecondary)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                items(filtrosTipo) { filtro ->
                    FilterChip(
                        selected = filtroTipo == filtro,
                        onClick = { onFiltroTipoChange(filtro) },
                        label = { Text(filtro, fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text("Estado:", fontSize = 12.sp, color = NotificationColors.TextSecondary)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                items(filtrosEstado) { filtro ->
                    FilterChip(
                        selected = filtroEstado == filtro,
                        onClick = { onFiltroEstadoChange(filtro) },
                        label = { Text(filtro, fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun NotificacionCardCascada(
    notif: NotificacionCobroCascada,
    rol: String,
    uid: String,
    context: Context,
    navController: NavHostController,
    db: FirebaseFirestore,
    onAplicarMora: (String, String, String) -> Unit
) {
    val scope = rememberCoroutineScope()

    val (urgenciaColor, urgenciaIcon) = when {
        notif.diferenciaDias < -3 -> NotificationColors.DangerRed to Icons.Default.ErrorOutline
        notif.diferenciaDias < 0 -> NotificationColors.WarningOrange to Icons.Default.Warning
        notif.diferenciaDias == 0 -> NotificationColors.InfoBlue to Icons.Default.Today
        notif.diferenciaDias <= 3 -> NotificationColors.SuccessGreen to Icons.Default.Schedule
        else -> NotificationColors.TextSecondary to Icons.Default.Schedule
    }

    val estadoText = when {
        notif.diferenciaDias < -3 -> "MORA (${-notif.diferenciaDias}d)"
        notif.diferenciaDias < 0 -> "VENCIDO (${-notif.diferenciaDias}d)"
        notif.diferenciaDias == 0 -> "HOY"
        notif.diferenciaDias == 1 -> "MAÑANA"
        else -> "${notif.diferenciaDias}d"
    }

    val progresoCuotas = if (notif.totalCuotas > 0) {
        notif.cuotasCompletadas.toFloat() / notif.totalCuotas.toFloat()
    } else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        notif.cliente,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = NotificationColors.DarkBlue,
                        maxLines = 1
                    )
                    Text(
                        "L. ${"%.2f".format(notif.montoSaldoPendiente)}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NotificationColors.PrimaryBlue
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = urgenciaColor.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            urgenciaIcon,
                            contentDescription = null,
                            tint = urgenciaColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            estadoText,
                            color = urgenciaColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Cuota ${notif.proximaCuotaNumero}/${notif.totalCuotas}",
                        fontSize = 13.sp,
                        color = NotificationColors.TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        notif.fechaProximaCuota,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = NotificationColors.DarkBlue
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${(progresoCuotas * 100).toInt()}% completo",
                        fontSize = 12.sp,
                        color = NotificationColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = progresoCuotas,
                        modifier = Modifier
                            .width(80.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = urgenciaColor,
                        trackColor = Color.LightGray.copy(alpha = 0.3f)
                    )
                }
            }

            if (!notif.ultimoPago.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Último pago: ${notif.ultimoPago}",
                    fontSize = 12.sp,
                    color = NotificationColors.TextSecondary
                )
            }

            if (notif.estado != "inactivo") {
                Spacer(modifier = Modifier.height(16.dp))

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
                                        navController.navigate("RegistrarPagoScreen/${clienteId}/${notif.prestamoId}/${notif.montoSaldoPendiente}/$rol")
                                    } else {
                                        Toast.makeText(context, "Cliente no encontrado", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NotificationColors.PrimaryBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Payment,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pagar", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            navController.navigate("CuotasPrestamoScreen/${notif.prestamoId}/${uid}/${rol}")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, NotificationColors.PrimaryBlue)
                    ) {
                        Icon(
                            Icons.Default.List,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = NotificationColors.PrimaryBlue
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Cuotas",
                            fontSize = 13.sp,
                            color = NotificationColors.PrimaryBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                enviarMensajeWhatsAppConNumero(context, notif.cliente, db)
                            }
                        },
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, Color(0xFF25D366)),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Default.Message,
                            contentDescription = "WhatsApp",
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF25D366)
                        )
                    }

                    if ((rol == "admin" || rol == "cobrador") && notif.diferenciaDias < -3) {
                        val diasMoraReales = -notif.diferenciaDias
                        val moraSugerida = "%.2f".format(notif.montoSaldoPendiente * 0.005 * diasMoraReales)

                        Button(
                            onClick = {
                                onAplicarMora(notif.prestamoId, notif.cliente, moraSugerida)
                            },
                            modifier = Modifier.size(40.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NotificationColors.DangerRed),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Mora",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aplicar mora") },
        text = {
            Column {
                Text("Cliente: $cliente")
                Text("Días de mora: $diasMora")
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = moraTexto,
                    onValueChange = { moraTexto = it },
                    label = { Text("Monto de mora (L)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
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
                }
            ) {
                Text("Aplicar")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

suspend fun enviarMensajeWhatsAppConNumero(
    context: Context,
    clienteNombre: String,
    db: FirebaseFirestore
) {
    try {
        val clienteDocs = db.collection("clientes")
            .whereEqualTo("nombre", clienteNombre)
            .whereEqualTo("eliminado", false)
            .limit(1)
            .get().await()

        val doc = clienteDocs.documents.firstOrNull()
        if (doc == null) {
            Toast.makeText(context, "Cliente no encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        val numero = doc.getString("telefono")?.filter { it.isDigit() } ?: ""
        val nombre = doc.getString("nombre") ?: "cliente"

        if (numero.isBlank()) {
            Toast.makeText(context, "Número de teléfono no encontrado", Toast.LENGTH_SHORT).show()
            return
        }

        val mensaje = "Estimado/a $nombre, le recordamos que tiene un pago pendiente con Capital Express. ¡Gracias!"
        val url = "https://wa.me/504$numero?text=${Uri.encode(mensaje)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show()
        }

    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}