package com.example.capitalexpressapp.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.capitalexpressapp.util.ReciboHelper.generarReciboPrestamoPDF
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import com.example.minifinancieraapp.util.SessionManager
import androidx.compose.foundation.background
import com.example.minifinancieraapp.ui.models.ClienteModel
import java.util.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.rotate

// Data class mejorada para historial
data class PrestamoHistorial(
    val id: String = "",
    val cliente: String = "",
    val clienteId: String = "",
    val monto: Double = 0.0,
    val interes: Double = 0.0,
    val interesMensual: Double = 0.0,
    val interesTotal: Double = 0.0,
    val totalPagar: Double = 0.0,
    val cuota: Double = 0.0,
    val cuotas: Int = 0,
    val plazo: String = "",
    val fecha: String = "",
    val fechaTimestamp: Timestamp? = null,
    val fechaCreacion: String = "",
    val fechaCreacionTimestamp: Timestamp? = null,
    val proximoPago: String = "",
    val proximoPagoTimestamp: Timestamp? = null,
    val lugar: String = "",
    val cobrador: String = "",
    val cobradorAsignado: String = "",
    val montoPagado: Double = 0.0,
    val saldo: Double = 0.0,
    val estado: String = "",
    val observaciones: String = "",
    val fotos: List<String> = emptyList(),
    val diasEfectivos: Int = 0,
    val cobradores: List<String> = emptyList(),
    val numeroPrestamo: Int = 0,
    val prestamoId: String = "",
    val eliminado: Boolean = false,
    val mora: Double = 0.0,
    val telefonoCobrador: String = ""
)

// Funciones helper reutilizadas de PrestamoAdminScreen
fun DocumentSnapshot.getTimestampSafeHistorial(field: String): Timestamp? {
    return try {
        this.getTimestamp(field)
    } catch (e: Exception) {
        Log.w("HistorialHelper", "Campo '$field' no es un Timestamp en documento ${this.id}: ${e.message}")
        val stringValue = this.getString(field)
        if (!stringValue.isNullOrBlank()) {
            try {
                val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                val date = formatter.parse(stringValue)
                date?.let { Timestamp(it) }
            } catch (parseException: Exception) {
                Log.w("HistorialHelper", "No se pudo parsear fecha desde string '$stringValue': ${parseException.message}")
                null
            }
        } else {
            null
        }
    }
}

fun DocumentSnapshot.getDateStringSafeHistorial(field: String, formatter: SimpleDateFormat): String {
    return try {
        val timestamp = this.getTimestamp(field)
        timestamp?.toDate()?.let { formatter.format(it) } ?: "-"
    } catch (e: Exception) {
        val stringValue = this.getString(field)
        if (!stringValue.isNullOrBlank()) {
            try {
                val date = formatter.parse(stringValue)
                formatter.format(date)
            } catch (parseException: Exception) {
                stringValue
            }
        } else {
            "-"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialPrestamosScreen(navController: NavController, uid: String, rol: String) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val fullFormatter = SimpleDateFormat("dd 'de' MMMM 'de' yyyy, HH:mm:ss a", Locale("es", "ES"))

    // Estados principales
    var prestamosOriginales by remember { mutableStateOf<List<PrestamoHistorial>>(emptyList()) }
    var prestamosFiltrados by remember { mutableStateOf<List<PrestamoHistorial>>(emptyList()) }
    var cargando by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }
    var firestoreListener by remember { mutableStateOf<ListenerRegistration?>(null) }

    // Estados de filtros
    var filtroFecha by remember { mutableStateOf("Todos") }
    var filtroEstado by remember { mutableStateOf("Todos") }
    var busqueda by remember { mutableStateOf("") }
    var expandedCard by remember { mutableStateOf<String?>(null) }

    // NUEVO: Estado para controlar si el resumen está expandido
    var resumenExpandido by remember { mutableStateOf(false) }

    // Estados para DatePickers
    var fechaInicio by remember { mutableStateOf("") }
    var fechaFin by remember { mutableStateOf("") }
    val datePickerInicio = rememberDatePickerState()
    val datePickerFin = rememberDatePickerState()
    val showInicioPicker = remember { mutableStateOf(false) }
    val showFinPicker = remember { mutableStateOf(false) }

    val esAdmin = rol.equals("admin", ignoreCase = true)
    val esCobrador = rol.equals("cobrador", ignoreCase = true)

    // Función para verificar conectividad
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }

    // Función para resetear filtros
    fun resetearFiltros() {
        filtroFecha = "Todos"
        filtroEstado = "Todos"
        busqueda = ""
        fechaInicio = ""
        fechaFin = ""
    }

    // Función para aplicar filtros
    fun aplicarFiltros() {
        val calendar = Calendar.getInstance()

        prestamosFiltrados = prestamosOriginales.filter { prestamo ->
            // Filtro por búsqueda
            val coincideBusqueda = if (busqueda.isBlank()) {
                true
            } else {
                prestamo.cliente.contains(busqueda, ignoreCase = true) ||
                        prestamo.numeroPrestamo.toString().contains(busqueda)
            }

            // Filtro por estado
            val coincideEstado = if (filtroEstado == "Todos") {
                true
            } else {
                prestamo.estado.equals(filtroEstado, ignoreCase = true)
            }

            // Filtro por fecha
            val coincideFecha = when (filtroFecha) {
                "Todos" -> true
                "Hoy" -> {
                    val hoy = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.time

                    val finDia = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                        set(Calendar.SECOND, 59)
                    }.time

                    val fechaPrestamo = prestamo.fechaCreacionTimestamp?.toDate()
                    fechaPrestamo != null && fechaPrestamo >= hoy && fechaPrestamo <= finDia
                }
                "Semana" -> {
                    val inicioSemana = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, -7)
                    }.time

                    val fechaPrestamo = prestamo.fechaCreacionTimestamp?.toDate()
                    fechaPrestamo != null && fechaPrestamo >= inicioSemana
                }
                "Mes" -> {
                    val inicioMes = Calendar.getInstance().apply {
                        add(Calendar.MONTH, -1)
                    }.time

                    val fechaPrestamo = prestamo.fechaCreacionTimestamp?.toDate()
                    fechaPrestamo != null && fechaPrestamo >= inicioMes
                }
                "Rango" -> {
                    if (fechaInicio.isNotEmpty() && fechaFin.isNotEmpty()) {
                        try {
                            val inicioDate = formatter.parse(fechaInicio)
                            val finDate = formatter.parse(fechaFin)
                            val fechaPrestamo = prestamo.fechaCreacionTimestamp?.toDate()

                            fechaPrestamo != null && inicioDate != null && finDate != null &&
                                    fechaPrestamo >= inicioDate && fechaPrestamo <= finDate
                        } catch (e: Exception) {
                            true
                        }
                    } else {
                        true
                    }
                }
                else -> true
            }

            coincideBusqueda && coincideEstado && coincideFecha
        }.sortedWith(
            compareBy<PrestamoHistorial> { it.cobradores.isEmpty() }
                .thenByDescending { it.fechaCreacionTimestamp?.toDate() }
        )
    }

    // Función para configurar listener Firebase
    fun configurarListenerFirebase() {
        cargando = true
        errorMessage = ""

        if (!isNetworkAvailable(context)) {
            errorMessage = "No hay conexión a internet"
            cargando = false
            return
        }

        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            db.firestoreSettings = settings

            // Query base - similar a PrestamoAdminScreen
            val query = if (esCobrador) {
                // Para cobradores, mostrar préstamos donde estén asignados
                db.collection("prestamos")
                    .whereArrayContains("cobradoresAsignados", uid)
            } else {
                // Para admin, mostrar todos
                db.collection("prestamos")
            }

            firestoreListener?.remove()
            firestoreListener = query.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("HistorialPrestamos", "Error en listener: ${error.message}")
                    errorMessage = "Error al cargar préstamos: ${error.localizedMessage}"
                    cargando = false
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    val lista = snapshot.documents.mapNotNull { doc ->
                        try {
                            val cliente = doc.getString("cliente") ?: return@mapNotNull null
                            val eliminado = doc.getBoolean("eliminado") ?: false

                            // Excluir préstamos eliminados en historial
                            if (eliminado) return@mapNotNull null

                            val monto = doc.getDouble("monto") ?: 0.0
                            val interes = doc.getDouble("interes") ?: 0.0
                            val interesTotal = doc.getDouble("interesTotal") ?: 0.0
                            val totalPagar = doc.getDouble("totalPagar") ?: (monto + interesTotal)
                            val montoPagado = doc.getDouble("montoPagado") ?: 0.0
                            val saldo = (totalPagar - montoPagado).coerceAtLeast(0.0)

                            // Obtener cobradores de ambos campos
                            val cobradores = mutableListOf<String>()
                            (doc.get("cobradoresAsignados") as? List<*>)?.forEach { cobrador ->
                                cobrador?.toString()?.takeIf { it.isNotBlank() }?.let { cobradores.add(it) }
                            }
                            (doc.get("cobradores") as? List<*>)?.forEach { cobrador ->
                                cobrador?.toString()?.takeIf { it.isNotBlank() }?.let { cobradores.add(it) }
                            }

                            val cobradorAsignado = doc.getString("cobradorAsignado") ?: ""
                            if (cobradorAsignado.isNotBlank() && !cobradores.contains(cobradorAsignado)) {
                                cobradores.add(cobradorAsignado)
                            }

                            PrestamoHistorial(
                                id = doc.id,
                                cliente = cliente,
                                clienteId = doc.getString("clienteId") ?: "",
                                monto = monto,
                                interes = interes,
                                interesTotal = interesTotal,
                                totalPagar = totalPagar,
                                cuota = doc.getDouble("cuota") ?: 0.0,
                                cuotas = doc.getLong("cuotas")?.toInt() ?: 0,
                                plazo = doc.getString("plazo") ?: "",
                                lugar = doc.getString("lugar") ?: "",
                                cobrador = doc.getString("cobrador") ?: "Administrador",
                                cobradorAsignado = cobradorAsignado,
                                montoPagado = montoPagado,
                                saldo = saldo,
                                estado = doc.getString("estado") ?: "activo",
                                observaciones = doc.getString("observaciones") ?: "",
                                diasEfectivos = doc.getLong("diasEfectivos")?.toInt() ?: 0,
                                numeroPrestamo = doc.getLong("numeroPrestamo")?.toInt() ?: 0,
                                prestamoId = doc.getString("prestamoId") ?: "",
                                mora = doc.getDouble("mora") ?: 0.0,
                                telefonoCobrador = doc.getString("telefonoCobrador") ?: "",

                                // Timestamps y fechas
                                fechaTimestamp = doc.getTimestampSafeHistorial("fecha"),
                                fechaCreacionTimestamp = doc.getTimestampSafeHistorial("fechaCreacion"),
                                proximoPagoTimestamp = doc.getTimestampSafeHistorial("proximoPago"),
                                fecha = doc.getDateStringSafeHistorial("fecha", formatter),
                                fechaCreacion = doc.getDateStringSafeHistorial("fechaCreacion", fullFormatter),
                                proximoPago = doc.getDateStringSafeHistorial("proximoPago", formatter),

                                cobradores = cobradores.distinct(),
                                fotos = when (val fotosData = doc.get("fotos")) {
                                    is List<*> -> fotosData.filterIsInstance<String>()
                                    else -> emptyList()
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("HistorialPrestamos", "Error al procesar documento ${doc.id}: ${e.message}")
                            null
                        }
                    }

                    prestamosOriginales = lista.sortedByDescending { it.fechaCreacionTimestamp?.toDate() }
                    errorMessage = ""

                    Log.d("HistorialPrestamos", "Cargados ${prestamosOriginales.size} préstamos")
                } else {
                    prestamosOriginales = emptyList()
                    errorMessage = if (esCobrador) {
                        "No tienes préstamos asignados"
                    } else {
                        "No se encontraron préstamos"
                    }
                }

                cargando = false
                aplicarFiltros()
            }

        } catch (e: Exception) {
            Log.e("HistorialPrestamos", "Error configurando listener: ${e.message}")
            errorMessage = "Error de configuración: ${e.localizedMessage}"
            prestamosOriginales = emptyList()
            cargando = false
        }
    }

    // Configurar listener al iniciar
    LaunchedEffect(Unit) {
        configurarListenerFirebase()
    }

    // Limpiar listener al salir
    DisposableEffect(Unit) {
        onDispose {
            firestoreListener?.remove()
        }
    }

    // Aplicar filtros cuando cambien
    LaunchedEffect(filtroFecha, filtroEstado, busqueda, fechaInicio, fechaFin, prestamosOriginales) {
        aplicarFiltros()
    }

    // Manejar refresh desde navegación
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(navBackStackEntry?.savedStateHandle?.get<Boolean>("refreshHistorial")) {
        val shouldRefresh = navBackStackEntry?.savedStateHandle?.get<Boolean>("refreshHistorial")
        if (shouldRefresh == true) {
            configurarListenerFirebase()
            navBackStackEntry?.savedStateHandle?.set("refreshHistorial", false)
        }
    }

    // Estadísticas calculadas
    val estadisticas = remember(prestamosFiltrados) {
        val totalPrestamos = prestamosFiltrados.size
        val prestamosActivos = prestamosFiltrados.count { it.estado.lowercase() == "activo" }
        val prestamosVencidos = prestamosFiltrados.count { it.estado.lowercase() == "vencido" }
        val prestamosCompletados = prestamosFiltrados.count { it.estado.lowercase() == "completado" }

        val totalMontoPrestado = prestamosFiltrados.sumOf { it.monto }
        val totalMontoPagado = prestamosFiltrados.sumOf { it.montoPagado }
        val totalPendiente = prestamosFiltrados.sumOf { it.saldo }

        mapOf(
            "total" to totalPrestamos,
            "activos" to prestamosActivos,
            "vencidos" to prestamosVencidos,
            "completados" to prestamosCompletados,
            "montoPrestado" to totalMontoPrestado,
            "montoPagado" to totalMontoPagado,
            "pendiente" to totalPendiente
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "💼 Historial de Préstamos",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            "Total: ${prestamosFiltrados.size} préstamos",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2196F3)
                ),
                actions = {
                    IconButton(
                        onClick = { configurarListenerFirebase() }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            "Actualizar",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    if (esAdmin) {
                        IconButton(
                            onClick = { navController.navigate("crearPrestamo") }
                        ) {
                            Icon(
                                Icons.Default.Add,
                                "Nuevo préstamo",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF8F9FA),
                            Color(0xFFE3F2FD)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp) // Reducido el espaciado
            ) {
                // NUEVO: Botón compacto para mostrar/ocultar resumen financiero
                if (!cargando && prestamosFiltrados.isNotEmpty()) {
                    val rotationAngle by animateFloatAsState(
                        targetValue = if (resumenExpandido) 180f else 0f,
                        animationSpec = tween(300)
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2196F3)
                        ),
                        elevation = CardDefaults.cardElevation(4.dp),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { resumenExpandido = !resumenExpandido }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Analytics,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "📊 Resumen Financiero",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        "Toca para ${if (resumenExpandido) "ocultar" else "mostrar"} estadísticas",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "L. ${String.format("%.0f", estadisticas["montoPrestado"] as Double)}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.ExpandMore,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .rotate(rotationAngle)
                                )
                            }
                        }
                    }

                    // Resumen expandible con animación
                    if (resumenExpandido) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White
                            ),
                            elevation = CardDefaults.cardElevation(6.dp),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFF2196F3), Color(0xFF1E88E5))
                                        ),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        StatCard("Total", estadisticas["total"].toString(), Icons.Default.Assignment, Color.White)
                                        StatCard("Activos", estadisticas["activos"].toString(), Icons.Default.AccessTime, Color.White)
                                        StatCard("Vencidos", estadisticas["vencidos"].toString(), Icons.Default.Warning, Color.White)
                                        StatCard("Completados", estadisticas["completados"].toString(), Icons.Default.CheckCircle, Color.White)
                                    }

                                    Divider(color = Color.White.copy(alpha = 0.3f), thickness = 1.dp)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        StatCard("Prestado", "L. ${String.format("%.0f", estadisticas["montoPrestado"] as Double)}", Icons.Default.TrendingUp, Color.White)
                                        StatCard("Pagado", "L. ${String.format("%.0f", estadisticas["montoPagado"] as Double)}", Icons.Default.Paid, Color.White)
                                        StatCard("Pendiente", "L. ${String.format("%.0f", estadisticas["pendiente"] as Double)}", Icons.Default.PendingActions, Color.White)
                                    }
                                }
                            }
                        }
                    }
                }

                // Filtros más compactos
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp), // Reducido padding
                        verticalArrangement = Arrangement.spacedBy(8.dp) // Reducido espaciado
                    ) {
                        // Header de filtros más compacto
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Search,
                                null,
                                tint = Color(0xFF2196F3),
                                modifier = Modifier.size(18.dp) // Reducido tamaño
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "🔍 Buscar y Filtrar",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp, // Reducido tamaño
                                color = Color(0xFF2196F3)
                            )
                        }

                        // Búsqueda más compacta
                        OutlinedTextField(
                            value = busqueda,
                            onValueChange = { busqueda = it },
                            placeholder = { Text("Buscar por cliente o número...", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    null,
                                    tint = Color(0xFF2196F3),
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            trailingIcon = if (busqueda.isNotBlank()) {
                                {
                                    IconButton(onClick = { busqueda = "" }) {
                                        Icon(
                                            Icons.Default.Clear,
                                            null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp), // Reducido altura
                            shape = RoundedCornerShape(22.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF2196F3),
                                unfocusedBorderColor = Color(0xFFE0E0E0)
                            )
                        )

                        // Filtros en fila compacta
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp) // Reducido espaciado
                        ) {
                            Icon(
                                Icons.Default.FilterList,
                                null,
                                tint = Color(0xFF2196F3),
                                modifier = Modifier.size(14.dp)
                            )

                            // Filtro de fecha compacto
                            var expandedFecha by remember { mutableStateOf(false) }
                            Box {
                                FilterChip(
                                    onClick = { expandedFecha = true },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.DateRange,
                                                null,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                filtroFecha,
                                                fontSize = 10.sp,
                                                maxLines = 1
                                            )
                                        }
                                    },
                                    selected = filtroFecha != "Todos",
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF2196F3),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFFF5F5F5)
                                    ),
                                    modifier = Modifier.height(28.dp)
                                )

                                DropdownMenu(
                                    expanded = expandedFecha,
                                    onDismissRequest = { expandedFecha = false }
                                ) {
                                    listOf("Todos", "Hoy", "Semana", "Mes", "Rango").forEach { periodo ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    val icon = when(periodo) {
                                                        "Hoy" -> "📅"
                                                        "Semana" -> "📆"
                                                        "Mes" -> "🗓️"
                                                        "Rango" -> "📊"
                                                        else -> "🌐"
                                                    }
                                                    Text("$icon $periodo", fontSize = 12.sp)
                                                }
                                            },
                                            onClick = {
                                                filtroFecha = periodo
                                                expandedFecha = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Filtro de estado compacto
                            var expandedEstado by remember { mutableStateOf(false) }
                            Box {
                                FilterChip(
                                    onClick = { expandedEstado = true },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Info,
                                                null,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                if (filtroEstado == "Todos") "Estado" else filtroEstado.replaceFirstChar { it.uppercase() },
                                                fontSize = 10.sp,
                                                maxLines = 1
                                            )
                                        }
                                    },
                                    selected = filtroEstado != "Todos",
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF2196F3),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFFF5F5F5)
                                    ),
                                    modifier = Modifier.height(28.dp)
                                )

                                DropdownMenu(
                                    expanded = expandedEstado,
                                    onDismissRequest = { expandedEstado = false }
                                ) {
                                    listOf("Todos", "activo", "completado", "vencido", "saldado").forEach { estado ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    val icon = when(estado.lowercase()) {
                                                        "activo" -> "🟢"
                                                        "completado" -> "🔵"
                                                        "vencido" -> "🔴"
                                                        "saldado" -> "✅"
                                                        else -> "🌐"
                                                    }
                                                    Text("$icon ${estado.replaceFirstChar { it.uppercase() }}", fontSize = 12.sp)
                                                }
                                            },
                                            onClick = {
                                                filtroEstado = estado
                                                expandedEstado = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Botón limpiar filtros elegante
                            val hayFiltrosActivos = filtroFecha != "Todos" || filtroEstado != "Todos" || busqueda.isNotBlank()
                            if (hayFiltrosActivos) {
                                IconButton(
                                    onClick = { resetearFiltros() },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(
                                            Color(0xFF2196F3).copy(alpha = 0.1f),
                                            RoundedCornerShape(14.dp)
                                        )
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        "Limpiar filtros",
                                        tint = Color(0xFF2196F3),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Selector de rango de fechas (solo cuando se necesite)
                        if (filtroFecha == "Rango") {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = { showInicioPicker.value = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF2196F3)
                                    ),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Icon(Icons.Default.DateRange, null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        if (fechaInicio.isEmpty()) "📅 Desde" else fechaInicio,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }

                                OutlinedButton(
                                    onClick = { showFinPicker.value = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF2196F3)
                                    ),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Icon(Icons.Default.DateRange, null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        if (fechaFin.isEmpty()) "📅 Hasta" else fechaFin,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // Indicador de resultados más compacto
                        if (!cargando && prestamosFiltrados.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Color(0xFF2196F3).copy(alpha = 0.1f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(6.dp)
                            ) {
                                Text(
                                    text = "📋 ${prestamosFiltrados.size} de ${prestamosOriginales.size} préstamos",
                                    fontSize = 11.sp,
                                    color = Color(0xFF2196F3),
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }

                // Mensaje de error con diseño mejorado
                if (errorMessage.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFEBEE)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(3.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Warning,
                                    "Error",
                                    tint = Color(0xFFD32F2F),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "❌ Error de conexión",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFD32F2F),
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        errorMessage,
                                        color = Color(0xFFD32F2F),
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Button(
                                onClick = { configurarListenerFirebase() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2196F3)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("🔄 Reintentar", fontSize = 13.sp)
                            }
                        }
                    }
                }

                // Contenido principal
                when {
                    cargando -> {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(6.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF2196F3),
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Text(
                                        "⏳ Cargando préstamos...",
                                        color = Color(0xFF666666),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "Por favor espera",
                                        color = Color(0xFF999999),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    prestamosFiltrados.isEmpty() && errorMessage.isBlank() -> {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(6.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    Text("📋", fontSize = 48.sp)
                                    Text(
                                        "No se encontraron préstamos",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF333333),
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        if (prestamosOriginales.isEmpty()) {
                                            "📝 No hay préstamos registrados"
                                        } else {
                                            "🔍 Ajusta los filtros para ver más resultados"
                                        },
                                        fontSize = 12.sp,
                                        color = Color(0xFF666666),
                                        textAlign = TextAlign.Center
                                    )

                                    if (prestamosOriginales.isEmpty() && esAdmin) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Button(
                                            onClick = { navController.navigate("crearPrestamo") },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF2196F3)
                                            ),
                                            shape = RoundedCornerShape(20.dp)
                                        ) {
                                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("➕ Crear préstamo", fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        // Lista de préstamos con más espacio
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp), // Reducido espaciado entre cards
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(prestamosFiltrados) { prestamo ->
                                PrestamoCardHistorial(
                                    prestamo = prestamo,
                                    navController = navController,
                                    isExpanded = expandedCard == prestamo.id,
                                    onExpandToggle = { id ->
                                        expandedCard = if (expandedCard == id) null else id
                                    },
                                    context = context,
                                    scope = scope,
                                    db = db
                                )
                            }
                            // Espacio adicional al final más pequeño
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // DatePickers con diseño mejorado
    if (showInicioPicker.value) {
        DatePickerDialog(
            onDismissRequest = { showInicioPicker.value = false },
            confirmButton = {
                Button(
                    onClick = {
                        val millis = datePickerInicio.selectedDateMillis
                        millis?.let {
                            fechaInicio = formatter.format(Date(it))
                            showInicioPicker.value = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Text("✅ Aceptar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInicioPicker.value = false }) {
                    Text("❌ Cancelar", color = Color(0xFF666666))
                }
            }
        ) {
            DatePicker(state = datePickerInicio)
        }
    }

    if (showFinPicker.value) {
        DatePickerDialog(
            onDismissRequest = { showFinPicker.value = false },
            confirmButton = {
                Button(
                    onClick = {
                        val millis = datePickerFin.selectedDateMillis
                        millis?.let {
                            fechaFin = formatter.format(Date(it))
                            showFinPicker.value = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Text("✅ Aceptar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinPicker.value = false }) {
                    Text("❌ Cancelar", color = Color(0xFF666666))
                }
            }
        ) {
            DatePicker(state = datePickerFin)
        }
    }
}

@Composable
private fun InfoColumn(
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(6.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color(0xFF666666),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF333333),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

// Componente StatCard mejorado para las estadísticas
@Composable
fun StatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(
                Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(10.dp)
            )
            .padding(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = title,
            color = color,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PrestamoCardHistorial(
    prestamo: PrestamoHistorial,
    navController: NavController,
    isExpanded: Boolean,
    onExpandToggle: (String) -> Unit,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    db: FirebaseFirestore
) {
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // Calcular progreso de pago
    val progreso = if (prestamo.totalPagar > 0) {
        (prestamo.montoPagado / prestamo.totalPagar).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    // Colores y emojis según estado
    val (estadoColor, estadoText, estadoEmoji) = when (prestamo.estado.lowercase()) {
        "activo" -> Triple(Color(0xFF4CAF50), "ACTIVO", "🟢")
        "completado" -> Triple(Color(0xFF2196F3), "COMPLETADO", "🔵")
        "saldado" -> Triple(Color(0xFF2196F3), "SALDADO", "✅")
        "vencido" -> Triple(Color(0xFFFF5722), "VENCIDO", "🔴")
        "atrasado" -> Triple(Color(0xFFFF5722), "ATRASADO", "⚠️")
        "inactivo" -> Triple(Color(0xFF757575), "INACTIVO", "⚫")
        else -> Triple(Color(0xFF757575), prestamo.estado.uppercase(), "⚪")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column {
            // Header con gradiente más compacto
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                estadoColor.copy(alpha = 0.1f),
                                estadoColor.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .padding(12.dp) // Reducido padding
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "👤 ${prestamo.cliente}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp, // Reducido tamaño
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color(0xFF333333)
                        )
                        if (prestamo.numeroPrestamo > 0) {
                            Text(
                                text = "📋 Préstamo #${prestamo.numeroPrestamo}",
                                fontSize = 11.sp,
                                color = Color(0xFF666666)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(estadoColor)
                            .padding(horizontal = 10.dp, vertical = 4.dp) // Reducido padding
                    ) {
                        Text(
                            text = "$estadoEmoji $estadoText",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(12.dp)) { // Reducido padding
                // Información financiera principal con diseño más compacto
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InfoColumn(
                        "💰 Capital",
                        "L. ${"%.0f".format(prestamo.monto)}",
                        Color(0xFF2196F3)
                    )
                    InfoColumn(
                        "📊 Total",
                        "L. ${"%.0f".format(prestamo.totalPagar)}",
                        Color(0xFF4CAF50)
                    )
                    InfoColumn(
                        "💳 Pagado",
                        "L. ${"%.0f".format(prestamo.montoPagado)}",
                        Color(0xFF009688)
                    )
                    InfoColumn("⏰ Saldo", "L. ${"%.0f".format(prestamo.saldo)}", Color(0xFFFF5722))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Barra de progreso más compacta
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "📈 Progreso",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF666666)
                        )
                        Text(
                            text = "${(progreso * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    LinearProgressIndicator(
                        progress = progreso,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp) // Reducido altura
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF4CAF50),
                        trackColor = Color(0xFFE8F5E8)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Información básica más compacta
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "📅 ${prestamo.fechaCreacion.take(10)}",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                        if (prestamo.proximoPago.isNotBlank() && prestamo.proximoPago != "-") {
                            Text(
                                text = "📋 ${prestamo.proximoPago}",
                                fontSize = 11.sp,
                                color = Color(0xFF2196F3),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Botón para expandir/contraer detalles más compacto
                    TextButton(
                        onClick = { onExpandToggle(prestamo.id) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color(0xFF2196F3)
                        )
                    ) {
                        Text(
                            text = if (isExpanded) "🔼 Ocultar" else "🔽 Ver más",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Detalles expandidos más compactos
                    if (isExpanded) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFF8F9FA)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "📋 Detalles del Préstamo",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color(0xFF2196F3),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )

                                DetailRow(
                                    "💰 Interés Total",
                                    "L. ${"%.2f".format(prestamo.interesTotal)}"
                                )
                                DetailRow("💳 Cuota", "L. ${"%.2f".format(prestamo.cuota)}")
                                DetailRow("🔢 Cuotas", "${prestamo.cuotas}")
                                DetailRow("⏱️ Plazo", prestamo.plazo.ifBlank { "No especificado" })
                                DetailRow("📍 Lugar", prestamo.lugar.ifBlank { "No especificado" })
                                DetailRow("👨‍💼 Cobrador", prestamo.cobrador)

                                if (prestamo.mora > 0) {
                                    DetailRow("⚠️ Mora", "L. ${"%.2f".format(prestamo.mora)}")
                                }

                                if (prestamo.observaciones.isNotBlank()) {
                                    DetailRow("📝 Observaciones", prestamo.observaciones)
                                }

                                if (prestamo.telefonoCobrador.isNotBlank() && prestamo.telefonoCobrador != "No especificado") {
                                    DetailRow("📱 Teléfono", prestamo.telefonoCobrador)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Botones de acción más compactos
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {

                            if (prestamo.clienteId.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val clienteDoc = db.collection("clientes")
                                                    .document(prestamo.clienteId).get().await()
                                                val clienteModel =
                                                    clienteDoc.toObject(ClienteModel::class.java)

                                                if (clienteModel != null) {
                                                    val recibo = generarReciboPrestamoPDF(
                                                        context = context,
                                                        cliente = clienteModel,
                                                        monto = prestamo.monto,
                                                        interesTotal = prestamo.interesTotal,
                                                        mora = prestamo.mora,
                                                        cuotas = prestamo.cuotas,
                                                        fecha = prestamo.fecha,
                                                        lugar = prestamo.lugar.ifBlank { "No especificado" },
                                                        numeroCobrador = prestamo.telefonoCobrador,
                                                        numeroPrestamo = "Préstamo Nº ${prestamo.numeroPrestamo}",
                                                        nombreCobrador = prestamo.cobrador,
                                                        fechaProximoPago = prestamo.proximoPago
                                                    )

                                                    recibo?.let { archivo ->
                                                        val intent =
                                                            Intent(Intent.ACTION_SEND).apply {
                                                                type = "application/pdf"
                                                                putExtra(
                                                                    Intent.EXTRA_STREAM,
                                                                    androidx.core.content.FileProvider.getUriForFile(
                                                                        context,
                                                                        "${context.packageName}.fileprovider",
                                                                        archivo
                                                                    )
                                                                )
                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            }
                                                        context.startActivity(
                                                            Intent.createChooser(
                                                                intent,
                                                                "Compartir recibo"
                                                            )
                                                        )
                                                        Toast.makeText(
                                                            context,
                                                            "📄 Recibo generado para ${clienteModel.nombre}",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        "❌ Error: No se encontraron datos del cliente",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } catch (e: Exception) {
                                                Log.e(
                                                    "HistorialPrestamos",
                                                    "Error generando recibo",
                                                    e
                                                )
                                                Toast.makeText(
                                                    context,
                                                    "❌ Error al generar recibo: ${e.localizedMessage}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF4CAF50)
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("📄 Recibo", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}