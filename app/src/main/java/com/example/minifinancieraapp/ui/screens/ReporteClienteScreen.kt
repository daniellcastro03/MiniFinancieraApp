package com.example.capitalexpressapp.ui.screens

import android.util.Log
import android.widget.Toast
import com.example.capitalexpressapp.core.formatearLempiras
import com.example.capitalexpressapp.ui.theme.CEColors
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
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

    // clienteId -> set de cobradores (UID normalizado / nombre usado en pagos)
    var cobradoresPorClienteFromPagos by remember {
        mutableStateOf<Map<String, Set<String>>>(emptyMap())
    }

    // filtros UI
    var filtroEstado by remember { mutableStateOf(EstadoClienteFiltro.TODOS) }

    // uidNormalizado -> nombre legible
    var cobradoresMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var filtroCobradorUid by remember { mutableStateOf<String?>(null) }

    /* ------------------------ HELPERS COBRADORES ------------------------ */

    suspend fun extraerNombre(doc: DocumentSnapshot?): String? {
        if (doc == null || !doc.exists()) return null
        val keysPosibles = listOf(
            "nombre", "name", "displayName", "fullName",
            "username", "usuario", "alias"
        )
        for (k in keysPosibles) {
            val posible = doc.getString(k)
            if (!posible.isNullOrBlank()) return posible
        }
        // fallback: correo
        return doc.getString("email") ?: doc.getString("correo")
    }

    suspend fun buscarDocPorUid(
        db: FirebaseFirestore,
        coleccion: String,
        uid: String
    ): DocumentSnapshot? {
        // 1) documento con id == uid
        val byId = db.collection(coleccion).document(uid).get().await()
        if (byId.exists()) return byId

        // 2) documento con campo uid == uid
        val byField = db.collection(coleccion)
            .whereEqualTo("uid", uid)
            .limit(1)
            .get()
            .await()

        return byField.documents.firstOrNull()
    }

    // Limpia valores basura comunes y devuelve un UID "usable"
    fun normalizarUidCobrador(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val limpio = raw.trim()

        val basura = listOf(
            "Sin asignar", "Sin_asignar",
            "COBRADOR", "Cobrador",
            "Desconocido",
            "N/A",
            "No asignado", "No_asignado",
            "No asignar", "Sin asignar."
        )

        if (basura.any { basuraVal ->
                limpio.equals(basuraVal, ignoreCase = true)
            }
        ) {
            return ""
        }

        return limpio
    }

    // Carga el nombre amigable del cobrador.
    // Si no encuentra nada en Firestore, devuelve el uid completo (sin cortar).
    suspend fun cargarNombreCobrador(db: FirebaseFirestore, uid: String): String {
        val colecciones = listOf("users", "usuarios", "cobradores")
        for (c in colecciones) {
            try {
                val found = buscarDocPorUid(db, c, uid)
                val nombre = extraerNombre(found)
                if (!nombre.isNullOrBlank()) {
                    return nombre
                }
            } catch (e: Exception) {
                Log.w("ReporteClientes", "Error buscando cobrador '$uid' en $c: ${e.message}")
            }
        }
        // fallback => deja el string original completo
        return uid
    }

    // Nombre que mostramos en el dropdown
    fun nombreCobrador(uidNormalizado: String?): String {
        if (uidNormalizado.isNullOrBlank()) return "Todos los cobradores"
        return cobradoresMap[uidNormalizado]
            ?: run {
                Log.w("ReporteClientes", "Nombre no encontrado para uid=$uidNormalizado")
                uidNormalizado
            }
    }

    /* ------------------------ CARGA DE DATOS ------------------------ */

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                isLoading = true

                // 1. Clientes
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

                // 2. Préstamos
                val psnap = db.collection("prestamos").get().await()
                val prestamosRawGroup = psnap.documents.groupBy(
                    keySelector = { it.getString("clienteId") ?: "" },
                    valueTransform = { doc ->
                        fun parseTS(v: Any?): Timestamp? = when (v) {
                            is Timestamp -> v
                            is String -> runCatching {
                                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                sdf.parse(v)?.let { Timestamp(it) }
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
                )

                prestamosPorCliente = prestamosRawGroup.mapValues { it.value.filterNotNull() }

                // 3. Pagos (para mapear cobradores reales por cliente)
                val pagosSnap = db.collection("pagos").get().await()
                val cobradoresMapTemp =
                    mutableMapOf<String, MutableSet<String>>() // clienteId -> set(uidNorm)
                for (pagoDoc in pagosSnap.documents) {
                    val clienteIdPago = pagoDoc.getString("clienteId") ?: continue
                    // tratamos de identificar quién cobró / registró
                    val uidCrudoPago = pagoDoc.getString("registradoPor")
                        ?: pagoDoc.getString("cobradorId")
                        ?: pagoDoc.getString("uidCobrador")
                        ?: pagoDoc.getString("cobradorAsignado")
                        ?: ""

                    val uidNormPago = normalizarUidCobrador(uidCrudoPago)
                    if (uidNormPago.isNotEmpty()) {
                        val set = cobradoresMapTemp.getOrPut(clienteIdPago) { mutableSetOf() }
                        set.add(uidNormPago)
                    }
                }
                cobradoresPorClienteFromPagos = cobradoresMapTemp

                // 4. Construir TODOS los UIDs / nombres de cobradores conocidos:
                //  - desde cliente.cobradorAsignado
                //  - desde prestamo.cobradorAsignado
                //  - desde pagos.<registradoPor/...>
                val uidsClientes = clientes
                    .map { normalizarUidCobrador(it.cobradorAsignado) }

                val uidsPrestamos = prestamosPorCliente.values
                    .flatten()
                    .map { normalizarUidCobrador(it.cobradorAsignado) }

                val uidsPagos = cobradoresPorClienteFromPagos
                    .values
                    .flatten()
                    .map { normalizarUidCobrador(it) }

                val allUids = (uidsClientes + uidsPrestamos + uidsPagos)
                    .filter { it.isNotBlank() }
                    .distinct()

                // 5. Resolver nombres legibles para cada UID / etiqueta
                val nombres = mutableMapOf<String, String>()
                for (uid in allUids) {
                    val nombreBonito = runCatching {
                        cargarNombreCobrador(db, uid)
                    }.onFailure {
                        Log.w("ReporteClientes", "cargarNombreCobrador($uid): ${it.message}")
                    }.getOrDefault(uid)
                    nombres[uid] = nombreBonito
                    Log.d("ReporteClientes", "Cobrador cargado: $uid -> $nombreBonito")
                }

                // 6. Ordenar por nombre para el dropdown
                cobradoresMap = nombres.toList()
                    .sortedBy { it.second.lowercase(Locale.getDefault()) }
                    .toMap()

                Log.d(
                    "ReporteClientes",
                    "Total cobradores cargados (final): ${cobradoresMap.size}"
                )

            } catch (e: Exception) {
                Log.e("ReporteClientes", "Error: ${e.message}", e)
                Toast.makeText(
                    context,
                    "Error cargando datos: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isLoading = false
            }
        }
    }

    /* ------------------------ LÓGICA DE ESTADO / RESUMEN ------------------------ */

    fun estadoEfectivo(cliente: ClienteModel): String {
        val prs = prestamosPorCliente[cliente.id].orEmpty()
        val todosSaldados = prs.isNotEmpty() && prs.all { p ->
            val total = (p.totalPagar ?: 0.0).takeIf { it > 0 }
                ?: (p.monto + (p.interesTotal ?: p.interes))
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

        val fechas = prs
            .filter { (it.saldo ?: 0.0) > 0.01 && it.proximoPago != null }
            .mapNotNull { asDateOrNull(it.proximoPago) }

        val futura = fechas.filter { !it.before(hoy) }.minOrNull()
        return futura ?: fechas.minOrNull()
    }

    val formatoFecha = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    // Lógica unificada de filtro por cobrador (MISMA que usamos en PDF)
    fun pasaCobradorParaCliente(cliente: ClienteModel, filtroUid: String?): Boolean {
        if (filtroUid.isNullOrBlank()) return true

        val uidClienteNorm = normalizarUidCobrador(cliente.cobradorAsignado)

        val matchCliente = uidClienteNorm == filtroUid

        val matchPrestamo = prestamosPorCliente[cliente.id]
            .orEmpty()
            .any { p ->
                normalizarUidCobrador(p.cobradorAsignado) == filtroUid
            }

        val matchPago = cobradoresPorClienteFromPagos[cliente.id]
            ?.any { uidPago -> uidPago == filtroUid }
            ?: false

        return matchCliente || matchPrestamo || matchPago
    }

    val clientesFiltrados = remember(
        clientes,
        filtroEstado,
        filtroCobradorUid,
        prestamosPorCliente,
        cobradoresPorClienteFromPagos
    ) {
        clientes.filter { c ->
            val pasaCobrador = pasaCobradorParaCliente(c, filtroCobradorUid)

            val estadoActual = estadoEfectivo(c)
            val pasaEstado = when (filtroEstado) {
                EstadoClienteFiltro.TODOS -> true
                EstadoClienteFiltro.ACTIVOS -> estadoActual == "activo"
                EstadoClienteFiltro.SALDADOS -> estadoActual == "saldado"
            }

            pasaCobrador && pasaEstado
        }.sortedBy { it.nombre.lowercase(Locale.getDefault()) }
    }

    /* ------------------------ UI ------------------------ */

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reporte de Clientes", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CEColors.Primary,
                    titleContentColor = Color.White
                ),
                actions = {
                    if (rol == "admin") {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        val archivo = generarReporteClientesPDF(
                                            context = context,
                                            clientes = clientes,
                                            prestamosPorCliente = prestamosPorCliente,
                                            filtroEstado = filtroEstado,
                                            filtroCobrador = filtroCobradorUid,
                                            nombresCobradores = cobradoresMap,
                                            cobradoresPorClienteFromPagos = cobradoresPorClienteFromPagos
                                        )

                                        if (archivo != null && archivo.exists()) {
                                            val printed =
                                                com.example.capitalexpressapp.util.ReciboHelper
                                                    .imprimirPDF(context, archivo)
                                            if (!printed)
                                                com.example.capitalexpressapp.util.ReciboHelper
                                                    .compartirReciboPDF(context, archivo)
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "No se pudo generar el PDF",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Error: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PictureAsPdf,
                                contentDescription = "Exportar PDF",
                                tint = Color.White
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = CEColors.Primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {

                /* --------- FILTROS --------- */
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF7F9FC)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Filtros",
                                fontWeight = FontWeight.Bold,
                                color = CEColors.Primary
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = filtroEstado == EstadoClienteFiltro.TODOS,
                                    onClick = { filtroEstado = EstadoClienteFiltro.TODOS },
                                    label = { Text("Todos") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.FormatListBulleted,
                                            contentDescription = null
                                        )
                                    }
                                )
                                FilterChip(
                                    selected = filtroEstado == EstadoClienteFiltro.ACTIVOS,
                                    onClick = { filtroEstado = EstadoClienteFiltro.ACTIVOS },
                                    label = { Text("Activos") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null
                                        )
                                    }
                                )
                                FilterChip(
                                    selected = filtroEstado == EstadoClienteFiltro.SALDADOS,
                                    onClick = { filtroEstado = EstadoClienteFiltro.SALDADOS },
                                    label = { Text("Saldados") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.DoneAll,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }

                            // Filtro por cobrador
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = CEColors.Primary
                                )

                                var expanded by remember { mutableStateOf(false) }

                                ExposedDropdownMenuBox(
                                    expanded = expanded,
                                    onExpandedChange = { expanded = !expanded }
                                ) {
                                    OutlinedTextField(
                                        value = nombreCobrador(filtroCobradorUid),
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Cobrador") },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(
                                                expanded = expanded
                                            )
                                        },
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth()
                                    )

                                    ExposedDropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Todos los cobradores") },
                                            onClick = {
                                                filtroCobradorUid = null
                                                expanded = false
                                            }
                                        )

                                        cobradoresMap.forEach { (uid, nombre) ->
                                            DropdownMenuItem(
                                                text = { Text(nombre) },
                                                onClick = {
                                                    filtroCobradorUid = uid
                                                    expanded = false
                                                    Log.d(
                                                        "ReporteClientes",
                                                        "Filtro seleccionado: $uid -> $nombre"
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                /* --------- RESUMEN --------- */
                item {
                    val resumen = calcularResumenActivosSaldados(
                        clientesFiltrados,
                        prestamosPorCliente
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = CEColors.Primary
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Resumen",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatChip("Clientes", resumen.totalClientes.toString())
                                StatChip("Activos", resumen.activos.toString())
                                StatChip("Saldados", resumen.saldados.toString())
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatChip(
                                    "Prestado",
                                    formatearLempiras(resumen.totalPrestado)
                                )
                                StatChip(
                                    "Abonado",
                                    formatearLempiras(resumen.totalAbonado)
                                )
                                StatChip(
                                    "Pendiente",
                                    formatearLempiras(resumen.totalPendiente)
                                )
                            }
                        }
                    }
                }

                /* --------- TABLA CON SCROLL HORIZONTAL --------- */
                item {
                    TablaClientesScrollable(
                        clientes = clientesFiltrados,
                        prestamosPorCliente = prestamosPorCliente,
                        obtenerEstado = { c -> estadoEfectivo(c) },
                        proximoPagoDe = { id -> proximoPagoCliente(id) },
                        formatoFecha = formatoFecha,
                        nombreCobradorDeCliente = { c ->
                            val uidNorm = normalizarUidCobrador(c.cobradorAsignado)
                            when {
                                uidNorm.isBlank() -> "Sin asignar"
                                else -> cobradoresMap[uidNorm] ?: uidNorm
                            }
                        },
                        calcularTotales = { id, mapa -> totalesCliente(id, mapa) }
                    )

                    Spacer(Modifier.height(16.dp))
                }

                // 🔴 Nota: ya NO renderizamos las cards por cliente.
            }
        }
    }
}

/* ------------------------ TABLA SCROLLABLE ------------------------ */

@Composable
private fun TablaClientesScrollable(
    clientes: List<ClienteModel>,
    prestamosPorCliente: Map<String, List<Prestamo>>,
    obtenerEstado: (ClienteModel) -> String,
    proximoPagoDe: (String) -> Date?,
    formatoFecha: SimpleDateFormat,
    nombreCobradorDeCliente: (ClienteModel) -> String,
    calcularTotales: (String, Map<String, List<Prestamo>>) -> Trio
) {
    // Scroll horizontal de TODA la tabla
    val horizontalScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF7F9FC))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(horizontalScrollState)
        ) {
            Column(
                modifier = Modifier
                    .background(Color.White)
            ) {

                // HEADER
                Row(
                    modifier = Modifier
                        .background(CEColors.Primary)
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TablaHeaderCellFixed("Cliente", width = 180.dp)
                    TablaHeaderCellFixed("Teléfono", width = 110.dp)
                    TablaHeaderCellFixed("Cobrador", width = 160.dp)
                    TablaHeaderCellFixed("Estado", width = 100.dp)
                    TablaHeaderCellFixed("Prestado", width = 120.dp)
                    TablaHeaderCellFixed("Abonado", width = 120.dp)
                    TablaHeaderCellFixed("Pendiente", width = 130.dp)
                    TablaHeaderCellFixed("Próximo pago", width = 140.dp)
                }

                Spacer(Modifier.height(4.dp))

                // FILAS
                clientes.forEach { cliente ->
                    val estado = obtenerEstado(cliente).replaceFirstChar { it.uppercase() }
                    val trio = calcularTotales(cliente.id, prestamosPorCliente)
                    val proxFecha =
                        proximoPagoDe(cliente.id)?.let { formatoFecha.format(it) } ?: "—"
                    val cobradorMostrado = nombreCobradorDeCliente(cliente)

                    Row(
                        modifier = Modifier
                            .background(Color.White)
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        TablaBodyCellFixed(
                            text = cliente.nombre,
                            width = 180.dp
                        )
                        TablaBodyCellFixed(
                            text = if (cliente.telefono.isBlank()) "N/D" else cliente.telefono,
                            width = 110.dp
                        )
                        TablaBodyCellFixed(
                            text = cobradorMostrado,
                            width = 160.dp
                        )
                        TablaBodyCellFixed(
                            text = estado,
                            width = 100.dp
                        )
                        TablaBodyCellFixed(
                            text = "L. %,.2f".format(trio.prestado),
                            width = 120.dp
                        )
                        TablaBodyCellFixed(
                            text = "L. %,.2f".format(trio.abonado),
                            width = 120.dp
                        )
                        TablaBodyCellFixed(
                            text = "L. %,.2f".format(trio.pendiente),
                            width = 130.dp
                        )
                        TablaBodyCellFixed(
                            text = proxFecha,
                            width = 140.dp
                        )
                    }

                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun TablaHeaderCellFixed(
    text: String,
    width: Dp
) {
    Text(
        text = text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 4.dp)
    )
}

@Composable
private fun TablaBodyCellFixed(
    text: String,
    width: Dp
) {
    Text(
        text = text,
        color = Color(0xFF212121),
        fontWeight = FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 4.dp)
    )
}

/* ------------------------ CHIP DEL RESUMEN ------------------------ */

@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        color = Color.White.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White)
            Spacer(Modifier.width(6.dp))
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    value,
                    color = CEColors.Primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/* ------------------------ CÁLCULO DE TOTALES POR CLIENTE ------------------------ */

private data class Trio(val prestado: Double, val abonado: Double, val pendiente: Double)

private fun totalesCliente(
    clienteId: String,
    prestamosPorCliente: Map<String, List<Prestamo>>
): Trio {
    val prs = prestamosPorCliente[clienteId].orEmpty()
    var prestado = 0.0
    var abonado = 0.0
    var pendiente = 0.0

    prs.forEach { p ->
        val totalPagar = if ((p.totalPagar ?: 0.0) > 0) {
            p.totalPagar!!
        } else {
            p.monto + (p.interesTotal ?: p.interes)
        }

        prestado += totalPagar
        abonado += (p.montoPagado ?: 0.0)
        pendiente += max(
            0.0,
            totalPagar - (p.montoPagado ?: 0.0)
        )
    }

    return Trio(prestado, abonado, pendiente)
}

/* ------------------------ RESUMEN GLOBAL ------------------------ */

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
    var prestado = 0.0
    var abonado = 0.0
    var pendiente = 0.0
    var activos = 0
    var saldados = 0

    clientes.forEach { c ->
        val prs = prestamosPorCliente[c.id].orEmpty()
        val trio = totalesCliente(c.id, prestamosPorCliente)

        prestado += trio.prestado
        abonado += trio.abonado
        pendiente += trio.pendiente

        val todosSaldados = prs.isNotEmpty() && prs.all { p ->
            val total = (p.totalPagar ?: 0.0).takeIf { it > 0 }
                ?: (p.monto + (p.interesTotal ?: p.interes))

            val pagado = p.montoPagado ?: 0.0

            (total - pagado) <= 0.01 ||
                    p.estado.equals("saldado", true) ||
                    p.estado.equals("completado", true)
        }

        if (todosSaldados || c.estado.equals("saldado", true)) {
            saldados++
        } else {
            activos++
        }
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
