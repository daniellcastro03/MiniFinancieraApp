package com.example.capitalexpressapp.ui.screens

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

// Data class actualizada para incluir el campo eliminado
data class PrestamoAdmin(
    val cliente: String = "",
    val monto: Double = 0.0,
    val interes: Double = 0.0,
    val interesMensual: Double = 0.0,
    val interesTotal: Double = 0.0,
    val totalPagar: Double = 0.0,
    val cuota: Double = 0.0,
    val cuotas: Int = 0,
    val plazo: String = "",
    val fecha: String = "",
    val fechaTimestamp: Timestamp? = null,
    val fechaCreacion: String = "",
    val fechaCreacionTimestamp: Timestamp? = null,
    val proximoPago: String = "",
    val proximoPagoTimestamp: Timestamp? = null,
    val lugar: String = "",
    val firma: String = "",
    val cobrador: String = "",
    val montoPagado: Double = 0.0,
    val saldo: Double = 0.0,
    val saldoAnterior: Double = 0.0,
    val estado: String = "",
    val observaciones: String = "",
    val fotos: List<String> = emptyList(),
    val diasEfectivos: Int = 0,
    val cobradores: List<String> = emptyList(),
    val numeroPrestamo: Int = 0,
    val prestamoId: String = "",
    val id: String = "",
    val eliminado: Boolean = false,
    val fechaEliminacion: String = "",
    val fechaEliminacionTimestamp: Timestamp? = null,
    val eliminadoPor: String = ""
)

// ✅ FUNCIONES CORREGIDAS PARA VERIFICAR ESTADO REAL

// Función para calcular fechas de cuotas (igual que en NotificacionesScreen)
private fun calcularFechaCuotaAdmin(fechaInicio: Date, plazo: String, numeroCuota: Int): String {
    val calendar = Calendar.getInstance().apply { time = fechaInicio }
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    when (plazo.lowercase()) {
        "diario" -> {
            calendar.add(Calendar.DAY_OF_YEAR, numeroCuota)
        }
        "lunes a sábado" -> {
            repeat(numeroCuota) {
                do {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
        }
        "semanal" -> {
            calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 7)
        }
        "quincenal" -> {
            calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 15)
        }
        "mensual" -> {
            calendar.add(Calendar.MONTH, numeroCuota)
        }
        "bimestral" -> {
            calendar.add(Calendar.MONTH, numeroCuota * 2)
        }
        else -> {
            calendar.add(Calendar.MONTH, numeroCuota)
        }
    }

    return dateFormat.format(calendar.time)
}

// Función para verificar si un préstamo está realmente saldado
private suspend fun verificarEstadoRealPrestamoAdmin(
    db: FirebaseFirestore,
    prestamoId: String,
    cuotasTotales: Int
): String {
    return try {
        if (cuotasTotales == 0) {
            return "activo" // Si no tiene cuotas válidas, asumir activo
        }

        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId)
            .get().await()

        val cuotasPagadasSet = mutableSetOf<Int>()
        for (pago in pagosSnapshot.documents) {
            val numeroCuota = when {
                pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                pago.contains("cuota") -> pago.getLong("cuota")?.toInt() ?: 1
                else -> 1
            }
            val cuotasCubiertas = pago.getLong("cuotasCubiertas")?.toInt() ?: 1

            for (i in 0 until cuotasCubiertas) {
                cuotasPagadasSet.add(numeroCuota + i)
            }
        }

        val cuotasPagadas = cuotasPagadasSet.size
        val realmenteSaldado = cuotasPagadas >= cuotasTotales

        Log.d("PrestamoAdminScreen", """
            === VERIFICACIÓN ESTADO REAL ===
            - Préstamo: $prestamoId
            - Cuotas totales: $cuotasTotales
            - Cuotas pagadas: $cuotasPagadas
            - Cuotas pagadas set: ${cuotasPagadasSet.sorted()}
            - Estado real: ${if (realmenteSaldado) "saldado" else "activo"}
        """.trimIndent())

        if (realmenteSaldado) "saldado" else "activo"

    } catch (e: Exception) {
        Log.e("PrestamoAdminScreen", "Error verificando estado real: ${e.message}")
        "activo"
    }
}

// Función para obtener nombres de cobradores
suspend fun obtenerNombresCobradores(cobradores: List<String>): String {
    if (cobradores.isEmpty()) return "Sin asignar"

    return try {
        val db = FirebaseFirestore.getInstance()
        val nombres = mutableListOf<String>()

        for (cobradorId in cobradores) {
            val doc = db.collection("users").document(cobradorId).get().await()
            val nombre = doc.getString("nombre") ?: doc.getString("email") ?: "Usuario desconocido"
            nombres.add(nombre)
        }

        nombres.joinToString(", ")
    } catch (e: Exception) {
        Log.e("PrestamoAdmin", "Error al obtener nombres de cobradores: ${e.message}")
        "Error al cargar nombres"
    }
}

// Componente para filtros horizontales compactos
@Composable
fun FiltrosCompactos(
    estadoSeleccionado: String,
    onEstadoChange: (String) -> Unit,
    search: String,
    onSearchChange: (String) -> Unit,
    mostrarEliminados: Boolean,
    onMostrarEliminadosChange: (Boolean) -> Unit,
    esAdmin: Boolean,
    onResetFiltros: () -> Unit
) {
    val opcionesEstado = listOf("Todos", "activo", "saldado", "atrasado", "inactivo")
    val hayFiltrosActivos = estadoSeleccionado != "Todos" || search.isNotBlank() || mostrarEliminados

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Barra de búsqueda compacta
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                placeholder = { Text("Buscar cliente...", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, null, tint = Color(0xFF0061A7))
                },
                trailingIcon = if (search.isNotBlank()) {
                    {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Clear, null, tint = Color.Gray)
                        }
                    }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF0061A7),
                    unfocusedBorderColor = Color(0xFFE0E0E0),
                    focusedLeadingIconColor = Color(0xFF0061A7)
                ),
                singleLine = true
            )

            // Toggle para eliminados (solo admin)
            if (esAdmin) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (mostrarEliminados) Color(0xFFD32F2F) else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Ver eliminados",
                            fontSize = 14.sp,
                            color = if (mostrarEliminados) Color(0xFFD32F2F) else Color.Gray
                        )
                    }
                    Switch(
                        checked = mostrarEliminados,
                        onCheckedChange = onMostrarEliminadosChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFD32F2F),
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFE0E0E0)
                        )
                    )
                }
            }

            // Filtros de estado como chips horizontales (solo si no está viendo eliminados)
            if (!mostrarEliminados) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = null,
                        tint = Color(0xFF0061A7),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Estado:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF0061A7)
                    )
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(opcionesEstado) { estado ->
                        FilterChip(
                            onClick = { onEstadoChange(estado) },
                            label = {
                                Text(
                                    estado.replaceFirstChar { it.uppercase() },
                                    fontSize = 12.sp
                                )
                            },
                            selected = estadoSeleccionado == estado,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF0061A7),
                                selectedLabelColor = Color.White,
                                containerColor = Color.White,
                                labelColor = Color(0xFF666666)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = estadoSeleccionado == estado,
                                borderColor = if (estadoSeleccionado == estado) Color(0xFF0061A7) else Color(0xFFE0E0E0)
                            )
                        )
                    }
                }
            }

            // Botón reset si hay filtros activos
            if (hayFiltrosActivos) {
                OutlinedButton(
                    onClick = onResetFiltros,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF0061A7)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Clear, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Limpiar filtros", fontSize = 14.sp)
                }
            }
        }
    }
}

// Componente mejorado para la tarjeta de préstamo
@Composable
fun TarjetaPrestamo(
    prestamo: PrestamoAdmin,
    nombresCobradores: String,
    onVerClick: () -> Unit,
    onEditarClick: () -> Unit,
    onEliminarClick: (() -> Unit)? = null
) {
    val sinCobrador = prestamo.cobradores.isEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                prestamo.eliminado -> Color(0xFFFFEBEE)
                sinCobrador -> Color(0xFFFFF3E0)
                else -> Color.White
            }
        ),
        elevation = CardDefaults.cardElevation(4.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header con indicador de eliminado
            if (prestamo.eliminado) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "ELIMINADO",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Info principal en una sola fila compacta
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        prestamo.cliente,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF1A1A1A)
                    )
                    Text(
                        "L. ${"%.0f".format(prestamo.monto)}",
                        color = Color(0xFF0061A7),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    if (prestamo.numeroPrestamo > 0) {
                        Text(
                            "#${prestamo.numeroPrestamo}",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    EstadoChipCompacto(prestamo.estado)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Información clave en filas compactas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoCompacta("Cuota", "L. ${"%.0f".format(prestamo.cuota)}")
                InfoCompacta("Cuotas", "${prestamo.cuotas}")
                InfoCompacta("Plazo", prestamo.plazo.take(10))
            }

            if (prestamo.montoPagado > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    InfoCompacta("Pagado", "L. ${"%.0f".format(prestamo.montoPagado)}")
                    if (prestamo.saldo > 0 && prestamo.estado.lowercase() != "saldado") {
                        InfoCompacta("Saldo", "L. ${"%.0f".format(prestamo.saldo)}")
                    }
                }
            }

            // Cobrador
            if (nombresCobradores != "Cargando...") {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = if (sinCobrador) Color(0xFFFF9800) else Color(0xFF666666),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        nombresCobradores,
                        fontSize = 12.sp,
                        color = if (sinCobrador) Color(0xFFFF9800) else Color(0xFF666666),
                        fontWeight = if (sinCobrador) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Botones de acción
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onVerClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF0061A7)
                    )
                ) {
                    Text("Ver", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onEditarClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text("Editar", fontSize = 12.sp)
                }

                onEliminarClick?.let { eliminar ->
                    OutlinedButton(
                        onClick = eliminar,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFD32F2F)
                        )
                    ) {
                        Text("Eliminar", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoCompacta(label: String, valor: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            valor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = Color(0xFF1A1A1A)
        )
        Text(
            label,
            fontSize = 10.sp,
            color = Color.Gray
        )
    }
}

@Composable
fun EstadoChipCompacto(estado: String) {
    val (backgroundColor, textColor) = when (estado.lowercase()) {
        "activo" -> Color(0xFF4CAF50) to Color.White
        "saldado" -> Color(0xFF9E9E9E) to Color.White
        "atrasado" -> Color(0xFFFF5722) to Color.White
        "inactivo" -> Color(0xFF424242) to Color.White
        else -> Color(0xFF757575) to Color.White
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = estado.uppercase(),
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// Componente para estadísticas rápidas
@Composable
fun EstadisticasRapidas(prestamos: List<PrestamoAdmin>) {
    val activos = prestamos.count { it.estado.lowercase() == "activo" }
    val saldados = prestamos.count { it.estado.lowercase() == "saldado" }
    val atrasados = prestamos.count { it.estado.lowercase() == "atrasado" }
    val total = prestamos.size

    if (total > 0) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F7FF)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EstadisticaItem("Total", total.toString(), Color(0xFF0061A7))
                EstadisticaItem("Activos", activos.toString(), Color(0xFF4CAF50))
                EstadisticaItem("Saldados", saldados.toString(), Color(0xFF9E9E9E))
                if (atrasados > 0) {
                    EstadisticaItem("Atrasados", atrasados.toString(), Color(0xFFFF5722))
                }
            }
        }
    }
}

@Composable
fun EstadisticaItem(label: String, valor: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            valor,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = color
        )
        Text(
            label,
            fontSize = 10.sp,
            color = Color.Gray
        )
    }
}

// Extensiones helper (mantener las existentes)
fun DocumentSnapshot.getTimestampSafe(field: String): Timestamp? {
    return try {
        this.getTimestamp(field)
    } catch (e: Exception) {
        Log.w("FirestoreHelper", "Campo '$field' no es un Timestamp en documento ${this.id}: ${e.message}")
        val stringValue = this.getString(field)
        if (!stringValue.isNullOrBlank()) {
            try {
                val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                val date = formatter.parse(stringValue)
                date?.let { Timestamp(it) }
            } catch (parseException: Exception) {
                Log.w("FirestoreHelper", "No se pudo parsear fecha desde string '$stringValue': ${parseException.message}")
                null
            }
        } else {
            null
        }
    }
}

fun DocumentSnapshot.getDateStringSafe(field: String, formatter: SimpleDateFormat): String {
    return try {
        val timestamp = this.getTimestamp(field)
        timestamp?.toDate()?.let { formatter.format(it) } ?: "-"
    } catch (e: Exception) {
        val stringValue = this.getString(field)
        if (!stringValue.isNullOrBlank()) {
            try {
                val date = formatter.parse(stringValue)
                formatter.format(date)
            } catch (parseException: Exception) {
                stringValue
            }
        } else {
            "-"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrestamoAdminScreen(navController: NavController, uid: String, rol: String) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val fullFormatter = SimpleDateFormat("dd 'de' MMMM 'de' yyyy, HH:mm:ss a", Locale("es", "ES"))

    var prestamosOriginales by remember { mutableStateOf(listOf<PrestamoAdmin>()) }
    var prestamosFiltrados by remember { mutableStateOf(listOf<PrestamoAdmin>()) }
    var prestamoAEliminar by remember { mutableStateOf<PrestamoAdmin?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }
    var hayConexion by remember { mutableStateOf(true) }
    var mostrarEliminados by remember { mutableStateOf(false) }
    var firestoreListener by remember { mutableStateOf<ListenerRegistration?>(null) }

    var estadoSeleccionado by remember { mutableStateOf("Todos") }
    var search by remember { mutableStateOf("") }

    val esAdmin = rol == "admin"
    val esCobrador = rol == "cobrador"

    // Función para verificar conectividad
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }

    // Función para formatear fecha desde timestamp (día/mes/año)
    fun formatearFecha(timestamp: Timestamp?): String {
        return timestamp?.toDate()?.let { formatter.format(it) } ?: "-"
    }

    // Función para formatear fecha completa (con hora)
    fun formatearFechaCompleta(timestamp: Timestamp?): String {
        return timestamp?.toDate()?.let { fullFormatter.format(it) } ?: "-"
    }

    // Función para resetear filtros
    fun resetearFiltros() {
        estadoSeleccionado = "Todos"
        search = ""
        mostrarEliminados = false
    }

    // ✅ FUNCIÓN CORREGIDA PARA CONFIGURAR LISTENER FIREBASE
    fun configurarListenerFirebase() {
        cargando = true
        errorMessage = ""
        hayConexion = isNetworkAvailable(context)

        if (!hayConexion) {
            errorMessage = "No hay conexión a internet. Los datos pueden no estar actualizados."
            cargando = false
            return
        }

        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            db.firestoreSettings = settings

            val query = if (esCobrador) {
                db.collection("prestamos")
                    .whereArrayContains("cobradoresAsignados", uid)
            } else {
                db.collection("prestamos")
            }

            firestoreListener = query.addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("PrestamoAdmin", "Error en el listener: ${e.message}", e)
                    errorMessage = "Error al escuchar cambios: ${e.localizedMessage}"
                    cargando = false
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    scope.launch {
                        val lista = mutableListOf<PrestamoAdmin>()

                        for (doc in snapshot.documents) {
                            try {
                                val cliente = doc.getString("cliente") ?: continue
                                val cuotasTotales = doc.getLong("cuotas")?.toInt() ?: 0
                                val estadoFirestore = doc.getString("estado") ?: "activo"
                                val prestamoId = doc.id

                                // ✅ VERIFICAR ESTADO REAL BASADO EN CUOTAS PAGADAS
                                val estadoReal = if (estadoFirestore.equals("saldado", ignoreCase = true)) {
                                    // Si dice que está saldado, verificar si realmente lo está
                                    val estadoVerificado = verificarEstadoRealPrestamoAdmin(db, prestamoId, cuotasTotales)

                                    if (estadoVerificado != "saldado" && estadoFirestore.equals("saldado", ignoreCase = true)) {
                                        Log.w("PrestamoAdminScreen", """
                                            ⚠️ INCONSISTENCIA DETECTADA EN PRESTAMO ADMIN:
                                            - Cliente: $cliente
                                            - Préstamo ID: $prestamoId
                                            - Estado en Firestore: $estadoFirestore
                                            - Estado real verificado: $estadoVerificado
                                            - Cuotas totales: $cuotasTotales
                                            - MOSTRANDO COMO: $estadoVerificado
                                        """.trimIndent())

                                        // Actualizar el estado en Firestore
                                        try {
                                            db.collection("prestamos").document(prestamoId)
                                                .update("estado", estadoVerificado)
                                                .await()
                                        } catch (updateE: Exception) {
                                            Log.e("PrestamoAdminScreen", "Error actualizando estado: ${updateE.message}")
                                        }
                                    }

                                    estadoVerificado
                                } else {
                                    estadoFirestore
                                }

                                val prestamoAdmin = PrestamoAdmin(
                                    cliente = cliente,
                                    monto = doc.getDouble("monto") ?: 0.0,
                                    interes = doc.getDouble("interes") ?: 0.0,
                                    interesMensual = doc.getDouble("interesMensual") ?: 0.0,
                                    interesTotal = doc.getDouble("interesTotal") ?: 0.0,
                                    totalPagar = doc.getDouble("totalPagar") ?: 0.0,
                                    cuota = doc.getDouble("cuota") ?: 0.0,
                                    cuotas = cuotasTotales,
                                    plazo = doc.getString("plazo") ?: "",
                                    lugar = doc.getString("lugar") ?: "",
                                    firma = doc.getString("firma") ?: "",
                                    cobrador = doc.getString("cobrador") ?: "",
                                    montoPagado = doc.getDouble("montoPagado") ?: 0.0,
                                    saldo = doc.getDouble("saldo") ?: 0.0,
                                    saldoAnterior = doc.getDouble("saldoAnterior") ?: 0.0,
                                    estado = estadoReal, // ✅ USAR ESTADO VERIFICADO
                                    observaciones = doc.getString("observaciones") ?: "",
                                    diasEfectivos = doc.getLong("diasEfectivos")?.toInt() ?: 0,
                                    numeroPrestamo = doc.getLong("numeroPrestamo")?.toInt() ?: 0,
                                    prestamoId = doc.getString("prestamoId") ?: "",
                                    fechaTimestamp = doc.getTimestampSafe("fecha"),
                                    fechaCreacionTimestamp = doc.getTimestampSafe("fechaCreacion"),
                                    proximoPagoTimestamp = doc.getTimestampSafe("proximoPago"),
                                    fecha = doc.getDateStringSafe("fecha", formatter),
                                    fechaCreacion = doc.getDateStringSafe("fechaCreacion", fullFormatter),
                                    proximoPago = doc.getDateStringSafe("proximoPago", formatter),
                                    fotos = when (val fotosData = doc.get("fotos")) {
                                        is List<*> -> fotosData.filterIsInstance<String>()
                                        else -> emptyList()
                                    },
                                    cobradores = when (val data = doc.get("cobradoresAsignados")) {
                                        is List<*> -> data.filterIsInstance<String>().filter { it.isNotBlank() }
                                        else -> {
                                            val unico = doc.getString("cobradorAsignado")
                                            if (!unico.isNullOrBlank()) listOf(unico) else emptyList()
                                        }
                                    },
                                    id = doc.id,
                                    eliminado = doc.getBoolean("eliminado") ?: false,
                                    fechaEliminacion = formatearFechaCompleta(doc.getTimestamp("fechaEliminacion")),
                                    fechaEliminacionTimestamp = doc.getTimestamp("fechaEliminacion"),
                                    eliminadoPor = doc.getString("eliminadoPor") ?: ""
                                )

                                lista.add(prestamoAdmin)

                            } catch (e: Exception) {
                                Log.e("PrestamoAdmin", "Error al parsear documento ${doc.id}: ${e.message}", e)
                            }
                        }

                        if (lista.isNotEmpty()) {
                            prestamosOriginales = lista.sortedByDescending { it.fechaCreacionTimestamp?.toDate() }
                            errorMessage = ""

                            Log.d("PrestamoAdminScreen", """
                                ✅ CARGA COMPLETADA EN PRESTAMO ADMIN:
                                - Total préstamos cargados: ${lista.size}
                                - Activos: ${lista.count { it.estado.lowercase() == "activo" }}
                                - Saldados: ${lista.count { it.estado.lowercase() == "saldado" }}
                                - Estados verificados correctamente
                            """.trimIndent())
                        } else {
                            errorMessage = if (esCobrador) {
                                "No tienes préstamos asignados."
                            } else {
                                "No se pudieron procesar préstamos válidos de Firebase."
                            }
                            prestamosOriginales = emptyList()
                        }

                        cargando = false
                    }
                } else {
                    errorMessage = if (esCobrador) {
                        "No tienes préstamos asignados."
                    } else {
                        "No se encontraron préstamos en la base de datos."
                    }
                    prestamosOriginales = emptyList()
                    cargando = false
                }
            }

        } catch (e: Exception) {
            Log.e("PrestamoAdmin", "Error al configurar listener: ${e.message}", e)
            errorMessage = "Error de carga: ${e.localizedMessage}"
            prestamosOriginales = emptyList()
            cargando = false
        }
    }

    // Configurar listener al iniciar
    LaunchedEffect(Unit) {
        configurarListenerFirebase()
    }

    // Limpiar listener al salir
    DisposableEffect(Unit) {
        onDispose {
            firestoreListener?.remove()
        }
    }

    // Efecto para filtrar
    LaunchedEffect(estadoSeleccionado, search, prestamosOriginales, mostrarEliminados) {
        prestamosFiltrados = prestamosOriginales
            .filter { prestamo ->
                val coincideEliminado = if (mostrarEliminados) {
                    prestamo.eliminado
                } else {
                    !prestamo.eliminado
                }

                val coincideEstado = estadoSeleccionado == "Todos" ||
                        prestamo.estado.equals(estadoSeleccionado, ignoreCase = true)

                val coincideCliente = prestamo.cliente.contains(search, ignoreCase = true)

                coincideEliminado && coincideEstado && coincideCliente
            }
            .sortedWith(compareBy<PrestamoAdmin> { it.cobradores.isEmpty() }
                .thenByDescending { it.fechaCreacionTimestamp?.toDate() })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val titulo = if (esCobrador) {
                            if (mostrarEliminados) "Mis préstamos eliminados" else "Mis préstamos"
                        } else {
                            if (mostrarEliminados) "Préstamos eliminados" else "Préstamos"
                        }
                        Text(
                            titulo,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 18.sp
                        )
                        Text(
                            "Total: ${prestamosFiltrados.size}",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (mostrarEliminados) Color(0xFFD32F2F) else Color(0xFF0061A7)
                )
            )
        },
        floatingActionButton = {
            if (esAdmin && !mostrarEliminados) {
                FloatingActionButton(
                    onClick = { navController.navigate("crearPrestamo") },
                    containerColor = Color(0xFF0061A7),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("+", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Filtros compactos
            FiltrosCompactos(
                estadoSeleccionado = estadoSeleccionado,
                onEstadoChange = { estadoSeleccionado = it },
                search = search,
                onSearchChange = { search = it },
                mostrarEliminados = mostrarEliminados,
                onMostrarEliminadosChange = { mostrarEliminados = it },
                esAdmin = esAdmin,
                onResetFiltros = ::resetearFiltros
            )

            // Estadísticas rápidas (solo si no está cargando y hay préstamos)
            if (!cargando && prestamosFiltrados.isNotEmpty() && !mostrarEliminados) {
                EstadisticasRapidas(prestamosFiltrados)
            }

            // Mensaje de error
            if (errorMessage.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Error de conexión",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                if (!hayConexion) {
                    OutlinedButton(
                        onClick = { configurarListenerFirebase() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF0061A7)
                        )
                    ) {
                        Text("Reintentar conexión")
                    }
                }
            }

            // Contenido principal
            when {
                cargando -> {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = if (mostrarEliminados) Color(0xFFD32F2F) else Color(0xFF0061A7),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Cargando préstamos...",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                prestamosFiltrados.isEmpty() && errorMessage.isBlank() -> {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "📋",
                                fontSize = 48.sp
                            )
                            Text(
                                if (mostrarEliminados) "No hay préstamos eliminados"
                                else "No se encontraron préstamos",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF666666)
                            )
                            Text(
                                if (prestamosOriginales.isEmpty()) {
                                    "No hay préstamos en la base de datos"
                                } else {
                                    "Ajusta los filtros para ver más resultados"
                                },
                                fontSize = 14.sp,
                                color = Color.Gray
                            )

                            if (prestamosOriginales.isEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { configurarListenerFirebase() },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color(0xFF0061A7)
                                        )
                                    ) {
                                        Text("Recargar")
                                    }

                                    if (!mostrarEliminados && esAdmin) {
                                        Button(
                                            onClick = { navController.navigate("crearPrestamo") },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF4CAF50)
                                            )
                                        ) {
                                            Text("+ Crear préstamo")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    // Lista de préstamos
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(prestamosFiltrados) { prestamo ->
                            val nombresCobradores by produceState(
                                initialValue = "Cargando...",
                                key1 = prestamo.id
                            ) {
                                value = obtenerNombresCobradores(prestamo.cobradores)
                            }

                            TarjetaPrestamo(
                                prestamo = prestamo,
                                nombresCobradores = nombresCobradores,
                                onVerClick = {
                                    navController.navigate("VerPrestamoScreen/${prestamo.id}/${uid}/${rol}")
                                },
                                onEditarClick = {
                                    navController.navigate("EditarPrestamoScreen/${prestamo.id}/$uid/$rol")
                                },
                                onEliminarClick = if (esAdmin && !prestamo.eliminado) {
                                    { prestamoAEliminar = prestamo }
                                } else null
                            )
                        }
                    }
                }
            }
        }
    }

    // Diálogo de confirmación para eliminar
    prestamoAEliminar?.let { prestamo ->
        AlertDialog(
            onDismissRequest = { prestamoAEliminar = null },
            title = {
                Text(
                    "¿Eliminar préstamo?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text("¿Deseas marcar como eliminado el préstamo de:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Cliente: ${prestamo.cliente}",
                        fontWeight = FontWeight.Bold
                    )
                    Text("Monto: L. ${"%.2f".format(prestamo.monto)}")
                    Text("Total: L. ${"%.2f".format(prestamo.totalPagar)}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Esta acción se puede revertir desde la vista de eliminados.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                db.collection("prestamos").document(prestamo.id).update(
                                    mapOf(
                                        "eliminado" to true,
                                        "fechaEliminacion" to Timestamp.now(),
                                        "eliminadoPor" to uid
                                    )
                                ).await()
                                Toast.makeText(
                                    context,
                                    "Préstamo marcado como eliminado",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Error: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                prestamoAEliminar = null
                            }
                        }
                    }
                ) {
                    Text(
                        "Eliminar",
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { prestamoAEliminar = null }) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
}