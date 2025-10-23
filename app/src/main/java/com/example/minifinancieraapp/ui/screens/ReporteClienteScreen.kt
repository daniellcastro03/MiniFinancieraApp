package com.example.capitalexpressapp.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.capitalexpressapp.util.ReciboHelper.generarReporteClientesPDF
import com.example.minifinancieraapp.ui.models.ClienteModel
import com.example.minifinancieraapp.ui.models.Prestamo
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

enum class EstadoClienteFiltro { TODOS, ACTIVOS, SALDADOS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReporteClientesScreen(
    navController: NavController,
    rol: String = "admin"
) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var clientes by remember { mutableStateOf<List<ClienteModel>>(emptyList()) }
    var prestamosPorCliente by remember { mutableStateOf<Map<String, List<Prestamo>>>(emptyMap()) }

    // filtros
    var filtroEstado by remember { mutableStateOf(EstadoClienteFiltro.TODOS) }
    var cobradoresMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // uid -> nombre
    var filtroCobradorUid by remember { mutableStateOf<String?>(null) }

    // ---------- helpers de nombres ----------
    suspend fun extraerNombre(doc: DocumentSnapshot?): String? {
        if (doc == null || !doc.exists()) return null
        val keysPosibles = listOf(
            "nombre", "name", "displayName", "fullName",
            "username", "usuario", "alias"
        )
        for (k in keysPosibles) {
            doc.getString(k)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // fallback a correo si no hay nombre
        return doc.getString("email") ?: doc.getString("correo")
    }

    suspend fun buscarDocPorUid(
        db: FirebaseFirestore,
        coleccion: String,
        uid: String
    ): DocumentSnapshot? {
        // 1) doc con id == uid
        val byId = db.collection(coleccion).document(uid).get().await()
        if (byId.exists()) return byId
        // 2) doc con campo uid == uid
        val byField = db.collection(coleccion).whereEqualTo("uid", uid).limit(1).get().await()
        return byField.documents.firstOrNull()
    }

    suspend fun cargarNombreCobrador(db: FirebaseFirestore, uid: String): String {
        val colecciones = listOf("users", "usuarios", "cobradores")
        for (c in colecciones) {
            runCatching { buscarDocPorUid(db, c, uid) }.onSuccess { found ->
                val nombre = extraerNombre(found)
                if (!nombre.isNullOrBlank()) return nombre
            }.onFailure {
                Log.w("ReporteClientes", "Error buscando en $c: ${it.message}")
            }
        }
        // último fallback: mostrar parte del UID para que no sea tan largo
        return if (uid.length > 8) uid.take(8) + "…" else uid
    }
    // ---------------------------------------

    // Helper para obtener nombre de cobrador de manera segura
    fun nombreCobrador(uid: String?): String {
        if (uid.isNullOrBlank() || uid == "Sin asignar") return "Todos los cobradores"
        return cobradoresMap[uid] ?: run {
            Log.w("ReporteClientes", "Nombre no encontrado para uid: $uid")
            if (uid.length > 8) "${uid.take(8)}…" else uid
        }
    }

    // Cargar datos
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                isLoading = true

                // clientes
                val csnap = db.collection("clientes").get().await()
                val listaClientes = csnap.documents.mapNotNull { doc ->
                    ClienteModel(
                        id = doc.getString("id") ?: doc.id,
                        nombre = doc.getString("nombre") ?: return@mapNotNull null,
                        identidad = doc.getString("identidad") ?: "",
                        telefono = doc.getString("telefono") ?: "",
                        direccionCasa = doc.getString("direccionCasa") ?: "",
                        direccionNegocio = doc.getString("direccionNegocio") ?: "",
                        estadoCivil = doc.getString("estadoCivil") ?: "",
                        nombreConyuge = doc.getString("nombreConyuge") ?: "",
                        identidadConyuge = doc.getString("identidadConyuge") ?: "",
                        telefonoConyuge = doc.getString("telefonoConyuge") ?: "",
                        referencia1Nombre = doc.getString("referencia1Nombre") ?: "",
                        referencia1Identidad = doc.getString("referencia1Identidad") ?: "",
                        referencia1Telefono = doc.getString("referencia1Telefono") ?: "",
                        referencia1Parentesco = doc.getString("referencia1Parentesco") ?: "",
                        referencia1Direccion = doc.getString("referencia1Direccion") ?: "",
                        referencia2Nombre = doc.getString("referencia2Nombre") ?: "",
                        referencia2Identidad = doc.getString("referencia2Identidad") ?: "",
                        referencia2Telefono = doc.getString("referencia2Telefono") ?: "",
                        referencia2Parentesco = doc.getString("referencia2Parentesco") ?: "",
                        referencia2Direccion = doc.getString("referencia2Direccion") ?: "",
                        fotoCasaUrl = doc.getString("fotoCasaUrl") ?: "",
                        fotoNegocioUrl = doc.getString("fotoNegocioUrl") ?: "",
                        fotoClienteUrl = doc.getString("fotoClienteUrl") ?: "",
                        fotoIdentidadFrenteUrl = doc.getString("fotoIdentidadFrenteUrl") ?: "",
                        fotoIdentidadReversoUrl = doc.getString("fotoIdentidadReversoUrl") ?: "",
                        fotoReciboLuzUrl = doc.getString("fotoReciboLuzUrl") ?: "",
                        garantiaTexto = doc.getString("garantiaTexto") ?: "",
                        garantiaFotoUrl = doc.getString("garantiaFotoUrl") ?: "",
                        estado = doc.getString("estado") ?: "activo",
                        tienePrestamo = doc.getBoolean("tienePrestamo") ?: false,
                        cobradorAsignado = doc.getString("cobradorAsignado") ?: "Sin asignar"
                    )
                }
                clientes = listaClientes

                // préstamos por cliente
                val psnap = db.collection("prestamos").get().await()
                prestamosPorCliente = psnap.documents.groupBy(
                    keySelector = { it.getString("clienteId") ?: "" },
                    valueTransform = { doc ->
                        fun parseTS(value: Any?): Timestamp? = when (value) {
                            is Timestamp -> value
                            is String -> runCatching {
                                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                sdf.parse(value)?.let { Timestamp(it) }
                            }.getOrNull()
                            else -> null
                        }
                        Prestamo(
                            monto = doc.getDouble("monto") ?: 0.0,
                            interes = doc.getDouble("interes") ?: 0.0,
                            saldo = doc.getDouble("saldo") ?: 0.0,
                            totalPagar = doc.getDouble("totalPagar") ?: 0.0,
                            montoPagado = doc.getDouble("montoPagado") ?: 0.0,
                            estado = doc.getString("estado") ?: "",
                            interesMensual = doc.getDouble("interesMensual"),
                            interesTotal = doc.getDouble("interesTotal"),
                            cuota = doc.getDouble("cuota"),
                            lugar = doc.getString("lugar"),
                            numeroPrestamo = doc.getLong("numeroPrestamo")?.toInt(),
                            garantia = doc.getString("garantia"),
                            eliminado = doc.getBoolean("eliminado") ?: false,
                            saldoAnterior = doc.getDouble("saldoAnterior"),
                            cobradorAsignado = doc.getString("cobradorAsignado"),
                            cuotas = doc.getLong("cuotas")?.toInt(),
                            prestamoId = doc.getString("prestamoId") ?: doc.id,
                            mora = doc.getDouble("mora") ?: 0.0,
                            interesManual = doc.getDouble("interesManual"),
                            pagos = doc.getDouble("pagos") ?: 0.0,
                            firma = doc.getString("firma"),
                            observaciones = doc.getString("observaciones"),
                            fechaCreacion = parseTS(doc.get("fechaCreacion")),
                            fechaUltimaActualizacion = parseTS(doc.get("fechaUltimaActualizacion")),
                            fotos = (doc.get("fotos") as? List<String>) ?: emptyList(),
                            proximoPago = parseTS(doc.get("proximoPago"))
                        )
                    }
                ).mapValues { it.value.filterNotNull() }

                // nombres de cobradores (uid -> nombre)
                // Obtener TODOS los UIDs únicos (de clientes y préstamos)
                val uidsClientes = clientes.map { it.cobradorAsignado }
                val uidsPrestamos = prestamosPorCliente.values.flatten()
                    .mapNotNull { it.cobradorAsignado }

                val uids = (uidsClientes + uidsPrestamos)
                    .distinct()
                    .filter { it.isNotBlank() && it != "Sin asignar" }

                val nombres = mutableMapOf<String, String>()
                for (uid in uids) {
                    val nombre = runCatching { cargarNombreCobrador(db, uid) }
                        .onFailure { Log.w("ReporteClientes", "cargarNombreCobrador($uid): ${it.message}") }
                        .getOrDefault(if (uid.length > 8) uid.take(8) + "…" else uid)
                    nombres[uid] = nombre
                    Log.d("ReporteClientes", "Cobrador cargado: $uid -> $nombre")
                }
                // ordenar por nombre
                cobradoresMap = nombres.toList()
                    .sortedBy { it.second.lowercase(Locale.getDefault()) }
                    .toMap()

                Log.d("ReporteClientes", "Total cobradores cargados: ${cobradoresMap.size}")

            } catch (e: Exception) {
                Log.e("ReporteClientes", "Error: ${e.message}", e)
                Toast.makeText(context, "Error cargando datos: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
            }
        }
    }

    fun estadoEfectivo(cliente: ClienteModel): String {
        val prs = prestamosPorCliente[cliente.id].orEmpty()
        val todosSaldados = prs.isNotEmpty() && prs.all { p ->
            val total = (p.totalPagar ?: 0.0).takeIf { it > 0 } ?: (p.monto + (p.interesTotal ?: p.interes))
            val pagado = p.montoPagado ?: 0.0
            (total - pagado) <= 0.01 ||
                    p.estado.equals("saldado", true) ||
                    p.estado.equals("completado", true)
        }
        return when {
            todosSaldados || cliente.estado.equals("saldado", true) -> "saldado"
            cliente.estado.equals("inactivo", true) -> "inactivo"
            else -> "activo"
        }
    }

    fun asDateOrNull(any: Any?): Date? = when (any) {
        is Timestamp -> any.toDate()
        is Date -> any
        is String -> runCatching {
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(any)
        }.getOrNull()
        else -> null
    }

    fun proximoPagoCliente(clienteId: String): Date? {
        val hoy = Date()
        val prs = prestamosPorCliente[clienteId].orEmpty()
        val fechas = prs.filter { (it.saldo ?: 0.0) > 0.01 && it.proximoPago != null }
            .mapNotNull { asDateOrNull(it.proximoPago) }
        val futura = fechas.filter { !it.before(hoy) }.minOrNull()
        return futura ?: fechas.minOrNull()
    }

    val formato = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    val clientesFiltrados = remember(clientes, filtroEstado, filtroCobradorUid, prestamosPorCliente) {
        clientes.filter { c ->
            val pasaCobrador = filtroCobradorUid.isNullOrBlank() || c.cobradorAsignado == filtroCobradorUid
            val estado = estadoEfectivo(c)
            val pasaEstado = when (filtroEstado) {
                EstadoClienteFiltro.TODOS -> true
                EstadoClienteFiltro.ACTIVOS -> estado == "activo"
                EstadoClienteFiltro.SALDADOS -> estado == "saldado"
            }
            pasaCobrador && pasaEstado
        }.sortedBy { it.nombre.lowercase(Locale.getDefault()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reporte de Clientes", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0061A7), titleContentColor = Color.White
                ),
                actions = {
                    if (rol == "admin") {
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    val archivo = generarReporteClientesPDF(
                                        context = context,
                                        clientes = clientes,
                                        prestamosPorCliente = prestamosPorCliente,
                                        filtroEstado = filtroEstado,
                                        filtroCobrador = filtroCobradorUid
                                    )
                                    if (archivo != null && archivo.exists()) {
                                        val printed = com.example.capitalexpressapp.util.ReciboHelper
                                            .imprimirPDF(context, archivo)
                                        if (!printed) com.example.capitalexpressapp.util.ReciboHelper
                                            .compartirReciboPDF(context, archivo)
                                    } else {
                                        Toast.makeText(context, "No se pudo generar el PDF", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) {
                            Icon(imageVector = Icons.Filled.PictureAsPdf, contentDescription = "Exportar PDF", tint = Color.White)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF0061A7))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Filtros
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FC)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Filtros", fontWeight = FontWeight.Bold, color = Color(0xFF0061A7))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = filtroEstado == EstadoClienteFiltro.TODOS,
                                onClick = { filtroEstado = EstadoClienteFiltro.TODOS },
                                label = { Text("Todos") },
                                leadingIcon = { Icon(imageVector = Icons.Filled.FormatListBulleted, contentDescription = null) }
                            )
                            FilterChip(
                                selected = filtroEstado == EstadoClienteFiltro.ACTIVOS,
                                onClick = { filtroEstado = EstadoClienteFiltro.ACTIVOS },
                                label = { Text("Activos") },
                                leadingIcon = { Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null) }
                            )
                            FilterChip(
                                selected = filtroEstado == EstadoClienteFiltro.SALDADOS,
                                onClick = { filtroEstado = EstadoClienteFiltro.SALDADOS },
                                label = { Text("Saldados") },
                                leadingIcon = { Icon(imageVector = Icons.Filled.DoneAll, contentDescription = null) }
                            )
                        }

                        // Filtro por cobrador (NOMBRES)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(imageVector = Icons.Filled.Person, contentDescription = null, tint = Color(0xFF0061A7))
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                                OutlinedTextField(
                                    value = nombreCobrador(filtroCobradorUid),
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Cobrador") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Todos los cobradores") },
                                        onClick = { filtroCobradorUid = null; expanded = false }
                                    )
                                    // nombres ordenados alfabéticamente
                                    cobradoresMap.forEach { (uid, nombre) ->
                                        DropdownMenuItem(
                                            text = { Text(nombre) },
                                            onClick = {
                                                filtroCobradorUid = uid
                                                expanded = false
                                                Log.d("ReporteClientes", "Filtro seleccionado: $uid -> $nombre")
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Resumen
                val resumen = calcularResumenActivosSaldados(clientesFiltrados, prestamosPorCliente)
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0061A7)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Resumen", color = Color.White, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatChip("Clientes", resumen.totalClientes.toString())
                            StatChip("Activos", resumen.activos.toString())
                            StatChip("Saldados", resumen.saldados.toString())
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatChip("Prestado", "L. ${"%.2f".format(resumen.totalPrestado)}")
                            StatChip("Abonado", "L. ${"%.2f".format(resumen.totalAbonado)}")
                            StatChip("Pendiente", "L. ${"%.2f".format(resumen.totalPendiente)}")
                        }
                    }
                }

                // Lista
                if (clientesFiltrados.isEmpty()) {
                    Text("No hay clientes con los filtros seleccionados.", color = Color.Gray, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(clientesFiltrados, key = { it.id }) { c ->
                            val estado = estadoEfectivo(c).replaceFirstChar { it.uppercase() }
                            val trio = totalesCliente(c.id, prestamosPorCliente)
                            val prox = proximoPagoCliente(c.id)

                            // Obtener nombre del cobrador de manera segura
                            val nombreCobradorCard = if (c.cobradorAsignado.isBlank() || c.cobradorAsignado == "Sin asignar") {
                                "Sin asignar"
                            } else {
                                cobradoresMap[c.cobradorAsignado] ?: c.cobradorAsignado.let {
                                    if (it.length > 8) "${it.take(8)}…" else it
                                }
                            }

                            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(c.nombre, fontWeight = FontWeight.Bold)
                                    Text("Cobrador: $nombreCobradorCard", color = Color(0xFF1565C0))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Estado: $estado")
                                        Text("Tel: ${c.telefono.ifBlank { "N/D" }}")
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Prestado: L. ${"%.2f".format(trio.prestado)}")
                                        Text("Abonado: L. ${"%.2f".format(trio.abonado)}")
                                        Text("Pendiente: L. ${"%.2f".format(trio.pendiente)}")
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text("Próximo pago: ${prox?.let { formato.format(it) } ?: "—"}", color = Color(0xFF455A64))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White)
            Spacer(Modifier.width(6.dp))
            Surface(color = Color.White, shape = RoundedCornerShape(10.dp)) {
                Text(value, color = Color(0xFF0061A7), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

private data class Trio(val prestado: Double, val abonado: Double, val pendiente: Double)

private fun totalesCliente(clienteId: String, prestamosPorCliente: Map<String, List<Prestamo>>): Trio {
    val prs = prestamosPorCliente[clienteId].orEmpty()
    var prestado = 0.0
    var abonado = 0.0
    var pendiente = 0.0
    prs.forEach { p ->
        val totalPagar = if ((p.totalPagar ?: 0.0) > 0) p.totalPagar!! else p.monto + (p.interesTotal ?: p.interes)
        prestado += totalPagar
        abonado += (p.montoPagado ?: 0.0)
        pendiente += max(0.0, totalPagar - (p.montoPagado ?: 0.0))
    }
    return Trio(prestado, abonado, pendiente)
}

private data class ResumenAS(
    val totalClientes: Int,
    val activos: Int,
    val saldados: Int,
    val totalPrestado: Double,
    val totalAbonado: Double,
    val totalPendiente: Double
)

private fun calcularResumenActivosSaldados(
    clientes: List<ClienteModel>,
    prestamosPorCliente: Map<String, List<Prestamo>>
): ResumenAS {
    var prestado = 0.0; var abonado = 0.0; var pendiente = 0.0
    var activos = 0; var saldados = 0
    clientes.forEach { c ->
        val prs = prestamosPorCliente[c.id].orEmpty()
        val trio = totalesCliente(c.id, prestamosPorCliente)
        prestado += trio.prestado
        abonado += trio.abonado
        pendiente += trio.pendiente

        val todosSaldados = prs.isNotEmpty() && prs.all { p ->
            val total = (p.totalPagar ?: 0.0).takeIf { it > 0 } ?: (p.monto + (p.interesTotal ?: p.interes))
            val pagado = p.montoPagado ?: 0.0
            (total - pagado) <= 0.01 || p.estado.equals("saldado", true) || p.estado.equals("completado", true)
        }
        if (todosSaldados || c.estado.equals("saldado", true)) saldados++ else activos++
    }
    return ResumenAS(
        totalClientes = clientes.size,
        activos = activos,
        saldados = saldados,
        totalPrestado = prestado,
        totalAbonado = abonado,
        totalPendiente = pendiente
    )
}