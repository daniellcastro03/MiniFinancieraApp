package com.example.capitalexpressapp.ui.screens

import android.app.DatePickerDialog
import android.content.Context
import android.util.Log
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.capitalexpressapp.util.NetworkUtils.isInternetAvailable
import com.example.capitalexpressapp.util.ReciboHelper.generarResumenPagosPDF
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.example.minifinancieraapp.ui.models.PagoItem
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

// ViewModel para gestionar el estado global de los préstamos
class PrestamoStateManager {
    companion object {
        private val _prestamoUpdates = mutableMapOf<String, MutableState<Double>>()

        fun getSaldoState(prestamoId: String): MutableState<Double> {
            return _prestamoUpdates.getOrPut(prestamoId) { mutableStateOf(0.0) }
        }

        fun updateSaldo(prestamoId: String, nuevoSaldo: Double) {
            _prestamoUpdates[prestamoId]?.value = nuevoSaldo
        }

        fun notifyPrestamoUpdate(prestamoId: String, db: FirebaseFirestore, context: Context) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
                    val nuevoSaldo = prestamoDoc.getDouble("saldo") ?: 0.0
                    updateSaldo(prestamoId, nuevoSaldo)

                    val prefs = context.getSharedPreferences("prestamo_updates", Context.MODE_PRIVATE)
                    prefs.edit().putString("last_update_${prestamoId}", System.currentTimeMillis().toString()).apply()

                } catch (e: Exception) {
                    Log.e("PrestamoStateManager", "Error actualizando saldo: ${e.message}")
                }
            }
        }
    }
}

// Modelo de datos para usuarios
data class UsuarioItem(
    val id: String,
    val nombre: String,
    val rol: String
)

// FUNCIÓN PRINCIPAL DE LA PANTALLA - CORREGIDA PARA ABONOS PARCIALES
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialPagosScreen(navController: NavController, rol: String) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    var pagos by remember { mutableStateOf(listOf<PagoItem>()) }
    var pagosFiltrados by remember { mutableStateOf(listOf<PagoItem>()) }
    var usuarios by remember { mutableStateOf(listOf<UsuarioItem>()) }
    var filtroCliente by remember { mutableStateOf("") }
    var filtroCobradorId by remember { mutableStateOf("") }
    var filtroCobradorNombre by remember { mutableStateOf("") }
    var fechaInicio by remember { mutableStateOf<Date?>(null) }
    var fechaFin by remember { mutableStateOf<Date?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var mostrarFiltros by remember { mutableStateOf(false) }
    var pagoAEliminar by remember { mutableStateOf<PagoItem?>(null) }

    val formatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    // FUNCIÓN CORREGIDA PARA APLICAR FILTROS
    fun aplicarFiltros() {
        pagosFiltrados = pagos.filter { pago ->
            // Filtro por cliente
            val cumpleFiltroCliente = filtroCliente.isBlank() ||
                    pago.cliente.contains(filtroCliente, ignoreCase = true)

            // FILTRO POR COBRADOR CORREGIDO - Usar el ID del cobrador
            val cumpleFiltroCobrador = filtroCobradorId.isBlank() || run {
                // Buscar en los datos originales el cobradorId que corresponde al pago
                val cobradorDelPago = usuarios.find { it.nombre == pago.cobrador }?.id ?: pago.cobrador
                cobradorDelPago == filtroCobradorId
            }

            // FILTRO POR FECHAS CORREGIDO
            val cumpleFiltroFecha = if (fechaInicio == null || fechaFin == null) {
                true
            } else {
                try {
                    // Parsear la fecha del pago (formato: "dd/MM/yyyy HH:mm" o "dd/MM/yyyy")
                    val fechaStr = pago.fecha.split(" ")[0] // Tomar solo la parte de fecha
                    val fechaPago = formatter.parse(fechaStr)

                    if (fechaPago != null) {
                        // Normalizar fechas a medianoche para comparación correcta
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

            val cumple = cumpleFiltroCliente && cumpleFiltroCobrador && cumpleFiltroFecha

            cumple
        }

        Log.d("Filtros", "Filtros aplicados: ${pagosFiltrados.size} de ${pagos.size} pagos")
    }

    // FUNCIÓN PARA LIMPIAR FILTROS
    fun limpiarFiltros() {
        filtroCliente = ""
        filtroCobradorId = ""
        filtroCobradorNombre = ""
        fechaInicio = null
        fechaFin = null
        pagosFiltrados = pagos
        Log.d("Filtros", "Filtros limpiados")
    }

    // Función para cargar usuarios
    fun cargarUsuarios() {
        scope.launch {
            try {
                if (isInternetAvailable(context)) {
                    val snapshot = db.collection("usuarios").get().await()
                    usuarios = snapshot.documents.mapNotNull { doc ->
                        val nombre = doc.getString("nombre") ?: return@mapNotNull null
                        val rolUsuario = doc.getString("rol") ?: return@mapNotNull null

                        if (rolUsuario in listOf("cobrador", "admin")) {
                            UsuarioItem(
                                id = doc.id,
                                nombre = nombre,
                                rol = rolUsuario
                            )
                        } else null
                    }.sortedBy { it.nombre }
                }
            } catch (e: Exception) {
                Log.e("CargarUsuarios", "Error: ${e.message}")
            }
        }
    }

    // Función para cargar pagos - ACTUALIZADA PARA ABONOS PARCIALES
    fun cargarPagos() {
        scope.launch {
            try {
                cargando = true
                val prefs = context.getSharedPreferences("offline_data", Context.MODE_PRIVATE)

                if (isInternetAvailable(context)) {
                    val usuariosDeferred = async {
                        db.collection("usuarios").get().await()
                            .associateBy({ it.id }, { it.getString("nombre") ?: it.id })
                    }
                    val prestamosDeferred = async {
                        db.collection("prestamos").get().await()
                            .associateBy({ it.id }, { it.data })
                    }
                    val pagosDeferred = async { db.collection("pagos").get().await() }

                    val usuariosMap = usuariosDeferred.await()
                    val prestamos = prestamosDeferred.await()
                    val snapshot = pagosDeferred.await()

                    Log.d("HistorialPagos", "Documentos encontrados en colección pagos: ${snapshot.documents.size}")

                    pagos = snapshot.documents.mapNotNull { doc ->
                        try {
                            val pago = procesarDocumentoPagoMejorado(doc, usuariosMap, prestamos, rol, formatter)
                            if (pago != null) {
                                Log.d("HistorialPagos", "Pago procesado: ${pago.cliente} - ${pago.tipoPago} - L. ${pago.monto}")
                            }
                            pago
                        } catch (e: Exception) {
                            Log.e("HistorialPagos", "Error procesando documento ${doc.id}: ${e.message}")
                            null
                        }
                    }.sortedByDescending {
                        try {
                            formatter.parse(it.fecha.split(" ")[0])?.time ?: 0L
                        } catch (e: Exception) {
                            0L
                        }
                    }

                    Log.d("HistorialPagos", "Total pagos procesados: ${pagos.size}")

                    prefs.edit().putString("historial_pagos", gson.toJson(pagos)).apply()
                } else {
                    val json = prefs.getString("historial_pagos", "[]") ?: "[]"
                    pagos = try {
                        gson.fromJson(json, Array<PagoItem>::class.java).toList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    Toast.makeText(context, "Modo offline", Toast.LENGTH_SHORT).show()
                }

                pagosFiltrados = pagos
            } catch (e: Exception) {
                Log.e("HistorialPagos", "Error: ${e.message}")
                Toast.makeText(context, "Error cargando pagos: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                cargando = false
            }
        }
    }

    // Cargar datos al iniciar
    LaunchedEffect(Unit) {
        cargarUsuarios()
        cargarPagos()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial de Pagos", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0061A7),
                    titleContentColor = Color.White
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
        if (cargando) {
            LoadingScreen(padding)
        } else {
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
                                    "Filtros de búsqueda",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color(0xFF0061A7)
                                )

                                // Filtro por cliente
                                OutlinedTextField(
                                    value = filtroCliente,
                                    onValueChange = {
                                        filtroCliente = it
                                        aplicarFiltros()
                                    },
                                    label = { Text("Buscar cliente") },
                                    placeholder = { Text("Nombre del cliente...") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                )

                                // Selector de cobrador
                                CobradorDropdown(
                                    usuarios = usuarios,
                                    filtroCobradorNombre = filtroCobradorNombre,
                                    onCobradorChange = { id, nombre ->
                                        filtroCobradorId = id
                                        filtroCobradorNombre = nombre
                                        aplicarFiltros()
                                    }
                                )

                                // Selector de fechas mejorado
                                FechaSelectorMejorado(
                                    fechaInicio = fechaInicio,
                                    fechaFin = fechaFin,
                                    onFechasChange = { inicio, fin ->
                                        fechaInicio = inicio
                                        fechaFin = fin
                                        aplicarFiltros()
                                    }
                                )

                                // Botones de acción
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Limpiar filtros
                                    Button(
                                        onClick = { limpiarFiltros() },
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
                                        onClick = {
                                            if (pagosFiltrados.isEmpty()) {
                                                Toast.makeText(context, "No hay pagos para exportar", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }

                                            scope.launch {
                                                try {
                                                    // Calcular fechas para el reporte
                                                    val fechaInicioReporte = fechaInicio ?: run {
                                                        // Usar la fecha más antigua de los pagos filtrados
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
                                                        // Usar la fecha más reciente de los pagos filtrados
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
                                                        val filtrosAplicados = mutableListOf<String>()

                                                        if (filtroCliente.isNotBlank()) {
                                                            filtrosAplicados.add("Cliente: $filtroCliente")
                                                        }
                                                        if (filtroCobradorNombre.isNotBlank()) {
                                                            filtrosAplicados.add("Cobrador: $filtroCobradorNombre")
                                                        }
                                                        if (fechaInicio != null && fechaFin != null) {
                                                            filtrosAplicados.add("Fechas: ${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}")
                                                        }

                                                        if (filtrosAplicados.isNotEmpty()) {
                                                            append(filtrosAplicados.joinToString(" | "))
                                                        } else {
                                                            append("Todos los registros")
                                                        }
                                                    }

                                                    // GENERAR PDF CON PAGOS FILTRADOS
                                                    val archivoPDF = generarResumenPagosPDF(
                                                        context = context,
                                                        pagos = pagosFiltrados, // PAGOS YA FILTRADOS
                                                        fechaInicio = fechaInicioReporte,
                                                        fechaFin = fechaFinReporte,
                                                        periodo = periodo
                                                    )

                                                    if (archivoPDF != null && archivoPDF.exists()) {
                                                        ReciboHelper.imprimirPDF(context, archivoPDF)
                                                        Toast.makeText(
                                                            context,
                                                            "PDF generado: ${pagosFiltrados.size} registros",
                                                            Toast.LENGTH_LONG
                                                        ).show()

                                                        // Debug log para verificar
                                                        Log.d("ExportarPDF", "PDF generado con ${pagosFiltrados.size} pagos filtrados")
                                                        Log.d("ExportarPDF", "Cobradores en PDF: ${pagosFiltrados.map { it.cobrador }.distinct().joinToString(", ")}")
                                                    } else {
                                                        Toast.makeText(context, "Error al generar PDF", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e("ExportarPDF", "Error: ${e.message}", e)
                                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
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
                                if (filtroCliente.isNotBlank() || filtroCobradorNombre.isNotBlank() || fechaInicio != null) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("Filtros aplicados:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                                            if (filtroCliente.isNotBlank()) Text("• Cliente: $filtroCliente", fontSize = 11.sp, color = Color(0xFF1976D2))
                                            if (filtroCobradorNombre.isNotBlank()) Text("• Cobrador: $filtroCobradorNombre", fontSize = 11.sp, color = Color(0xFF1976D2))
                                            if (fechaInicio != null && fechaFin != null) Text("• Fechas: ${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}", fontSize = 11.sp, color = Color(0xFF1976D2))
                                            Text("Mostrando ${pagosFiltrados.size} registro(s)", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2E7D32))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Estadísticas
                if (pagosFiltrados.isNotEmpty()) {
                    item { StatsCard(pagosFiltrados) }
                }

                // Lista de pagos
                if (pagosFiltrados.isEmpty()) {
                    item { EmptyStateCard() }
                } else {
                    items(items = pagosFiltrados, key = { it.docId }) { pago ->
                        PagoCard(
                            pago = pago,
                            rol = rol,
                            onEliminar = { pagoAEliminar = pago },
                            onReimprimir = {
                                scope.launch {
                                    reimprimirRecibo(context, pago)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Diálogo de confirmación para eliminar
        pagoAEliminar?.let { pago ->
            ConfirmDeleteDialog(
                pago = pago,
                db = db,
                context = context,
                onConfirm = {
                    scope.launch {
                        eliminarPagoCorregido(
                            context = context,
                            db = db,
                            pago = pago,
                            onComplete = {
                                cargarPagos()
                                pagoAEliminar = null
                            }
                        )
                    }
                },
                onDismiss = { pagoAEliminar = null }
            )
        }
    }
}

// ===== FUNCIÓN MEJORADA PARA PROCESAR DOCUMENTOS DE PAGO - INCLUYE ABONOS PARCIALES =====
private fun procesarDocumentoPagoMejorado(
    doc: com.google.firebase.firestore.DocumentSnapshot,
    usuarios: Map<String, String>,
    prestamos: Map<String, Map<String, Any>>,
    rol: String,
    formatter: SimpleDateFormat
): PagoItem? {
    try {
        // Obtener datos básicos con múltiples campos posibles
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

        // ===== NUEVA LÓGICA PARA DETECTAR TIPO DE PAGO =====
        val metodoPago = doc.getString("metodoPago") ?: doc.getString("tipoPago") ?: "Efectivo"
        val esAbonoParcial = doc.getBoolean("esAbonoParcial") ?: false

        // Para abonos parciales, modificar el método de pago para identificarlos
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

        // ===== NUEVA LÓGICA PARA SALDO RESTANTE EN ABONOS PARCIALES =====
        val saldoRestante = if (esAbonoParcial) {
            // Para abonos parciales, obtener info específica
            val montoRestanteAntes = doc.getDouble("montoRestanteAntes") ?: 0.0
            val montoOriginalCuota = doc.getDouble("montoOriginalCuota") ?: 0.0

            // Calcular el nuevo saldo restante después del abono
            (montoRestanteAntes - monto).coerceAtLeast(0.0)
        } else {
            doc.getDouble("saldoRestante") ?: 0.0
        }

        // Manejo de fechas mejorado
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
            Log.w("ProcesarPago", "Error parseando fecha para documento ${doc.id}: ${e.message}")
            formatter.format(Date())
        }

        val nombreCobrador = usuarios[cobradorId] ?: cobradorId

        // Filtrar por rol (mantener lógica existente)
        if (rol != "admin" && cobradorId != rol) return null

        val datosPrestamoInt = prestamos[prestamoId]
        val numeroPrestamo = (datosPrestamoInt?.get("numeroPrestamo") as? Long ?: 0L).toInt()

        // ===== LOG PARA DEBUG DE ABONOS PARCIALES =====
        if (esAbonoParcial) {
            Log.d("HistorialPagos", """
                ABONO PARCIAL DETECTADO:
                - Cliente: $clienteNombre
                - Cuota: $cuota
                - Monto abono: L. ${String.format("%.2f", monto)}
                - Saldo restante cuota: L. ${String.format("%.2f", saldoRestante)}
                - Fecha: $fecha
                - Cobrador: $nombreCobrador
            """.trimIndent())
        }

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
            tipoPago = tipoPagoFinal, // USAR EL TIPO MEJORADO
            saldoRestante = saldoRestante,
            numeroPrestamo = numeroPrestamo
        )

    } catch (e: Exception) {
        Log.e("ProcesarPago", "Error procesando documento ${doc.id}: ${e.message}", e)
        return null
    }
}

// SELECTOR DE COBRADOR
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CobradorDropdown(
    usuarios: List<UsuarioItem>,
    filtroCobradorNombre: String,
    onCobradorChange: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedTextField(
            value = filtroCobradorNombre.ifBlank { "Todos los cobradores" },
            onValueChange = { },
            readOnly = true,
            label = { Text("Cobrador") },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            trailingIcon = {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            shape = RoundedCornerShape(8.dp)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Todos los cobradores") },
                onClick = {
                    onCobradorChange("", "")
                    expanded = false
                }
            )

            usuarios.forEach { usuario ->
                DropdownMenuItem(
                    text = {
                        Text("${if (usuario.rol == "admin") "👑" else "👨‍💼"} ${usuario.nombre}")
                    },
                    onClick = {
                        onCobradorChange(usuario.id, usuario.nombre)
                        expanded = false
                    }
                )
            }
        }
    }
}

// SELECTOR DE FECHAS MEJORADO
@Composable
private fun FechaSelectorMejorado(
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
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        Text(
                            "Desde",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        Text(
                            fechaInicio?.let { formatter.format(it) } ?: "Seleccionar",
                            fontSize = 13.sp,
                            fontWeight = if (fechaInicio != null) FontWeight.Bold else FontWeight.Normal,
                            color = if (fechaInicio != null) Color(0xFF1976D2) else Color.Gray
                        )
                    }
                    if (fechaInicio != null) {
                        IconButton(
                            onClick = { onFechasChange(null, fechaFin) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Limpiar",
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
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
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        Text(
                            "Hasta",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        Text(
                            fechaFin?.let { formatter.format(it) } ?: "Seleccionar",
                            fontSize = 13.sp,
                            fontWeight = if (fechaFin != null) FontWeight.Bold else FontWeight.Normal,
                            color = if (fechaFin != null) Color(0xFF1976D2) else Color.Gray
                        )
                    }
                    if (fechaFin != null) {
                        IconButton(
                            onClick = { onFechasChange(fechaInicio, null) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Limpiar",
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Mostrar rango seleccionado
        if (fechaInicio != null && fechaFin != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "Rango: ${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}",
                        fontSize = 12.sp,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { onFechasChange(null, null) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Limpiar rango",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Botones de acceso rápido
        if (fechaInicio == null && fechaFin == null) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier
                            .clickable {
                                val hoy = Date()
                                onFechasChange(hoy, hoy)
                            }
                            .height(40.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1976D2)),
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
                                Icons.Default.Today,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Hoy",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .clickable {
                                calendar.time = Date()
                                calendar.add(Calendar.DAY_OF_MONTH, -7)
                                val inicioSemana = calendar.time
                                val finSemana = Date()
                                onFechasChange(inicioSemana, finSemana)
                            }
                            .height(40.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32)),
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
                                Icons.Default.CalendarViewWeek,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Última semana",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .clickable {
                                calendar.time = Date()
                                calendar.add(Calendar.DAY_OF_MONTH, -30)
                                val inicioMes = calendar.time
                                val finMes = Date()
                                onFechasChange(inicioMes, finMes)
                            }
                            .height(40.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF6A1B9A)),
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
                                Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Último mes",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

// FUNCIÓN ELIMINAR PAGO CORREGIDA
private suspend fun eliminarPagoCorregido(
    context: Context,
    db: FirebaseFirestore,
    pago: PagoItem,
    onComplete: () -> Unit
) {
    try {
        // Eliminar el pago
        db.collection("pagos").document(pago.docId).delete().await()

        // Recalcular saldo del préstamo
        val pagosRestantes = db.collection("pagos")
            .whereEqualTo("prestamoId", pago.prestamoId)
            .get().await()
            .documents

        val nuevoMontoPagado = pagosRestantes.sumOf { it.getDouble("monto") ?: 0.0 }

        val prestamoRef = db.collection("prestamos").document(pago.prestamoId)
        val prestamoDoc = prestamoRef.get().await()
        val montoPrestado = prestamoDoc.getDouble("monto") ?: 0.0
        val interesTotal = prestamoDoc.getDouble("interesTotal") ?: prestamoDoc.getDouble("interes") ?: 0.0
        val totalPagar = montoPrestado + interesTotal
        val nuevoSaldo = (totalPagar - nuevoMontoPagado).coerceAtLeast(0.0)
        val nuevoEstado = if (nuevoSaldo <= 0.0) "saldado" else "activo"

        // Actualizar préstamo
        prestamoRef.update(
            mapOf(
                "saldo" to nuevoSaldo,
                "estado" to nuevoEstado,
                "pagos" to nuevoMontoPagado,
                "montoPagado" to nuevoMontoPagado
            )
        ).await()

        // Notificar cambios
        PrestamoStateManager.updateSaldo(pago.prestamoId, nuevoSaldo)
        PrestamoStateManager.notifyPrestamoUpdate(pago.prestamoId, db, context)

        Toast.makeText(context, "Pago eliminado correctamente", Toast.LENGTH_SHORT).show()
        onComplete()

    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// Función para reimprimir recibo
private suspend fun reimprimirRecibo(context: Context, pago: PagoItem) {
    try {
        val saldoAnterior = pago.saldoRestante + pago.monto
        val nuevoSaldo = pago.saldoRestante

        // Usar la función correcta de generarReciboAbonoPDF
        val file = ReciboHelper.generarReciboAbonoPDF(
            context = context,
            cliente = pago.cliente,
            prestamoId = if (pago.numeroPrestamo > 0) "Préstamo Nº ${pago.numeroPrestamo}" else pago.prestamoId,
            saldoAnterior = saldoAnterior,
            montoAbonado = pago.monto,
            nuevoSaldo = nuevoSaldo,
            fecha = pago.fecha,
            cuota = pago.cuota, // Ya viene con el formato correcto desde la BD
            cobrador = pago.cobrador
        )

        if (file != null && file.exists()) {
            ReciboHelper.imprimirPDF(context, file)
            Toast.makeText(context, "Recibo reimpreso correctamente", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Error al generar PDF", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Log.e("ReimprimirRecibo", "Error: ${e.message}", e)
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ===== TARJETA DE PAGO MEJORADA PARA MOSTRAR ABONOS PARCIALES =====
@Composable
fun PagoCard(
    pago: PagoItem,
    rol: String,
    onEliminar: () -> Unit,
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
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
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
                    Text(
                        pago.cobrador,
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

            // Botones de acción
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onReimprimir,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reimprimir", fontSize = 12.sp)
                }

                if (rol == "admin") {
                    Button(
                        onClick = onEliminar,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Eliminar", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(pagos: List<PagoItem>) {
    val totalPagos = pagos.size
    val totalMonto = pagos.sumOf { it.monto }
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
            Text(
                "Estadísticas",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    title = "Total pagos",
                    value = totalPagos.toString(),
                    icon = "💰"
                )
                StatItem(
                    title = "Monto total",
                    value = "L. ${String.format("%.2f", totalMonto)}",
                    icon = "💵"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (totalMora > 0) {
                    StatItem(
                        title = "Total mora",
                        value = "L. ${String.format("%.2f", totalMora)}",
                        icon = "🚨"
                    )
                }
                if (abonosParciales > 0) {
                    StatItem(
                        title = "Abonos parciales",
                        value = abonosParciales.toString(),
                        icon = "🧩"
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(title: String, value: String, icon: String) {
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
private fun EmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
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
                "No hay pagos registrados",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color(0xFF424242),
                textAlign = TextAlign.Center
            )
            Text(
                "Los pagos registrados aparecerán aquí",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoadingScreen(padding: PaddingValues) {
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
                "Cargando historial...",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    pago: PagoItem,
    db: FirebaseFirestore,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    context: Context
) {
    val scope = rememberCoroutineScope()
    var nuevoSaldo by remember { mutableStateOf<Double?>(null) }
    var cargando by remember { mutableStateOf(true) }

    LaunchedEffect(pago) {
        scope.launch {
            try {
                val prestamoDoc = db.collection("prestamos").document(pago.prestamoId).get().await()
                val montoPrestado = prestamoDoc.getDouble("monto") ?: 0.0
                val interesTotal = prestamoDoc.getDouble("interesTotal") ?: prestamoDoc.getDouble("interes") ?: 0.0
                val totalPagar = montoPrestado + interesTotal

                val pagosRestantes = db.collection("pagos")
                    .whereEqualTo("prestamoId", pago.prestamoId)
                    .get().await()
                    .documents
                    .filter { it.id != pago.docId }
                    .map { it.getDouble("monto") ?: 0.0 }

                val totalPagadoRestante = pagosRestantes.sum()
                nuevoSaldo = (totalPagar - totalPagadoRestante).coerceAtLeast(0.0)
            } catch (e: Exception) {
                Toast.makeText(context, "Error calculando datos", Toast.LENGTH_SHORT).show()
            } finally {
                cargando = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirmar eliminación", fontWeight = FontWeight.Bold) },
        text = {
            if (cargando) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF0061A7))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Calculando nuevo saldo...", fontSize = 14.sp, color = Color.Gray)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("¿Estás seguro de eliminar este pago?", fontSize = 16.sp)

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Cliente: ${pago.cliente}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Monto: L. ${String.format("%.2f", pago.monto)}", fontSize = 14.sp)
                            Text("Fecha: ${pago.fecha}", fontSize = 14.sp)
                            if (pago.tipoPago == "Abono Parcial") {
                                Text(
                                    "Tipo: Abono Parcial",
                                    fontSize = 14.sp,
                                    color = Color(0xFFFF9800),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    nuevoSaldo?.let { saldo ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Nuevo saldo: L. ${String.format("%.2f", saldo)}",
                                modifier = Modifier.padding(12.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color(0xFF1B5E20)
                            )
                        }
                    }

                    Text(
                        "Esta acción no se puede deshacer",
                        fontSize = 14.sp,
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                enabled = !cargando
            ) {
                Text("Eliminar", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}
// FUNCIÓN AUXILIAR PARA PROCESAR DOCUMENTOS
private fun procesarDocumentoPago(
    doc: com.google.firebase.firestore.DocumentSnapshot,
    usuarios: Map<String, String>,
    prestamos: Map<String, Map<String, Any>>,
    rol: String,
    formatter: SimpleDateFormat
): PagoItem? {
    val clienteNombre = doc.getString("clienteNombre") ?: doc.getString("cliente") ?: return null
    val monto = doc.getDouble("monto") ?: doc.getDouble("montoPagado") ?: return null
    val mora = doc.getDouble("mora") ?: 0.0
    val interesTotal = doc.getDouble("interesTotal") ?: doc.getDouble("interes") ?: 0.0
    val prestamoId = doc.getString("prestamoId") ?: return null
    val cobradorId = doc.getString("registradoPor") ?: doc.getString("cobrador") ?: "Desconocido"

    val cuota = when {
        doc.contains("numeroCuota") -> doc.get("numeroCuota").toString()
        doc.contains("cuota") -> doc.get("cuota").toString()
        doc.contains("cuotaActual") -> doc.get("cuotaActual").toString()
        else -> "1"
    }

    val lugar = doc.getString("lugar") ?: ""
    val firma = doc.getString("firma") ?: ""
    val tipoPago = doc.getString("metodoPago") ?: doc.getString("tipoPago") ?: "Efectivo"
    val saldoRestante = doc.getDouble("saldoRestante") ?: 0.0

    // Manejo de fechas simplificado
    val fecha = try {
        when (val fechaRaw = doc.get("fechaPago") ?: doc.get("fecha")) {
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

    // Filtrar por rol
    if (rol != "admin" && cobradorId != rol) return null

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
        tipoPago = tipoPago,
        saldoRestante = saldoRestante,
        numeroPrestamo = numeroPrestamo
    )
}