package com.example.capitalexpressapp.ui.screens

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
    val ultimaActualizacion: String?
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
                val doc = db.collection("prestamos").document(prestamoId).get().await()

                if (doc.exists()) {
                    val data = doc.data!!

                    // Lógica de estado corregida
                    val estadoOriginal = (data["estado"] as? String ?: "activo").lowercase()
                    val eliminado = data["eliminado"] as? Boolean ?: false
                    val estado = if (eliminado) "eliminado" else estadoOriginal

                    val cliente = data["cliente"] as? String ?: "Cliente desconocido"
                    val monto = (data["monto"] as? Number)?.toDouble() ?: 0.0
                    val totalPagar = (data["totalPagar"] as? Number)?.toDouble() ?: 0.0
                    val montoPagado = (data["montoPagado"] as? Number)?.toDouble() ?: 0.0
                    val saldoPendiente = totalPagar - montoPagado
                    val cuota = (data["cuota"] as? Number)?.toDouble() ?: 0.0
                    val cuotas = (data["cuotas"] as? Number)?.toInt() ?: 0
                    val plazo = data["plazo"] as? String ?: "No especificado"

                    val cobrador = (data["cobrador"] as? String)
                        ?: (data["cobradorAsignado"] as? String) ?: "No asignado"

                    val interesMensual = (data["interesMensual"] as? Number)?.toDouble()
                        ?: (data["interes"] as? Number)?.toDouble() ?: 0.0
                    val diasEfectivos = (data["diasEfectivos"] as? Number)?.toInt() ?: 0
                    val interesTotal = (data["interesTotal"] as? Number)?.toDouble()
                        ?: (data["interes"] as? Number)?.toDouble() ?: 0.0

                    val observaciones = data["observaciones"] as? String
                    val telefono = data["telefono"] as? String
                    val direccion = data["direccion"] as? String
                    val cedula = data["cedula"] as? String
                    clienteId = data["clienteId"]?.toString() ?: ""

                    val fecha = when (val f = data["fecha"] ?: data["fechaCreacion"]) {
                        is Timestamp -> dateFormatter.format(f.toDate())
                        is String -> f
                        else -> "Sin fecha"
                    }

                    val proximoPago = when (val p = data["proximoPago"]) {
                        is Timestamp -> dateFormatter.format(p.toDate())
                        is String -> p
                        else -> "Sin definir"
                    }

                    val fechaCreacion = when (val fc = data["fechaCreacion"]) {
                        is Timestamp -> formatter.format(fc.toDate())
                        is String -> fc
                        else -> "Sin fecha"
                    }

                    val ultimaActualizacion = when (val ua = data["ultimaActualizacion"]) {
                        is Timestamp -> formatter.format(ua.toDate())
                        is String -> ua
                        else -> null
                    }

                    prestamo = PrestamoDetalle(
                        id = prestamoId,
                        clienteId = clienteId,
                        cliente = cliente,
                        monto = monto,
                        totalPagar = totalPagar,
                        montoPagado = montoPagado,
                        saldoPendiente = saldoPendiente,
                        cuota = cuota,
                        cuotas = cuotas,
                        plazo = plazo,
                        fecha = fecha,
                        proximoPago = proximoPago,
                        estado = estado,
                        cobrador = cobrador,
                        interesMensual = interesMensual,
                        diasEfectivos = diasEfectivos,
                        interesTotal = interesTotal,
                        observaciones = observaciones,
                        telefono = telefono,
                        direccion = direccion,
                        cedula = cedula,
                        fechaCreacion = fechaCreacion,
                        ultimaActualizacion = ultimaActualizacion
                    )
                } else {
                    Toast.makeText(context, "Préstamo no encontrado", Toast.LENGTH_SHORT).show()
                    navController.popBackStack()
                    navController.previousBackStackEntry?.savedStateHandle?.set("refreshHistorial", true)
                }
                isLoading = false
            } catch (e: Exception) {
                Toast.makeText(context, "Error al cargar el préstamo: ${e.message}", Toast.LENGTH_LONG).show()
                isLoading = false
            }
        }
    }

    fun eliminarPrestamo() {
        scope.launch {
            isProcessing = true
            try {
                val docRef = db.collection("prestamos").document(prestamoId)

                // ✅ Actualiza directamente el documento, no solo en transacción
                val snapshot = docRef.get().await()
                if (snapshot.exists()) {
                    val estadoActual = snapshot.getString("estado") ?: "activo"

                    val updates = mapOf(
                        "estadoAnterior" to estadoActual,
                        "estado" to "eliminado",           // ✅ CAMBIAMOS EL ESTADO AQUÍ
                        "eliminado" to true,
                        "eliminadoPor" to uid,
                        "fechaEliminacion" to Timestamp.now(),
                        "ultimaActualizacion" to Timestamp.now()
                    )

                    docRef.update(updates).await()  // ✅ Asegura que Firestore lo guarda bien
                }

                Toast.makeText(context, "Préstamo movido a eliminados", Toast.LENGTH_SHORT).show()

                // ✅ Refrescar UI
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
                    Text(
                        "Detalle del Préstamo",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when (p.estado.lowercase()) {
                                "activo" -> Color(0xFFE8F5E8)
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
                                    "completado" -> Icons.Default.CheckCircle
                                    "vencido" -> Icons.Default.Warning
                                    "eliminado" -> Icons.Default.Delete
                                    else -> Icons.Default.Info
                                },
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = when (p.estado.lowercase()) {
                                    "activo" -> Color(0xFF4CAF50)
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
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SectionCard(
                        title = "Información Adicional",
                        icon = Icons.Default.Info
                    ) {
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
                        onClick = { navController.navigate("CuotasPrestamoScreen/${p.id}/$uid/$rol") },
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