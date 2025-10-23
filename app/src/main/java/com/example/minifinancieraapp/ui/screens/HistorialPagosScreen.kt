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
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.example.minifinancieraapp.ui.models.PagoItem
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

// ===== NUEVO: Filtro por estado =====
enum class EstadoFiltro { TODOS, ACTIVOS, SALDADOS }

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

    // ===== NUEVO: estado del filtro Activos/Saldados =====
    var estadoFiltro by remember { mutableStateOf(EstadoFiltro.TODOS) }

    // ===== NUEVO: estado para Vista Previa =====
    var mostrarPreview by remember { mutableStateOf(false) }
    var previewPagos by remember { mutableStateOf<List<PagoItem>>(emptyList()) }
    var previewPeriodo by remember { mutableStateOf("Todos los registros") }
    var previewFechaInicio by remember { mutableStateOf<Date?>(null) }
    var previewFechaFin by remember { mutableStateOf<Date?>(null) }

    val formatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    // FUNCIÓN PARA APLICAR FILTROS
    fun aplicarFiltros() {
        pagosFiltrados = pagos.filter { pago ->
            val cumpleFiltroCliente = filtroCliente.isBlank() ||
                    pago.cliente.contains(filtroCliente, ignoreCase = true)

            val cumpleFiltroCobrador = filtroCobradorId.isBlank() || run {
                val cobradorDelPago = usuarios.find { it.nombre == pago.cobrador }?.id ?: pago.cobrador
                cobradorDelPago == filtroCobradorId
            }

            val cumpleFiltroFecha = if (fechaInicio == null || fechaFin == null) {
                true
            } else {
                try {
                    val fechaStr = pago.fecha.split(" ")[0]
                    val fechaPago = formatter.parse(fechaStr)
                    if (fechaPago != null) {
                        val calPago = Calendar.getInstance().apply {
                            time = fechaPago
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }
                        val calInicio = Calendar.getInstance().apply {
                            time = fechaInicio!!
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }
                        val calFin = Calendar.getInstance().apply {
                            time = fechaFin!!
                            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                        }
                        calPago.timeInMillis >= calInicio.timeInMillis && calPago.timeInMillis <= calFin.timeInMillis
                    } else true
                } catch (e: Exception) {
                    Log.e("FiltroFecha", "Error parseando fecha: ${pago.fecha}", e)
                    true
                }
            }

            // ===== NUEVO: Filtro por estado usando saldoRestante =====
            val cumpleEstado = when (estadoFiltro) {
                EstadoFiltro.TODOS -> true
                EstadoFiltro.ACTIVOS -> (pago.saldoRestante ?: 0.0) > 0.01
                EstadoFiltro.SALDADOS -> (pago.saldoRestante ?: 0.0) <= 0.01
            }

            cumpleFiltroCliente && cumpleFiltroCobrador && cumpleFiltroFecha && cumpleEstado
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
        estadoFiltro = EstadoFiltro.TODOS
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
                            UsuarioItem(id = doc.id, nombre = nombre, rol = rolUsuario)
                        } else null
                    }.sortedBy { it.nombre }
                }
            } catch (e: Exception) {
                Log.e("CargarUsuarios", "Error: ${e.message}")
            }
        }
    }

    // Función para cargar pagos
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

                    Log.d("HistorialPagos", "Documentos encontrados: ${snapshot.documents.size}")

                    pagos = snapshot.documents.mapNotNull { doc ->
                        try {
                            procesarDocumentoPagoMejorado(doc, usuariosMap, prestamos, rol, formatter)
                        } catch (e: Exception) {
                            Log.e("HistorialPagos", "Error procesando ${doc.id}: ${e.message}")
                            null
                        }
                    }.sortedByDescending {
                        try {
                            formatter.parse(it.fecha.split(" ")[0])?.time ?: 0L
                        } catch (_: Exception) { 0L }
                    }

                    Log.d("HistorialPagos", "Total procesados: ${pagos.size}")
                    prefs.edit().putString("historial_pagos", Gson().toJson(pagos)).apply()
                } else {
                    val json = prefs.getString("historial_pagos", "[]") ?: "[]"
                    pagos = try {
                        Gson().fromJson(json, Array<PagoItem>::class.java).toList()
                    } catch (_: Exception) { emptyList() }
                    Toast.makeText(context, "Modo offline", Toast.LENGTH_SHORT).show()
                }

                pagosFiltrados = pagos
            } catch (e: Exception) {
                Log.e("HistorialPagos", "Error: ${e.message}")
                Toast.makeText(context, "Error cargando pagos: ${e.message}", Toast.LENGTH_LONG).show()
            } finally { cargando = false }
        }
    }

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
                        Icon(Icons.Default.FilterList, contentDescription = "Filtros", tint = Color.White)
                    }
                    IconButton(onClick = { cargarPagos() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = Color.White)
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
                if (mostrarFiltros) {
                    item {
                        FiltrosCard(
                            filtroCliente = filtroCliente,
                            onFiltroClienteChange = { filtroCliente = it; aplicarFiltros() },
                            usuarios = usuarios,
                            filtroCobradorNombre = filtroCobradorNombre,
                            onCobradorChange = { id, nombre ->
                                filtroCobradorId = id
                                filtroCobradorNombre = nombre
                                aplicarFiltros()
                            },
                            fechaInicio = fechaInicio,
                            fechaFin = fechaFin,
                            onFechasChange = { inicio, fin ->
                                fechaInicio = inicio
                                fechaFin = fin
                                aplicarFiltros()
                            },
                            estadoFiltro = estadoFiltro,
                            onEstadoChange = {
                                estadoFiltro = it
                                aplicarFiltros()
                            },
                            onLimpiar = { limpiarFiltros() },
                            pagosFiltrados = pagosFiltrados,
                            formatter = formatter,
                            context = context,
                            scope = scope,
                            onPreview = { pagosSel, fIni, fFin, periodo ->
                                previewPagos = pagosSel
                                previewFechaInicio = fIni
                                previewFechaFin = fFin
                                previewPeriodo = periodo
                                mostrarPreview = true
                            }
                        )
                    }
                }

                if (pagosFiltrados.isNotEmpty()) {
                    item { StatsCard(pagosFiltrados) }
                }

                if (pagosFiltrados.isEmpty()) {
                    item { EmptyStateCard() }
                } else {
                    items(items = pagosFiltrados, key = { it.docId }) { pago ->
                        PagoCard(
                            pago = pago,
                            rol = rol,
                            onEliminar = { pagoAEliminar = pago },
                            onReimprimir = {
                                scope.launch { reimprimirRecibo(context, pago) }
                            }
                        )
                    }
                }
            }
        }

        pagoAEliminar?.let { pago ->
            ConfirmDeleteDialog(
                pago = pago,
                db = db,
                context = context,
                onConfirm = {
                    scope.launch {
                        eliminarPagoCorregido(context, db, pago) {
                            cargarPagos()
                            pagoAEliminar = null
                        }
                    }
                },
                onDismiss = { pagoAEliminar = null }
            )
        }

        // ===== NUEVO: VISTA PREVIA (no crea pantalla nueva; bottom sheet con scroll)
        if (mostrarPreview) {
            ReportePagosPreview(
                pagos = previewPagos,
                periodo = previewPeriodo,
                fechaInicio = previewFechaInicio,
                fechaFin = previewFechaFin,
                onCerrar = { mostrarPreview = false },
                onExportar = { pagosAExportar, fi, ff, periodoStr ->
                    scope.launch {
                        try {
                            val archivo = ReciboHelper.generarResumenPagosPDF(
                                context = context,
                                pagos = pagosAExportar,
                                fechaInicio = fi ?: Date(0),
                                fechaFin = ff ?: Date(),
                                periodo = periodoStr
                            )
                            if (archivo != null && archivo.exists()) {
                                val printed = ReciboHelper.imprimirPDF(context, archivo)
                                if (!printed) ReciboHelper.compartirReciboPDF(context, archivo)
                                Toast.makeText(context, "PDF generado", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No se pudo generar el PDF", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Log.e("PreviewExport", "Error: ${e.message}", e)
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }
}

// ===== FUNCIÓN FORMATEAR LEMPIRAS =====
fun formatearLempiras(valor: Double): String {
    return "L. %, .2f".format(Locale("es", "HN"), valor).replace(", .", ",")
}

// ===== FUNCIÓN MEJORADA PARA PROCESAR PAGOS CON CUOTAS MÚLTIPLES =====
private fun procesarDocumentoPagoMejorado(
    doc: com.google.firebase.firestore.DocumentSnapshot,
    usuarios: Map<String, String>,
    prestamos: Map<String, Map<String, Any>>,
    rol: String,
    formatter: SimpleDateFormat
): PagoItem? {
    try {
        val clienteNombre = doc.getString("clienteNombre") ?: doc.getString("cliente") ?: return null
        val monto = doc.getDouble("monto") ?: doc.getDouble("montoPagado") ?: return null
        val mora = doc.getDouble("mora") ?: 0.0
        val interesTotal = doc.getDouble("interesTotal") ?: doc.getDouble("interes") ?: 0.0
        val prestamoId = doc.getString("prestamoId") ?: return null
        val cobradorId = doc.getString("registradoPor") ?: doc.getString("cobrador") ?: "Desconocido"

        val metodoPago = doc.getString("metodoPago") ?: doc.getString("tipoPago") ?: "Efectivo"
        val esAbonoParcial = doc.getBoolean("esAbonoParcial") ?: false

        val tipoPagoFinal = when {
            esAbonoParcial -> "Abono Parcial"
            metodoPago == "Manual (Admin)" -> "Manual (Admin)"
            else -> metodoPago
        }

        // ===== PROCESAMIENTO DE CUOTAS MEJORADO =====
        val cuotasCubiertas = doc.get("cuotasCubiertas") as? List<*>
        val cuotaDescripcion = if (cuotasCubiertas != null && cuotasCubiertas.isNotEmpty()) {
            try {
                data class CuotaDetalle(val numero: Int, val completada: Boolean)
                val cuotasDetalle = mutableListOf<CuotaDetalle>()
                cuotasCubiertas.forEach { cuotaData ->
                    if (cuotaData is Map<*, *>) {
                        val numeroCuota = (cuotaData["numeroCuota"] as? Number)?.toInt() ?: 0
                        val completada = cuotaData["completada"] as? Boolean ?: false
                        if (numeroCuota > 0) cuotasDetalle.add(CuotaDetalle(numeroCuota, completada))
                    }
                }
                cuotasDetalle.sortBy { it.numero }
                when {
                    cuotasDetalle.isEmpty() -> "Sin cuotas"
                    cuotasDetalle.size == 1 -> {
                        val c = cuotasDetalle.first()
                        if (c.completada) "#${c.numero}" else "#${c.numero} parcial"
                    }
                    cuotasDetalle.size == 2 -> {
                        val c1 = cuotasDetalle[0]; val c2 = cuotasDetalle[1]
                        buildString {
                            append("#${c1.numero}"); if (!c1.completada) append(" parcial")
                            append(", #${c2.numero}"); if (!c2.completada) append(" parcial")
                        }
                    }
                    cuotasDetalle.size == 3 -> {
                        buildString {
                            cuotasDetalle.forEachIndexed { i, c ->
                                if (i > 0) append(", ")
                                append("#${c.numero}"); if (!c.completada) append(" parcial")
                            }
                        }
                    }
                    else -> {
                        val nums = cuotasDetalle.map { it.numero }
                        val todas = cuotasDetalle.all { it.completada }
                        if (sonConsecutivas(nums) && todas) {
                            "#${nums.first()} a #${nums.last()}"
                        } else {
                            val c1 = cuotasDetalle[0]; val c2 = cuotasDetalle[1]; val u = cuotasDetalle.last()
                            buildString {
                                append("#${c1.numero}"); if (!c1.completada) append(" parcial")
                                append(", #${c2.numero}"); if (!c2.completada) append(" parcial")
                                append("...#${u.numero}"); if (!u.completada) append(" parcial")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ProcesarPago", "Error cuotasCubiertas: ${e.message}", e)
                "Múltiples cuotas"
            }
        } else {
            doc.getString("descripcionCuotas")
                ?: doc.get("numeroCuota")?.toString()
                ?: doc.get("cuota")?.toString()
                ?: "1"
        }

        val lugar = doc.getString("lugar") ?: ""
        val firma = doc.getString("firma") ?: ""

        val saldoRestante = if (esAbonoParcial) {
            val montoRestanteAntes = doc.getDouble("montoRestanteAntes") ?: 0.0
            (montoRestanteAntes - monto).coerceAtLeast(0.0)
        } else {
            doc.getDouble("saldoRestante") ?: 0.0
        }

        val fecha = try {
            when (val f = doc.get("fechaPago") ?: doc.get("fecha") ?: doc.get("fechaCreacion")) {
                is Timestamp -> formatter.format(f.toDate())
                is String -> {
                    if (f.matches(Regex("\\d{2}/\\d{2}/\\d{4}"))) f
                    else {
                        try {
                            val input = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            input.parse(f)?.let { formatter.format(it) } ?: f
                        } catch (_: Exception) { f }
                    }
                }
                else -> formatter.format(Date())
            }
        } catch (_: Exception) { formatter.format(Date()) }

        val nombreCobrador = usuarios[cobradorId] ?: cobradorId
        if (rol != "admin" && cobradorId != rol) return null

        val datosPrestamoInt = prestamos[prestamoId]
        val numeroPrestamo = (datosPrestamoInt?.get("numeroPrestamo") as? Long ?: 0L).toInt()

        val cuotasCubiertasSize = doc.get("cuotasCubiertas") as? List<*>
        if (cuotasCubiertasSize != null && cuotasCubiertasSize.size > 1) {
            Log.d("HistorialPagos", "MÚLTIPLES CUOTAS: $clienteNombre - $cuotaDescripcion - L.${String.format("%.2f", monto)}")
        }

        return PagoItem(
            docId = doc.id,
            cliente = clienteNombre,
            prestamoId = prestamoId,
            fecha = fecha,
            monto = monto,
            mora = mora,
            interesTotal = interesTotal,
            cuota = cuotaDescripcion,
            cobrador = nombreCobrador,
            lugar = lugar,
            firma = firma,
            tipoPago = tipoPagoFinal,
            saldoRestante = saldoRestante,
            numeroPrestamo = numeroPrestamo
        )
    } catch (e: Exception) {
        Log.e("ProcesarPago", "Error: ${e.message}", e)
        return null
    }
}

// ===== FUNCIÓN AUXILIAR =====
private fun sonConsecutivas(numeros: List<Int>): Boolean {
    if (numeros.size <= 1) return true
    val ord = numeros.sorted()
    for (i in 0 until ord.size - 1) if (ord[i + 1] - ord[i] != 1) return false
    return true
}

// ===== FUNCIÓN DE REIMPRESIÓN =====
private suspend fun reimprimirRecibo(context: Context, pago: PagoItem) {
    try {
        Toast.makeText(context, "Generando recibo...", Toast.LENGTH_SHORT).show()
        val db = FirebaseFirestore.getInstance()

        // Traer datos del préstamo para conocer proximoPago y saldo (estado actual)
        val prestamoDoc = db.collection("prestamos")
            .document(pago.prestamoId)
            .get()
            .await()

        // Parse de fecha flexible
        fun parseFecha(any: Any?): String? {
            val out = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            return when (any) {
                is com.google.firebase.Timestamp -> out.format(any.toDate())
                is Date -> out.format(any)
                is String -> {
                    runCatching { out.format(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(any)!!) }
                        .getOrElse {
                            runCatching { out.format(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(any)!!) }
                                .getOrNull()
                        }
                }
                else -> null
            }
        }

        // Acepta varios nombres de campo por si varía en Firestore
        val proxRaw =
            prestamoDoc.get("proximoPago")
                ?: prestamoDoc.get("proxPago")
                ?: prestamoDoc.get("proximo_pago")
                ?: prestamoDoc.get("fechaProximoPago")

        val saldoPrestamoActual = prestamoDoc.getDouble("saldo") ?: (pago.saldoRestante ?: 0.0)

        // Qué imprimir en el campo "Próximo pago"
        val proximoPagoStr = if (saldoPrestamoActual <= 0.01) {
            "SALDADO"
        } else {
            parseFecha(proxRaw) ?: "—"
        }

        // Saldos para el PDF (mostramos el antes y el nuevo saldo según el pago reimpreso)
        val saldoAnterior = (pago.saldoRestante ?: 0.0) + pago.monto
        val nuevoSaldo = pago.saldoRestante ?: saldoPrestamoActual

        // Texto de cuota para el recibo
        val cuotaParaRecibo = when {
            pago.cuota.contains(" a ") -> "Cuotas ${pago.cuota}"
            pago.cuota.contains(",") -> "Cuotas ${pago.cuota}"
            pago.cuota.contains("parcial", ignoreCase = true) -> "Cuota ${pago.cuota}"
            pago.cuota.startsWith("#") -> "Cuota ${pago.cuota}"
            else -> "Cuota #${pago.cuota}"
        }

        val file = ReciboHelper.generarReciboPDF(
            context = context,
            cliente = pago.cliente,
            prestamoId = if (pago.numeroPrestamo > 0) "Préstamo Nº ${pago.numeroPrestamo}" else pago.prestamoId,
            fecha = pago.fecha,
            montoPagado = pago.monto.toString(),
            saldoAnterior = saldoAnterior,
            proximoPago = proximoPagoStr,   // ← fecha o “SALDADO”/“—”
            cuota = cuotaParaRecibo,
            cobrador = pago.cobrador,
            lugar = pago.lugar,
            firma = pago.firma,
            tipoPago = pago.tipoPago,
            mora = pago.mora,
            saldoNuevoFijo = nuevoSaldo
        )

        if (file != null && file.exists()) {
            val printed = ReciboHelper.imprimirPDF(context, file)
            if (!printed) ReciboHelper.compartirReciboPDF(context, file)
            Toast.makeText(context, "Recibo generado", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Error al generar PDF", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Log.e("REIMPRIMIR", "Error: ${e.message}", e)
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ===== ELIMINAR PAGO =====
private suspend fun eliminarPagoCorregido(
    context: Context,
    db: FirebaseFirestore,
    pago: PagoItem,
    onComplete: () -> Unit
) {
    try {
        db.collection("pagos").document(pago.docId).delete().await()

        val pagosRestantes = db.collection("pagos")
            .whereEqualTo("prestamoId", pago.prestamoId)
            .get().await().documents

        val nuevoMontoPagado = pagosRestantes.sumOf { it.getDouble("monto") ?: 0.0 }

        val prestamoRef = db.collection("prestamos").document(pago.prestamoId)
        val prestamoDoc = prestamoRef.get().await()
        val montoPrestado = prestamoDoc.getDouble("monto") ?: 0.0
        val interesTotal = prestamoDoc.getDouble("interesTotal") ?: prestamoDoc.getDouble("interes") ?: 0.0
        val totalPagar = montoPrestado + interesTotal
        val nuevoSaldo = (totalPagar - nuevoMontoPagado).coerceAtLeast(0.0)
        val nuevoEstado = if (nuevoSaldo <= 0.01) "saldado" else "activo"

        prestamoRef.update(
            mapOf(
                "saldo" to nuevoSaldo,
                "estado" to nuevoEstado,
                "pagos" to nuevoMontoPagado,
                "montoPagado" to nuevoMontoPagado
            )
        ).await()

        PrestamoStateManager.updateSaldo(pago.prestamoId, nuevoSaldo)
        PrestamoStateManager.notifyPrestamoUpdate(pago.prestamoId, db, context)

        Toast.makeText(context, "Pago eliminado", Toast.LENGTH_SHORT).show()
        onComplete()
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ===== COMPONENTES UI =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltrosCard(
    filtroCliente: String,
    onFiltroClienteChange: (String) -> Unit,
    usuarios: List<UsuarioItem>,
    filtroCobradorNombre: String,
    onCobradorChange: (String, String) -> Unit,
    fechaInicio: Date?,
    fechaFin: Date?,
    onFechasChange: (Date?, Date?) -> Unit,
    // ===== NUEVO: props estado =====
    estadoFiltro: EstadoFiltro,
    onEstadoChange: (EstadoFiltro) -> Unit,
    onLimpiar: () -> Unit,
    pagosFiltrados: List<PagoItem>,
    formatter: SimpleDateFormat,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    // ===== NUEVO: callback vista previa =====
    onPreview: (List<PagoItem>, Date?, Date?, String) -> Unit
) {
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

            OutlinedTextField(
                value = filtroCliente,
                onValueChange = onFiltroClienteChange,
                label = { Text("Buscar cliente") },
                placeholder = { Text("Nombre del cliente...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )

            // ===== NUEVO: Botones de estado (Todos / Activos / Saldados)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EstadoChip(
                    text = "Todos",
                    selected = estadoFiltro == EstadoFiltro.TODOS,
                    onClick = { onEstadoChange(EstadoFiltro.TODOS) },
                    color = Color(0xFF607D8B),
                    icon = Icons.Default.ViewList
                )
                EstadoChip(
                    text = "Activos",
                    selected = estadoFiltro == EstadoFiltro.ACTIVOS,
                    onClick = { onEstadoChange(EstadoFiltro.ACTIVOS) },
                    color = Color(0xFF2E7D32),
                    icon = Icons.Default.Task
                )
                EstadoChip(
                    text = "Saldados",
                    selected = estadoFiltro == EstadoFiltro.SALDADOS,
                    onClick = { onEstadoChange(EstadoFiltro.SALDADOS) },
                    color = Color(0xFF1565C0),
                    icon = Icons.Default.CheckCircle
                )
            }

            CobradorDropdown(
                usuarios = usuarios,
                filtroCobradorNombre = filtroCobradorNombre,
                onCobradorChange = onCobradorChange
            )

            FechaSelectorMejorado(
                fechaInicio = fechaInicio,
                fechaFin = fechaFin,
                onFechasChange = onFechasChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onLimpiar,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C757D)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Limpiar", fontSize = 13.sp)
                }

                // ===== NUEVO: VISTA PREVIA (no exporta aún, solo abre la vista con scroll)
                Button(
                    onClick = {
                        if (pagosFiltrados.isEmpty()) {
                            Toast.makeText(context, "No hay pagos para previsualizar", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val fechaInicioReporte = fechaInicio ?: run {
                            pagosFiltrados.minByOrNull { pago ->
                                try { formatter.parse(pago.fecha.split(" ")[0])?.time ?: Long.MAX_VALUE }
                                catch (_: Exception) { Long.MAX_VALUE }
                            }?.let { pago -> formatter.parse(pago.fecha.split(" ")[0]) } ?: Date(0)
                        }

                        val fechaFinReporte = fechaFin ?: run {
                            pagosFiltrados.maxByOrNull { pago ->
                                try { formatter.parse(pago.fecha.split(" ")[0])?.time ?: 0L }
                                catch (_: Exception) { 0L }
                            }?.let { pago -> formatter.parse(pago.fecha.split(" ")[0]) } ?: Date()
                        }

                        val periodo = buildString {
                            val filtrosAplicados = mutableListOf<String>()
                            if (estadoFiltro != EstadoFiltro.TODOS) {
                                filtrosAplicados.add("Estado: ${when (estadoFiltro) {
                                    EstadoFiltro.ACTIVOS -> "Activos"
                                    EstadoFiltro.SALDADOS -> "Saldados"
                                    else -> "Todos"
                                }}")
                            }
                            if (filtroCliente.isNotBlank()) filtrosAplicados.add("Cliente: $filtroCliente")
                            if (filtroCobradorNombre.isNotBlank()) filtrosAplicados.add("Cobrador: $filtroCobradorNombre")
                            if (fechaInicio != null && fechaFin != null) {
                                filtrosAplicados.add("Fechas: ${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}")
                            }
                            if (filtrosAplicados.isNotEmpty()) append(filtrosAplicados.joinToString(" | "))
                            else append("Todos los registros")
                        }

                        onPreview(pagosFiltrados, fechaInicioReporte, fechaFinReporte, periodo)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Vista previa (${pagosFiltrados.size})", fontSize = 12.sp)
                }

                // ===== Botón PDF directo (opcional, lo dejamos)
                Button(
                    onClick = {
                        if (pagosFiltrados.isEmpty()) {
                            Toast.makeText(context, "No hay pagos para exportar", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        scope.launch {
                            try {
                                val fechaInicioReporte = fechaInicio ?: run {
                                    pagosFiltrados.minByOrNull { pago ->
                                        try { formatter.parse(pago.fecha.split(" ")[0])?.time ?: Long.MAX_VALUE }
                                        catch (_: Exception) { Long.MAX_VALUE }
                                    }?.let { pago -> formatter.parse(pago.fecha.split(" ")[0]) } ?: Date(0)
                                }

                                val fechaFinReporte = fechaFin ?: run {
                                    pagosFiltrados.maxByOrNull { pago ->
                                        try { formatter.parse(pago.fecha.split(" ")[0])?.time ?: 0L }
                                        catch (_: Exception) { 0L }
                                    }?.let { pago -> formatter.parse(pago.fecha.split(" ")[0]) } ?: Date()
                                }

                                val periodo = buildString {
                                    val filtrosAplicados = mutableListOf<String>()
                                    if (estadoFiltro != EstadoFiltro.TODOS) {
                                        filtrosAplicados.add("Estado: ${when (estadoFiltro) {
                                            EstadoFiltro.ACTIVOS -> "Activos"
                                            EstadoFiltro.SALDADOS -> "Saldados"
                                            else -> "Todos"
                                        }}")
                                    }
                                    if (filtroCliente.isNotBlank()) filtrosAplicados.add("Cliente: $filtroCliente")
                                    if (filtroCobradorNombre.isNotBlank()) filtrosAplicados.add("Cobrador: $filtroCobradorNombre")
                                    if (fechaInicio != null && fechaFin != null) {
                                        filtrosAplicados.add("Fechas: ${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}")
                                    }
                                    if (filtrosAplicados.isNotEmpty()) append(filtrosAplicados.joinToString(" | "))
                                    else append("Todos los registros")
                                }

                                val archivoPDF = ReciboHelper.generarResumenPagosPDF(
                                    context = context,
                                    pagos = pagosFiltrados,
                                    fechaInicio = fechaInicioReporte,
                                    fechaFin = fechaFinReporte,
                                    periodo = periodo
                                )

                                if (archivoPDF != null && archivoPDF.exists()) {
                                    val printed = ReciboHelper.imprimirPDF(context, archivoPDF)
                                    if (!printed) ReciboHelper.compartirReciboPDF(context, archivoPDF)
                                    Toast.makeText(
                                        context,
                                        "PDF generado: ${pagosFiltrados.size} registros",
                                        Toast.LENGTH_LONG
                                    ).show()
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

            if (filtroCliente.isNotBlank() || filtroCobradorNombre.isNotBlank() || fechaInicio != null || estadoFiltro != EstadoFiltro.TODOS) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Filtros aplicados:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                        Text("• Estado: ${when (estadoFiltro) {
                            EstadoFiltro.TODOS -> "Todos"
                            EstadoFiltro.ACTIVOS -> "Activos"
                            EstadoFiltro.SALDADOS -> "Saldados"
                        }}", fontSize = 11.sp, color = Color(0xFF1976D2))
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

@Composable
private fun EstadoChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.18f),
            selectedLabelColor = color,
            selectedLeadingIconColor = color
        )
    )
}

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
                    text = { Text("${if (usuario.rol == "admin") "👑" else "👨‍💼"} ${usuario.nombre}") },
                    onClick = {
                        onCobradorChange(usuario.id, usuario.nombre)
                        expanded = false
                    }
                )
            }
        }
    }
}

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
        Text("Filtrar por fechas", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1976D2))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Color(0xFF1976D2), modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Desde", fontSize = 11.sp, color = Color.Gray)
                        Text(
                            fechaInicio?.let { formatter.format(it) } ?: "Seleccionar",
                            fontSize = 13.sp,
                            fontWeight = if (fechaInicio != null) FontWeight.Bold else FontWeight.Normal,
                            color = if (fechaInicio != null) Color(0xFF1976D2) else Color.Gray
                        )
                    }
                    if (fechaInicio != null) {
                        IconButton(onClick = { onFechasChange(null, fechaFin) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

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
                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = Color(0xFF1976D2), modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hasta", fontSize = 11.sp, color = Color.Gray)
                        Text(
                            fechaFin?.let { formatter.format(it) } ?: "Seleccionar",
                            fontSize = 13.sp,
                            fontWeight = if (fechaFin != null) FontWeight.Bold else FontWeight.Normal,
                            color = if (fechaFin != null) Color(0xFF1976D2) else Color.Gray
                        )
                    }
                    if (fechaFin != null) {
                        IconButton(onClick = { onFechasChange(fechaInicio, null) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

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
                    Icon(Icons.Default.DateRange, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                    Text(
                        "Rango: ${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}",
                        fontSize = 12.sp,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { onFechasChange(null, null) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Limpiar rango", tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (fechaInicio == null && fechaFin == null) {
            val calendarQuick = Calendar.getInstance()
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
                            Icon(Icons.Default.Today, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text("Hoy", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .clickable {
                                calendarQuick.time = Date()
                                calendarQuick.add(Calendar.DAY_OF_MONTH, -7)
                                val inicioSemana = calendarQuick.time
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
                            Icon(Icons.Default.CalendarViewWeek, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text("Última semana", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .clickable {
                                calendarQuick.time = Date()
                                calendarQuick.add(Calendar.DAY_OF_MONTH, -30)
                                val inicioMes = calendarQuick.time
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
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text("Último mes", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

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
                esAbonoParcial -> Color(0xFFFFF3E0)
                esManual -> Color(0xFFE8EAF6)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pago.cliente, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1A1A1A))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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

                if (pago.numeroPrestamo > 0) {
                    Surface(color = Color(0xFF0061A7), shape = RoundedCornerShape(8.dp)) {
                        Text("#${pago.numeroPrestamo}", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }

            HorizontalDivider(color = Color(0xFFE0E0E0))

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
                        Text("Base: L. ${String.format("%.2f", pago.monto - pago.mora)}", fontSize = 12.sp, color = Color.Gray)
                    }

                    val activo = (pago.saldoRestante ?: 0.0) > 0.01
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = if (activo) Color(0xFF2E7D32) else Color(0xFF1565C0),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (activo) "Activo" else "Saldado",
                            fontSize = 10.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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

            if (pago.mora > 0.0 || pago.interesTotal > 0.0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pago.fecha, fontSize = 13.sp, color = Color(0xFF424242))
                    Text(pago.cobrador, fontSize = 13.sp, color = Color(0xFF424242))
                    if (pago.lugar.isNotBlank()) {
                        Text(pago.lugar, fontSize = 13.sp, color = Color(0xFF424242))
                    }
                }
            }

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

    val activos = pagos.count { (it.saldoRestante ?: 0.0) > 0.01 }
    val saldados = pagos.count { (it.saldoRestante ?: 0.0) <= 0.01 }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0061A7)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Estadísticas", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(title = "Total pagos", value = totalPagos.toString(), icon = "💰")
                StatItem(title = "Monto total", value = "L. ${String.format("%.2f", totalMonto)}", icon = "💵")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (totalMora > 0) {
                    StatItem(title = "Total mora", value = "L. ${String.format("%.2f", totalMora)}", icon = "🚨")
                }
                if (abonosParciales > 0) {
                    StatItem(title = "Abonos parciales", value = abonosParciales.toString(), icon = "🧩")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(title = "Activos", value = activos.toString(), icon = "📌")
                StatItem(title = "Saldados", value = saldados.toString(), icon = "✅")
            }
        }
    }
}

@Composable
private fun StatItem(title: String, value: String, icon: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 24.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White, textAlign = TextAlign.Center)
        Text(title, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
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
            Text("No hay pagos registrados", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF424242), textAlign = TextAlign.Center)
            Text("Los pagos registrados aparecerán aquí", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
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
            CircularProgressIndicator(color = Color(0xFF0061A7), strokeWidth = 3.dp)
            Text("Cargando historial...", color = Color.Gray, fontSize = 14.sp)
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
            } catch (_: Exception) {
                Toast.makeText(context, "Error calculando datos", Toast.LENGTH_SHORT).show()
            } finally { cargando = false }
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
                            Text("Cuotas: ${pago.cuota}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Monto: L. ${String.format("%.2f", pago.monto)}", fontSize = 14.sp)
                            Text("Fecha: ${pago.fecha}", fontSize = 14.sp)
                            if (pago.tipoPago == "Abono Parcial") {
                                Text("Tipo: Abono Parcial", fontSize = 14.sp, color = Color(0xFFFF9800), fontWeight = FontWeight.Bold)
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

                    Text("Esta acción no se puede deshacer", fontSize = 14.sp, color = Color(0xFFD32F2F), fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                enabled = !cargando
            ) { Text("Eliminar", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

/* =======================
   NUEVO: VISTA PREVIA
   ======================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportePagosPreview(
    pagos: List<PagoItem>,
    periodo: String,
    fechaInicio: Date?,
    fechaFin: Date?,
    onCerrar: () -> Unit,
    onExportar: (List<PagoItem>, Date?, Date?, String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    val totalMonto = remember(pagos) { pagos.sumOf { it.monto } }
    val totalMora = remember(pagos) { pagos.sumOf { it.mora } }
    val abonosParciales = remember(pagos) { pagos.count { it.tipoPago == "Abono Parcial" } }
    val activos = remember(pagos) { pagos.count { (it.saldoRestante ?: 0.0) > 0.01 } }
    val saldados = remember(pagos) { pagos.count { (it.saldoRestante ?: 0.0) <= 0.01 } }

    ModalBottomSheet(
        onDismissRequest = onCerrar,
        sheetState = sheetState,
        containerColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Vista previa del reporte", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        if (fechaInicio != null && fechaFin != null)
                            "Período: ${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}"
                        else
                            "Período: (automático según registros)",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    if (periodo.isNotBlank()) {
                        Text(periodo, fontSize = 12.sp, color = Color(0xFF1565C0))
                    }
                }
                IconButton(onClick = onCerrar) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar")
                }
            }

            // Acciones
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onExportar(pagos, fechaInicio, fechaFin, periodo) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Exportar / Imprimir")
                }
                OutlinedButton(
                    onClick = { onExportar(pagos, fechaInicio, fechaFin, periodo) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Compartir PDF")
                }
            }

            // Resumen
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PreviewStat("Registros", pagos.size.toString(), "📋")
                        PreviewStat("Total monto", formatearLempiras(totalMonto), "💵")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        if (totalMora > 0) PreviewStat("Total mora", formatearLempiras(totalMora), "🚨")
                        if (abonosParciales > 0) PreviewStat("Abonos parciales", abonosParciales.toString(), "🧩")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PreviewStat("Activos", activos.toString(), "📌")
                        PreviewStat("Saldados", saldados.toString(), "✅")
                    }
                }
            }

            // Encabezados tabla
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE3F2FD), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                PreviewHeader("Cliente", 1.4f)
                PreviewHeader("Fecha", 1.0f)
                PreviewHeader("Cuota", 1.0f)
                PreviewHeader("Tipo", 1.0f)
                PreviewHeader("Mora", 0.8f, right = true)
                PreviewHeader("Monto", 1.0f, right = true)
                PreviewHeader("Saldo", 1.0f, right = true)
            }

            // Filas
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(pagos.size, key = { pagos[it].docId }) { idx ->
                    val p = pagos[idx]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFDFDFD), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        PreviewCell(p.cliente, 1.4f)
                        PreviewCell(p.fecha, 1.0f)
                        PreviewCell(p.cuota, 1.0f)
                        PreviewBadgeTipo(p.tipoPago, 1.0f)
                        PreviewCell(formatearLempiras(p.mora), 0.8f, right = true)
                        PreviewCell(formatearLempiras(p.monto), 1.0f, right = true)
                        PreviewEstadoSaldo(p.saldoRestante ?: 0.0, 1.0f)
                    }
                }
            }

            // Totales al pie
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total registros: ${pagos.size}", fontWeight = FontWeight.Medium, color = Color(0xFF2E7D32))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (totalMora > 0)
                            Text("Mora: ${formatearLempiras(totalMora)}", fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
                        Text("Monto: ${formatearLempiras(totalMonto)}", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PreviewStat(title: String, value: String, icon: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 22.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(title, fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun RowScope.PreviewHeader(text: String, weight: Float, right: Boolean = false) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF0D47A1),
        textAlign = if (right) TextAlign.End else TextAlign.Start
    )
}

@Composable
private fun RowScope.PreviewCell(text: String, weight: Float, right: Boolean = false) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        fontSize = 12.sp,
        color = Color(0xFF444444),
        textAlign = if (right) TextAlign.End else TextAlign.Start,
        maxLines = 2
    )
}

@Composable
private fun RowScope.PreviewBadgeTipo(tipo: String, weight: Float) {
    val color = when (tipo) {
        "Efectivo" -> Color(0xFF4CAF50)
        "Abono Parcial" -> Color(0xFFFF9800)
        "Manual (Admin)" -> Color(0xFF673AB7)
        else -> Color(0xFF2196F3)
    }
    Box(
        modifier = Modifier
            .weight(weight)
            .padding(end = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(color = color, shape = RoundedCornerShape(12.dp)) {
            Text(
                tipo,
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun RowScope.PreviewEstadoSaldo(saldoRestante: Double, weight: Float) {
    val activo = saldoRestante > 0.01
    Row(
        modifier = Modifier.weight(weight),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = if (activo) Color(0xFF2E7D32) else Color(0xFF1565C0),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                if (activo) formatearLempiras(saldoRestante) else "SALDADO",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}
