package com.example.minifinancieraapp.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import coil.compose.rememberAsyncImagePainter
import com.example.capitalexpressapp.util.hayInternet
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientesVista(navController: NavController, uid: String, rol: String) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    val hayConexion = remember { mutableStateOf(true) }

    var clientes by remember { mutableStateOf(listOf<ClienteVistaModel>()) }
    var search by remember { mutableStateOf("") }
    var clienteAInactivar by remember { mutableStateOf<ClienteVistaModel?>(null) }
    var estadoSeleccionado by remember { mutableStateOf("Todos") }
    var expanded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var mostrarResumen by remember { mutableStateOf(false) }

    val opcionesEstado = listOf("Todos", "activo", "inactivo", "saldado")

    LaunchedEffect(Unit) {
        hayConexion.value = hayInternet(context)
        val source = if (hayConexion.value) Source.DEFAULT else Source.CACHE
        isLoading = true

        try {
            val clientesSnapshot = db.collection("clientes").get(source).await()

            val clientesFiltradosPorRol = if (rol == "cobrador") {
                clientesSnapshot.documents.filter { doc ->
                    val asignadoPrincipal = doc.getString("cobradorAsignado") == uid
                    val asignadosMultiples = (doc.get("cobradoresAsignados") as? List<*>)?.contains(uid) == true
                    asignadoPrincipal || asignadosMultiples
                }
            } else {
                clientesSnapshot.documents
            }

            val clientesConPrestamos = clientesFiltradosPorRol.map { doc ->
                val clienteId = doc.id
                val prestamosSnapshot = db.collection("prestamos")
                    .whereEqualTo("clienteId", clienteId)
                    .get(source)
                    .await()

                var totalPrestado = 0.0
                var totalAbonado = 0.0
                var totalPendiente = 0.0
                var ultimoPago = ""
                var estadoCliente = "activo"
                var prestamosActivos = 0
                var prestamosVencidos = 0
                var prestamosCompletados = 0
                val totalPrestamos = prestamosSnapshot.documents.size
                val tienePrestamo = prestamosSnapshot.documents.isNotEmpty()

                if (tienePrestamo) {
                    for (prestamoDoc in prestamosSnapshot.documents) {
                        val monto = prestamoDoc.getDouble("monto") ?: 0.0
                        val interes = prestamoDoc.getDouble("interesTotal") ?: 0.0
                        val saldo = prestamoDoc.getDouble("saldo") ?: 0.0
                        val fechaPago = prestamoDoc.getString("ultimoPago") ?: ""
                        val estadoPrestamo = prestamoDoc.getString("estado") ?: "activo"

                        totalPrestado += monto + interes
                        totalPendiente += saldo
                        totalAbonado += (monto + interes) - saldo

                        when (estadoPrestamo.lowercase()) {
                            "activo" -> prestamosActivos++
                            "vencido" -> prestamosVencidos++
                            "completado", "saldado" -> prestamosCompletados++
                            else -> prestamosActivos++
                        }

                        if (fechaPago.isNotBlank()) {
                            if (ultimoPago.isBlank() || fechaPago > ultimoPago) {
                                ultimoPago = fechaPago
                            }
                        }
                    }

                    val prestamosNoSaldados = prestamosSnapshot.documents.filter {
                        val estado = it.getString("estado") ?: "activo"
                        estado.lowercase() != "saldado" && estado.lowercase() != "completado"
                    }

                    val prestamosSaldados = prestamosSnapshot.documents.filter {
                        val estado = it.getString("estado") ?: "activo"
                        estado.lowercase() == "saldado" || estado.lowercase() == "completado"
                    }

                    estadoCliente = when {
                        prestamosNoSaldados.isEmpty() && prestamosSaldados.isNotEmpty() -> "saldado"
                        totalPendiente > 0 -> "activo"
                        else -> "activo"
                    }
                }

                ClienteVistaModel(
                    id = clienteId,
                    nombre = doc.getString("nombre") ?: "",
                    telefono = doc.getString("telefono") ?: "",
                    identidad = doc.getString("identidad") ?: "",
                    direccion = doc.getString("direccionCasa") ?: "",
                    nombreEmpresa = doc.getString("nombreEmpresa") ?: "",
                    tienePrestamo = tienePrestamo,
                    monto = totalPrestado,
                    fotoPersona = doc.getString("fotoPersonaUrl") ?: "",
                    cobradorAsignado = doc.getString("cobradorAsignado") ?: "",
                    totalAbonado = totalAbonado,
                    saldoPendiente = totalPendiente,
                    ultimoPago = if (ultimoPago.isNotBlank()) ultimoPago else "Sin pagos",
                    estado = doc.getString("estado") ?: estadoCliente,
                    prestamosActivos = prestamosActivos,
                    prestamosVencidos = prestamosVencidos,
                    prestamosCompletados = prestamosCompletados,
                    totalPrestamos = totalPrestamos,
                    tienePagosTarde = doc.getBoolean("tienePagosTarde") ?: false
                )
            }

            clientes = clientesConPrestamos

        } catch (e: Exception) {
            Toast.makeText(context, "Error cargando clientes: ${e.message}", Toast.LENGTH_LONG)
                .show()
        } finally {
            isLoading = false
        }
    }

    val clientesFiltrados = clientes.filter {
        (estadoSeleccionado == "Todos" || it.estado.equals(
            estadoSeleccionado,
            ignoreCase = true
        )) &&
                it.nombre.contains(search, ignoreCase = true)
    }

    // Calcular estadísticas globales
    val totalClientes = clientesFiltrados.size
    val clientesConPrestamos = clientesFiltrados.count { it.tienePrestamo }
    val clientesActivos = clientesFiltrados.count { it.estado.equals("activo", ignoreCase = true) }
    val clientesSaldados = clientesFiltrados.count { it.estado.equals("saldado", ignoreCase = true) }
    val clientesConPagosTarde = clientesFiltrados.count { it.tienePagosTarde }
    val totalMontoPrestado = clientesFiltrados.sumOf { it.monto }
    val totalMontoAbonado = clientesFiltrados.sumOf { it.totalAbonado }
    val totalMontoPendiente = clientesFiltrados.sumOf { it.saldoPendiente }
    val totalPrestamosActivos = clientesFiltrados.sumOf { it.prestamosActivos }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clientes Registrados", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0061A7)),
                actions = {
                    // 🔹 Botón Reporte con filtros (estado actual + cobrador si rol = cobrador)
                    IconButton(
                        onClick = {
                            val pref = "none"      // o el id real del cobrador seleccionado
                            val estado = "Todos"   // o "Activos" / "Saldados" si prefieres
                            navController.navigate("reporteClientes/$rol/$pref/$estado")
                        }
                    ) {
                        Icon(Icons.Default.Assessment, contentDescription = "Reporte de clientes", tint = Color.White)
                    }

                    // Botón para mostrar/ocultar resumen
                    IconButton(onClick = { mostrarResumen = !mostrarResumen }) {
                        Icon(
                            if (mostrarResumen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (mostrarResumen) "Ocultar resumen" else "Mostrar resumen",
                            tint = Color.White
                        )
                    }

                    // Refresh
                    IconButton(onClick = {
                        scope.launch {
                            isLoading = true
                            try {
                                val hayConexionActual = hayInternet(context)
                                val source = if (hayConexionActual) Source.DEFAULT else Source.CACHE

                                val clientesSnapshot = db.collection("clientes").get(source).await()

                                val clientesFiltradosPorRol = if (rol == "cobrador") {
                                    clientesSnapshot.documents.filter { doc ->
                                        val asignadoPrincipal = doc.getString("cobradorAsignado")?.trim() == uid.trim()
                                        val asignadosMultiples = (doc.get("cobradoresAsignados") as? List<*>)
                                            ?.mapNotNull { it?.toString()?.trim() }
                                            ?.contains(uid.trim()) == true
                                        asignadoPrincipal || asignadosMultiples
                                    }
                                } else {
                                    clientesSnapshot.documents
                                }

                                val clientesConPrestamos = clientesFiltradosPorRol.map { doc ->
                                    calcularDatosCliente(db, doc, source)
                                }

                                clientes = clientesConPrestamos

                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al actualizar: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isLoading = false
                            }
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = Color.White)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("crearCliente") }, containerColor = Color(0xFF0061A7)) {
                Icon(Icons.Default.Add, contentDescription = "Nuevo Cliente", tint = Color.White)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (!hayConexion.value) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Red)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Sin conexión", color = Color.White, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Resumen compacto
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0061A7)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatCardMini(
                        title = "Total",
                        value = totalClientes.toString(),
                        icon = Icons.Default.People,
                        color = Color.White
                    )
                    StatCardMini(
                        title = "Activos",
                        value = clientesActivos.toString(),
                        icon = Icons.Default.TrendingUp,
                        color = Color.White
                    )
                    StatCardMini(
                        title = "Pagos Tarde",
                        value = clientesConPagosTarde.toString(),
                        icon = Icons.Default.Warning,
                        color = if (clientesConPagosTarde > 0) Color(0xFFFFAB00) else Color.White
                    )
                    StatCardMini(
                        title = "Pendiente",
                        value = "L.${String.format("%.0f", totalMontoPendiente)}",
                        icon = Icons.Default.PendingActions,
                        color = Color.White
                    )
                }
            }

            // Resumen detallado desplegable
            if (mostrarResumen) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Resumen Detallado",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Con Préstamos", fontSize = 10.sp, color = Color.Gray)
                                Text(clientesConPrestamos.toString(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Saldados", fontSize = 10.sp, color = Color.Gray)
                                Text(clientesSaldados.toString(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Préstamos", fontSize = 10.sp, color = Color.Gray)
                                Text(totalPrestamosActivos.toString(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Prestado", fontSize = 10.sp, color = Color.Gray)
                                Text("L.${String.format("%.0f", totalMontoPrestado)}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Abonado", fontSize = 10.sp, color = Color.Gray)
                                Text("L.${String.format("%.0f", totalMontoAbonado)}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Filtros compactos
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Buscar", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    singleLine = true
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.weight(0.8f)
                ) {
                    OutlinedTextField(
                        value = estadoSeleccionado,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Estado", fontSize = 12.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        opcionesEstado.forEach { opcion ->
                            DropdownMenuItem(
                                text = { Text(opcion.replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    estadoSeleccionado = opcion
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    text = "Mostrando ${clientesFiltrados.size} clientes",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0061A7),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(clientesFiltrados) { cliente ->
                        ClienteCardMejorado(
                            cliente = cliente,
                            navController = navController,
                            context = context,
                            scope = scope,
                            uid = uid,
                            rol = rol,
                            onEliminar = { clienteAInactivar = it }
                        )
                    }
                }
            }
        }
    }

    clienteAInactivar?.let { cliente ->
        AlertDialog(
            onDismissRequest = { clienteAInactivar = null },
            title = { Text("¿Inactivar cliente?") },
            text = { Text("¿Deseas marcar como inactivo a ${cliente.nombre}? No se eliminará su información.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            db.collection("clientes").document(cliente.id).update("estado", "inactivo").await()
                            Toast.makeText(context, "Cliente inactivado", Toast.LENGTH_SHORT).show()
                            clienteAInactivar = null
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error al inactivar", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text("Inactivar", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { clienteAInactivar = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

// =====================
// Helpers y UI Reusables
// =====================

suspend fun calcularDatosCliente(
    db: FirebaseFirestore,
    doc: com.google.firebase.firestore.DocumentSnapshot,
    source: Source
): ClienteVistaModel {
    val clienteId = doc.id
    val prestamosSnapshot = db.collection("prestamos")
        .whereEqualTo("clienteId", clienteId)
        .get(source)
        .await()

    var totalPrestado = 0.0
    var totalAbonado = 0.0
    var totalPendiente = 0.0
    var ultimoPago = ""
    var estadoCliente = "activo"
    var prestamosActivos = 0
    var prestamosVencidos = 0
    var prestamosCompletados = 0
    val totalPrestamos = prestamosSnapshot.documents.size
    val tienePrestamo = prestamosSnapshot.documents.isNotEmpty()

    if (tienePrestamo) {
        for (prestamoDoc in prestamosSnapshot.documents) {
            val monto = prestamoDoc.getDouble("monto") ?: 0.0
            val interes = prestamoDoc.getDouble("interesTotal") ?: 0.0
            val saldo = prestamoDoc.getDouble("saldo") ?: 0.0
            val fechaPago = prestamoDoc.getString("ultimoPago") ?: ""
            val estadoPrestamo = prestamoDoc.getString("estado") ?: "activo"

            totalPrestado += monto + interes
            totalPendiente += saldo
            totalAbonado += (monto + interes) - saldo

            when (estadoPrestamo.lowercase()) {
                "activo" -> prestamosActivos++
                "vencido" -> prestamosVencidos++
                "completado", "saldado" -> prestamosCompletados++
                else -> prestamosActivos++
            }

            if (fechaPago.isNotBlank() && (ultimoPago.isBlank() || fechaPago > ultimoPago)) {
                ultimoPago = fechaPago
            }
        }

        val prestamosNoSaldados = prestamosSnapshot.documents.filter {
            val estado = it.getString("estado") ?: "activo"
            estado.lowercase() !in listOf("saldado", "completado")
        }

        val prestamosSaldados = prestamosSnapshot.documents.filter {
            val estado = it.getString("estado") ?: "activo"
            estado.lowercase() in listOf("saldado", "completado")
        }

        estadoCliente = when {
            prestamosNoSaldados.isEmpty() && prestamosSaldados.isNotEmpty() -> "saldado"
            totalPendiente > 0 -> "activo"
            else -> "activo"
        }
    }

    return ClienteVistaModel(
        id = clienteId,
        nombre = doc.getString("nombre") ?: "",
        telefono = doc.getString("telefono") ?: "",
        identidad = doc.getString("identidad") ?: "",
        direccion = doc.getString("direccionCasa") ?: "",
        nombreEmpresa = doc.getString("nombreEmpresa") ?: "",
        tienePrestamo = tienePrestamo,
        monto = totalPrestado,
        fotoPersona = doc.getString("fotoPersonaUrl") ?: "",
        cobradorAsignado = doc.getString("cobradorAsignado") ?: "",
        totalAbonado = totalAbonado,
        saldoPendiente = totalPendiente,
        ultimoPago = if (ultimoPago.isNotBlank()) ultimoPago else "Sin pagos",
        estado = doc.getString("estado") ?: estadoCliente,
        prestamosActivos = prestamosActivos,
        prestamosVencidos = prestamosVencidos,
        prestamosCompletados = prestamosCompletados,
        totalPrestamos = totalPrestamos,
        tienePagosTarde = doc.getBoolean("tienePagosTarde") ?: false
    )
}

@Composable
fun StatCardMini(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = title,
            color = color,
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ClienteCardMejorado(
    cliente: ClienteVistaModel,
    navController: NavController,
    context: Context,
    scope: CoroutineScope,
    uid: String,
    rol: String,
    onEliminar: (ClienteVistaModel) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                navController.navigate("PerfilClienteScreen/${cliente.id}/admin")
            },
        colors = CardDefaults.cardColors(
            containerColor = when {
                cliente.estado.equals("inactivo", ignoreCase = true) -> Color(0xFFFFEBEE)
                cliente.estado.equals("saldado", ignoreCase = true) -> Color(0xFFE8F5E8)
                cliente.saldoPendiente > 0 -> Color(0xFFFFF3E0)
                else -> Color.White
            }
        ),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = cliente.nombre.uppercase(),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        if (cliente.tienePagosTarde) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Cliente con pagos tardíos",
                                tint = Color.Red,
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(start = 4.dp)
                            )
                        }
                    }

                    Text("📞 ${cliente.telefono}", color = Color.Gray)
                    if (cliente.nombreEmpresa.isNotBlank()) {
                        Text("🏢 ${cliente.nombreEmpresa}", color = Color.Gray)
                    }
                }

                if (cliente.fotoPersona.isNotBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(cliente.fotoPersona),
                        contentDescription = "Foto del cliente",
                        modifier = Modifier.size(64.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color.LightGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Sin foto",
                            tint = Color.Gray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (cliente.tienePrestamo) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0061A7))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "📊 Resumen de Préstamos",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            MiniStatItem("Total", cliente.totalPrestamos.toString(), "📋")
                            MiniStatItem("Activos", cliente.prestamosActivos.toString(), "⏳")
                            MiniStatItem("Completados", cliente.prestamosCompletados.toString(), "✅")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            MiniStatItem("Prestado", "L. ${String.format("%.0f", cliente.monto)}", "💰")
                            MiniStatItem("Abonado", "L. ${String.format("%.0f", cliente.totalAbonado)}", "💵")
                            MiniStatItem("Pendiente", "L. ${String.format("%.0f", cliente.saldoPendiente)}", "⚠️")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "📅 Último pago: ${cliente.ultimoPago}",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )

                        if (cliente.prestamosVencidos > 0) {
                            Text(
                                text = "🚨 Préstamos vencidos: ${cliente.prestamosVencidos}",
                                color = Color.Red,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (cliente.tienePagosTarde) {
                            Text(
                                text = "⚠️ Este cliente tiene historial de pagos tardíos",
                                color = Color(0xFFD84315),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Sin préstamos registrados",
                    color = Color.Gray,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier.padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (cliente.estado.lowercase()) {
                            "saldado" -> Color(0xFFE8F5E8)
                            "inactivo" -> Color(0xFFFFEBEE)
                            "activo" -> Color(0xFFFFF3E0)
                            else -> Color.LightGray
                        }
                    )
                ) {
                    Text(
                        text = "📍 ${cliente.estado.replaceFirstChar { it.uppercase() }}",
                        color = when (cliente.estado.lowercase()) {
                            "saldado" -> Color(0xFF2E7D32)
                            "inactivo" -> Color(0xFFD32F2F)
                            "activo" -> Color(0xFFFF8F00)
                            else -> Color.Gray
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                Row {
                    IconButton(onClick = {
                        navController.navigate("EditarClienteScreen/${cliente.id}")
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = Color(0xFF0061A7))
                    }

                    IconButton(onClick = { onEliminar(cliente) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = Color.Red)
                    }

                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_DIAL).apply {
                            data = Uri.parse("tel:+504${cliente.telefono.filter { it.isDigit() }}")
                        }
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.Phone, contentDescription = "Llamar", tint = Color(0xFF388E3C))
                    }

                    IconButton(onClick = {
                        val numeroLimpio = cliente.telefono.filter { it.isDigit() }
                        val numeroWhatsApp = if (numeroLimpio.length == 8) "504$numeroLimpio" else numeroLimpio
                        val mensaje = "Buen día, le hablamos de Capital Express, estimado/a ${cliente.nombre}."
                        val url = "https://wa.me/$numeroWhatsApp?text=${Uri.encode(mensaje)}"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "WhatsApp no está instalado o el número es inválido", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Send, contentDescription = "WhatsApp", tint = Color(0xFF25D366))
                    }
                }
            }
        }
    }
}

@Composable
fun MiniStatItem(title: String, value: String, emoji: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(2.dp)
    ) {
        Text(
            text = "$emoji $value",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = title,
            color = Color.White,
            fontSize = 9.sp
        )
    }
}

data class ClienteVistaModel(
    val id: String,
    val nombre: String,
    val telefono: String,
    val identidad: String,
    val direccion: String,
    val nombreEmpresa: String,
    val tienePrestamo: Boolean,
    val monto: Double,
    val fotoPersona: String,
    val cobradorAsignado: String,
    val totalAbonado: Double,
    val saldoPendiente: Double,
    val ultimoPago: String,
    val estado: String,
    val prestamosActivos: Int = 0,
    val prestamosVencidos: Int = 0,
    val prestamosCompletados: Int = 0,
    val totalPrestamos: Int = 0,
    val tienePagosTarde: Boolean = false
)
