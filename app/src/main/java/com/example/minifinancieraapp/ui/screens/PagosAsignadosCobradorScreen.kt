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
import com.example.minifinancieraapp.ui.models.PagoItem
import com.example.minifinancieraapp.util.SessionManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

// ─────────────────────────────────────────────────────────────────────────────
// FUNCIÓN AUXILIAR: Parsear fecha desde el string almacenado en PagoItem.fecha
// ─────────────────────────────────────────────────────────────────────────────
private val FORMATTERS_COBRADOR = listOf(
    SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()),
    SimpleDateFormat("dd/MM/yyyy HH:mm",    Locale.getDefault()),
    SimpleDateFormat("dd/MM/yyyy",           Locale.getDefault()),
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
    SimpleDateFormat("yyyy-MM-dd",           Locale.getDefault()),
).also { list -> list.forEach { it.isLenient = false } }

private fun parsearFechaPago(fechaStr: String): Date? {
    val limpia = fechaStr.trim()
    for (fmt in FORMATTERS_COBRADOR) {
        try {
            val d = fmt.parse(limpia)
            if (d != null) return d
        } catch (_: Exception) { }
    }
    return null
}

private fun inicioDelDia(d: Date): Date = Calendar.getInstance().apply {
    time = d
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
}.time

private fun finDelDia(d: Date): Date = Calendar.getInstance().apply {
    time = d
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
    set(Calendar.SECOND, 59);       set(Calendar.MILLISECOND, 999)
}.time

// ─────────────────────────────────────────────────────────────────────────────
// PANTALLA PRINCIPAL
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagosAsignadosCobradorScreen(navController: NavController) {
    val db             = FirebaseFirestore.getInstance()
    val context        = LocalContext.current
    val session        = remember { SessionManager(context) }
    val scope          = rememberCoroutineScope()
    val formatter      = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    val uidCobrador    = session.getUid()
    val nombreCobrador = session.getNombre()

    var pagos          by remember { mutableStateOf<List<PagoItem>>(emptyList()) }
    var pagosFiltrados by remember { mutableStateOf<List<PagoItem>>(emptyList()) }
    var isLoading      by remember { mutableStateOf(true) }
    var searchText     by remember { mutableStateOf("") }
    var mostrarFiltros by remember { mutableStateOf(false) }
    var fechaInicio    by remember { mutableStateOf<Date?>(null) }
    var fechaFin       by remember { mutableStateOf<Date?>(null) }
    var generandoPDF   by remember { mutableStateOf(false) }

    // ── Filtrado ─────────────────────────────────────────────────────────────
    fun aplicarFiltros(
        lista:  List<PagoItem>,
        texto:  String,
        inicio: Date?,
        fin:    Date?
    ): List<PagoItem> {
        val inicioMs = inicio?.let { inicioDelDia(it).time }
        val finMs    = fin?.let    { finDelDia(it).time    }
        return lista.filter { pago ->
            val pasaCliente = texto.isBlank() ||
                    pago.cliente.contains(texto, ignoreCase = true)
            val pasaFecha = if (inicioMs == null || finMs == null) true
            else {
                val fechaPago = parsearFechaPago(pago.fecha)
                if (fechaPago == null) true else fechaPago.time in inicioMs..finMs
            }
            pasaCliente && pasaFecha
        }
    }

    fun limpiarFiltros() {
        searchText     = ""
        fechaInicio    = null
        fechaFin       = null
        pagosFiltrados = pagos
    }

    // ── Carga Firestore ───────────────────────────────────────────────────────
    fun cargarPagos() {
        scope.launch {
            try {
                if (uidCobrador.isNullOrEmpty()) {
                    Toast.makeText(context, "Error: sesión no disponible.", Toast.LENGTH_LONG).show()
                    isLoading = false
                    return@launch
                }
                isLoading = true

                val usuariosMap = db.collection("usuarios").get().await()
                    .documents.associateBy({ it.id }, { it.getString("nombre") ?: it.id })

                val prestamosMap = db.collection("prestamos").get().await()
                    .documents.associateBy({ it.id }, { it.data ?: emptyMap() })

                val listaCargada = db.collection("pagos")
                    .whereEqualTo("registradoPor", uidCobrador)
                    .get().await()
                    .documents
                    .mapNotNull { doc ->
                        try { procesarDocumentoPagoCobrador(doc, usuariosMap, prestamosMap, formatter) }
                        catch (e: Exception) { Log.e("CargarPagos", "Error ${doc.id}: ${e.message}"); null }
                    }
                    .sortedByDescending { parsearFechaPago(it.fecha)?.time ?: 0L }

                pagos          = listaCargada
                pagosFiltrados = aplicarFiltros(listaCargada, searchText, fechaInicio, fechaFin)

            } catch (e: Exception) {
                Toast.makeText(context, "Error al cargar pagos: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    // ── Generación de PDF ────────────────────────────────────────────────────
    fun generarPDF() {
        if (generandoPDF) return
        if (pagosFiltrados.isEmpty()) {
            Toast.makeText(context, "No hay pagos para exportar", Toast.LENGTH_SHORT).show()
            return
        }

        generandoPDF = true

        scope.launch {
            val job = launch {
                try {
                    Log.d("PDFCobrador", "▶ Iniciando PDF con ${pagosFiltrados.size} registros")

                    val fechasOrdenadas = pagosFiltrados
                        .mapNotNull { parsearFechaPago(it.fecha) }
                        .sorted()

                    val fechaInicioReporte = fechaInicio
                        ?: fechasOrdenadas.firstOrNull()
                        ?: Date(0)

                    val fechaFinReporte = fechaFin
                        ?: fechasOrdenadas.lastOrNull()
                        ?: Date()

                    val periodo = buildString {
                        append("Cobrador: ${nombreCobrador ?: "Desconocido"}")
                        if (searchText.isNotBlank()) append(" | Cliente: $searchText")
                        if (fechaInicio != null && fechaFin != null)
                            append(" | ${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}")
                    }

                    Log.d("PDFCobrador", "▶ Llamando generarResumenPagosPDF...")

                    val archivoPDF = ReciboHelper.generarResumenPagosPDF(
                        context     = context,
                        pagos       = pagosFiltrados,
                        fechaInicio = fechaInicioReporte,
                        fechaFin    = fechaFinReporte,
                        periodo     = periodo
                    )

                    Log.d("PDFCobrador", "▶ Resultado: $archivoPDF | existe: ${archivoPDF?.exists()}")

                    if (archivoPDF != null && archivoPDF.exists()) {
                        // ✅ CORRECCIÓN: usar compartirReciboPDF para mostrar el selector
                        //    (WhatsApp, Drive, Adobe, impresora, etc.)
                        ReciboHelper.compartirReciboPDF(context, archivoPDF)
                        Toast.makeText(
                            context,
                            "✅ PDF listo: ${pagosFiltrados.size} registros",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Log.e("PDFCobrador", "❌ archivoPDF es null o no existe")
                        Toast.makeText(context, "❌ No se pudo generar el PDF", Toast.LENGTH_LONG).show()
                    }

                } catch (e: Exception) {
                    Log.e("PDFCobrador", "❌ Excepción: ${e::class.simpleName} - ${e.message}", e)
                    Toast.makeText(
                        context,
                        "Error: ${e::class.simpleName}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    Log.d("PDFCobrador", "▶ finally ejecutado — liberando generandoPDF")
                    generandoPDF = false
                }
            }

            // Timeout de seguridad: 30 segundos
            kotlinx.coroutines.delay(30_000)
            if (job.isActive) {
                Log.e("PDFCobrador", "⏰ TIMEOUT — cancelando")
                job.cancel()
                Toast.makeText(context, "Tiempo de espera agotado al generar PDF", Toast.LENGTH_LONG).show()
                generandoPDF = false
            }
        }
    }

    LaunchedEffect(Unit) { cargarPagos() }

    LaunchedEffect(searchText, fechaInicio, fechaFin) {
        pagosFiltrados = aplicarFiltros(pagos, searchText, fechaInicio, fechaFin)
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Mis Pagos Registrados", color = Color.White, fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0061A7)),
                actions = {
                    IconButton(onClick = { mostrarFiltros = !mostrarFiltros }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filtros", tint = Color.White)
                    }
                    IconButton(onClick = { cargarPagos() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(
                    colors = listOf(Color(0xFFF5F7FA), Color(0xFFE8EEF5))
                ))
        ) {
            if (isLoading) {
                LoadingScreenCobrador(padding)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (mostrarFiltros) {
                        item {
                            FiltrosCobradorCard(
                                searchText         = searchText,
                                onSearchTextChange = { nuevo ->
                                    searchText     = nuevo
                                    pagosFiltrados = aplicarFiltros(pagos, nuevo, fechaInicio, fechaFin)
                                },
                                fechaInicio        = fechaInicio,
                                fechaFin           = fechaFin,
                                onFechasChange     = { inicio, fin ->
                                    fechaInicio    = inicio
                                    fechaFin       = fin
                                    pagosFiltrados = aplicarFiltros(pagos, searchText, inicio, fin)
                                },
                                pagosFiltrados     = pagosFiltrados,
                                nombreCobrador     = nombreCobrador ?: "",
                                onLimpiarFiltros   = { limpiarFiltros() },
                                generandoPDF       = generandoPDF,
                                onGenerarPDF       = { generarPDF() }
                            )
                        }
                    }

                    if (pagosFiltrados.isNotEmpty()) {
                        item {
                            StatisticsCardCobrador(
                                pagos          = pagosFiltrados,
                                nombreCobrador = nombreCobrador ?: ""
                            )
                        }
                    }

                    if (pagosFiltrados.isEmpty()) {
                        item {
                            if (pagos.isEmpty()) EmptyStateCardCobrador()
                            else EmptySearchCardCobrador(searchText)
                        }
                    } else {
                        items(items = pagosFiltrados, key = { it.docId }) { pago ->
                            PagoCardCobrador(
                                pago         = pago,
                                session      = session,
                                context      = context,
                                onReimprimir = {
                                    scope.launch { reimprimirReciboCobrador(context, pago) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FiltrosCobradorCard
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun FiltrosCobradorCard(
    searchText:         String,
    onSearchTextChange: (String) -> Unit,
    fechaInicio:        Date?,
    fechaFin:           Date?,
    onFechasChange:     (Date?, Date?) -> Unit,
    pagosFiltrados:     List<PagoItem>,
    nombreCobrador:     String,
    onLimpiarFiltros:   () -> Unit,
    generandoPDF:       Boolean,
    onGenerarPDF:       () -> Unit
) {
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Filtros y reportes",
                fontWeight = FontWeight.Bold,
                fontSize   = 16.sp,
                color      = Color(0xFF0061A7)
            )

            OutlinedTextField(
                value         = searchText,
                onValueChange = onSearchTextChange,
                label         = { Text("Buscar cliente") },
                placeholder   = { Text("Nombre del cliente...") },
                leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon  = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { onSearchTextChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(8.dp)
            )

            FechaSelectorCobrador(
                fechaInicio    = fechaInicio,
                fechaFin       = fechaFin,
                onFechasChange = onFechasChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Botón Limpiar
                Button(
                    onClick  = onLimpiarFiltros,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C757D)),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Limpiar", fontSize = 13.sp)
                }

                // Botón PDF con indicador de carga
                Button(
                    onClick  = onGenerarPDF,
                    enabled  = !generandoPDF && pagosFiltrados.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = Color(0xFF2E7D32),
                        disabledContainerColor = Color(0xFF9E9E9E)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (generandoPDF) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(16.dp),
                            color       = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generando...", fontSize = 11.sp)
                    } else {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("PDF (${pagosFiltrados.size})", fontSize = 12.sp)
                    }
                }
            }

            // Resumen de filtros activos
            if (searchText.isNotBlank() || fechaInicio != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                    shape  = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Filtros aplicados:",
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color      = Color(0xFF1565C0)
                        )
                        Text("• Cobrador: $nombreCobrador", fontSize = 11.sp, color = Color(0xFF1976D2))
                        if (searchText.isNotBlank())
                            Text("• Cliente: $searchText", fontSize = 11.sp, color = Color(0xFF1976D2))
                        if (fechaInicio != null && fechaFin != null)
                            Text(
                                "• Fechas: ${formatter.format(fechaInicio)} – ${formatter.format(fechaFin)}",
                                fontSize = 11.sp, color = Color(0xFF1976D2)
                            )
                        Text(
                            "Mostrando ${pagosFiltrados.size} registro(s)",
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color      = Color(0xFF2E7D32)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PROCESADOR DE DOCUMENTO FIRESTORE → PagoItem
// ─────────────────────────────────────────────────────────────────────────────
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

        val mora          = doc.getDouble("mora") ?: 0.0
        val interesTotal  = doc.getDouble("interesTotal") ?: doc.getDouble("interes") ?: 0.0
        val prestamoId    = doc.getString("prestamoId") ?: return null
        val cobradorId    = doc.getString("registradoPor") ?: doc.getString("cobrador") ?: "Desconocido"
        val metodoPago    = doc.getString("metodoPago") ?: doc.getString("tipoPago") ?: "Efectivo"
        val esAbonoParcial = doc.getBoolean("esAbonoParcial") ?: false

        val tipoPagoFinal = when {
            esAbonoParcial                  -> "Abono Parcial"
            metodoPago == "Manual (Admin)"  -> "Manual (Admin)"
            else                            -> metodoPago
        }

        val cuota = when {
            doc.contains("numeroCuota")  -> doc.get("numeroCuota").toString()
            doc.contains("cuota")        -> doc.get("cuota").toString()
            doc.contains("cuotaActual")  -> doc.get("cuotaActual").toString()
            else                         -> "1"
        }

        val lugar = doc.getString("lugar") ?: ""
        val firma = doc.getString("firma") ?: ""

        val saldoRestante = if (esAbonoParcial) {
            val anterior = doc.getDouble("montoRestanteAntes") ?: 0.0
            (anterior - monto).coerceAtLeast(0.0)
        } else {
            doc.getDouble("saldoRestante") ?: 0.0
        }

        val fullFormatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        val fecha: String = try {
            when (val raw = doc.get("fechaPago") ?: doc.get("fecha") ?: doc.get("fechaCreacion")) {
                is Timestamp -> fullFormatter.format(raw.toDate())
                is String    -> {
                    val parsed = parsearFechaPago(raw)
                    if (parsed != null) fullFormatter.format(parsed) else raw
                }
                else -> fullFormatter.format(Date())
            }
        } catch (e: Exception) {
            fullFormatter.format(Date())
        }

        val nombreCobrador    = usuarios[cobradorId] ?: cobradorId
        val datosPrestamoInt  = prestamos[prestamoId]

        val numeroPrestamoStr = when (val n = datosPrestamoInt?.get("numeroPrestamo")) {
            is String -> n
            is Long   -> n.toString()
            is Int    -> n.toString()
            else      -> doc.getString("numeroPrestamo") ?: ""
        }

        return PagoItem(
            docId          = doc.id,
            cliente        = clienteNombre,
            prestamoId     = prestamoId,
            fecha          = fecha,
            monto          = monto,
            mora           = mora,
            interesTotal   = interesTotal,
            cuota          = cuota,
            cobrador       = nombreCobrador,
            lugar          = lugar,
            firma          = firma,
            tipoPago       = tipoPagoFinal,
            saldoRestante  = saldoRestante,
            numeroPrestamo = numeroPrestamoStr
        )

    } catch (e: Exception) {
        Log.e("ProcesarPagoCobrador", "Error en ${doc.id}: ${e.message}", e)
        return null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// COMPOSABLE: Selector de fechas con DatePickerDialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun FechaSelectorCobrador(
    fechaInicio:    Date?,
    fechaFin:       Date?,
    onFechasChange: (Date?, Date?) -> Unit
) {
    val context   = LocalContext.current
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    fun showDatePicker(initialDate: Date?, onSelected: (Date) -> Unit) {
        val cal = Calendar.getInstance().apply {
            if (initialDate != null) time = initialDate
        }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val selected = Calendar.getInstance().apply { set(year, month, day) }.time
                onSelected(selected)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Filtrar por fechas", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1976D2))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Desde
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp)
                    .clickable {
                        showDatePicker(fechaInicio) { selected ->
                            val finFinal = if (fechaFin != null && selected.after(fechaFin)) selected else fechaFin
                            onFechasChange(selected, finFinal)
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (fechaInicio != null) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Color(0xFF1976D2), modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Desde", fontSize = 11.sp, color = Color.Gray)
                        Text(
                            fechaInicio?.let { formatter.format(it) } ?: "Seleccionar",
                            fontSize   = 13.sp,
                            fontWeight = if (fechaInicio != null) FontWeight.Bold else FontWeight.Normal,
                            color      = if (fechaInicio != null) Color(0xFF1976D2) else Color.Gray
                        )
                    }
                }
            }

            // Hasta
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp)
                    .clickable {
                        showDatePicker(fechaFin) { selected ->
                            val inicioFinal = if (fechaInicio != null && selected.before(fechaInicio)) selected else fechaInicio
                            onFechasChange(inicioFinal, selected)
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (fechaFin != null) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Color(0xFF1976D2), modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hasta", fontSize = 11.sp, color = Color.Gray)
                        Text(
                            fechaFin?.let { formatter.format(it) } ?: "Seleccionar",
                            fontSize   = 13.sp,
                            fontWeight = if (fechaFin != null) FontWeight.Bold else FontWeight.Normal,
                            color      = if (fechaFin != null) Color(0xFF1976D2) else Color.Gray
                        )
                    }
                }
            }
        }

        // Chips de acceso rápido
        if (fechaInicio == null && fechaFin == null) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChipCobrador("Hoy", Icons.Default.Today, Color(0xFF1976D2)) {
                        val hoy = Date(); onFechasChange(hoy, hoy)
                    }
                }
                item {
                    FilterChipCobrador("Última semana", Icons.Default.CalendarViewWeek, Color(0xFF2E7D32)) {
                        val fin    = Date()
                        val inicio = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -7) }.time
                        onFechasChange(inicio, fin)
                    }
                }
                item {
                    FilterChipCobrador("Último mes", Icons.Default.CalendarMonth, Color(0xFF6A1B9A)) {
                        val fin    = Date()
                        val inicio = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -30) }.time
                        onFechasChange(inicio, fin)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composables auxiliares
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun FilterChipCobrador(text: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable { onClick() }.height(40.dp),
        colors   = CardDefaults.cardColors(containerColor = color),
        shape    = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text(text, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StatisticsCardCobrador(pagos: List<PagoItem>, nombreCobrador: String) {
    val totalPagos      = pagos.size
    val montoTotal      = pagos.sumOf { it.monto }
    val totalMora       = pagos.sumOf { it.mora }
    val totalRecaudado  = montoTotal + totalMora          // ← NUEVO: suma total
    val abonosParciales = pagos.count { it.tipoPago == "Abono Parcial" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF0061A7)),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mis estadísticas", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Surface(color = Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp)) {
                    Text(nombreCobrador, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }

            // ── TOTAL RECAUDADO destacado ──────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color    = Color.White.copy(alpha = 0.18f),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("💰 TOTAL RECAUDADO", fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
                    Spacer(Modifier.height(4.dp))
                    Text("L. ${String.format("%,.2f", totalRecaudado)}",
                        fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    if (totalMora > 0)
                        Text("(abonos: L. ${String.format("%,.2f", montoTotal)}  +  mora: L. ${String.format("%,.2f", totalMora)})",
                            fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItemCobrador("Total pagos",  totalPagos.toString(), "📋")
                StatItemCobrador("Abonos",       "L. ${String.format("%.2f", montoTotal)}", "💵")
            }
            if (totalMora > 0 || abonosParciales > 0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (totalMora > 0)
                        StatItemCobrador("Total mora", "L. ${String.format("%.2f", totalMora)}", "🚨")
                    if (abonosParciales > 0)
                        StatItemCobrador("Abonos parciales", abonosParciales.toString(), "🧩")
                }
            }
        }
    }
}

@Composable
private fun StatItemCobrador(title: String, value: String, icon: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 24.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White, textAlign = TextAlign.Center)
        Text(title, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun EmptyStateCardCobrador() {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("📋", fontSize = 48.sp)
            Text("No has registrado pagos", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF424242), textAlign = TextAlign.Center)
            Text("Los pagos que registres aparecerán aquí", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun EmptySearchCardCobrador(searchText: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(64.dp))
            Text("No se encontraron resultados", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Gray, textAlign = TextAlign.Center)
            Text(
                if (searchText.isNotBlank()) "No hay pagos que coincidan con \"$searchText\""
                else "No hay pagos en el rango de fechas seleccionado",
                fontSize = 14.sp, color = Color.Gray.copy(alpha = 0.7f), textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoadingScreenCobrador(padding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Color(0xFF0061A7), strokeWidth = 3.dp)
            Text("Cargando mis pagos...", color = Color.Gray, fontSize = 14.sp)
        }
    }
}

@Composable
private fun PagoCardCobrador(
    pago:         PagoItem,
    session:      SessionManager,
    context:      android.content.Context,
    onReimprimir: () -> Unit
) {
    val esAbonoParcial = pago.tipoPago == "Abono Parcial"
    val esManual       = pago.tipoPago == "Manual (Admin)"

    val fechaMostrar = remember(pago.fecha) {
        val d = parsearFechaPago(pago.fecha)
        if (d != null) SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(d)
        else pago.fecha.split(" ").firstOrNull() ?: pago.fecha
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = when {
                esAbonoParcial -> Color(0xFFFFF3E0)
                esManual       -> Color(0xFFE8EAF6)
                else           -> Color.White
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape     = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Encabezado
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pago.cliente, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1A1A1A))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cuota: ${pago.cuota}", fontSize = 14.sp, color = Color.Gray)
                        if (esAbonoParcial) {
                            Surface(color = Color(0xFFFF9800), shape = RoundedCornerShape(12.dp)) {
                                Text("ABONO", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        if (esManual) {
                            Surface(color = Color(0xFF673AB7), shape = RoundedCornerShape(12.dp)) {
                                Text("MANUAL", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
                if (pago.numeroPrestamo.isNotEmpty()) {
                    Surface(color = Color(0xFF0061A7), shape = RoundedCornerShape(8.dp)) {
                        Text("#${pago.numeroPrestamo}", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }

            HorizontalDivider(color = Color(0xFFE0E0E0))

            // Montos
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (esAbonoParcial) "Abono realizado" else "Total pagado", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        "L. ${String.format("%.2f", pago.monto)}",
                        fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        color = when { esAbonoParcial -> Color(0xFFFF9800); esManual -> Color(0xFF673AB7); else -> Color(0xFF2E7D32) }
                    )
                    if (pago.mora > 0)
                        Text("Base: L. ${String.format("%.2f", pago.monto - pago.mora)}", fontSize = 12.sp, color = Color.Gray)
                    if (esAbonoParcial && (pago.saldoRestante ?: 0.0) > 0)
                        Text("Resta de cuota: L. ${String.format("%.2f", pago.saldoRestante ?: 0.0)}", fontSize = 12.sp, color = Color(0xFFFF9800), fontWeight = FontWeight.Medium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        color = when (pago.tipoPago) {
                            "Efectivo"       -> Color(0xFF4CAF50)
                            "Abono Parcial"  -> Color(0xFFFF9800)
                            "Manual (Admin)" -> Color(0xFF673AB7)
                            else             -> Color(0xFF2196F3)
                        },
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(pago.tipoPago, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
            }

            // Mora e interés
            if (pago.mora > 0.0 || pago.interesTotal > 0.0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (pago.mora > 0.0) {
                        Column {
                            Text("Mora", fontSize = 12.sp, color = Color.Red)
                            Text("L. ${String.format("%.2f", pago.mora)}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Red)
                        }
                    }
                    if (pago.interesTotal > 0.0) {
                        Column {
                            Text("Interés", fontSize = 12.sp, color = Color(0xFF6A1B9A))
                            Text("L. ${String.format("%.2f", pago.interesTotal)}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF6A1B9A))
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFFE0E0E0))

            // Fecha y lugar
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(fechaMostrar, fontSize = 13.sp, color = Color(0xFF424242))
                    if (pago.lugar.isNotBlank())
                        Text(pago.lugar, fontSize = 13.sp, color = Color(0xFF424242))
                }
            }

            // Botón reimprimir — ahora muestra selector de apps (compartir)
            Button(
                onClick  = onReimprimir,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Compartir Recibo", fontSize = 14.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ✅ CORRECCIÓN: usar compartirReciboPDF en lugar de imprimirPDF
// ─────────────────────────────────────────────────────────────────────────────
private suspend fun reimprimirReciboCobrador(context: android.content.Context, pago: PagoItem) {
    try {
        val saldoAnterior = (pago.saldoRestante ?: 0.0) + pago.monto
        val file = ReciboHelper.generarReciboPDF(
            context       = context,
            cliente       = pago.cliente,
            prestamoId    = if (pago.numeroPrestamo.isNotEmpty()) "Préstamo Nº ${pago.numeroPrestamo}" else pago.prestamoId,
            fecha         = pago.fecha.split(" ").firstOrNull() ?: pago.fecha,
            montoPagado   = pago.monto.toString(),
            saldoAnterior = saldoAnterior,
            proximoPago   = "Consultar sistema",
            cuota         = pago.cuota,
            cobrador      = pago.cobrador,
            lugar         = pago.lugar,
            firma         = pago.firma.ifBlank { pago.cobrador },
            tipoPago      = pago.tipoPago,
            mora          = pago.mora
        )
        if (file != null) {
            // ✅ CORRECCIÓN: compartirReciboPDF muestra WhatsApp, Drive, Adobe, etc.
            ReciboHelper.compartirReciboPDF(context, file)
            Toast.makeText(context, "Recibo listo para compartir", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Error al generar PDF", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}