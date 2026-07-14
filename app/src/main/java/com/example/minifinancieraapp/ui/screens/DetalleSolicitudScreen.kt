package com.example.minifinancieraapp.ui.screens

import com.example.minifinancieraapp.ui.models.SolicitudModel
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.capitalexpressapp.util.ReciboHelper
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke

// ─────────────────────────────────────────────
//  MAPPER DESDE FIRESTORE
// ─────────────────────────────────────────────
fun Map<String, Any>.toSolicitudModel(): SolicitudModel {
    return SolicitudModel(
        id = this["id"] as? String ?: "",
        cliente = this["cliente"] as? String ?: "",
        clienteId = this["clienteId"] as? String ?: "",
        monto = (this["monto"] as? Number)?.toDouble() ?: 0.0,
        interes = (this["interes"] as? Number)?.toDouble() ?: 0.0,
        interesMensual = (this["interesMensual"] as? Number)?.toDouble() ?: 0.0,
        interesTotal = (this["interesTotal"] as? Number)?.toDouble() ?: 0.0,
        totalPagar = (this["totalPagar"] as? Number)?.toDouble() ?: 0.0,
        cuota = (this["cuota"] as? Number)?.toDouble() ?: 0.0,
        cuotas = (this["cuotas"] as? Number)?.toInt() ?: 0,
        plazo = this["plazo"] as? String ?: "",
        fecha = this["fecha"] as? String ?: "",
        lugar = this["lugar"] as? String ?: "",
        firma = this["firma"] as? String,
        estado = this["estado"] as? String ?: "",
        observaciones = this["observaciones"] as? String ?: "",
        fotos = (this["fotos"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
        mora = (this["mora"] as? Number)?.toDouble() ?: 0.0,
        garantia = this["garantia"] as? String ?: "",
        cobrador = this["cobrador"] as? String ?: "",
        cobradorUid = this["cobradorUid"] as? String,
        cobradorSolicitante = this["cobradorSolicitante"] as? String ?: "",
        diasEfectivos = (this["diasEfectivos"] as? Number)?.toInt() ?: 0,
        interesManual = (this["interesManual"] as? Number)?.toDouble() ?: 0.0
    )
}

// ─────────────────────────────────────────────
//  FUNCIONES DE CÁLCULO (renombradas con sufijo "Detalle"
//  para evitar colisión de nombres con otros archivos del
//  mismo package, como EditarPrestamoScreen.kt)
// ─────────────────────────────────────────────
fun calcularDiasEfectivosDetalle(plazo: String, cuotas: Int, fechaInicio: Calendar): Int {
    return when (plazo) {
        "Diario"         -> cuotas
        "Lunes a Sábado" -> contarDiasSinDomingosDetalle(cuotas, fechaInicio)
        "Semanal"        -> contarDiasSinDomingosDetalle(cuotas * 6, fechaInicio)
        "Quincenal"      -> cuotas * 15
        "Mensual"        -> cuotas * 30
        "Bimestral"      -> cuotas * 60
        else             -> cuotas * 30
    }
}

private fun contarDiasSinDomingosDetalle(diasNecesarios: Int, fechaInicio: Calendar): Int {
    var diasEfectivos = 0
    var diasTotales = 0
    val fecha = fechaInicio.clone() as Calendar
    while (diasEfectivos < diasNecesarios) {
        if (fecha.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) diasEfectivos++
        diasTotales++
        fecha.add(Calendar.DAY_OF_YEAR, 1)
    }
    return diasTotales
}

fun calcularProximoPagoDesdeHoyDetalle(plazo: String): Timestamp {
    val proximaFecha = Calendar.getInstance()
    when (plazo) {
        "Diario" -> proximaFecha.add(Calendar.DAY_OF_MONTH, 1)
        "Lunes a Sábado" -> {
            proximaFecha.add(Calendar.DAY_OF_MONTH, 1)
            if (proximaFecha.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                proximaFecha.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        "Semanal" -> proximaFecha.add(Calendar.WEEK_OF_YEAR, 1)
        "Quincenal" -> proximaFecha.add(Calendar.DAY_OF_MONTH, 15)
        "Mensual" -> proximaFecha.add(Calendar.MONTH, 1)
        "Bimestral" -> proximaFecha.add(Calendar.MONTH, 2)
        else -> proximaFecha.add(Calendar.MONTH, 1)
    }
    return Timestamp(proximaFecha.time)
}

fun calcularInteresTotalDetalle(
    monto: Double,
    interesMensual: Double,
    plazo: String,
    cuotas: Int,
    fechaInicio: Calendar
): Double {
    val diasEfectivos = calcularDiasEfectivosDetalle(plazo, cuotas, fechaInicio)
    val interesMensualMonto = monto * (interesMensual / 100)
    return when (plazo) {
        "Diario" -> {
            val interesDiario = interesMensualMonto / 30
            interesDiario * diasEfectivos
        }
        "Lunes a Sábado" -> {
            val interesPorDiaLaborable = interesMensualMonto / 26
            interesPorDiaLaborable * cuotas
        }
        "Semanal" -> {
            val interesSemanal = interesMensualMonto / 4
            interesSemanal * cuotas
        }
        "Quincenal" -> {
            val interesQuincenal = interesMensualMonto / 2
            interesQuincenal * cuotas
        }
        "Mensual" -> {
            interesMensualMonto * cuotas
        }
        "Bimestral" -> {
            val interesBimestral = interesMensualMonto * 2
            interesBimestral * cuotas
        }
        else -> {
            val mesesReales = diasEfectivos / 30.0
            interesMensualMonto * mesesReales
        }
    }
}

// ─────────────────────────────────────────────
//  FUNCIÓN PARA ACEPTAR SOLICITUD Y GENERAR RECIBO
// ─────────────────────────────────────────────
suspend fun aceptarSolicitudYGenerarRecibo(
    solicitud: SolicitudModel,
    context: Context,
    db: FirebaseFirestore,
    onSuccess: () -> Unit
) {
    try {
        // Contador para numeroPrestamo
        val prestamosSnapshot = db.collection("prestamos").get().await()

        // Fecha inicio desde la solicitud (o ahora)
        val fechaInicio = Calendar.getInstance().apply {
            try {
                val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                time = fmt.parse(solicitud.fecha) ?: Date()
            } catch (_: Exception) { time = Date() }
        }

        // Interés total coherente
        val interesTotal = if (solicitud.interesManual > 0) {
            solicitud.interesManual
        } else {
            calcularInteresTotalDetalle(
                solicitud.monto,
                solicitud.interesMensual,
                solicitud.plazo,
                solicitud.cuotas,
                fechaInicio
            )
        }

        val totalPagar = solicitud.monto + interesTotal
        val cuota = if (solicitud.cuotas > 0) (totalPagar / solicitud.cuotas).roundToInt() else 0
        val proximoPago = calcularProximoPagoDesdeHoyDetalle(solicitud.plazo)

        // Nombre y UID del cobrador solicitante
        val cobradorNombre = when {
            !solicitud.cobrador.isNullOrBlank() -> solicitud.cobrador!!
            !solicitud.cobradorSolicitante.isNullOrBlank() -> solicitud.cobradorSolicitante
            else -> "Administrador"
        }
        val cobradorUid = solicitud.cobradorUid?.trim() ?: ""

        // 🔍 Log para detectar si el UID llega vacío
        Log.d("AceptarSolicitud", "cobradorNombre=$cobradorNombre | cobradorUid='$cobradorUid'")

        // Crear referencia del préstamo
        val nuevoPrestamoRef = db.collection("prestamos").document()
        val prestamoId = nuevoPrestamoRef.id

        // Lista de cobradores (sin vacíos)
        val cobradoresAsignados = listOfNotNull(
            cobradorUid.takeIf { it.isNotBlank() }
        )

        val nuevoPrestamo = hashMapOf(
            "id" to prestamoId,
            "prestamoId" to prestamoId,
            "numeroPrestamo" to (prestamosSnapshot.size() + 1),
            "clienteId" to solicitud.clienteId,
            "cliente" to solicitud.cliente,

            "monto" to solicitud.monto,
            "interes" to interesTotal,
            "interesMensual" to solicitud.interesMensual,
            "interesManual" to solicitud.interesManual,
            "interesTotal" to interesTotal,
            "totalPagar" to totalPagar,

            "cuotas" to solicitud.cuotas,
            "cuota" to cuota,
            "plazo" to solicitud.plazo,

            "mora" to solicitud.mora,
            "garantia" to solicitud.garantia,
            "observaciones" to solicitud.observaciones,
            "firma" to (solicitud.firma ?: ""),

            // ✅ Cobrador asignado correctamente en todos los campos que usa la app
            "cobrador" to cobradorNombre,
            "cobradorAsignado" to cobradorUid,
            "cobradorUid" to cobradorUid,
            "cobradoresAsignados" to cobradoresAsignados,

            "fecha" to com.google.firebase.Timestamp.now(),
            "fechaCreacion" to com.google.firebase.Timestamp.now(),
            "proximoPago" to proximoPago,

            "fotos" to solicitud.fotos,
            "pagos" to 0.0,
            "montoPagado" to 0.0,
            "saldo" to totalPagar,

            "estado" to "activo",
            "eliminado" to false
        )

        // Guardar préstamo
        nuevoPrestamoRef.set(nuevoPrestamo).await()

        // ✅ CORRECCIÓN PRINCIPAL: Reasignar cliente al cobrador con TODOS los campos necesarios
        if (solicitud.clienteId.isNotBlank()) {
            val updateMap = mutableMapOf<String, Any>(
                "tienePrestamo"    to true,
                "prestamoActivoId" to prestamoId,
                "cobrador"         to cobradorNombre   // nombre visible en UI
            )

            // Solo actualizar campos de UID si el cobrador tiene UID válido
            if (cobradorUid.isNotBlank()) {
                updateMap["cobradorAsignado"]    = cobradorUid   // campo string que usa ClientesVista
                updateMap["cobradoresAsignados"] = cobradoresAsignados  // campo array que usa ClientesVista
            } else {
                // ⚠️ Si no hay UID, loguear para detectar el problema en la solicitud
                Log.w("AceptarSolicitud",
                    "⚠️ cobradorUid vacío para solicitud ${solicitud.id}. " +
                            "El cobrador '${cobradorNombre}' NO podrá ver este préstamo. " +
                            "Revisa que al crear la solicitud se guarde el campo 'cobradorUid'."
                )
            }

            db.collection("clientes").document(solicitud.clienteId)
                .update(updateMap)
                .await()

            Log.d("AceptarSolicitud",
                "✅ Cliente ${solicitud.clienteId} reasignado a cobrador '$cobradorNombre' (uid='$cobradorUid')"
            )
        }

        // Generar recibo
        ReciboHelper.generarReciboPDF(
            context = context,
            cliente = solicitud.cliente,
            prestamoId = prestamoId,
            fecha = solicitud.fecha,
            montoPagado = solicitud.monto.toString(),
            saldoAnterior = totalPagar,
            proximoPago = proximoPago.toString(),
            cuota = "1",
            cobrador = cobradorNombre,
            lugar = solicitud.lugar,
            firma = solicitud.firma ?: "",
            tipoPago = "Efectivo",
            mora = 0.0
        )

        // Cerrar solicitud
        db.collection("solicitudes_prestamo").document(solicitud.id).delete().await()

        onSuccess()

    } catch (e: Exception) {
        Log.e("AceptarSolicitud", "Error al aceptar solicitud: ${e.message}", e)
        Toast.makeText(context, "Error al aceptar: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ─────────────────────────────────────────────
//  COMPOSABLE PRINCIPAL
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalleSolicitudScreen(
    solicitudId: String,
    navController: NavController
) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var selectedImageUrl by remember { mutableStateOf<String?>(null) }
    var solicitud by remember { mutableStateOf<SolicitudModel?>(null) }

    // Cargar la solicitud
    LaunchedEffect(solicitudId) {
        try {
            val snapshot = db.collection("solicitudes_prestamo")
                .document(solicitudId)
                .get()
                .await()

            if (snapshot.exists()) {
                val data = snapshot.data ?: emptyMap()
                val solicitudData = data.toMutableMap().apply {
                    put("id", snapshot.id)
                    if (!containsKey("clienteId")) {
                        put("clienteId", this["cliente"] as? String ?: "")
                    }
                }
                solicitud = solicitudData.toSolicitudModel()
            } else {
                error = "Solicitud no encontrada"
            }
        } catch (e: Exception) {
            error = "Error al cargar la solicitud: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    // Mostrar estado de carga / error si aún no hay solicitud
    val solicitudActual = solicitud ?: run {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Detalle de Solicitud") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Cargando solicitud...")
                        }
                    }
                    error != null -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Error: $error", color = Color.Red)
                            Button(onClick = { navController.popBackStack() }) {
                                Text("Volver")
                            }
                        }
                    }
                    else -> {
                        Text("Solicitud no encontrada", color = Color.Red)
                    }
                }
            }
        }
        return
    }

    // Cálculos para la solicitud
    val fechaInicio = Calendar.getInstance().apply {
        try {
            val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            time = formatter.parse(solicitudActual.fecha) ?: Date()
        } catch (e: Exception) {
            time = Date()
        }
    }

    val diasEfectivos = if (solicitudActual.diasEfectivos > 0) {
        solicitudActual.diasEfectivos
    } else {
        calcularDiasEfectivosDetalle(solicitudActual.plazo, solicitudActual.cuotas, fechaInicio)
    }

    val mesesAproximados = diasEfectivos / 30.0

    val interesCalculado = if (solicitudActual.interesTotal > 0) {
        solicitudActual.interesTotal
    } else {
        calcularInteresTotalDetalle(
            solicitudActual.monto,
            solicitudActual.interesMensual,
            solicitudActual.plazo,
            solicitudActual.cuotas,
            fechaInicio
        )
    }

    val totalAPagar = if (solicitudActual.totalPagar > 0) {
        solicitudActual.totalPagar
    } else {
        solicitudActual.monto + interesCalculado
    }

    val cuotaEstimada = if (solicitudActual.cuota > 0) {
        solicitudActual.cuota
    } else if (solicitudActual.cuotas > 0) {
        (totalAPagar / solicitudActual.cuotas).roundToInt().toDouble()
    } else {
        0.0
    }

    val descripcionPlazo = when (solicitudActual.plazo) {
        "Diario"         -> "Incluye domingos"
        "Lunes a Sábado" -> "Excluye domingos"
        "Semanal"        -> "Cada 7 días"
        "Quincenal"      -> "Cada 15 días"
        "Mensual"        -> "Cada 30 días"
        "Bimestral"      -> "Cada 60 días"
        else             -> ""
    }

    val fotosUrl = solicitudActual.fotos.filter { it.isNotBlank() }

    // ── Dialog imagen ampliada ────────────────────────────────────────────
    selectedImageUrl?.let { imageUrl ->
        Dialog(onDismissRequest = { selectedImageUrl = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                    ) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = "Imagen ampliada",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            error = painterResource(id = android.R.drawable.ic_menu_gallery),
                            placeholder = painterResource(id = android.R.drawable.ic_menu_gallery)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { selectedImageUrl = null }) {
                            Text("Cerrar")
                        }
                    }
                }
            }
        }
    }

    // ── Scaffold principal ────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Detalle de Solicitud", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(16.dp)
        ) {

            // ── Información del cliente ───────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Información del Cliente",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                        InfoRow(label = "Cliente", value = solicitudActual.cliente)
                        InfoRow(
                            label = "ID del Cliente",
                            value = solicitudActual.clienteId.ifBlank { "No disponible" }
                        )
                        InfoRow(label = "Fecha", value = solicitudActual.fecha)
                        InfoRow(label = "Lugar", value = solicitudActual.lugar.ifBlank { "No especificado" })

                        if (solicitudActual.cobradorSolicitante.isNotBlank()) {
                            InfoRow(label = "Cobrador solicitante", value = solicitudActual.cobradorSolicitante)
                        }
                        // ✅ Mostrar UID del cobrador para depuración (quitar en producción si se prefiere)
                        val cobradorUidDisplay = solicitudActual.cobradorUid
                        if (!cobradorUidDisplay.isNullOrBlank()) {
                            InfoRow(label = "UID cobrador", value = cobradorUidDisplay)
                        } else {
                            InfoRow(
                                label = "UID cobrador",
                                value = "⚠️ No registrado",
                                valueColor = Color(0xFFD32F2F)
                            )
                        }

                        if (solicitudActual.observaciones.isNotBlank()) {
                            InfoRow(label = "Observaciones", value = solicitudActual.observaciones)
                        }
                    }
                }
            }

            // ── Detalles del préstamo ─────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Detalles del Préstamo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                Icons.Default.AttachMoney,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                        InfoRow(label = "Monto solicitado", value = "L. ${"%.2f".format(solicitudActual.monto)}")
                        InfoRow(label = "Interés mensual", value = "${"%.1f".format(solicitudActual.interesMensual)}%")
                        InfoRow(label = "Número de cuotas", value = "${solicitudActual.cuotas}")
                        InfoRow(label = "Plazo", value = "${solicitudActual.plazo} ($descripcionPlazo)")
                        if (solicitudActual.firma?.isNotBlank() == true) {
                            InfoRow(label = "Firma", value = solicitudActual.firma!!)
                        }
                        if (solicitudActual.garantia.isNotBlank()) {
                            InfoRow(label = "Garantía", value = solicitudActual.garantia)
                        }
                    }
                }
            }

            // ── Resumen financiero ────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Resumen Financiero",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                            Icon(
                                Icons.Default.Calculate,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32)
                            )
                        }

                        HorizontalDivider(color = Color(0xFF2E7D32).copy(alpha = 0.3f))

                        InfoRow(
                            label = "Días efectivos",
                            value = "$diasEfectivos días ≈ ${"%.2f".format(mesesAproximados)} meses"
                        )
                        InfoRow(
                            label = "Interés total",
                            value = "L. ${"%.2f".format(interesCalculado)}",
                            valueColor = Color(0xFF1976D2)
                        )
                        InfoRow(
                            label = "Total a pagar",
                            value = "L. ${"%.2f".format(totalAPagar)}",
                            valueColor = Color(0xFF2E7D32),
                            isHighlighted = true
                        )
                        InfoRow(
                            label = "Cuota estimada",
                            value = "L. ${"%.0f".format(cuotaEstimada)}",
                            valueColor = Color(0xFF1976D2)
                        )

                        if (totalAPagar <= solicitudActual.monto) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFD32F2F),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Error en cálculo detectado",
                                        color = Color(0xFFD32F2F),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Evidencia fotográfica ─────────────────────────────────────
            if (fotosUrl.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Evidencia Fotográfica",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                items(fotosUrl) { url ->
                                    PhotoCard(url) { selectedImageUrl = url }
                                }
                            }
                        }
                    }
                }
            }

            // ── Botones de acción ─────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // APROBAR
                    Button(
                        onClick = {
                            scope.launch {
                                isProcessing = true
                                try {
                                    aceptarSolicitudYGenerarRecibo(solicitudActual, context, db) {
                                        Toast.makeText(
                                            context,
                                            "¡Solicitud aprobada y recibo generado!",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        navController.popBackStack()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Error al aprobar solicitud: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text("Aprobar")
                    }

                    // RECHAZAR
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isProcessing = true
                                try {
                                    db.collection("solicitudes_prestamo")
                                        .document(solicitudActual.id)
                                        .delete()
                                        .await()
                                    Toast.makeText(
                                        context,
                                        "Solicitud rechazada y eliminada",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    navController.popBackStack()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Error al rechazar solicitud: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.error,
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                            )
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rechazar")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
//  COMPONENTES REUTILIZABLES
// ─────────────────────────────────────────────
@Composable
fun PhotoCard(imageUrl: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(120.dp)
            .height(120.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Foto de evidencia",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = painterResource(id = android.R.drawable.ic_menu_gallery),
                placeholder = painterResource(id = android.R.drawable.ic_menu_gallery)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.7f), shape = CircleShape)
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Default.ZoomIn,
                    contentDescription = "Ampliar",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    isHighlighted: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = if (isHighlighted) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}