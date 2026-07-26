package com.example.minifinancieraapp.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.example.capitalexpressapp.core.coincideAproximado
import com.example.capitalexpressapp.core.formatearLempiras
import com.example.capitalexpressapp.util.hayInternet
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

// ✅ CACHE GLOBAL para evitar recálculos
private val prestamosClienteCache = ConcurrentHashMap<String, List<Map<String, Any>>>()

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
    var isLoading by remember { mutableStateOf(false) }
    var mostrarResumen by remember { mutableStateOf(false) }
    // ✅ Por defecto NO se trae toda la colección: se espera a que el usuario
    // busque o pida "Ver todos" para hacer la carga completa (una sola vez,
    // después queda en memoria y las búsquedas siguientes son instantáneas).
    var datosYaCargados by remember { mutableStateOf(false) }

    val opcionesEstado = listOf("Todos", "activo", "inactivo", "saldado")

    // ✅ FUNCIÓN ULTRA-OPTIMIZADA - REDUCCIÓN DE 20s A 3-5s
    suspend fun cargarClientesUltraRapido() {
        val tiempoInicio = System.currentTimeMillis()
        isLoading = true

        try {
            hayConexion.value = hayInternet(context)
            val source = if (hayConexion.value) Source.DEFAULT else Source.CACHE

            Log.d("ClientesOptimizado", "🚀 Iniciando carga ultra-optimizada...")

            // ✅ PASO 1: Cargar clientes y préstamos EN PARALELO
            val (clientesSnapshot, prestamosSnapshot) = withContext(Dispatchers.IO) {
                val clientesDeferred = async {
                    // ✅ FIX: Sin filtros — trae TODOS los clientes sin excepción.
                    // Antes se podía perder clientes con campos inesperados.
                    db.collection("clientes")
                        .get(source)
                        .await()
                }

                val prestamosDeferred = async {
                    db.collection("prestamos")
                        .get(source)
                        .await()
                }

                clientesDeferred.await() to prestamosDeferred.await()
            }

            Log.d("ClientesOptimizado", "📊 Clientes: ${clientesSnapshot.size()}, Préstamos: ${prestamosSnapshot.size()}")

            // ✅ PASO 2: Filtrar clientes por rol de forma eficiente
            val clientesFiltradosPorRol = withContext(Dispatchers.Default) {
                if (rol == "cobrador") {
                    clientesSnapshot.documents.filter { doc ->
                        val asignadoPrincipal = doc.getString("cobradorAsignado")?.trim() == uid.trim()
                        val asignadosMultiples = (doc.get("cobradoresAsignados") as? List<*>)
                            ?.mapNotNull { it?.toString()?.trim() }
                            ?.contains(uid.trim()) == true
                        asignadoPrincipal || asignadosMultiples
                    }
                } else {
                    clientesSnapshot.documents // admin ve absolutamente todos
                }
            }

            Log.d("ClientesOptimizado", "✅ Clientes filtrados: ${clientesFiltradosPorRol.size}")

            // ✅ PASO 3: Construir cache de préstamos por clienteId de forma ultra-eficiente
            val prestamosPorCliente = withContext(Dispatchers.Default) {
                val cache = mutableMapOf<String, MutableList<Map<String, Any>>>()

                for (prestamoDoc in prestamosSnapshot.documents) {
                    val clienteId = prestamoDoc.getString("clienteId") ?: continue

                    // Agregar campos importantes
                    val prestamoInfo = mapOf(
                        "monto" to (prestamoDoc.getDouble("monto") ?: 0.0),
                        "interesTotal" to (prestamoDoc.getDouble("interesTotal") ?: 0.0),
                        "saldo" to (prestamoDoc.getDouble("saldo") ?: 0.0),
                        "ultimoPago" to (prestamoDoc.getString("ultimoPago") ?: ""),
                        "estado" to (prestamoDoc.getString("estado") ?: "activo")
                    )

                    cache.getOrPut(clienteId) { mutableListOf() }.add(prestamoInfo)
                }

                cache as Map<String, List<Map<String, Any>>>
            }

            prestamosClienteCache.clear()
            prestamosClienteCache.putAll(prestamosPorCliente)

            Log.d("ClientesOptimizado", "💾 Cache construido para ${prestamosPorCliente.size} clientes con préstamos")

            // ✅ PASO 4: Procesar clientes en PARALELO con coroutines
            val clientesConPrestamos = withContext(Dispatchers.Default) {
                clientesFiltradosPorRol.chunked(20).flatMap { chunk ->
                    chunk.map { doc ->
                        async {
                            procesarClienteOptimizado(doc, prestamosPorCliente)
                        }
                    }.awaitAll()
                }
            }

            clientes = clientesConPrestamos

            val tiempoTotal = System.currentTimeMillis() - tiempoInicio
            Log.d("ClientesOptimizado", "✅ ¡Carga completada en ${tiempoTotal}ms! (${tiempoTotal / 1000.0}s)")
            Log.d("ClientesOptimizado", "📈 Velocidad: ${clientesConPrestamos.size} clientes procesados")

        } catch (e: Exception) {
            Log.e("ClientesOptimizado", "❌ Error: ${e.message}", e)
            Toast.makeText(context, "Error cargando clientes: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            isLoading = false
            datosYaCargados = true
        }
    }

    // ✅ FIX: "Todos" muestra absolutamente todos sin excluir ningún estado.
    // Búsqueda "inteligente": tolera errores de tipeo, nombre/apellido invertido
    // y segundo nombre salteado (ver BusquedaUtils.coincideAproximado).
    val clientesFiltrados = clientes.filter {
        (estadoSeleccionado == "Todos" || it.estado.equals(estadoSeleccionado, ignoreCase = true)) &&
                (search.isBlank() || coincideAproximado(search, it.nombre))
    }

    // Calcular estadísticas globales de forma eficiente
    val estadisticas = remember(clientesFiltrados) {
        calcularEstadisticasGlobales(clientesFiltrados)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Clientes Registrados",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0061A7)
                ),
                actions = {
                    IconButton(
                        onClick = {
                            val pref = "none"
                            val estado = "Todos"
                            navController.navigate("reporteClientes/$rol/$pref/$estado")
                        }
                    ) {
                        Icon(
                            Icons.Default.Assessment,
                            contentDescription = "Reporte de clientes",
                            tint = Color.White
                        )
                    }

                    IconButton(onClick = { mostrarResumen = !mostrarResumen }) {
                        Icon(
                            imageVector = if (mostrarResumen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (mostrarResumen) "Ocultar resumen" else "Mostrar resumen",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                cargarClientesUltraRapido()
                            }
                        },
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Actualizar",
                                tint = Color.White
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("crearCliente") },
                containerColor = Color(0xFF0061A7)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Nuevo Cliente",
                    tint = Color.White
                )
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
                    Text(
                        "Sin conexión - Modo offline",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Resumen compacto
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF0061A7)
                ),
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
                        value = estadisticas.totalClientes.toString(),
                        icon = Icons.Default.People,
                        color = Color.White
                    )
                    StatCardMini(
                        title = "Activos",
                        value = estadisticas.clientesActivos.toString(),
                        icon = Icons.Default.TrendingUp,
                        color = Color.White
                    )
                    StatCardMini(
                        title = "Pagos Tarde",
                        value = estadisticas.clientesConPagosTarde.toString(),
                        icon = Icons.Default.Warning,
                        color = if (estadisticas.clientesConPagosTarde > 0) Color(0xFFFFAB00) else Color.White
                    )
                    StatCardMini(
                        title = "Pendiente",
                        value = formatearLempiras(estadisticas.totalMontoPendiente),
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
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE8F5E9)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚡ Resumen Detallado",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                            Text(
                                text = "Ultra-rápido",
                                fontSize = 10.sp,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Con Préstamos", fontSize = 10.sp, color = Color.Gray)
                                Text(
                                    estadisticas.clientesConPrestamos.toString(),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Saldados", fontSize = 10.sp, color = Color.Gray)
                                Text(
                                    estadisticas.clientesSaldados.toString(),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Préstamos", fontSize = 10.sp, color = Color.Gray)
                                Text(
                                    estadisticas.totalPrestamosActivos.toString(),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Prestado", fontSize = 10.sp, color = Color.Gray)
                                Text(
                                    formatearLempiras(estadisticas.totalMontoPrestado),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Abonado", fontSize = 10.sp, color = Color.Gray)
                                Text(
                                    formatearLempiras(estadisticas.totalMontoAbonado),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
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
                    label = { Text("Buscar por nombre", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Limpiar", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        scope.launch { cargarClientesUltraRapido() }
                    })
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
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        opcionesEstado.forEach { opcion ->
                            DropdownMenuItem(
                                text = {
                                    Text(opcion.replaceFirstChar { it.uppercase() })
                                },
                                onClick = {
                                    estadoSeleccionado = opcion
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (!datosYaCargados && !isLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { scope.launch { cargarClientesUltraRapido() } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Buscar")
                    }
                    OutlinedButton(
                        onClick = { scope.launch { cargarClientesUltraRapido() } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Ver todos")
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Escribí un nombre y tocá \"Buscar\", o tocá \"Ver todos\" para la lista completa",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            color = Color(0xFF0061A7)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Cargando clientes...", color = Color.Gray)
                        Text(
                            "⚡ Optimizado para velocidad",
                            color = Color(0xFF2E7D32),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                // ✅ FIX: Muestra total real vs filtrados para detectar clientes ocultos
                Text(
                    text = "Mostrando ${clientesFiltrados.size} de ${clientes.size} clientes",
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
                    items(clientesFiltrados, key = { it.id }) { cliente ->
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
            text = {
                Text("¿Deseas marcar como inactivo a ${cliente.nombre}? No se eliminará su información.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            db.collection("clientes")
                                .document(cliente.id)
                                .update("estado", "inactivo")
                                .await()
                            Toast.makeText(context, "Cliente inactivado", Toast.LENGTH_SHORT).show()
                            clienteAInactivar = null
                            cargarClientesUltraRapido()
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

// ✅ FUNCIÓN PROCESAMIENTO ULTRA-OPTIMIZADO DE CLIENTE
private fun procesarClienteOptimizado(
    doc: DocumentSnapshot,
    prestamosPorCliente: Map<String, List<Map<String, Any>>>
): ClienteVistaModel {
    val clienteId = doc.id
    val prestamosCliente = prestamosPorCliente[clienteId] ?: emptyList()

    var totalPrestado = 0.0
    var totalAbonado = 0.0
    var totalPendiente = 0.0
    var ultimoPago = ""
    var estadoCliente = "activo"
    var prestamosActivos = 0
    var prestamosVencidos = 0
    var prestamosCompletados = 0
    val totalPrestamos = prestamosCliente.size
    val tienePrestamo = prestamosCliente.isNotEmpty()

    if (tienePrestamo) {
        var tienePrestamosSaldados = false
        var tienePrestamosNoSaldados = false

        for (prestamo in prestamosCliente) {
            val monto = (prestamo["monto"] as? Number)?.toDouble() ?: 0.0
            val interes = (prestamo["interesTotal"] as? Number)?.toDouble() ?: 0.0
            val saldo = (prestamo["saldo"] as? Number)?.toDouble() ?: 0.0
            val fechaPago = prestamo["ultimoPago"] as? String ?: ""
            val estadoPrestamo = (prestamo["estado"] as? String ?: "activo").lowercase()

            totalPrestado += monto + interes
            totalPendiente += saldo
            totalAbonado += (monto + interes) - saldo

            when (estadoPrestamo) {
                "activo" -> {
                    prestamosActivos++
                    tienePrestamosNoSaldados = true
                }
                "vencido" -> {
                    prestamosVencidos++
                    tienePrestamosNoSaldados = true
                }
                "completado", "saldado" -> {
                    prestamosCompletados++
                    tienePrestamosSaldados = true
                }
                else -> {
                    prestamosActivos++
                    tienePrestamosNoSaldados = true
                }
            }

            if (fechaPago.isNotBlank() && (ultimoPago.isBlank() || fechaPago > ultimoPago)) {
                ultimoPago = fechaPago
            }
        }

        estadoCliente = when {
            !tienePrestamosNoSaldados && tienePrestamosSaldados -> "saldado"
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
        // ✅ FIX: el estado de Firestore tiene prioridad (respeta "inactivo" guardado)
        estado = doc.getString("estado")?.takeIf { it.isNotBlank() } ?: estadoCliente,
        prestamosActivos = prestamosActivos,
        prestamosVencidos = prestamosVencidos,
        prestamosCompletados = prestamosCompletados,
        totalPrestamos = totalPrestamos,
        tienePagosTarde = doc.getBoolean("tienePagosTarde") ?: false
    )
}

// ✅ DATA CLASS PARA ESTADÍSTICAS
data class EstadisticasGlobales(
    val totalClientes: Int,
    val clientesConPrestamos: Int,
    val clientesActivos: Int,
    val clientesSaldados: Int,
    val clientesConPagosTarde: Int,
    val totalMontoPrestado: Double,
    val totalMontoAbonado: Double,
    val totalMontoPendiente: Double,
    val totalPrestamosActivos: Int
)

// ✅ FUNCIÓN OPTIMIZADA PARA CALCULAR ESTADÍSTICAS
private fun calcularEstadisticasGlobales(clientes: List<ClienteVistaModel>): EstadisticasGlobales {
    var totalClientes = 0
    var clientesConPrestamos = 0
    var clientesActivos = 0
    var clientesSaldados = 0
    var clientesConPagosTarde = 0
    var totalMontoPrestado = 0.0
    var totalMontoAbonado = 0.0
    var totalMontoPendiente = 0.0
    var totalPrestamosActivos = 0

    for (cliente in clientes) {
        totalClientes++
        if (cliente.tienePrestamo) clientesConPrestamos++
        if (cliente.estado.equals("activo", ignoreCase = true)) clientesActivos++
        if (cliente.estado.equals("saldado", ignoreCase = true)) clientesSaldados++
        if (cliente.tienePagosTarde) clientesConPagosTarde++

        totalMontoPrestado += cliente.monto
        totalMontoAbonado += cliente.totalAbonado
        totalMontoPendiente += cliente.saldoPendiente
        totalPrestamosActivos += cliente.prestamosActivos
    }

    return EstadisticasGlobales(
        totalClientes = totalClientes,
        clientesConPrestamos = clientesConPrestamos,
        clientesActivos = clientesActivos,
        clientesSaldados = clientesSaldados,
        clientesConPagosTarde = clientesConPagosTarde,
        totalMontoPrestado = totalMontoPrestado,
        totalMontoAbonado = totalMontoAbonado,
        totalMontoPendiente = totalMontoPendiente,
        totalPrestamosActivos = totalPrestamosActivos
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

                    Text("📞 ${cliente.telefono}", color = Color.Gray, fontSize = 13.sp)
                    if (cliente.nombreEmpresa.isNotBlank()) {
                        Text("🏢 ${cliente.nombreEmpresa}", color = Color.Gray, fontSize = 13.sp)
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
                            MiniStatItem("Prestado", formatearLempiras(cliente.monto), "💰")
                            MiniStatItem("Abonado", formatearLempiras(cliente.totalAbonado), "💵")
                            MiniStatItem("Pendiente", formatearLempiras(cliente.saldoPendiente), "⚠️")
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
                // ✅ FIX: Badge de estado visible con color según activo/inactivo/saldado
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when (cliente.estado.lowercase()) {
                            "saldado"  -> Color(0xFFE8F5E8)
                            "inactivo" -> Color(0xFFFFEBEE)
                            "activo"   -> Color(0xFFFFF3E0)
                            else       -> Color.LightGray
                        }
                    )
                ) {
                    Text(
                        text = when (cliente.estado.lowercase()) {
                            "activo"   -> "🟡 Activo"
                            "inactivo" -> "🔴 Inactivo"
                            "saldado"  -> "✅ Saldado"
                            else       -> "📍 ${cliente.estado.replaceFirstChar { it.uppercase() }}"
                        },
                        color = when (cliente.estado.lowercase()) {
                            "saldado"  -> Color(0xFF2E7D32)
                            "inactivo" -> Color(0xFFD32F2F)
                            "activo"   -> Color(0xFFFF8F00)
                            else       -> Color.Gray
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