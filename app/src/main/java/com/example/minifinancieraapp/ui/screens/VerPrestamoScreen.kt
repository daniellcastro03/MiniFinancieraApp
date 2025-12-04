package com.example.capitalexpressapp.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

data class PrestamoDetalle(
    val id: String,
    val numeroPrestamo: String?,
    val clienteId: String,
    val cliente: String,
    val monto: Double,
    val totalPagar: Double,
    val montoPagado: Double,
    val saldoPendiente: Double,
    val cuota: Double,
    val cuotas: Int,
    val plazo: String,
    val fecha: String,
    val proximoPago: String,
    val estado: String,
    val cobrador: String,
    val interesMensual: Double,
    val diasEfectivos: Int,
    val interesTotal: Double,
    val observaciones: String?,
    val telefono: String?,
    val direccion: String?,
    val cedula: String?,
    val fechaCreacion: String,
    val ultimaActualizacion: String?,
    val fechaCancelacion: String? = null  // ⭐ NUEVO CAMPO
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerPrestamoScreen(
    navController: NavController,
    prestamoId: String,
    uid: String,
    rol: String
) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prestamo by remember { mutableStateOf<PrestamoDetalle?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeleteForeverDialog by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val dateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    var clienteId by remember { mutableStateOf("") }

    fun cargarPrestamo() {
        scope.launch {
            isLoading = true
            try {
                val docRef = db.collection("prestamos").document(prestamoId)
                val doc = docRef.get().await()
                if (!doc.exists()) {
                    Toast.makeText(context, "Préstamo no encontrado", Toast.LENGTH_SHORT).show()
                    navController.popBackStack()
                    navController.previousBackStackEntry?.savedStateHandle?.set("refreshHistorial", true)
                    isLoading = false
                    return@launch
                }

                val data = doc.data!!

                val eliminado = data["eliminado"] as? Boolean ?: false
                val estadoOriginal = (data["estado"] as? String ?: "activo").lowercase()

                val cliente       = data["cliente"] as? String ?: "Cliente desconocido"
                val numeroPrestamo = data["numeroPrestamo"] as? String // ⭐ CAMPO EXISTENTE
                val monto         = (data["monto"] as? Number)?.toDouble() ?: 0.0
                val interesTotal  = (data["interesTotal"] as? Number)?.toDouble()
                    ?: (data["interes"] as? Number)?.toDouble() ?: 0.0
                val totalPagarRaw = (data["totalPagar"] as? Number)?.toDouble()
                val totalPagar    = totalPagarRaw ?: (monto + interesTotal)

                val cuota         = (data["cuota"] as? Number)?.toDouble() ?: 0.0
                val cuotas        = (data["cuotas"] as? Number)?.toInt() ?: 0
                val plazo         = data["plazo"] as? String ?: "No especificado"

                val cobrador = (data["cobrador"] as? String)
                    ?: (data["cobradorAsignado"] as? String) ?: "No asignado"

                val interesMensual = (data["interesMensual"] as? Number)?.toDouble()
                    ?: (data["interes"] as? Number)?.toDouble() ?: 0.0
                val diasEfectivos  = (data["diasEfectivos"] as? Number)?.toInt() ?: 0

                val observaciones  = data["observaciones"] as? String
                val telefono       = data["telefono"] as? String
                val direccion      = data["direccion"] as? String
                val cedula         = data["cedula"] as? String
                clienteId          = data["clienteId"]?.toString() ?: ""

                val fecha = when (val f = data["fecha"] ?: data["fechaCreacion"]) {
                    is Timestamp -> dateFormatter.format(f.toDate())
                    is String    -> f
                    else         -> "Sin fecha"
                }

                val proximoPagoRaw = when (val p = data["proximoPago"]) {
                    is Timestamp -> dateFormatter.format(p.toDate())
                    is String    -> p
                    else         -> "Sin definir"
                }

                val fechaCreacion = when (val fc = data["fechaCreacion"]) {
                    is Timestamp -> formatter.format(fc.toDate())
                    is String    -> fc
                    else         -> "Sin fecha"
                }

                val ultimaActualizacion = when (val ua = data["ultimaActualizacion"]) {
                    is Timestamp -> formatter.format(ua.toDate())
                    is String    -> ua
                    else         -> null
                }

                // ⭐⭐⭐ LEER FECHA DE CANCELACIÓN ⭐⭐⭐
                val fechaCancelacion = when (val fc = data["fechaCancelacion"]) {
                    is Timestamp -> formatter.format(fc.toDate())
                    is String    -> fc
                    else         -> null
                }

                // ✅✅✅ RECALCULAR MONTO PAGADO DESDE LA COLECCIÓN DE PAGOS ✅✅✅
                val moraActual = (data["mora"] as? Number)?.toDouble() ?: 0.0
                val totalConMora = totalPagar + moraActual

                val pagosSnapshot = db.collection("pagos")
                    .whereEqualTo("prestamoId", prestamoId)
                    .get().await()

                var montoPagadoReal = 0.0
                for (pago in pagosSnapshot.documents) {
                    val montoPago = pago.getDouble("monto") ?: 0.0
                    val moraPago = pago.getDouble("mora") ?: 0.0
                    montoPagadoReal += montoPago + moraPago
                }

                Log.d("VerPrestamoScreen", """
                    💰 RECÁLCULO DE SALDO DESDE PAGOS:
                    - Total a pagar (base): L. $totalPagar
                    - Mora aplicada: L. $moraActual
                    - Total con mora: L. $totalConMora
                    - Monto REAL pagado (desde pagos): L. $montoPagadoReal
                """.trimIndent())

                // ====== NORMALIZACIÓN CRÍTICA ======
                val epsilon = 0.01
                // Calcular saldo pendiente usando el monto REAL pagado
                val saldoPendienteCalculado = totalConMora - montoPagadoReal
                val saldoPendiente = if (saldoPendienteCalculado <= epsilon) 0.0 else saldoPendienteCalculado

                Log.d("VerPrestamoScreen", """
                    === CÁLCULO DE ESTADO ===
                    Préstamo ID: $prestamoId
                    Total con mora: L. $totalConMora
                    Monto REAL pagado: L. $montoPagadoReal
                    Saldo calculado: L. $saldoPendienteCalculado
                    Saldo final: L. $saldoPendiente
                    Estado original: $estadoOriginal
                    Eliminado: $eliminado
                """.trimIndent())

                // Determinar el estado efectivo basado en el saldo REAL
                val estadoEfectivo = when {
                    eliminado -> "eliminado"
                    // Si el saldo es 0 o negativo, SIEMPRE es saldado
                    saldoPendiente <= epsilon -> "saldado"
                    saldoPendienteCalculado <= epsilon -> "saldado"
                    montoPagadoReal >= totalConMora -> "saldado"
                    estadoOriginal == "saldado" -> "saldado"
                    estadoOriginal == "completado" -> "saldado"
                    else -> estadoOriginal
                }

                Log.d("VerPrestamoScreen", "Estado efectivo determinado: $estadoEfectivo")

                // Si está saldado, muestra "saldado" en Próximo pago
                val proximoPago = if (estadoEfectivo == "saldado") "saldado" else proximoPagoRaw

                prestamo = PrestamoDetalle(
                    id = prestamoId,
                    numeroPrestamo = numeroPrestamo,
                    clienteId = clienteId,
                    cliente = cliente,
                    monto = monto,
                    totalPagar = totalConMora,
                    montoPagado = montoPagadoReal,
                    saldoPendiente = saldoPendiente,
                    cuota = cuota,
                    cuotas = cuotas,
                    plazo = plazo,
                    fecha = fecha,
                    proximoPago = proximoPago,
                    estado = estadoEfectivo,
                    cobrador = cobrador,
                    interesMensual = interesMensual,
                    diasEfectivos = diasEfectivos,
                    interesTotal = interesTotal,
                    observaciones = observaciones,
                    telefono = telefono,
                    direccion = direccion,
                    cedula = cedula,
                    fechaCreacion = fechaCreacion,
                    ultimaActualizacion = ultimaActualizacion,
                    fechaCancelacion = fechaCancelacion  // ⭐ NUEVO CAMPO
                )

                // Corregir el documento en Firestore si está inconsistente
                val montoPagadoDoc = (data["montoPagado"] as? Number)?.toDouble()
                val saldoDoc = (data["saldo"] as? Number)?.toDouble()
                val estadoDoc = data["estado"] as? String ?: "activo"

                val debeActualizar =
                    (estadoEfectivo == "saldado" && estadoDoc != "saldado") ||
                            (estadoEfectivo != estadoOriginal && estadoEfectivo != "eliminado") ||
                            (saldoDoc == null || kotlin.math.abs(saldoDoc - saldoPendiente) > epsilon) ||
                            (montoPagadoDoc == null || kotlin.math.abs(montoPagadoDoc - montoPagadoReal) > epsilon) ||
                            (totalPagarRaw == null) ||
                            // ⭐ NUEVO: Agregar fecha de cancelación a préstamos saldados sin fecha
                            (estadoEfectivo == "saldado" && data["fechaCancelacion"] == null)

                if (debeActualizar) {
                    try {
                        val updates = mutableMapOf<String, Any>(
                            "estado" to estadoEfectivo,
                            "saldo" to saldoPendiente,
                            "montoPagado" to montoPagadoReal,
                            "ultimaActualizacion" to Timestamp.now()
                        )
                        if (totalPagarRaw == null) updates["totalPagar"] = totalPagar

                        // ⭐ NUEVO: Si está saldado y no tiene fecha de cancelación, agregarla
                        if (estadoEfectivo == "saldado" && data["fechaCancelacion"] == null) {
                            updates["fechaCancelacion"] = Timestamp.now()
                            Log.d("VerPrestamoScreen", "⭐ Agregando fechaCancelacion a préstamo saldado antiguo")
                        }

                        Log.d("VerPrestamoScreen", """
                            🔧 Actualizando préstamo $prestamoId:
                            - estado: $estadoEfectivo
                            - saldo: L. $saldoPendiente
                            - montoPagado: L. $montoPagadoReal
                        """.trimIndent())

                        docRef.update(updates).await()
                        Log.d("VerPrestamoScreen", "✅ Préstamo actualizado correctamente en Firestore")
                    } catch (e: Exception) {
                        Log.w("VerPrestamoScreen", "⚠️ No se pudo normalizar el doc: ${e.message}")
                    }
                }

                isLoading = false
            } catch (e: Exception) {
                Toast.makeText(context, "Error al cargar el préstamo: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e("VerPrestamoScreen", "Error al cargar préstamo", e)
                isLoading = false
            }
        }
    }

    fun eliminarPrestamo() {
        scope.launch {
            isProcessing = true
            try {
                val docRef = db.collection("prestamos").document(prestamoId)

                val snapshot = docRef.get().await()
                if (snapshot.exists()) {
                    val estadoActual = snapshot.getString("estado") ?: "activo"

                    val updates = mapOf(
                        "estadoAnterior" to estadoActual,
                        "estado" to "eliminado",
                        "eliminado" to true,
                        "eliminadoPor" to uid,
                        "fechaEliminacion" to Timestamp.now(),
                        "ultimaActualizacion" to Timestamp.now()
                    )

                    docRef.update(updates).await()
                }

                Toast.makeText(context, "Préstamo movido a eliminados", Toast.LENGTH_SHORT).show()

                prestamo = prestamo?.copy(estado = "eliminado")
                cargarPrestamo()
                navController.previousBackStackEntry?.savedStateHandle?.set("refreshHistorial", true)

            } catch (e: Exception) {
                Toast.makeText(context, "Error al eliminar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isProcessing = false
            }
        }
    }

    fun restaurarPrestamo() {
        scope.launch {
            isProcessing = true
            try {
                val docRef = db.collection("prestamos").document(prestamoId)

                db.runTransaction { transaction ->
                    val snapshot = transaction.get(docRef)

                    if (snapshot.exists()) {
                        val estadoAnterior = snapshot.getString("estadoAnterior") ?: "activo"

                        transaction.update(docRef, mapOf(
                            "estado" to estadoAnterior,
                            "eliminado" to false,
                            "ultimaActualizacion" to Timestamp.now(),
                            "eliminadoPor" to com.google.firebase.firestore.FieldValue.delete(),
                            "fechaEliminacion" to com.google.firebase.firestore.FieldValue.delete(),
                            "estadoAnterior" to com.google.firebase.firestore.FieldValue.delete()
                        ))
                    }
                }.await()

                Toast.makeText(context, "Préstamo restaurado correctamente", Toast.LENGTH_SHORT).show()
                cargarPrestamo()
                navController.previousBackStackEntry?.savedStateHandle?.set("refreshHistorial", true)

            } catch (e: Exception) {
                Toast.makeText(context, "Error al restaurar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isProcessing = false
            }
        }
    }

    fun eliminarDefinitivo() {
        if (prestamo?.estado != "eliminado") {
            Toast.makeText(context, "Solo se pueden eliminar definitivamente préstamos que estén en estado eliminado", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            isProcessing = true
            try {
                db.collection("prestamos").document(prestamoId).delete().await()

                Toast.makeText(context, "Préstamo eliminado definitivamente de la base de datos", Toast.LENGTH_SHORT).show()

                navController.previousBackStackEntry?.savedStateHandle?.set("refreshHistorial", true)
                navController.popBackStack()

            } catch (e: Exception) {
                Toast.makeText(context, "Error al eliminar definitivamente: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isProcessing = false
            }
        }
    }

    LaunchedEffect(prestamoId) {
        cargarPrestamo()
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showDeleteDialog = false },
            title = { Text("Confirmar eliminación", fontWeight = FontWeight.Bold) },
            text = {
                Text("¿Está seguro que desea mover este préstamo a la sección de eliminados?\n\nEl préstamo será ocultado de las listas principales pero podrá restaurarlo posteriormente.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        eliminarPrestamo()
                    },
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Mover a Eliminados", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    enabled = !isProcessing
                ) {
                    Text("Cancelar")
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showRestoreDialog = false },
            title = { Text("Restaurar préstamo", fontWeight = FontWeight.Bold) },
            text = {
                Text("¿Desea restaurar este préstamo?\n\nVolverá a aparecer en las listas principales y recuperará su estado anterior.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreDialog = false
                        restaurarPrestamo()
                    },
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF4CAF50)
                        )
                    } else {
                        Text("Restaurar", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestoreDialog = false },
                    enabled = !isProcessing
                ) {
                    Text("Cancelar")
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showDeleteForeverDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showDeleteForeverDialog = false },
            title = { Text("⚠️ Eliminar Definitivamente", fontWeight = FontWeight.Bold, color = Color.Red) },
            text = {
                Column {
                    Text(
                        "ATENCIÓN: Esta acción eliminará permanentemente el préstamo de la base de datos.",
                        color = Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "• No se puede deshacer esta acción\n• Se perderán todos los datos del préstamo\n• Se eliminará el historial de cuotas\n• No hay respaldo automático",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "¿Está completamente seguro?",
                        color = Color.Red,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteForeverDialog = false
                        eliminarDefinitivo()
                    },
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.Red
                        )
                    } else {
                        Text("SÍ, ELIMINAR DEFINITIVAMENTE", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteForeverDialog = false },
                    enabled = !isProcessing
                ) {
                    Text("Cancelar", fontWeight = FontWeight.Medium)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Detalle del Préstamo",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        // ⭐⭐⭐ MOSTRAR NÚMERO DE PRÉSTAMO EN EL TOPBAR ⭐⭐⭐
                        prestamo?.numeroPrestamo?.let {
                            Text(
                                it,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0061A7)),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
                    }
                },
                actions = {
                    if (rol == "admin" && prestamo?.estado != "eliminado") {
                        IconButton(onClick = {
                            navController.navigate("EditarPrestamoScreen/$prestamoId/$uid/$rol")
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Editar", tint = Color.White)
                        }
                    }

                    if (rol == "admin" && prestamo?.estado != "eliminado") {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = Color.White)
                        }
                    }

                    IconButton(
                        onClick = { cargarPrestamo() },
                        enabled = !isLoading && !isProcessing
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = Color.White)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF0061A7),
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Cargando información...",
                        color = Color.Gray,
                        fontSize = 16.sp
                    )
                }
            }
        } else {
            prestamo?.let { p ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // ⭐⭐⭐ CARD DEL NÚMERO DE PRÉSTAMO (DESTACADO) ⭐⭐⭐
                    p.numeroPrestamo?.let { numero ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF1565C0)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                            shape = RoundedCornerShape(16.dp)
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
                                        "Número de Préstamo",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontWeight = FontWeight.Normal
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        numero,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Icon(
                                    Icons.Default.Tag,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when (p.estado.lowercase()) {
                                "activo" -> Color(0xFFE8F5E8)
                                "saldado" -> Color(0xFFE3F2FD)
                                "completado" -> Color(0xFFE3F2FD)
                                "vencido" -> Color(0xFFFFEBEE)
                                "eliminado" -> Color(0xFFFFF3E0)
                                else -> Color(0xFFF5F5F5)
                            }
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Estado del Préstamo",
                                    fontSize = 14.sp,
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    p.estado.uppercase(),
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (p.estado.lowercase()) {
                                        "activo" -> Color(0xFF4CAF50)
                                        "saldado" -> Color(0xFF2196F3)
                                        "completado" -> Color(0xFF2196F3)
                                        "vencido" -> Color(0xFFFF5722)
                                        "eliminado" -> Color(0xFFFF9800)
                                        else -> Color.Gray
                                    }
                                )
                            }

                            Icon(
                                when (p.estado.lowercase()) {
                                    "activo" -> Icons.Default.Schedule
                                    "saldado" -> Icons.Default.CheckCircle
                                    "completado" -> Icons.Default.CheckCircle
                                    "vencido" -> Icons.Default.Warning
                                    "eliminado" -> Icons.Default.Delete
                                    else -> Icons.Default.Info
                                },
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = when (p.estado.lowercase()) {
                                    "activo" -> Color(0xFF4CAF50)
                                    "saldado" -> Color(0xFF2196F3)
                                    "completado" -> Color(0xFF2196F3)
                                    "vencido" -> Color(0xFFFF5722)
                                    "eliminado" -> Color(0xFFFF9800)
                                    else -> Color.Gray
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    SectionCard(
                        title = "Información del Cliente",
                        icon = Icons.Default.Person
                    ) {
                        InfoRow("Nombre", p.cliente)
                        p.cedula?.let { InfoRow("Cédula", it) }
                        p.telefono?.let { InfoRow("Teléfono", it) }
                        p.direccion?.let { InfoRow("Dirección", it) }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SectionCard(
                        title = "Información Financiera",
                        icon = Icons.Default.AttachMoney
                    ) {
                        InfoRow("Monto prestado", "L. ${"%.2f".format(p.monto)}")
                        InfoRow("Interés mensual", "${"%.1f".format(p.interesMensual)}%")
                        InfoRow("Interés total", "L. ${"%.2f".format(p.interesTotal)}")
                        InfoRow("Total a pagar", "L. ${"%.2f".format(p.totalPagar)}")
                        InfoRow("Monto pagado", "L. ${"%.2f".format(p.montoPagado)}")
                        InfoRow(
                            "Saldo pendiente",
                            "L. ${"%.2f".format(p.saldoPendiente)}",
                            valueColor = if (p.saldoPendiente > 0) Color.Red else Color.Green
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SectionCard(
                        title = "Información de Cuotas",
                        icon = Icons.Default.Schedule
                    ) {
                        InfoRow("Número de cuotas", p.cuotas.toString())
                        InfoRow("Plazo", p.plazo)
                        InfoRow("Cuota", "L. ${"%.0f".format(p.cuota)}")
                        if (p.diasEfectivos > 0) {
                            InfoRow("Días efectivos", p.diasEfectivos.toString())
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SectionCard(
                        title = "Fechas Importantes",
                        icon = Icons.Default.CalendarToday
                    ) {
                        InfoRow("Fecha del préstamo", p.fecha)
                        InfoRow("Próximo pago", p.proximoPago)
                        InfoRow("Fecha de creación", p.fechaCreacion)
                        p.ultimaActualizacion?.let {
                            InfoRow("Última actualización", it)
                        }

                        // ⭐⭐⭐ MOSTRAR FECHA DE CANCELACIÓN SI EXISTE ⭐⭐⭐
                        if (p.estado.lowercase() == "saldado" || p.estado.lowercase() == "completado") {
                            p.fechaCancelacion?.let {
                                InfoRow(
                                    "Fecha de cancelación",
                                    it,
                                    valueColor = Color(0xFF4CAF50)  // Verde para destacar
                                )
                            } ?: run {
                                // Si está saldado pero no tiene fecha, mostrar advertencia
                                InfoRow(
                                    "Fecha de cancelación",
                                    "No registrada",
                                    valueColor = Color(0xFFFF9800)  // Naranja para advertencia
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SectionCard(
                        title = "Información Adicional",
                        icon = Icons.Default.Info
                    ) {
                        // ⭐ MOSTRAR NÚMERO DE PRÉSTAMO AQUÍ SOLO SI NO EXISTE
                        if (p.numeroPrestamo != null) {
                            InfoRow("Número de préstamo", p.numeroPrestamo)
                        } else {
                            InfoRow("Número de préstamo", "No asignado (préstamo antiguo)")
                        }
                        InfoRow("ID del préstamo", p.id)
                        InfoRow("Cobrador asignado", p.cobrador)
                        p.observaciones?.let {
                            InfoRow("Observaciones", it)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    if (rol.equals("admin", ignoreCase = true) && p.estado.lowercase() == "eliminado") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                ) {
                                    Icon(
                                        Icons.Default.AdminPanelSettings,
                                        contentDescription = null,
                                        tint = Color(0xFFFF9800),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        "Acciones de Administrador",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF9800)
                                    )
                                }

                                Text(
                                    "Este préstamo está en la sección de eliminados. Puede restaurarlo o eliminarlo definitivamente.",
                                    color = Color.Gray,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )

                                Button(
                                    onClick = { showRestoreDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = !isProcessing
                                ) {
                                    if (isProcessing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White
                                        )
                                    } else {
                                        Icon(Icons.Default.Restore, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Restaurar Préstamo", fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = { showDeleteForeverDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = !isProcessing
                                ) {
                                    if (isProcessing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White
                                        )
                                    } else {
                                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Eliminar Definitivamente", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Button(
                        onClick = {
                            try {
                                Log.d("VerPrestamoScreen", "=== NAVEGACIÓN A CUOTAS ===")
                                Log.d("VerPrestamoScreen", "Préstamo ID: '${p.id}'")
                                Log.d("VerPrestamoScreen", "UID: '$uid'")
                                Log.d("VerPrestamoScreen", "ROL: '$rol'")

                                if (p.id.isBlank() || uid.isBlank() || rol.isBlank()) {
                                    Toast.makeText(context, "Error: Faltan datos para navegar", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                val ruta = "CuotasPrestamoScreen/${p.id}/$uid/$rol"
                                Log.d("VerPrestamoScreen", "Navegando con ruta: '$ruta'")

                                navController.navigate(ruta)

                                Log.d("VerPrestamoScreen", "✅ Navegación ejecutada")

                            } catch (e: Exception) {
                                Log.e("VerPrestamoScreen", "❌ Error en navegación: ${e.message}", e)
                                Toast.makeText(context, "Error al navegar: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Default.List, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ver Cuotas del Préstamo", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color(0xFF0061A7),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0061A7)
                )
            }
            content()
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = Color.Black
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 15.sp,
            color = Color.Gray,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}