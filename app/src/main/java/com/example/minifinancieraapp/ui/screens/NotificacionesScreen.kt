import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────
//  DATA CLASS
// ─────────────────────────────────────────────
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
    val cuotasCompletadas: Int = 0,
    val numeroPrestamo: String = "N/D",
    val tieneMoraActiva: Boolean = false,
    val cantidadMorasAplicadas: Int = 0,
    // ✅ totalPagar del préstamo — base fija para calcular mora
    val totalPagarOriginal: Double = 0.0,
    val fechaUltimaMora: Date? = null
)

// ─────────────────────────────────────────────
//  PALETA DE COLORES
// ─────────────────────────────────────────────
object NC {
    val Navy        = Color(0xFF0A1628)
    val NavyMid     = Color(0xFF0F2044)
    val Blue        = Color(0xFF1A56DB)
    val BlueSoft    = Color(0xFF3B82F6)
    val BlueLight   = Color(0xFFEFF6FF)

    val Red         = Color(0xFFEF4444)
    val RedSoft     = Color(0xFFFEF2F2)
    val Orange      = Color(0xFFF97316)
    val OrangeSoft  = Color(0xFFFFF7ED)
    val Amber       = Color(0xFFF59E0B)
    val AmberSoft   = Color(0xFFFFFBEB)
    val Green       = Color(0xFF10B981)
    val GreenSoft   = Color(0xFFECFDF5)

    val Surface     = Color(0xFFF8FAFC)
    val Card        = Color(0xFFFFFFFF)
    val Border      = Color(0xFFE2E8F0)
    val TextPrimary = Color(0xFF0F172A)
    val TextSec     = Color(0xFF64748B)
    val TextMuted   = Color(0xFF94A3B8)

    val GradStart   = Color(0xFF0A1628)
    val GradEnd     = Color(0xFF1A3A6B)
}

// ─────────────────────────────────────────────
//  FORMATEADORES / FECHAS
// ─────────────────────────────────────────────
private val fmtFecha     = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
private val fmtFechaHora = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

private fun startOfDay(d: Date): Date {
    val c = Calendar.getInstance().apply { time = d }
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.time
}

private fun daysBetween(from: Date, to: Date): Int {
    val a = startOfDay(from).time
    val b = startOfDay(to).time
    return ((b - a) / (1000L * 60L * 60L * 24L)).toInt()
}

private fun parseFlexibleDate(raw: Any?): Date? {
    return try {
        when (raw) {
            is Timestamp -> raw.toDate()
            is Date      -> raw
            is String    -> {
                val s = raw.trim()
                if (s.isBlank() || s.equals("saldado", true) || s.equals("null", true)) null
                else fmtFecha.parse(s.take(10))
            }
            else -> null
        }
    } catch (_: Exception) { null }
}

private fun addOneInterval(base: Date, plazo: String): Date {
    val cal = Calendar.getInstance().apply { time = base }
    when (plazo.lowercase().trim()) {
        "diario"                           -> cal.add(Calendar.DAY_OF_YEAR, 1)
        "lunes a sábado", "lunes a sabado" -> {
            do { cal.add(Calendar.DAY_OF_YEAR, 1) }
            while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
        }
        "semanal"   -> cal.add(Calendar.DAY_OF_YEAR, 7)
        "quincenal" -> cal.add(Calendar.DAY_OF_YEAR, 15)
        "mensual"   -> cal.add(Calendar.MONTH, 1)
        "bimestral" -> cal.add(Calendar.MONTH, 2)
        else        -> cal.add(Calendar.MONTH, 1)
    }
    return cal.time
}

private fun obtenerFechaInicioPrestamo(doc: DocumentSnapshot): Date {
    doc.getTimestamp("fecha")?.toDate()?.let { return it }
    doc.getTimestamp("fechaCreacion")?.toDate()?.let { return it }
    parseFlexibleDate(doc.get("fecha"))?.let { return it }
    return Date()
}

private fun obtenerProximoPagoDelPrestamo(doc: DocumentSnapshot): Date? =
    parseFlexibleDate(doc.get("proximoPago"))

private fun ultimaFechaPagoDesdePagos(pagos: List<Map<String, Any>>): Date? {
    var max: Date? = null
    for (p in pagos) {
        val d = parseFlexibleDate(p["fechaPago"])
        if (d != null && (max == null || d.after(max))) max = d
    }
    return max
}

// ─────────────────────────────────────────────
//  CACHE
// ─────────────────────────────────────────────
private val fechasCuotasCache = ConcurrentHashMap<String, String>()

// ─────────────────────────────────────────────
//  HELPERS
// ─────────────────────────────────────────────
fun DocumentSnapshot.getNumeroPrestamoSafeNotif(): String {
    return try {
        if (!this.contains("numeroPrestamo")) return "N/D"
        when (val raw = this.get("numeroPrestamo")) {
            null      -> "N/D"
            is String -> if (raw.isNotBlank()) raw else "N/D"
            is Long   -> raw.toString()
            is Int    -> raw.toString()
            is Double -> raw.toInt().toString()
            is Number -> raw.toString()
            else      -> raw.toString().takeIf { it.isNotBlank() && it != "null" } ?: "N/D"
        }
    } catch (_: Exception) { "N/D" }
}

fun obtenerUltimoPagoOptimizado(doc: DocumentSnapshot): String? {
    return try {
        when (val raw = doc.get("ultimoPago")) {
            is String    -> raw.takeIf { it.isNotBlank() && it != "saldado" }
            is Timestamp -> fmtFechaHora.format(raw.toDate())
            is Date      -> fmtFechaHora.format(raw)
            else         -> null
        }
    } catch (_: Exception) { null }
}

// ─────────────────────────────────────────────
//  PROCESAMIENTO
// ─────────────────────────────────────────────
suspend fun procesarPrestamoUltraOptimizado(
    doc: DocumentSnapshot,
    pagosCache: Map<String, List<Map<String, Any>>>
): NotificacionCobroCascada? {
    return try {
        val cliente = doc.getString("cliente") ?: return null
        if (cliente.isBlank()) return null
        if (doc.getBoolean("eliminado") == true) return null

        val prestamoId     = doc.id
        val numeroPrestamo = doc.getNumeroPrestamoSafeNotif()
        val plazo          = (doc.getString("plazo") ?: "semanal").trim()
        val estadoDoc      = doc.getString("estado")?.lowercase()?.trim() ?: "activo"
        val cuotasNum      = doc.getLong("cuotas")?.toInt() ?: 1

        val estadosExcluidos = setOf(
            "saldado", "completado", "cancelado", "eliminado", "rechazado", "pendiente"
        )
        if (estadoDoc in estadosExcluidos) return null
        if (cuotasNum <= 0 || cuotasNum > 500) return null

        val saldoPendiente = doc.getDouble("saldo") ?: 0.0
        val cuotaEstimada  = doc.getDouble("cuota") ?: 0.0
        val montoPagado    = doc.getDouble("montoPagado") ?: 0.0

        // ✅ totalPagar: base fija para la fórmula de mora
        // Se lee directamente del documento; si no existe se reconstruye como monto+interés
        val montoPrestamo  = doc.getDouble("monto") ?: 0.0
        val interesTotal   = doc.getDouble("interesTotal")
            ?: doc.getDouble("interes") ?: 0.0
        val totalPagar     = doc.getDouble("totalPagar")
            ?: (montoPrestamo + interesTotal)

        if (saldoPendiente <= 0.01 && montoPagado >= totalPagar - 0.01) return null

        // Datos de mora
        val moraActual      = doc.getDouble("mora") ?: 0.0
        val tieneMoraActiva = moraActual > 0.0
        val morasAplicadas  = (doc.get("morasAplicadas") as? List<*>)?.size ?: 0
        val fechaUltimaMora = doc.getTimestamp("fechaUltimaMora")?.toDate()

        val pagosDelPrestamo = pagosCache[prestamoId] ?: emptyList()
        val montoPorCuota    = mutableMapOf<Int, Double>()

        for (pago in pagosDelPrestamo) {
            @Suppress("UNCHECKED_CAST")
            val cuotasCubiertasRaw = pago["cuotasCubiertas"]
            when {
                cuotasCubiertasRaw is List<*> && cuotasCubiertasRaw.isNotEmpty() -> {
                    for (item in cuotasCubiertasRaw) {
                        val cuotaMap      = item as? Map<*, *> ?: continue
                        val num           = (cuotaMap["numeroCuota"] as? Number)?.toInt() ?: continue
                        val montoAplicado = (cuotaMap["montoAplicado"] as? Number)?.toDouble() ?: 0.0
                        if (num > 0 && montoAplicado > 0)
                            montoPorCuota[num] = (montoPorCuota[num] ?: 0.0) + montoAplicado
                    }
                }
                else -> {
                    val num = when {
                        pago.containsKey("numeroCuota") -> (pago["numeroCuota"] as? Number)?.toInt() ?: 1
                        pago.containsKey("cuota")       -> (pago["cuota"] as? Number)?.toInt() ?: 1
                        else -> 1
                    }
                    val monto = (pago["monto"] as? Number)?.toDouble() ?: 0.0
                    val mora  = (pago["mora"]  as? Number)?.toDouble() ?: 0.0
                    val total = monto + mora
                    if (num > 0 && total > 0)
                        montoPorCuota[num] = (montoPorCuota[num] ?: 0.0) + total
                }
            }
        }

        var cuotasCompletadas  = 0
        var proximaCuotaNumero = -1

        for (i in 1..cuotasNum) {
            val pagado = montoPorCuota[i] ?: 0.0
            if (cuotaEstimada > 0 && pagado >= cuotaEstimada - 0.01) {
                cuotasCompletadas++
            } else if (proximaCuotaNumero == -1) {
                proximaCuotaNumero = i
                break
            }
        }
        if (proximaCuotaNumero == -1) proximaCuotaNumero = cuotasNum + 1
        if (cuotasCompletadas >= cuotasNum && saldoPendiente <= 0.01) return null

        val ultimoPagoRealDate = ultimaFechaPagoDesdePagos(pagosDelPrestamo)
        val fechaInicio        = obtenerFechaInicioPrestamo(doc)
        val proximoPagoDoc     = obtenerProximoPagoDelPrestamo(doc)

        val fechaProximaCuotaDate: Date = when {
            proximaCuotaNumero > cuotasNum && saldoPendiente <= 0.01 -> return null
            proximoPagoDoc != null     -> proximoPagoDoc
            ultimoPagoRealDate != null -> addOneInterval(ultimoPagoRealDate, plazo)
            else                       -> addOneInterval(fechaInicio, plazo)
        }

        val fechaProximaCuota = fmtFecha.format(fechaProximaCuotaDate)
        val diasHastaProximo  = daysBetween(Date(), fechaProximaCuotaDate)

        val (tipo, estadoFinal) = when {
            estadoDoc.equals("inactivo", ignoreCase = true) -> "inactivo" to "inactivo"
            diasHastaProximo < -3  -> "vencido" to "mora"
            diasHastaProximo < 0   -> "vencido" to "activo"
            diasHastaProximo == 0  -> "hoy"     to "activo"
            diasHastaProximo <= 3  -> "próximo" to "activo"
            else                   -> "futuro"  to "activo"
        }

        NotificacionCobroCascada(
            cliente                = cliente,
            montoSaldoPendiente    = saldoPendiente,
            fechaProximaCuota      = fechaProximaCuota,
            tipo                   = tipo,
            prestamoId             = prestamoId,
            plazo                  = plazo,
            diferenciaDias         = diasHastaProximo,
            estado                 = estadoFinal,
            ultimoPago             = ultimoPagoRealDate?.let { fmtFechaHora.format(it) }
                ?: obtenerUltimoPagoOptimizado(doc),
            proximaCuotaNumero     = proximaCuotaNumero,
            totalCuotas            = cuotasNum,
            cuotasCompletadas      = cuotasCompletadas,
            numeroPrestamo         = numeroPrestamo,
            tieneMoraActiva        = tieneMoraActiva,
            cantidadMorasAplicadas = morasAplicadas,
            // ✅ totalPagar sin moras acumuladas = base fija para calcular mora
            totalPagarOriginal     = totalPagar,
            fechaUltimaMora        = fechaUltimaMora
        )
    } catch (e: Exception) {
        Log.e("Notif", "Error en ${doc.id}: ${e.message}", e)
        null
    }
}

// ─────────────────────────────────────────────
//  PANTALLA PRINCIPAL
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificacionesScreen(navController: NavHostController, uid: String, rol: String) {
    val context = LocalContext.current
    val db      = FirebaseFirestore.getInstance()
    val scope   = rememberCoroutineScope()

    val notificaciones         = remember { mutableStateListOf<NotificacionCobroCascada>() }
    val todasLasNotificaciones = remember { mutableStateListOf<NotificacionCobroCascada>() }
    var isLoading    by remember { mutableStateOf(true) }
    var hasError     by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    var filtroTipo    by rememberSaveable { mutableStateOf("Todos") }
    var textoBusqueda by rememberSaveable { mutableStateOf("") }

    var mostrarDialogoMora  by remember { mutableStateOf(false) }
    var clienteMora         by remember { mutableStateOf("") }
    var prestamoIdMora      by remember { mutableStateOf("") }
    var moraCalculada       by remember { mutableStateOf("") }
    var diasMora            by remember { mutableStateOf(0) }
    var totalPagarMora      by remember { mutableStateOf(0.0) }
    var esSegundaMora       by remember { mutableStateOf(false) }

    // ─── CARGA ───────────────────────────────────────────────────────────
    suspend fun cargarNotificaciones() {
        try {
            isLoading = true
            hasError  = false
            todasLasNotificaciones.clear()
            fechasCuotasCache.clear()

            val (documentosRaw, pagosSnapshot) = withContext(Dispatchers.IO) {

                val pagosDeferred = async {
                    db.collection("pagos").get().await()
                }

                val docsDeferred = async {
                    if (rol != "cobrador") {
                        db.collection("prestamos")
                            .whereEqualTo("eliminado", false)
                            .get().await()
                            .documents
                    } else {
                        val clientes1 = async {
                            try {
                                db.collection("clientes")
                                    .whereEqualTo("cobradorAsignado", uid)
                                    .get().await()
                                    .documents
                                    .filter { it.getBoolean("eliminado") != true }
                                    .map { it.id }
                            } catch (e: Exception) {
                                Log.w("Notif", "clientes.cobradorAsignado falló: ${e.message}")
                                emptyList()
                            }
                        }

                        val clientes2 = async {
                            try {
                                db.collection("clientes")
                                    .whereArrayContains("cobradoresAsignados", uid)
                                    .get().await()
                                    .documents
                                    .filter { it.getBoolean("eliminado") != true }
                                    .map { it.id }
                            } catch (e: Exception) {
                                Log.w("Notif", "clientes.cobradoresAsignados falló: ${e.message}")
                                emptyList()
                            }
                        }

                        val todosClienteIds = (clientes1.await() + clientes2.await()).distinct()

                        val prestamosPorClientes = if (todosClienteIds.isNotEmpty()) {
                            todosClienteIds
                                .chunked(10)
                                .map { chunk ->
                                    async {
                                        try {
                                            db.collection("prestamos")
                                                .whereIn("clienteId", chunk)
                                                .get().await()
                                                .documents
                                                .filter { it.getBoolean("eliminado") != true }
                                        } catch (e: Exception) {
                                            Log.w("Notif", "whereIn clienteId falló: ${e.message}")
                                            emptyList()
                                        }
                                    }
                                }.flatMap { it.await() }
                        } else {
                            emptyList()
                        }

                        val qCobradorUid = async {
                            try {
                                db.collection("prestamos")
                                    .whereEqualTo("cobradorUid", uid)
                                    .get().await()
                                    .documents
                                    .filter { it.getBoolean("eliminado") != true }
                            } catch (e: Exception) {
                                Log.w("Notif", "Q cobradorUid falló: ${e.message}")
                                emptyList()
                            }
                        }

                        val q1 = async {
                            try {
                                db.collection("prestamos")
                                    .whereArrayContains("cobradoresAsignados", uid)
                                    .get().await()
                                    .documents
                                    .filter { it.getBoolean("eliminado") != true }
                            } catch (e: Exception) {
                                Log.w("Notif", "Q1 cobradoresAsignados falló: ${e.message}")
                                emptyList()
                            }
                        }

                        val q2 = async {
                            try {
                                db.collection("prestamos")
                                    .whereEqualTo("cobradorAsignado", uid)
                                    .get().await()
                                    .documents
                                    .filter { it.getBoolean("eliminado") != true }
                            } catch (e: Exception) {
                                Log.w("Notif", "Q2 cobradorAsignado falló: ${e.message}")
                                emptyList()
                            }
                        }

                        val q3 = async {
                            try {
                                db.collection("prestamos")
                                    .whereEqualTo("cobrador", uid)
                                    .get().await()
                                    .documents
                                    .filter { it.getBoolean("eliminado") != true }
                            } catch (e: Exception) {
                                Log.w("Notif", "Q3 cobrador legacy falló: ${e.message}")
                                emptyList()
                            }
                        }

                        val todos = prestamosPorClientes +
                                qCobradorUid.await() +
                                q1.await() +
                                q2.await() +
                                q3.await()

                        val vistos = mutableSetOf<String>()
                        todos.filter { vistos.add(it.id) }
                    }
                }

                docsDeferred.await() to pagosDeferred.await()
            }

            val documentos = documentosRaw
            if (documentos.isEmpty()) {
                isLoading = false
                notificaciones.clear()
                return
            }

            val pagosCache = withContext(Dispatchers.Default) {
                val cache = mutableMapOf<String, MutableList<Map<String, Any>>>()
                for (pagoDoc in pagosSnapshot.documents) {
                    val pid  = pagoDoc.getString("prestamoId") ?: continue
                    val data = pagoDoc.data ?: continue
                    cache.getOrPut(pid) { mutableListOf() }.add(data)
                }
                cache as Map<String, List<Map<String, Any>>>
            }

            val estadosExcluidos = setOf(
                "saldado", "completado", "cancelado", "eliminado", "rechazado", "pendiente"
            )

            val documentosValidos = withContext(Dispatchers.Default) {
                documentos.filter { doc ->
                    if (doc.getBoolean("eliminado") == true) return@filter false
                    val cliente = doc.getString("cliente")
                    val cuotas  = doc.getLong("cuotas")?.toInt() ?: 0
                    val estado  = doc.getString("estado")?.lowercase()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: "activo"
                    !cliente.isNullOrBlank() &&
                            cuotas > 0 && cuotas <= 500 &&
                            estado !in estadosExcluidos
                }
            }

            val resultado = withContext(Dispatchers.Default) {
                documentosValidos.chunked(50).flatMap { chunk ->
                    chunk.mapNotNull { doc ->
                        try { procesarPrestamoUltraOptimizado(doc, pagosCache) }
                        catch (_: Exception) { null }
                    }
                }
            }

            val ordenado = withContext(Dispatchers.Default) {
                resultado.sortedWith(
                    compareBy<NotificacionCobroCascada> {
                        when (it.tipo) {
                            "vencido" -> 0
                            "hoy"     -> 1
                            "próximo" -> 2
                            "futuro"  -> 3
                            else      -> 4
                        }
                    }.thenBy { it.diferenciaDias }
                )
            }

            todasLasNotificaciones.addAll(ordenado)

        } catch (e: Exception) {
            hasError = true
            errorMessage = when {
                e.message?.contains("network", ignoreCase = true) == true    -> "Sin conexión a internet"
                e.message?.contains("permission", ignoreCase = true) == true -> "Sin permisos de acceso"
                else -> "Error inesperado. Intenta de nuevo."
            }
            Log.e("Notif", "Error general: ${e.message}", e)
        } finally {
            isLoading = false
        }
    }

    fun aplicarFiltros() {
        notificaciones.clear()
        val filtradas = todasLasNotificaciones.asSequence().filter { n ->
            val pasaTipo     = filtroTipo == "Todos" || filtroTipo == n.tipo
            val pasaBusqueda = textoBusqueda.isBlank() ||
                    n.cliente.contains(textoBusqueda, ignoreCase = true)
            pasaTipo && pasaBusqueda
        }.toList()
        notificaciones.addAll(filtradas)
    }

    LaunchedEffect(filtroTipo, textoBusqueda, todasLasNotificaciones.size) { aplicarFiltros() }
    LaunchedEffect(Unit) { scope.launch { cargarNotificaciones() } }

    // ─── UI ──────────────────────────────────────────────────────────────
    Scaffold(
        containerColor = NC.Surface,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(colors = listOf(NC.GradStart, NC.GradEnd)))
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
                        Column {
                            Text(
                                "Cobros",
                                fontSize      = 28.sp,
                                fontWeight    = FontWeight.ExtraBold,
                                color         = Color.White,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                "Gestión de notificaciones",
                                fontSize   = 13.sp,
                                color      = Color.White.copy(alpha = 0.6f),
                                fontWeight = FontWeight.Normal
                            )
                        }
                        IconButton(
                            onClick  = { scope.launch { cargarNotificaciones() } },
                            enabled  = !isLoading,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(18.dp),
                                    color       = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Actualizar",
                                    tint               = Color.White,
                                    modifier           = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value         = textoBusqueda,
                        onValueChange = { textoBusqueda = it },
                        placeholder   = {
                            Text(
                                "Buscar cliente...",
                                color    = Color.White.copy(alpha = 0.5f),
                                fontSize = 14.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint               = Color.White.copy(alpha = 0.7f),
                                modifier           = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (textoBusqueda.isNotBlank()) {
                                IconButton(onClick = { textoBusqueda = "" }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Limpiar",
                                        tint               = Color.White.copy(alpha = 0.7f),
                                        modifier           = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        modifier   = Modifier.fillMaxWidth(),
                        shape      = RoundedCornerShape(14.dp),
                        colors     = OutlinedTextFieldDefaults.colors(
                            focusedTextColor        = Color.White,
                            unfocusedTextColor      = Color.White,
                            focusedBorderColor      = Color.White.copy(alpha = 0.5f),
                            unfocusedBorderColor    = Color.White.copy(alpha = 0.2f),
                            focusedContainerColor   = Color.White.copy(alpha = 0.1f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.07f),
                            cursorColor             = Color.White
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                    )
                }
            }
        }
    ) { padding ->

        when {
            hasError  -> ErrorView(errorMessage) { scope.launch { cargarNotificaciones() } }
            isLoading -> LoadingView()
            else -> {
                val vencidos = notificaciones.count { it.tipo == "vencido" }
                val hoyCount = notificaciones.count { it.tipo == "hoy" }
                val proximos = notificaciones.count { it.tipo == "próximo" }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding()),
                    contentPadding = PaddingValues(
                        top    = 20.dp,
                        bottom = 32.dp,
                        start  = 16.dp,
                        end    = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        ResumenRow(
                            vencidos = vencidos,
                            hoy      = hoyCount,
                            proximos = proximos,
                            total    = notificaciones.size
                        )
                    }

                    item {
                        FiltrosTipo(
                            filtroActual   = filtroTipo,
                            onFiltroChange = { filtroTipo = it }
                        )
                    }

                    if (notificaciones.isEmpty()) {
                        item { EstadoVacio() }
                    } else {
                        itemsIndexed(
                            items = notificaciones,
                            key   = { _, n -> n.prestamoId }
                        ) { index, notif ->
                            AnimatedVisibility(
                                visible = true,
                                enter   = fadeIn(
                                    animationSpec = tween(
                                        durationMillis = 300,
                                        delayMillis    = (index * 30).coerceAtMost(300)
                                    )
                                ) + slideInVertically(
                                    initialOffsetY = { it / 4 },
                                    animationSpec  = tween(
                                        durationMillis = 300,
                                        delayMillis    = (index * 30).coerceAtMost(300)
                                    )
                                )
                            ) {
                                NotifCard(
                                    notif         = notif,
                                    rol           = rol,
                                    uid           = uid,
                                    context       = context,
                                    navController = navController,
                                    db            = db,
                                    onAplicarMora = { pid, cli, mora, esSeg, totalPagar, dias ->
                                        prestamoIdMora  = pid
                                        clienteMora     = cli
                                        moraCalculada   = mora
                                        diasMora        = dias
                                        totalPagarMora  = totalPagar
                                        esSegundaMora   = esSeg
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

    // ── Diálogo mora ─────────────────────────────────────────────────────
    if (mostrarDialogoMora) {
        DialogoAplicarMora(
            cliente       = clienteMora,
            moraSugerida  = moraCalculada,
            diasMora      = diasMora,
            totalPagar    = totalPagarMora,
            esSegundaMora = esSegundaMora,
            onDismiss     = { mostrarDialogoMora = false },
            onConfirmar   = { montoMora ->
                scope.launch {
                    try {
                        val ref = db.collection("prestamos").document(prestamoIdMora)
                        val doc = ref.get().await()
                        if (doc.getBoolean("eliminado") == true) {
                            Toast.makeText(context, "Préstamo eliminado", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val saldoActual      = doc.getDouble("saldo") ?: 0.0
                        val morasAplicadas   = (doc.get("morasAplicadas") as? List<*>)
                            ?.mapNotNull { it as? String } ?: emptyList()

                        // ✅ FIX: "totalPagar" es SIEMPRE la base fija del préstamo
                        // (monto + interés) y NUNCA debe incluir la mora. La mora
                        // se guarda por separado en el campo "mora"; solo se suma
                        // a totalPagar en tiempo de lectura (ej. totalConMora en
                        // VerPrestamoScreen) para calcular saldo/estado real.
                        // Antes aquí se hacía "totalPagar" to (totalPagarActual + montoMora),
                        // lo que duplicaba el monto de la mora y provocaba saldos
                        // incorrectos al cancelarla.
                        val updateMap = mutableMapOf<String, Any>(
                            "saldo"                    to (saldoActual + montoMora),
                            "mora"                     to montoMora,
                            "morasAplicadas"           to (morasAplicadas + "${clienteMora}_${System.currentTimeMillis()}"),
                            "estado"                   to "mora",
                            "fechaUltimaActualizacion" to Timestamp.now(),
                            "fechaUltimaMora"          to Timestamp.now()
                        )
                        ref.update(updateMap).await()

                        Toast.makeText(
                            context,
                            "✅ Mora de L. ${"%.2f".format(montoMora)} aplicada",
                            Toast.LENGTH_SHORT
                        ).show()
                        cargarNotificaciones()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        mostrarDialogoMora = false
                    }
                }
            }
        )
    }
}

// ─────────────────────────────────────────────
//  COMPONENTES UI
// ─────────────────────────────────────────────
@Composable
private fun ResumenRow(vencidos: Int, hoy: Int, proximos: Int, total: Int) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ResumenChip("Total",    total,    NC.Blue,   NC.BlueLight,  Modifier.weight(1f))
        ResumenChip("Mora",     vencidos, NC.Red,    NC.RedSoft,    Modifier.weight(1f))
        ResumenChip("Hoy",      hoy,      NC.Orange, NC.OrangeSoft, Modifier.weight(1f))
        ResumenChip("Próximos", proximos, NC.Green,  NC.GreenSoft,  Modifier.weight(1f))
    }
}

@Composable
private fun ResumenChip(
    label: String, count: Int, color: Color, bgColor: Color, modifier: Modifier = Modifier
) {
    Card(
        modifier  = modifier,
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(count.toString(), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = color)
            Text(
                label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = color.copy(alpha = 0.75f), textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FiltrosTipo(filtroActual: String, onFiltroChange: (String) -> Unit) {
    val opciones = listOf("Todos", "vencido", "hoy", "próximo", "futuro", "inactivo")
    val labels   = mapOf(
        "Todos"    to "Todos",
        "vencido"  to "En mora",
        "hoy"      to "Hoy",
        "próximo"  to "Próximos",
        "futuro"   to "Futuros",
        "inactivo" to "Inactivos"
    )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding        = PaddingValues(horizontal = 0.dp)
    ) {
        items(opciones) { opcion ->
            val isSelected = filtroActual == opcion
            val color = when (opcion) {
                "vencido"  -> NC.Red
                "hoy"      -> NC.Orange
                "próximo"  -> NC.Green
                "futuro"   -> NC.BlueSoft
                "inactivo" -> NC.TextSec
                else       -> NC.Blue
            }
            FilterChip(
                selected = isSelected,
                onClick  = { onFiltroChange(opcion) },
                label    = {
                    Text(
                        labels[opcion] ?: opcion,
                        fontSize   = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                shape  = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = color.copy(alpha = 0.15f),
                    selectedLabelColor     = color,
                    containerColor         = NC.Card,
                    labelColor             = NC.TextSec
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled             = true,
                    selected            = isSelected,
                    selectedBorderColor = color.copy(alpha = 0.4f),
                    borderColor         = NC.Border
                )
            )
        }
    }
}

// ─────────────────────────────────────────────
//  TARJETA DE NOTIFICACIÓN
// ─────────────────────────────────────────────
@Composable
private fun NotifCard(
    notif: NotificacionCobroCascada,
    rol: String,
    uid: String,
    context: Context,
    navController: NavHostController,
    db: FirebaseFirestore,
    onAplicarMora: (pid: String, cli: String, mora: String, esSegunda: Boolean, totalPagar: Double, dias: Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val dec   = DecimalFormat("#,##0.00")

    val urgencia = when {
        notif.diferenciaDias < -3 -> UrgenciaStyle(NC.Red,     NC.RedSoft,    "EN MORA",   Icons.Default.ErrorOutline, "${-notif.diferenciaDias}d")
        notif.diferenciaDias < 0  -> UrgenciaStyle(NC.Orange,  NC.OrangeSoft, "VENCIDO",   Icons.Default.Warning,      "${-notif.diferenciaDias}d")
        notif.diferenciaDias == 0 -> UrgenciaStyle(NC.Amber,   NC.AmberSoft,  "HOY",       Icons.Default.Today,        "Hoy")
        notif.diferenciaDias <= 3 -> UrgenciaStyle(NC.Green,   NC.GreenSoft,  "PRÓXIMO",   Icons.Outlined.Schedule,    "${notif.diferenciaDias}d")
        else                      -> UrgenciaStyle(NC.TextSec, NC.Surface,    "PENDIENTE", Icons.Outlined.Schedule,    "${notif.diferenciaDias}d")
    }

    val progreso = if (notif.totalCuotas > 0)
        notif.cuotasCompletadas.toFloat() / notif.totalCuotas.toFloat()
    else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation    = if (notif.diferenciaDias < 0) 6.dp else 2.dp,
                shape        = RoundedCornerShape(20.dp),
                ambientColor = urgencia.color.copy(alpha = 0.12f),
                spotColor    = urgencia.color.copy(alpha = 0.18f)
            ),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = NC.Card),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(urgencia.color, urgencia.color.copy(alpha = 0.3f))
                        )
                    )
            )

            Column(modifier = Modifier.padding(16.dp)) {

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.Top
                ) {
                    Column(modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)) {
                        Text(
                            notif.cliente,
                            fontSize   = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color      = NC.TextPrimary,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                        if (notif.numeroPrestamo != "N/D") {
                            Text(
                                "# ${notif.numeroPrestamo}",
                                fontSize   = 12.sp,
                                color      = NC.TextMuted,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(urgencia.bgColor)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(urgencia.icon, contentDescription = null,
                            tint = urgencia.color, modifier = Modifier.size(13.dp))
                        Text(urgencia.label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                            color = urgencia.color, letterSpacing = 0.3.sp)
                        Text(urgencia.dias, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = urgencia.color.copy(alpha = 0.8f))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Saldo pendiente", fontSize = 11.sp, color = NC.TextMuted, fontWeight = FontWeight.Medium)
                        Text(
                            "L. ${dec.format(notif.montoSaldoPendiente)}",
                            fontSize   = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color      = NC.TextPrimary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Próxima cuota", fontSize = 11.sp, color = NC.TextMuted, fontWeight = FontWeight.Medium)
                        Text(
                            notif.fechaProximaCuota,
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color      = urgencia.color
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        "Cuota ${notif.proximaCuotaNumero} de ${notif.totalCuotas}",
                        fontSize   = 12.sp,
                        color      = NC.TextSec,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${(progreso * 100).toInt()}%",
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color      = urgencia.color
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress   = progreso,
                    modifier   = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color      = urgencia.color,
                    trackColor = NC.Border
                )

                if (!notif.ultimoPago.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                            tint = NC.Green, modifier = Modifier.size(12.dp))
                        Text("Último pago: ${notif.ultimoPago}", fontSize = 11.sp, color = NC.TextMuted)
                    }
                }

                if (notif.estado != "inactivo") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = NC.Border, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        // ── Botón Pagar ───────────────────────────────────────────
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val prestamoDoc = db.collection("prestamos")
                                            .document(notif.prestamoId).get().await()
                                        val clienteId = prestamoDoc.getString("clienteId")
                                        if (!clienteId.isNullOrEmpty()) {
                                            navController.navigate(
                                                "RegistrarPagoScreen/${clienteId}/${notif.prestamoId}/${notif.montoSaldoPendiente}/$rol"
                                            )
                                        } else {
                                            Toast.makeText(context, "Cliente no encontrado", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier       = Modifier
                                .weight(1f)
                                .height(38.dp),
                            colors         = ButtonDefaults.buttonColors(containerColor = NC.Blue),
                            shape          = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pagar", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        // ── Botón Cuotas ──────────────────────────────────────────
                        OutlinedButton(
                            onClick = {
                                navController.navigate("CuotasPrestamoScreen/${notif.prestamoId}/$uid/$rol")
                            },
                            modifier       = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape          = RoundedCornerShape(10.dp),
                            border         = BorderStroke(1.dp, NC.Border),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors         = ButtonDefaults.outlinedButtonColors(contentColor = NC.TextSec)
                        ) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cuotas", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }

                        // ── Botón WhatsApp ────────────────────────────────────────
                        OutlinedButton(
                            onClick = {
                                scope.launch { enviarMensajeWhatsAppConNumero(context, notif.cliente, db) }
                            },
                            modifier       = Modifier.size(38.dp),
                            shape          = RoundedCornerShape(10.dp),
                            border         = BorderStroke(1.dp, Color(0xFF25D366).copy(alpha = 0.4f)),
                            contentPadding = PaddingValues(0.dp),
                            colors         = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF25D366))
                        ) {
                            Icon(Icons.Default.Message, contentDescription = "WhatsApp", modifier = Modifier.size(16.dp))
                        }

                        // ── Botón Mora ────────────────────────────────────────────
                        if ((rol == "admin" || rol == "cobrador") &&
                            (notif.diferenciaDias < 0 || notif.tieneMoraActiva)) {

                            val diasParaMora = if (notif.tieneMoraActiva && notif.fechaUltimaMora != null) {
                                daysBetween(notif.fechaUltimaMora, Date()).coerceAtLeast(0)
                            } else {
                                -notif.diferenciaDias
                            }

                            // ✅ FÓRMULA CORRECTA: totalPagar × 0.5% × días de mora
                            val moraSugerida = "%.2f".format(
                                notif.totalPagarOriginal * 0.005 * diasParaMora.coerceAtLeast(1)
                            )

                            val esSeg = notif.cantidadMorasAplicadas > 0

                            Button(
                                onClick = {
                                    onAplicarMora(
                                        notif.prestamoId,
                                        notif.cliente,
                                        moraSugerida,
                                        esSeg,
                                        notif.totalPagarOriginal,
                                        diasParaMora
                                    )
                                },
                                modifier       = Modifier.size(38.dp),
                                colors         = ButtonDefaults.buttonColors(
                                    containerColor = NC.Red.copy(alpha = 0.12f)
                                ),
                                shape          = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(0.dp),
                                elevation      = ButtonDefaults.buttonElevation(0.dp)
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Aplicar Mora",
                                    modifier           = Modifier.size(16.dp),
                                    tint               = NC.Red
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class UrgenciaStyle(
    val color: Color, val bgColor: Color, val label: String,
    val icon: ImageVector, val dias: String
)

// ─────────────────────────────────────────────
//  ESTADOS: CARGA / ERROR / VACÍO
// ─────────────────────────────────────────────
@Composable
private fun LoadingView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier    = Modifier.size(48.dp),
                color       = NC.Blue,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "Cargando notificaciones...",
                fontSize   = 15.sp,
                color      = NC.TextSec,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ErrorView(mensaje: String, onRetry: () -> Unit) {
    Box(
        modifier         = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier         = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(NC.RedSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null,
                    modifier = Modifier.size(40.dp), tint = NC.Red)
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text("Algo salió mal", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NC.TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(mensaje, fontSize = 14.sp, color = NC.TextSec, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick  = onRetry,
                colors   = ButtonDefaults.buttonColors(containerColor = NC.Blue),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reintentar", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EstadoVacio() {
    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(NC.BlueLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.NotificationsNone, contentDescription = null,
                    modifier = Modifier.size(32.dp), tint = NC.BlueSoft)
            }
            Text("Sin resultados", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = NC.TextPrimary)
            Text(
                "No hay préstamos que coincidan\ncon los filtros seleccionados.",
                fontSize  = 13.sp,
                color     = NC.TextSec,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────
//  DIÁLOGO MORA
// ─────────────────────────────────────────────
@Composable
fun DialogoAplicarMora(
    cliente: String,
    moraSugerida: String,
    diasMora: Int,
    totalPagar: Double,
    esSegundaMora: Boolean,
    onDismiss: () -> Unit,
    onConfirmar: (Double) -> Unit
) {
    var moraTexto by remember { mutableStateOf(moraSugerida) }
    val moraCalculadaRef = moraSugerida.toDoubleOrNull() ?: 0.0
    val dec = DecimalFormat("#,##0.00")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = NC.Card,
        shape            = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null,
                    tint = NC.Red, modifier = Modifier.size(22.dp))
                Column {
                    Text("Aplicar mora", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (esSegundaMora) {
                        Text(
                            "⚠️ Ya existe una mora previa aplicada",
                            fontSize   = 12.sp,
                            color      = NC.Orange,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                if (esSegundaMora) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = NC.OrangeSoft),
                        shape  = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null,
                                    tint = NC.Orange, modifier = Modifier.size(16.dp))
                                Text(
                                    "Mora adicional",
                                    fontWeight = FontWeight.Bold,
                                    color      = NC.Orange,
                                    fontSize   = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Los días se cuentan desde la última mora aplicada. " +
                                        "La base siempre es el total a pagar del préstamo.",
                                fontSize = 12.sp,
                                color    = Color(0xFFB45309)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Total a pagar (base): L. ${dec.format(totalPagar)}",
                                fontSize   = 12.sp,
                                color      = NC.Orange,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Info cliente + días
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NC.RedSoft)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Cliente", fontSize = 11.sp, color = NC.TextMuted)
                        Text(
                            cliente,
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 14.sp,
                            color      = NC.TextPrimary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (esSegundaMora) "Días desde última mora" else "Días de mora",
                            fontSize = 11.sp,
                            color    = NC.TextMuted
                        )
                        Text(
                            "$diasMora días",
                            fontWeight = FontWeight.Bold,
                            color      = NC.Red,
                            fontSize   = 14.sp
                        )
                    }
                }

                // ✅ Fórmula correcta: totalPagar × 0.5% × días
                Text(
                    "Cálculo: Total a pagar (L. ${dec.format(totalPagar)}) × 0.5% × $diasMora días = L. ${"%.2f".format(moraCalculadaRef)}",
                    fontSize = 11.sp,
                    color    = NC.TextMuted
                )

                OutlinedTextField(
                    value           = moraTexto,
                    onValueChange   = { moraTexto = it },
                    label           = { Text("Monto de mora (L) — editable") },
                    placeholder     = { Text("Ej: $moraSugerida") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier        = Modifier.fillMaxWidth(),
                    shape           = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (moraTexto != moraSugerida) {
                            IconButton(onClick = { moraTexto = moraSugerida }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Restaurar monto calculado",
                                    tint               = NC.Red,
                                    modifier           = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NC.Red,
                        focusedLabelColor  = NC.Red
                    )
                )

                if (moraTexto != moraSugerida && moraTexto.toDoubleOrNull() != null) {
                    Text(
                        "⚠️ Monto modificado. El sugerido era L. $moraSugerida",
                        fontSize = 11.sp,
                        color    = NC.Orange
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val monto = moraTexto.toDoubleOrNull()
                    if (monto != null && monto > 0) onConfirmar(monto)
                },
                colors = ButtonDefaults.buttonColors(containerColor = NC.Red),
                shape  = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Aplicar mora", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape   = RoundedCornerShape(10.dp),
                border  = BorderStroke(1.dp, NC.Border)
            ) { Text("Cancelar", color = NC.TextSec) }
        }
    )
}

// ─────────────────────────────────────────────
//  WHATSAPP
// ─────────────────────────────────────────────
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
            Toast.makeText(context, "Número de teléfono no registrado", Toast.LENGTH_SHORT).show()
            return
        }

        val mensaje = "Estimado/a $nombre, le recordamos que tiene un pago pendiente con Capital Express. ¡Gracias!"
        val url     = "https://wa.me/504$numero?text=${Uri.encode(mensaje)}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}