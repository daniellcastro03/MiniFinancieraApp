package com.example.minifinancieraapp.ui.screens

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.capitalexpressapp.util.ReciboHelper.generarResumenPagosPDF
import com.example.minifinancieraapp.ui.models.PagoItem
import com.example.minifinancieraapp.util.SessionManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagosAsignadosCobradorScreen(navController: NavController) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val session = remember { SessionManager(context) }
    val scope = rememberCoroutineScope()
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    val uidCobrador = session.getUid()
    val nombreCobrador = session.getNombre()

    var pagos by remember { mutableStateOf<List<PagoItem>>(emptyList()) }
    var pagosFiltrados by remember { mutableStateOf<List<PagoItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchText by remember { mutableStateOf("") }
    var mostrarFiltros by remember { mutableStateOf(false) }

    // Estados para filtros de fecha
    var fechaInicio by remember { mutableStateOf<Date?>(null) }
    var fechaFin by remember { mutableStateOf<Date?>(null) }

    // Función para aplicar filtros
    fun aplicarFiltros() {
        pagosFiltrados = pagos.filter { pago ->
            // Filtro por cliente
            val cumpleFiltroCliente = searchText.isBlank() ||
                    pago.cliente.contains(searchText, ignoreCase = true)

            // Filtro por fechas
            val cumpleFiltroFecha = if (fechaInicio == null || fechaFin == null) {
                true
            } else {
                try {
                    val fechaStr = pago.fecha.split(" ")[0]
                    val fechaPago = formatter.parse(fechaStr)

                    if (fechaPago != null) {
                        val calPago = Calendar.getInstance().apply {
                            time = fechaPago
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        val calInicio = Calendar.getInstance().apply {
                            time = fechaInicio!!
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        val calFin = Calendar.getInstance().apply {
                            time = fechaFin!!
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                            set(Calendar.SECOND, 59)
                            set(Calendar.MILLISECOND, 999)
                        }

                        calPago.timeInMillis >= calInicio.timeInMillis &&
                                calPago.timeInMillis <= calFin.timeInMillis
                    } else {
                        true
                    }
                } catch (e: Exception) {
                    Log.e("FiltroFecha", "Error parseando fecha: ${pago.fecha}", e)
                    true
                }
            }

            cumpleFiltroCliente && cumpleFiltroFecha
        }
    }

    // Función para limpiar filtros
    fun limpiarFiltros() {
        searchText = ""
        fechaInicio = null
        fechaFin = null
        pagosFiltrados = pagos
    }

    // Función para cargar pagos del cobrador
    fun cargarPagos() {
        scope.launch {
            try {
                if (uidCobrador.isNullOrEmpty()) {
                    Toast.makeText(context, "Error: No se pudo obtener la sesión del cobrador.", Toast.LENGTH_LONG).show()
                    isLoading = false
                    return@launch
                }

                isLoading = true

                // Cargar usuarios para mapear nombres
                val usuariosSnapshot = db.collection("usuarios").get().await()
                val usuariosMap = usuariosSnapshot.documents
                    .associateBy({ it.id }, { it.getString("nombre") ?: it.id })

                // Cargar préstamos para obtener números
                val prestamosSnapshot = db.collection("prestamos").get().await()
                val prestamosMap = prestamosSnapshot.documents
                    .associateBy({ it.id }, { it.data ?: emptyMap() })

                // Cargar pagos del cobrador
                val pagosSnapshot = db.collection("pagos")
                    .whereEqualTo("registradoPor", uidCobrador)
                    .get()
                    .await()

                pagos = pagosSnapshot.documents.mapNotNull { doc ->
                    try {
                        procesarDocumentoPagoCobrador(doc, usuariosMap, prestamosMap, formatter)
                    } catch (e: Exception) {
                        Log.e("CargarPagos", "Error procesando documento ${doc.id}: ${e.message}")
                        null
                    }
                }.sortedByDescending {
                    try {
                        formatter.parse(it.fecha.split(" ")[0])?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }

                pagosFiltrados = pagos
                Log.d("PagosCobrador", "Pagos cargados para cobrador $nombreCobrador: ${pagos.size}")

            } catch (e: Exception) {
                Toast.makeText(context, "Error al cargar pagos: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("PagosCobrador", "Error al cargar pagos", e)
            } finally {
                isLoading = false
            }
        }
    }

    // Cargar datos al iniciar
    LaunchedEffect(Unit) {
        cargarPagos()
    }

    // Aplicar filtros cuando cambian
    LaunchedEffect(searchText, fechaInicio, fechaFin) {
        aplicarFiltros()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Mis Pagos Registrados",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0061A7)
                ),
                actions = {
                    IconButton(onClick = { mostrarFiltros = !mostrarFiltros }) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "Filtros",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { cargarPagos() }) {
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF5F7FA),
                            Color(0xFFE8EEF5)
                        )
                    )
                )
        ) {
            when {
                isLoading -> {
                    LoadingScreenCobrador(padding)
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        // SECCIÓN DE FILTROS
                        if (mostrarFiltros) {
                            item {
                                FiltrosCobradorCard(
                                    searchText = searchText,
                                    onSearchTextChange = {
                                        searchText = it
                                        aplicarFiltros()
                                    },
                                    fechaInicio = fechaInicio,
                                    fechaFin = fechaFin,
                                    onFechasChange = { inicio, fin ->
                                        fechaInicio = inicio
                                        fechaFin = fin
                                        aplicarFiltros()
                                    },
                                    pagosFiltrados = pagosFiltrados,
                                    nombreCobrador = nombreCobrador,
                                    onLimpiarFiltros = { limpiarFiltros() },
                                    onGenerarPDF = {
                                        if (pagosFiltrados.isEmpty()) {
                                            Toast.makeText(context, "No hay pagos para exportar", Toast.LENGTH_SHORT).show()
                                            return@FiltrosCobradorCard
                                        }

                                        scope.launch {
                                            try {
                                                // Calcular fechas para el reporte
                                                val fechaInicioReporte = fechaInicio ?: run {
                                                    pagosFiltrados.minByOrNull { pago ->
                                                        try {
                                                            formatter.parse(pago.fecha.split(" ")[0])?.time ?: Long.MAX_VALUE
                                                        } catch (e: Exception) {
                                                            Long.MAX_VALUE
                                                        }
                                                    }?.let { pago ->
                                                        formatter.parse(pago.fecha.split(" ")[0])
                                                    } ?: Date(0)
                                                }

                                                val fechaFinReporte = fechaFin ?: run {
                                                    pagosFiltrados.maxByOrNull { pago ->
                                                        try {
                                                            formatter.parse(pago.fecha.split(" ")[0])?.time ?: 0L
                                                        } catch (e: Exception) {
                                                            0L
                                                        }
                                                    }?.let { pago ->
                                                        formatter.parse(pago.fecha.split(" ")[0])
                                                    } ?: Date()
                                                }

                                                val periodo = buildString {
                                                    append("Cobrador: $nombreCobrador")

                                                    if (searchText.isNotBlank()) {
                                                        append(" | Cliente: $searchText")
                                                    }
                                                    if (fechaInicio != null && fechaFin != null) {
                                                        append(" | Fechas: ${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}")
                                                    }
                                                }

                                                // Generar PDF con pagos del cobrador
                                                val archivoPDF = generarResumenPagosPDF(
                                                    context = context,
                                                    pagos = pagosFiltrados,
                                                    fechaInicio = fechaInicioReporte,
                                                    fechaFin = fechaFinReporte,
                                                    periodo = periodo
                                                )

                                                if (archivoPDF != null && archivoPDF.exists()) {
                                                    ReciboHelper.imprimirPDF(context, archivoPDF)
                                                    Toast.makeText(
                                                        context,
                                                        "PDF generado: ${pagosFiltrados.size} registros del cobrador $nombreCobrador",
                                                        Toast.LENGTH_LONG
                                                    ).show()

                                                    Log.d("ExportarPDFCobrador", "PDF generado con ${pagosFiltrados.size} pagos del cobrador $nombreCobrador")
                                                } else {
                                                    Toast.makeText(context, "Error al generar PDF", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                Log.e("ExportarPDFCobrador", "Error: ${e.message}", e)
                                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        // Estadísticas
                        if (pagosFiltrados.isNotEmpty()) {
                            item {
                                StatisticsCardCobrador(
                                    pagos = pagosFiltrados,
                                    nombreCobrador = nombreCobrador
                                )
                            }
                        }

                        // Lista de pagos
                        if (pagosFiltrados.isEmpty()) {
                            item {
                                if (pagos.isEmpty()) {
                                    EmptyStateCardCobrador()
                                } else {
                                    EmptySearchCardCobrador(searchText)
                                }
                            }
                        } else {
                            items(items = pagosFiltrados, key = { it.docId }) { pago ->
                                PagoCardCobrador(
                                    pago = pago,
                                    session = session,
                                    context = context,
                                    onReimprimir = {
                                        scope.launch {
                                            reimprimirReciboCobrador(context, pago)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Función para procesar documentos de pago del cobrador
private fun procesarDocumentoPagoCobrador(
    doc: com.google.firebase.firestore.DocumentSnapshot,
    usuarios: Map<String, String>,
    prestamos: Map<String, Map<String, Any>>,
    formatter: SimpleDateFormat
): PagoItem? {
    try {
        val clienteNombre = doc.getString("clienteNombre")
            ?: doc.getString("cliente")
            ?: return null

        val monto = doc.getDouble("monto")
            ?: doc.getDouble("montoPagado")
            ?: return null

        val mora = doc.getDouble("mora") ?: 0.0
        val interesTotal = doc.getDouble("interesTotal") ?: doc.getDouble("interes") ?: 0.0
        val prestamoId = doc.getString("prestamoId") ?: return null
        val cobradorId = doc.getString("registradoPor")
            ?: doc.getString("cobrador")
            ?: "Desconocido"

        val metodoPago = doc.getString("metodoPago") ?: doc.getString("tipoPago") ?: "Efectivo"
        val esAbonoParcial = doc.getBoolean("esAbonoParcial") ?: false

        val tipoPagoFinal = when {
            esAbonoParcial -> "Abono Parcial"
            metodoPago == "Manual (Admin)" -> "Manual (Admin)"
            else -> metodoPago
        }

        val cuota = when {
            doc.contains("numeroCuota") -> doc.get("numeroCuota").toString()
            doc.contains("cuota") -> doc.get("cuota").toString()
            doc.contains("cuotaActual") -> doc.get("cuotaActual").toString()
            else -> "1"
        }

        val lugar = doc.getString("lugar") ?: ""
        val firma = doc.getString("firma") ?: ""

        val saldoRestante = if (esAbonoParcial) {
            val montoRestanteAntes = doc.getDouble("montoRestanteAntes") ?: 0.0
            (montoRestanteAntes - monto).coerceAtLeast(0.0)
        } else {
            doc.getDouble("saldoRestante") ?: 0.0
        }

        // Manejo de fechas
        val fecha = try {
            when (val fechaRaw = doc.get("fechaPago") ?: doc.get("fecha") ?: doc.get("fechaCreacion")) {
                is Timestamp -> formatter.format(fechaRaw.toDate())
                is String -> {
                    if (fechaRaw.matches(Regex("\\d{2}/\\d{2}/\\d{4}"))) {
                        fechaRaw
                    } else {
                        try {
                            val inputFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            val date = inputFormatter.parse(fechaRaw)
                            if (date != null) formatter.format(date) else fechaRaw
                        } catch (e: Exception) {
                            fechaRaw
                        }
                    }
                }
                else -> formatter.format(Date())
            }
        } catch (e: Exception) {
            formatter.format(Date())
        }

        val nombreCobrador = usuarios[cobradorId] ?: cobradorId
        val datosPrestamoInt = prestamos[prestamoId]
        val numeroPrestamo = (datosPrestamoInt?.get("numeroPrestamo") as? Long ?: 0L).toInt()

        return PagoItem(
            docId = doc.id,
            cliente = clienteNombre,
            prestamoId = prestamoId,
            fecha = fecha,
            monto = monto,
            mora = mora,
            interesTotal = interesTotal,
            cuota = cuota,
            cobrador = nombreCobrador,
            lugar = lugar,
            firma = firma,
            tipoPago = tipoPagoFinal,
            saldoRestante = saldoRestante,
            numeroPrestamo = numeroPrestamo
        )

    } catch (e: Exception) {
        Log.e("ProcesarPagoCobrador", "Error procesando documento ${doc.id}: ${e.message}", e)
        return null
    }
}

@Composable
private fun FiltrosCobradorCard(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    fechaInicio: Date?,
    fechaFin: Date?,
    onFechasChange: (Date?, Date?) -> Unit,
    pagosFiltrados: List<PagoItem>,
    nombreCobrador: String,
    onLimpiarFiltros: () -> Unit,
    onGenerarPDF: () -> Unit
) {
    val context = LocalContext.current
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val calendar = Calendar.getInstance()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Filtros y reportes",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF0061A7)
            )

            // Campo de búsqueda por cliente
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                label = { Text("Buscar cliente") },
                placeholder = { Text("Nombre del cliente...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { onSearchTextChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            // Selector de fechas
            FechaSelectorCobrador(
                fechaInicio = fechaInicio,
                fechaFin = fechaFin,
                onFechasChange = onFechasChange
            )

            // Botones de acción
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Limpiar filtros
                Button(
                    onClick = onLimpiarFiltros,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C757D)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Limpiar", fontSize = 13.sp)
                }

                // Exportar PDF
                Button(
                    onClick = onGenerarPDF,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PDF (${pagosFiltrados.size})", fontSize = 12.sp)
                }
            }

            // Info de filtros aplicados
            if (searchText.isNotBlank() || fechaInicio != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Filtros aplicados:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                        Text("• Cobrador: $nombreCobrador", fontSize = 11.sp, color = Color(0xFF1976D2))
                        if (searchText.isNotBlank()) Text("• Cliente: $searchText", fontSize = 11.sp, color = Color(0xFF1976D2))
                        if (fechaInicio != null && fechaFin != null) Text("• Fechas: ${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}", fontSize = 11.sp, color = Color(0xFF1976D2))
                        Text("Mostrando ${pagosFiltrados.size} registro(s)", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2E7D32))
                    }
                }
            }
        }
    }
}

@Composable
private fun FechaSelectorCobrador(
    fechaInicio: Date?,
    fechaFin: Date?,
    onFechasChange: (Date?, Date?) -> Unit
) {
    val context = LocalContext.current
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val calendar = Calendar.getInstance()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Filtrar por fechas",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1976D2)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Fecha inicio
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        val datePickerDialog = DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                val selectedDate = Calendar.getInstance().apply {
                                    set(year, month, dayOfMonth)
                                }.time
                                onFechasChange(selectedDate, fechaFin)
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        )
                        datePickerDialog.show()
                    }
                    .height(60.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (fechaInicio != null) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = Color(0xFF1976D2),
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Desde", fontSize = 11.sp, color = Color.Gray)
                        Text(
                            fechaInicio?.let { formatter.format(it) } ?: "Seleccionar",
                            fontSize = 13.sp,
                            fontWeight = if (fechaInicio != null) FontWeight.Bold else FontWeight.Normal,
                            color = if (fechaInicio != null) Color(0xFF1976D2) else Color.Gray
                        )
                    }
                }
            }

            // Fecha fin
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        val datePickerDialog = DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                val selectedDate = Calendar.getInstance().apply {
                                    set(year, month, dayOfMonth)
                                }.time
                                onFechasChange(fechaInicio, selectedDate)
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        )
                        datePickerDialog.show()
                    }
                    .height(60.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (fechaFin != null) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = Color(0xFF1976D2),
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hasta", fontSize = 11.sp, color = Color.Gray)
                        Text(
                            fechaFin?.let { formatter.format(it) } ?: "Seleccionar",
                            fontSize = 13.sp,
                            fontWeight = if (fechaFin != null) FontWeight.Bold else FontWeight.Normal,
                            color = if (fechaFin != null) Color(0xFF1976D2) else Color.Gray
                        )
                    }
                }
            }
        }

        // Botones de acceso rápido
        if (fechaInicio == null && fechaFin == null) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChipCobrador(
                        text = "Hoy",
                        icon = Icons.Default.Today,
                        color = Color(0xFF1976D2)
                    ) {
                        val hoy = Date()
                        onFechasChange(hoy, hoy)
                    }
                }

                item {
                    FilterChipCobrador(
                        text = "Última semana",
                        icon = Icons.Default.CalendarViewWeek,
                        color = Color(0xFF2E7D32)
                    ) {
                        calendar.time = Date()
                        calendar.add(Calendar.DAY_OF_MONTH, -7)
                        val inicioSemana = calendar.time
                        val finSemana = Date()
                        onFechasChange(inicioSemana, finSemana)
                    }
                }

                item {
                    FilterChipCobrador(
                        text = "Último mes",
                        icon = Icons.Default.CalendarMonth,
                        color = Color(0xFF6A1B9A)
                    ) {
                        calendar.time = Date()
                        calendar.add(Calendar.DAY_OF_MONTH, -30)
                        val inicioMes = calendar.time
                        val finMes = Date()
                        onFechasChange(inicioMes, finMes)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipCobrador(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .clickable { onClick() }
            .height(40.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                fontSize = 12.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StatisticsCardCobrador(
    pagos: List<PagoItem>,
    nombreCobrador: String
) {
    val totalPagos = pagos.size
    val montoTotal = pagos.sumOf { it.monto }
    val totalMora = pagos.sumOf { it.mora }
    val abonosParciales = pagos.count { it.tipoPago == "Abono Parcial" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0061A7)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Mis estadísticas",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
                Surface(
                    color = Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        nombreCobrador,
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItemCobrador(
                    title = "Total pagos",
                    value = totalPagos.toString(),
                    icon = "💰"
                )
                StatItemCobrador(
                    title = "Monto total",
                    value = "L. ${String.format("%.2f", montoTotal)}",
                    icon = "💵"
                )
            }

            if (totalMora > 0 || abonosParciales > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (totalMora > 0) {
                        StatItemCobrador(
                            title = "Total mora",
                            value = "L. ${String.format("%.2f", totalMora)}",
                            icon = "🚨"
                        )
                    }
                    if (abonosParciales > 0) {
                        StatItemCobrador(
                            title = "Abonos parciales",
                            value = abonosParciales.toString(),
                            icon = "🧩"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItemCobrador(title: String, value: String, icon: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 24.sp)
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            title,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyStateCardCobrador() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("📋", fontSize = 48.sp)
            Text(
                "No has registrado pagos",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color(0xFF424242),
                textAlign = TextAlign.Center
            )
            Text(
                "Los pagos que registres aparecerán aquí",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EmptySearchCardCobrador(searchText: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(64.dp)
            )
            Text(
                "No se encontraron resultados",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Text(
                "No hay pagos tuyos que coincidan con \"$searchText\"",
                fontSize = 14.sp,
                color = Color.Gray.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoadingScreenCobrador(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = Color(0xFF0061A7),
                strokeWidth = 3.dp
            )
            Text(
                "Cargando mis pagos...",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun PagoCardCobrador(
    pago: PagoItem,
    session: SessionManager,
    context: android.content.Context,
    onReimprimir: () -> Unit
) {
    val esAbonoParcial = pago.tipoPago == "Abono Parcial"
    val esManual = pago.tipoPago == "Manual (Admin)"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                esAbonoParcial -> Color(0xFFFFF3E0) // Naranja claro para abonos parciales
                esManual -> Color(0xFFE8EAF6) // Azul claro para manuales
                else -> Color.White
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header del pago
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pago.cliente,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF1A1A1A)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Cuota: ${pago.cuota}",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )

                        // Indicador visual para abonos parciales
                        if (esAbonoParcial) {
                            Surface(
                                color = Color(0xFFFF9800),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "ABONO",
                                    fontSize = 10.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Indicador para pagos manuales
                        if (esManual) {
                            Surface(
                                color = Color(0xFF673AB7),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "MANUAL",
                                    fontSize = 10.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                if (pago.numeroPrestamo > 0) {
                    Surface(
                        color = Color(0xFF0061A7),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "#${pago.numeroPrestamo}",
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFFE0E0E0))

            // Información del pago
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (esAbonoParcial) "Abono realizado" else "Total pagado",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Text(
                        "L. ${String.format("%.2f", pago.monto)}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = when {
                            esAbonoParcial -> Color(0xFFFF9800)
                            esManual -> Color(0xFF673AB7)
                            else -> Color(0xFF2E7D32)
                        }
                    )

                    if (pago.mora > 0) {
                        Text(
                            "Base: L. ${String.format("%.2f", pago.monto - pago.mora)}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    // Info específica para abonos parciales
                    if (esAbonoParcial && pago.saldoRestante > 0) {
                        Text(
                            "Resta de cuota: L. ${String.format("%.2f", pago.saldoRestante)}",
                            fontSize = 12.sp,
                            color = Color(0xFFFF9800),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        color = when (pago.tipoPago) {
                            "Efectivo" -> Color(0xFF4CAF50)
                            "Abono Parcial" -> Color(0xFFFF9800)
                            "Manual (Admin)" -> Color(0xFF673AB7)
                            else -> Color(0xFF2196F3)
                        },
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            pago.tipoPago,
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Mora e interés
            if (pago.mora > 0.0 || pago.interesTotal > 0.0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (pago.mora > 0.0) {
                        Column {
                            Text(
                                "Mora",
                                fontSize = 12.sp,
                                color = Color.Red
                            )
                            Text(
                                "L. ${String.format("%.2f", pago.mora)}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.Red
                            )
                        }
                    }
                    if (pago.interesTotal > 0.0) {
                        Column {
                            Text(
                                "Interés",
                                fontSize = 12.sp,
                                color = Color(0xFF6A1B9A)
                            )
                            Text(
                                "L. ${String.format("%.2f", pago.interesTotal)}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF6A1B9A)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFFE0E0E0))

            // Información adicional
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pago.fecha,
                        fontSize = 13.sp,
                        color = Color(0xFF424242)
                    )
                    if (pago.lugar.isNotBlank()) {
                        Text(
                            pago.lugar,
                            fontSize = 13.sp,
                            color = Color(0xFF424242)
                        )
                    }
                }
            }

            // Botón de reimprimir
            Button(
                onClick = onReimprimir,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reimprimir Recibo", fontSize = 14.sp)
            }
        }
    }
}

// Función para reimprimir recibo del cobrador
private suspend fun reimprimirReciboCobrador(context: android.content.Context, pago: PagoItem) {
    try {
        val saldoAnterior = pago.saldoRestante + pago.monto
        val file = ReciboHelper.generarReciboPDF(
            context = context,
            cliente = pago.cliente,
            prestamoId = if (pago.numeroPrestamo > 0) "Préstamo Nº ${pago.numeroPrestamo}" else pago.prestamoId,
            fecha = pago.fecha,
            montoPagado = pago.monto.toString(),
            saldoAnterior = saldoAnterior,
            proximoPago = "Consultar sistema",
            cuota = pago.cuota,
            cobrador = pago.cobrador,
            lugar = pago.lugar,
            firma = pago.firma.ifBlank { pago.cobrador },
            tipoPago = pago.tipoPago,
            mora = pago.mora
        )

        if (file != null) {
            ReciboHelper.imprimirPDF(context, file)
            Toast.makeText(context, "Recibo reimpreso", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Error al generar PDF", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}