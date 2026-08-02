package com.example.capitalexpressapp.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.example.capitalexpressapp.core.formatearLempiras
import com.example.capitalexpressapp.ui.screens.calcularDiasEfectivos
import com.example.capitalexpressapp.util.PrestamoNumberHelper
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.minifinancieraapp.ui.components.ImprimirOpcionesDialog
import com.example.minifinancieraapp.ui.components.intentarImprimirConRespaldo
import com.example.minifinancieraapp.ui.models.ClienteModel
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════════════════════════════════════
// 🎨 COLORES PERSONALIZADOS
// ═══════════════════════════════════════════════════════════════════════

object SolicitudColors {
    val Primary = Color(0xFF1976D2)
    val PrimaryDark = Color(0xFF1565C0)
    val Success = Color(0xFF2E7D32)
    val SuccessLight = Color(0xFFE8F5E9)
    val Warning = Color(0xFFE65100)
    val WarningLight = Color(0xFFFFF3E0)
    val Danger = Color(0xFFD32F2F)
    val DangerLight = Color(0xFFFFEBEE)
    val Info = Color(0xFF0288D1)
    val InfoLight = Color(0xFFE1F5FE)
    val Purple = Color(0xFF9C27B0)
    val PurpleLight = Color(0xFFF3E5F5)
    val TextPrimary = Color(0xFF212121)
    val TextSecondary = Color(0xFF757575)
    val Background = Color(0xFFF5F5F5)
}

// ═══════════════════════════════════════════════════════════════════════
// 🔥 FUNCIÓN PARA APROBAR SOLICITUD Y CREAR PRÉSTAMO
// ═══════════════════════════════════════════════════════════════════════

suspend fun aceptarSolicitudYGenerarRecibo(
    solicitud: Map<String, Any>,
    context: Context,
    db: FirebaseFirestore,
    onListoParaImprimir: ((accionImprimirDirecto: suspend () -> Boolean, accionSoloPdf: () -> Unit) -> Unit)? = null
): Boolean {
    return try {
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        // 1️⃣ EXTRAER DATOS DE LA SOLICITUD
        val solicitudId = solicitud["id"] as? String ?: return false
        val clienteNombre = solicitud["cliente"] as? String ?: return false
        val clienteId = solicitud["clienteId"] as? String ?: return false

        val monto = (solicitud["monto"] as? Number)?.toDouble() ?: return false
        val interesMensual = (solicitud["interesMensual"] as? Number)?.toDouble() ?: 0.0
        val cuotas = (solicitud["cuotas"] as? Number)?.toInt() ?: return false
        val plazo = solicitud["plazo"] as? String ?: "Semanal"
        val mora = (solicitud["mora"] as? Number)?.toDouble() ?: 0.0

        val lugar = solicitud["lugar"] as? String ?: ""
        val firma = solicitud["firma"] as? String ?: ""
        val garantia = solicitud["garantia"] as? String ?: ""
        val observaciones = solicitud["observaciones"] as? String ?: ""

        // 🔥 OBTENER COBRADOR QUE CREÓ LA SOLICITUD
        val cobradorSolicitanteNombre = solicitud["cobradorSolicitante"] as? String
            ?: solicitud["cobrador"] as? String
            ?: "Administrador"
        val cobradorSolicitanteUid = (solicitud["cobradorUid"] as? String
            ?: solicitud["cobradorSolicitanteUid"] as? String
            ?: "").trim()

        val numeroCobrador = if (cobradorSolicitanteUid.isNotBlank()) {
            try {
                val userDoc = db.collection("usuarios").document(cobradorSolicitanteUid).get().await()
                userDoc.getString("telefono") ?: "N/D"
            } catch (e: Exception) {
                "N/D"
            }
        } else {
            "N/D"
        }

        // 2️⃣ PARSEAR FECHA
        val fechaString = solicitud["fecha"] as? String ?: formatter.format(Date())
        val fechaSeleccionada = try {
            val cal = Calendar.getInstance()
            cal.time = formatter.parse(fechaString) ?: Date()
            cal
        } catch (e: Exception) {
            Calendar.getInstance()
        }

        // 3️⃣ CALCULAR INTERESES
        val interesManual = (solicitud["interesManual"] as? Number)?.toDouble()
            ?: (solicitud["interesTotalFijo"] as? Number)?.toDouble() ?: 0.0
        val usarInteresMensual = interesManual <= 0.0

        val fechaInicio = fechaSeleccionada.clone() as Calendar
        val diasEfectivos = calcularDiasEfectivos(plazo, cuotas, fechaInicio)

        val interesCalculado = if (!usarInteresMensual && interesManual > 0) {
            interesManual
        } else if (usarInteresMensual && interesMensual > 0) {
            val interesMensualMonto = monto * (interesMensual / 100)
            when (plazo) {
                "Mensual" -> interesMensualMonto * cuotas
                "Lunes a Sábado" -> {
                    val semanasLaborables = diasEfectivos / 6.0
                    val mesesEquivalentes = semanasLaborables / 4.0
                    interesMensualMonto * mesesEquivalentes
                }
                "Semanal" -> {
                    val semanasTotal = cuotas.toDouble()
                    val mesesEquivalentes = semanasTotal / 4.0
                    interesMensualMonto * mesesEquivalentes
                }
                else -> interesMensualMonto * (diasEfectivos / 30.0)
            }
        } else {
            0.0
        }

        val totalAPagar = monto + interesCalculado
        val cuotaEstimada = if (cuotas > 0) (totalAPagar / cuotas) else 0.0

        // 4️⃣ CALCULAR PRÓXIMO PAGO
        val proximoCal = fechaSeleccionada.clone() as Calendar
        when (plazo) {
            "Diario" -> proximoCal.add(Calendar.DAY_OF_YEAR, 1)
            "Lunes a Sábado" -> {
                do {
                    proximoCal.add(Calendar.DAY_OF_YEAR, 1)
                } while (proximoCal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
            "Semanal" -> proximoCal.add(Calendar.DAY_OF_YEAR, 7)
            "Quincenal" -> proximoCal.add(Calendar.DAY_OF_YEAR, 15)
            "Mensual" -> proximoCal.add(Calendar.MONTH, 1)
            "Bimestral" -> proximoCal.add(Calendar.MONTH, 2)
        }
        val proximoPagoString = formatter.format(proximoCal.time)
        val proximoPagoTimestamp = Timestamp(proximoCal.time)

        // 5️⃣ GENERAR NÚMERO ÚNICO DE PRÉSTAMO
        val numeroPrestamoUnico = PrestamoNumberHelper.generarNumeroPrestamo(
            clienteNombre = clienteNombre,
            db = db
        )

        // 6️⃣ CREAR PRÉSTAMO
        val docRef = db.collection("prestamos").document()
        val prestamoId = docRef.id
        val fecha = formatter.format(fechaSeleccionada.time)

        // Lista de cobradores (sin vacíos)
        val cobradoresAsignados = listOfNotNull(
            cobradorSolicitanteUid.takeIf { it.isNotBlank() }
        )

        val prestamo = hashMapOf(
            "id" to prestamoId,
            "numeroPrestamo" to numeroPrestamoUnico,
            "cliente" to clienteNombre,
            "clienteId" to clienteId,
            "monto" to monto,
            "interes" to interesCalculado,
            "interesMensual" to if (usarInteresMensual) interesMensual else 0.0,
            "interesTotal" to interesCalculado,
            "usarInteresMensual" to usarInteresMensual,
            "interesTotalFijo" to if (!usarInteresMensual) interesManual else 0.0,
            "mora" to mora,
            "totalPagar" to totalAPagar,
            "cuota" to cuotaEstimada,
            "cuotas" to cuotas,
            "plazo" to plazo,
            "fecha" to Timestamp(fechaSeleccionada.time),
            "fechaCreacion" to Timestamp(Date()),
            "lugar" to lugar,
            "firma" to firma,
            "garantia" to garantia,
            "cobrador" to cobradorSolicitanteNombre,
            "numeroCobrador" to numeroCobrador,
            "cobradorUid" to cobradorSolicitanteUid,
            // ✅ Todos los campos que usa NotificacionesScreen y ClientesVista
            "cobradoresAsignados" to cobradoresAsignados,
            "cobradorAsignado" to cobradorSolicitanteUid,
            "prestamoId" to prestamoId,
            "proximoPago" to proximoPagoTimestamp,
            "montoPagado" to 0.0,
            "saldoAnterior" to monto,
            "estado" to "activo",
            "observaciones" to observaciones,
            "fotos" to emptyList<String>(),
            "diasEfectivos" to diasEfectivos.toDouble(),
            "saldo" to totalAPagar,
            "eliminado" to false,
            "origenSolicitud" to true,
            "solicitudId" to solicitudId
        )

        docRef.set(prestamo).await()

        // 7️⃣ ACTUALIZAR CLIENTE - ✅ CORRECCIÓN PRINCIPAL
        // Leer el cliente actual para NO sobreescribir el cobrador si ya tiene uno asignado
        val clienteDocActual = db.collection("clientes").document(clienteId).get().await()
        val cobradorActual   = clienteDocActual.getString("cobradorAsignado")?.trim() ?: ""
        val cobradoresActuales = (clienteDocActual.get("cobradoresAsignados") as? List<*>)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        // Usar el cobrador de la solicitud solo si el cliente no tiene uno ya asignado
        val cobradorFinal = when {
            cobradorActual.isNotBlank()           -> cobradorActual          // respetar el existente
            cobradorSolicitanteUid.isNotBlank()   -> cobradorSolicitanteUid  // usar el de la solicitud
            else                                  -> ""
        }

        // Unir la lista existente con el nuevo cobrador (sin duplicados)
        val cobradoresFinal = (cobradoresActuales + cobradoresAsignados)
            .distinct()
            .filter { it.isNotBlank() }

        val clienteUpdateMap = mutableMapOf<String, Any>(
            "tienePrestamo"    to true,
            "prestamoActivoId" to prestamoId,
            "cobrador"         to cobradorSolicitanteNombre
        )
        if (cobradorFinal.isNotBlank()) {
            clienteUpdateMap["cobradorAsignado"]    = cobradorFinal
            clienteUpdateMap["cobradoresAsignados"] = cobradoresFinal
        }

        db.collection("clientes").document(clienteId)
            .update(clienteUpdateMap)
            .await()

        // ✅ Actualizar cobrador en TODOS los préstamos existentes del cliente
        // Solo si el cliente tiene un cobrador asignado válido
        if (cobradorFinal.isNotBlank()) {
            val prestamosExistentes = db.collection("prestamos")
                .whereEqualTo("clienteId", clienteId)
                .get().await()

            prestamosExistentes.documents.forEach { prestamoDoc ->
                // No actualizar el que acabamos de crear (ya tiene los datos correctos)
                if (prestamoDoc.id != prestamoId) {
                    db.collection("prestamos").document(prestamoDoc.id).update(
                        mapOf(
                            "cobradoresAsignados" to cobradoresFinal,
                            "cobradorAsignado"    to cobradorFinal
                        )
                    ).await()
                }
            }
        }

        // 8️⃣ GENERAR PDF
        try {
            val clienteDoc = db.collection("clientes").document(clienteId).get().await()
            val cliente = ClienteModel(
                id = clienteId,
                nombre = clienteNombre,
                telefono = clienteDoc.getString("telefono") ?: "",
                direccionCasa = clienteDoc.getString("direccionCasa") ?: "",
                identidad = clienteDoc.getString("identidad") ?: ""
            )

            val reciboFile = ReciboHelper.generarReciboPrestamoPDF(
                context = context,
                cliente = cliente,
                monto = monto,
                interesTotal = interesCalculado,
                mora = mora,
                cuotas = cuotas,
                fecha = fecha,
                lugar = lugar,
                numeroCobrador = numeroCobrador,
                numeroPrestamo = numeroPrestamoUnico,
                nombreCobrador = cobradorSolicitanteNombre,
                fechaProximoPago = proximoPagoString
            )

            if (reciboFile != null && reciboFile.exists()) {
                val accionImprimirDirecto: suspend () -> Boolean = {
                    ReciboHelper.imprimirReciboPrestamoDirecto(
                        context = context,
                        cliente = cliente.nombre,
                        telefono = cliente.telefono,
                        monto = monto,
                        interesTotal = interesCalculado,
                        mora = mora,
                        cuotas = cuotas,
                        fecha = fecha,
                        lugar = lugar,
                        numeroCobrador = numeroCobrador,
                        numeroPrestamo = numeroPrestamoUnico,
                        nombreCobrador = cobradorSolicitanteNombre,
                        fechaProximoPago = proximoPagoString
                    )
                }
                val accionSoloPdf: () -> Unit = {
                    ReciboHelper.imprimirPDF(context, reciboFile)

                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        reciboFile
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, "Recibo del préstamo generado desde Capital Express.")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Compartir recibo con:"))
                }

                if (onListoParaImprimir != null) {
                    onListoParaImprimir(accionImprimirDirecto, accionSoloPdf)
                } else {
                    accionSoloPdf()
                }
            }
        } catch (reciboError: Exception) {
            Toast.makeText(
                context,
                "Préstamo creado. Error al generar recibo: ${reciboError.message}",
                Toast.LENGTH_LONG
            ).show()
        }

        // 9️⃣ ELIMINAR SOLICITUD
        db.collection("solicitudes_prestamo").document(solicitudId).delete().await()

        Toast.makeText(
            context,
            "✅ Préstamo aprobado exitosamente\nNúmero: $numeroPrestamoUnico",
            Toast.LENGTH_LONG
        ).show()

        true

    } catch (e: Exception) {
        Toast.makeText(
            context,
            "❌ Error al aprobar solicitud: ${e.message}",
            Toast.LENGTH_LONG
        ).show()
        e.printStackTrace()
        false
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 📊 MODELO DE DATOS
// ═══════════════════════════════════════════════════════════════════════

data class SolicitudModel(
    val id: String = "",
    val cliente: String = "",
    val clienteId: String = "",
    val monto: Double = 0.0,
    val interesMensual: Double = 0.0,
    val cuotas: Int = 0,
    val plazo: String = "",
    val mora: Double = 0.0,
    val fecha: String = "",
    val estado: String = "pendiente",
    val cobradorSolicitante: String = "",
    val cobradorUid: String = "",
    val lugar: String = "",
    val firma: String = "",
    val garantia: String = "",
    val observaciones: String = "",
    val interesManual: Double = 0.0,
    val usarInteresMensual: Boolean = true,
    val interesTotalFijo: Double = 0.0
)

// ═══════════════════════════════════════════════════════════════════════
// 🎨 PANTALLA PRINCIPAL
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolicitudesAdminScreen(navController: NavController) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    var solicitudes by remember { mutableStateOf<List<SolicitudModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var procesandoId by remember { mutableStateOf<String?>(null) }

    // Diálogo "Imprimir directo o solo PDF"
    var mostrarDialogoImprimir by remember { mutableStateOf(false) }
    var accionImprimirDirectoPendiente by remember { mutableStateOf<(suspend () -> Boolean)?>(null) }
    var accionSoloPdfPendiente by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun cargarSolicitudes() {
        scope.launch {
            try {
                isLoading = true
                val snapshot = db.collection("solicitudes_prestamo")
                    .whereEqualTo("estado", "pendiente")
                    .get()
                    .await()

                solicitudes = snapshot.documents.mapNotNull { doc ->
                    try {
                        SolicitudModel(
                            id = doc.id,
                            cliente = doc.getString("cliente") ?: "",
                            clienteId = doc.getString("clienteId") ?: "",
                            monto = doc.getDouble("monto") ?: 0.0,
                            interesMensual = doc.getDouble("interesMensual") ?: 0.0,
                            cuotas = doc.getLong("cuotas")?.toInt() ?: 0,
                            plazo = doc.getString("plazo") ?: "",
                            mora = doc.getDouble("mora") ?: 0.0,
                            fecha = doc.getString("fecha") ?: "",
                            estado = doc.getString("estado") ?: "pendiente",
                            cobradorSolicitante = doc.getString("cobradorSolicitante")
                                ?: doc.getString("cobrador") ?: "",
                            cobradorUid = doc.getString("cobradorUid") ?: "",
                            lugar = doc.getString("lugar") ?: "",
                            firma = doc.getString("firma") ?: "",
                            garantia = doc.getString("garantia") ?: "",
                            observaciones = doc.getString("observaciones") ?: "",
                            interesManual = doc.getDouble("interesManual") ?: 0.0,
                            usarInteresMensual = doc.getBoolean("usarInteresMensual") ?: true,
                            interesTotalFijo = doc.getDouble("interesTotalFijo") ?: 0.0
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error al cargar: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        cargarSolicitudes()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Solicitudes Pendientes",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            "${solicitudes.size} solicitud${if (solicitudes.size != 1) "es" else ""}",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Volver", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { cargarSolicitudes() },
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, "Actualizar", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SolicitudColors.Primary
                )
            )
        }
    ) { padding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SolicitudColors.Background)
        ) {
            when {
                isLoading -> {
                    LoadingState()
                }
                solicitudes.isEmpty() -> {
                    EmptyState()
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(solicitudes, key = { it.id }) { solicitud ->
                            SolicitudCardModerna(
                                solicitud = solicitud,
                                isProcessing = procesandoId == solicitud.id,
                                onAprobar = {
                                    scope.launch {
                                        procesandoId = solicitud.id

                                        val exito = aceptarSolicitudYGenerarRecibo(
                                            solicitud = mapOf(
                                                "id" to solicitud.id,
                                                "cliente" to solicitud.cliente,
                                                "clienteId" to solicitud.clienteId,
                                                "monto" to solicitud.monto,
                                                "interesMensual" to solicitud.interesMensual,
                                                "cuotas" to solicitud.cuotas,
                                                "plazo" to solicitud.plazo,
                                                "mora" to solicitud.mora,
                                                "fecha" to solicitud.fecha,
                                                "cobradorSolicitante" to solicitud.cobradorSolicitante,
                                                "cobradorUid" to solicitud.cobradorUid,
                                                "lugar" to solicitud.lugar,
                                                "firma" to solicitud.firma,
                                                "garantia" to solicitud.garantia,
                                                "observaciones" to solicitud.observaciones,
                                                "interesManual" to solicitud.interesManual,
                                                "interesTotalFijo" to solicitud.interesTotalFijo,
                                                "usarInteresMensual" to solicitud.usarInteresMensual
                                            ),
                                            context = context,
                                            db = db,
                                            onListoParaImprimir = { accionImprimirDirecto, accionSoloPdf ->
                                                accionImprimirDirectoPendiente = accionImprimirDirecto
                                                accionSoloPdfPendiente = accionSoloPdf
                                                mostrarDialogoImprimir = true
                                            }
                                        )

                                        procesandoId = null

                                        if (exito) {
                                            cargarSolicitudes()
                                        }
                                    }
                                },
                                onRechazar = {
                                    scope.launch {
                                        try {
                                            db.collection("solicitudes_prestamo")
                                                .document(solicitud.id)
                                                .delete()
                                                .await()

                                            Toast.makeText(context, "Solicitud rechazada", Toast.LENGTH_SHORT).show()
                                            cargarSolicitudes()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (mostrarDialogoImprimir) {
            ImprimirOpcionesDialog(
                onImprimirDirecto = {
                    mostrarDialogoImprimir = false
                    scope.launch { intentarImprimirConRespaldo(context, accionImprimirDirectoPendiente, accionSoloPdfPendiente) }
                },
                onSoloPdf = {
                    mostrarDialogoImprimir = false
                    accionSoloPdfPendiente?.invoke()
                },
                onDismiss = { mostrarDialogoImprimir = false }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 🎨 COMPONENTES UI
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                color = SolicitudColors.Primary,
                strokeWidth = 4.dp
            )
            Text(
                "Cargando solicitudes...",
                fontSize = 16.sp,
                color = SolicitudColors.TextSecondary
            )
        }
    }
}

@Composable
fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = SolicitudColors.Success
            )
            Text(
                "¡Todo al día!",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = SolicitudColors.TextPrimary
            )
            Text(
                "No hay solicitudes pendientes de aprobación",
                fontSize = 14.sp,
                color = SolicitudColors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SolicitudCardModerna(
    solicitud: SolicitudModel,
    isProcessing: Boolean,
    onAprobar: () -> Unit,
    onRechazar: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        SolicitudColors.Warning,
                                        SolicitudColors.Warning.copy(alpha = 0.7f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column {
                        Text(
                            solicitud.cliente,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = SolicitudColors.TextPrimary
                        )
                        Text(
                            "Solicitud de préstamo",
                            fontSize = 12.sp,
                            color = SolicitudColors.TextSecondary
                        )
                    }
                }

                Surface(
                    color = SolicitudColors.WarningLight,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "PENDIENTE",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SolicitudColors.Warning
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // MONTO PRINCIPAL
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SolicitudColors.InfoLight),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "MONTO SOLICITADO",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SolicitudColors.Info.copy(alpha = 0.7f),
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            formatearLempiras(solicitud.monto),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = SolicitudColors.Info
                        )
                    }

                    Icon(
                        Icons.Default.Paid,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = SolicitudColors.Info.copy(alpha = 0.3f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // DETALLES PRINCIPALES
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoChipModerno(
                    icon = Icons.Default.CalendarToday,
                    label = "Cuotas",
                    value = "${solicitud.cuotas}",
                    color = SolicitudColors.Primary,
                    modifier = Modifier.weight(1f)
                )
                InfoChipModerno(
                    icon = Icons.Default.Schedule,
                    label = "Plazo",
                    value = solicitud.plazo,
                    color = SolicitudColors.Purple,
                    modifier = Modifier.weight(1f)
                )
                InfoChipModerno(
                    icon = Icons.Default.Event,
                    label = "Fecha",
                    value = solicitud.fecha,
                    color = SolicitudColors.Success,
                    modifier = Modifier.weight(1f)
                )
            }

            // DETALLES EXPANDIBLES
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider(color = SolicitudColors.Background, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    val interesTexto = if (solicitud.usarInteresMensual) {
                        "${"%.1f".format(solicitud.interesMensual)}% mensual"
                    } else {
                        "${formatearLempiras(solicitud.interesTotalFijo)} fijo"
                    }

                    DetalleRow("Tipo de interés", interesTexto)

                    if (solicitud.mora > 0) {
                        DetalleRow("Mora diaria", formatearLempiras(solicitud.mora))
                    }

                    if (solicitud.lugar.isNotBlank()) {
                        DetalleRow("Lugar", solicitud.lugar)
                    }

                    if (solicitud.cobradorSolicitante.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = SolicitudColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Solicitado por: ${solicitud.cobradorSolicitante}",
                                fontSize = 13.sp,
                                color = SolicitudColors.TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // BOTONES DE ACCIÓN
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.weight(0.4f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, SolicitudColors.Primary)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = SolicitudColors.Primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (expanded) "Menos" else "Más",
                        fontSize = 13.sp,
                        color = SolicitudColors.Primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onAprobar,
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = SolicitudColors.Success),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Procesando...")
                    } else {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("APROBAR", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onRechazar,
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, SolicitudColors.Danger),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SolicitudColors.Danger),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("RECHAZAR", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun InfoChipModerno(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(
                value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                label,
                fontSize = 10.sp,
                color = color.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DetalleRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = SolicitudColors.TextSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SolicitudColors.TextPrimary)
    }
}