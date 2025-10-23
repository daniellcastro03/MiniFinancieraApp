package com.example.capitalexpressapp.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.minifinancieraapp.ui.models.ClienteModel
import com.example.minifinancieraapp.ui.models.PagoItem
import com.example.minifinancieraapp.ui.models.Prestamo
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerfilClienteScreen(
    clienteId: String,
    navController: NavController,
    rol: String = ""
) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    var cliente by remember { mutableStateOf<ClienteModel?>(null) }
    var prestamos by remember { mutableStateOf<List<Prestamo>>(emptyList()) }
    var pagos by remember { mutableStateOf<List<PagoItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Totales
    var totalPrestado by remember { mutableStateOf(0.0) }
    var totalAbonado by remember { mutableStateOf(0.0) }
    var totalPendiente by remember { mutableStateOf(0.0) }
    var totalSaldado by remember { mutableStateOf(0.0) }
    var prestamosActivos by remember { mutableStateOf(0) }
    var prestamosSaldados by remember { mutableStateOf(0) }

    // Extensión helper para sumOf seguro
    fun <T> List<T>.sumOfOrNull(selector: (T) -> Double?): Double {
        return this.mapNotNull { selector(it) }.sum()
    }

    // 🔄 Cargar datos desde Firestore
    LaunchedEffect(clienteId) {
        try {
            isLoading = true

            // 🔹 Cargar Cliente
            val clienteDoc = db.collection("clientes").document(clienteId).get().await()
            if (clienteDoc.exists()) {
                cliente = ClienteModel(
                    id = clienteDoc.getString("id") ?: clienteDoc.id,
                    nombre = clienteDoc.getString("nombre") ?: "",
                    identidad = clienteDoc.getString("identidad") ?: "",
                    telefono = clienteDoc.getString("telefono") ?: "",
                    direccionCasa = clienteDoc.getString("direccionCasa") ?: "",
                    direccionNegocio = clienteDoc.getString("direccionNegocio") ?: "",
                    estadoCivil = clienteDoc.getString("estadoCivil") ?: "",
                    nombreConyuge = clienteDoc.getString("nombreConyuge") ?: "",
                    identidadConyuge = clienteDoc.getString("identidadConyuge") ?: "",
                    telefonoConyuge = clienteDoc.getString("telefonoConyuge") ?: "",
                    referencia1Nombre = clienteDoc.getString("referencia1Nombre") ?: "",
                    referencia1Identidad = clienteDoc.getString("referencia1Identidad") ?: "",
                    referencia1Telefono = clienteDoc.getString("referencia1Telefono") ?: "",
                    referencia1Parentesco = clienteDoc.getString("referencia1Parentesco") ?: "",
                    referencia1Direccion = clienteDoc.getString("referencia1Direccion") ?: "",
                    referencia2Nombre = clienteDoc.getString("referencia2Nombre") ?: "",
                    referencia2Identidad = clienteDoc.getString("referencia2Identidad") ?: "",
                    referencia2Telefono = clienteDoc.getString("referencia2Telefono") ?: "",
                    referencia2Parentesco = clienteDoc.getString("referencia2Parentesco") ?: "",
                    referencia2Direccion = clienteDoc.getString("referencia2Direccion") ?: "",
                    fotoCasaUrl = clienteDoc.getString("fotoCasaUrl") ?: "",
                    fotoNegocioUrl = clienteDoc.getString("fotoNegocioUrl") ?: "",
                    fotoClienteUrl = clienteDoc.getString("fotoClienteUrl") ?: "",
                    fotoIdentidadFrenteUrl = clienteDoc.getString("fotoIdentidadFrenteUrl") ?: "",
                    fotoIdentidadReversoUrl = clienteDoc.getString("fotoIdentidadReversoUrl") ?: "",
                    fotoReciboLuzUrl = clienteDoc.getString("fotoReciboLuzUrl") ?: "",
                    garantiaTexto = clienteDoc.getString("garantiaTexto") ?: "",
                    garantiaFotoUrl = clienteDoc.getString("garantiaFotoUrl") ?: "",
                    estado = clienteDoc.getString("estado") ?: "activo",
                    tienePrestamo = clienteDoc.getBoolean("tienePrestamo") ?: false,
                    cobradorAsignado = clienteDoc.getString("cobradorAsignado") ?: ""
                )
            }

            // 🔹 Cargar Préstamos con mapeo seguro y fechas corregidas
            val prestamosSnap = db.collection("prestamos")
                .whereEqualTo("clienteId", clienteId)
                .get()
                .await()

            prestamos = prestamosSnap.documents.mapNotNull { doc ->
                try {
                    // Helper para parsear proximoPago
                    val proximoPago = when (val value = doc.get("proximoPago")) {
                        is Timestamp -> value
                        is String -> {
                            try {
                                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                sdf.parse(value)?.let { Timestamp(it) }
                            } catch (e: Exception) {
                                null
                            }
                        }
                        else -> null
                    }

                    // Helper para parsear fechaCreacion con compatibilidad mixta
                    val fechaCreacion = when (val value = doc.get("fechaCreacion")) {
                        is Timestamp -> value
                        is String -> {
                            try {
                                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                val date = sdf.parse(value)
                                date?.let { Timestamp(it) }
                            } catch (e: Exception) {
                                null
                            }
                        }
                        else -> null
                    }

                    // Helper para parsear fechaUltimaActualizacion con compatibilidad mixta
                    val fechaUltimaActualizacion = when (val value = doc.get("fechaUltimaActualizacion")) {
                        is Timestamp -> value
                        is String -> {
                            try {
                                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                                val date = sdf.parse(value)
                                date?.let { Timestamp(it) }
                            } catch (e: Exception) {
                                null
                            }
                        }
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
                        fechaCreacion = fechaCreacion,
                        fechaUltimaActualizacion = fechaUltimaActualizacion,
                        fotos = (doc.get("fotos") as? List<String>) ?: emptyList(),
                        proximoPago = proximoPago
                    )
                } catch (e: Exception) {
                    null // Ignorar préstamos con errores
                }
            }

            // 🔹 Cargar Pagos con manejo de errores
            try {
                val pagosSnap = db.collection("pagos")
                    .whereEqualTo("clienteId", clienteId)
                    .get()
                    .await()

                pagos = pagosSnap.documents.map { doc ->
                    PagoItem(
                        docId = doc.id,
                        cliente = doc.getString("clienteNombre") ?: "",
                        prestamoId = doc.getString("prestamoId") ?: "",
                        fecha = doc.getTimestamp("fechaPago")?.toDate()?.let {
                            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(it)
                        } ?: "",
                        monto = doc.getDouble("monto") ?: 0.0,
                        mora = doc.getDouble("mora") ?: 0.0,
                        interesTotal = doc.getDouble("interesTotal") ?: 0.0,
                        cuota = doc.getLong("cuota")?.toString() ?: "",
                        cobrador = doc.getString("nombreCobrador") ?: "",
                        lugar = doc.getString("lugar") ?: "",
                        firma = doc.getString("firma") ?: "",
                        tipoPago = doc.getString("metodoPago") ?: "",
                        saldoRestante = doc.getDouble("saldoRestante") ?: 0.0,
                        numeroPrestamo = doc.getLong("numeroPrestamo")?.toInt() ?: 0
                    )
                }
            } catch (e: Exception) {
                // Si falla cargar pagos, continuar con lista vacía
                pagos = emptyList()
            }

            // 🔹 Calcular totales con validaciones seguras
            totalPrestado = prestamos.sumOf { p ->
                when {
                    p.totalPagar > 0 -> p.totalPagar
                    else -> p.monto + (p.interesTotal ?: p.interes)
                }
            }

            totalPendiente = prestamos.sumOf { p ->
                when {
                    p.saldo > 0 -> p.saldo
                    else -> {
                        val totalAPagar = if (p.totalPagar > 0) p.totalPagar
                        else p.monto + (p.interesTotal ?: p.interes)
                        maxOf(0.0, totalAPagar - p.montoPagado)
                    }
                }
            }

            totalAbonado = pagos.sumOf { it.monto }
            prestamosActivos = prestamos.count { it.estado.equals("activo", true) }
            prestamosSaldados = prestamos.count { it.estado.equals("saldado", true) }
            totalSaldado = prestamos.filter { it.estado.equals("saldado", true) }
                .sumOf { if (it.totalPagar > 0) it.totalPagar else it.monto + (it.interesTotal ?: it.interes) }

        } catch (e: Exception) {
            Toast.makeText(context, "Error cargando datos: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            isLoading = false
        }
    }

    // 🗑️ Función para borrar cliente
    fun borrarCliente() {
        scope.launch {
            try {
                // Borrar el cliente de Firestore
                db.collection("clientes").document(clienteId).delete().await()

                Toast.makeText(context, "Cliente eliminado correctamente", Toast.LENGTH_SHORT).show()
                navController.popBackStack()
            } catch (e: Exception) {
                Toast.makeText(context, "Error al eliminar cliente: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 🚨 Diálogo de confirmación para borrar
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    "⚠️ Confirmar Eliminación",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F)
                )
            },
            text = {
                Column {
                    Text("¿Estás seguro de que deseas eliminar este cliente?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Esta acción NO se puede deshacer.",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD32F2F)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Nota: Los préstamos y pagos asociados NO serán eliminados automáticamente.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        borrarCliente()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F)
                    )
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // 🎨 UI Mejorado
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = Color(0xFF667eea)
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF667eea),
                        Color(0xFF764ba2),
                        Color(0xFFf093fb)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Header con información del cliente
            cliente?.let { clienteData ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Avatar del cliente
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(Color(0xFF4CAF50), Color(0xFF2E7D32))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Cliente",
                                modifier = Modifier.size(40.dp),
                                tint = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = clienteData.nombre,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )

                        Text(
                            text = "ID: ${clienteData.identidad}",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Información adicional del cliente
                        ClientInfoRow("Dirección", clienteData.direccionCasa)
                        ClientInfoRow("Referencia", clienteData.referencia1Nombre)
                        ClientInfoRow("Teléfono", clienteData.telefono)

                        Spacer(modifier = Modifier.height(20.dp))

                        // Botones de contacto mejorados
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ContactButton(
                                text = "Llamar",
                                icon = Icons.Default.Call,
                                color = Color(0xFF4CAF50),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (clienteData.telefono.isNotEmpty()) {
                                    val intent = Intent(Intent.ACTION_DIAL).apply {
                                        data = Uri.parse("tel:${clienteData.telefono}")
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "No se pudo abrir la aplicación de teléfono", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "El cliente no tiene número de teléfono registrado", Toast.LENGTH_SHORT).show()
                                }
                            }

                            ContactButton(
                                text = "WhatsApp",
                                icon = Icons.Default.Message,
                                color = Color(0xFF25D366),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (clienteData.telefono.isNotEmpty()) {
                                    // Obtener la hora actual para personalizar el saludo
                                    val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                                    val saludo = when {
                                        currentHour < 12 -> "Buenos días"
                                        currentHour < 18 -> "Buenas tardes"
                                        else -> "Buenas noches"
                                    }

                                    // Crear el mensaje personalizado
                                    val mensaje = "$saludo ${clienteData.nombre}, le hablamos desde Capital Express"

                                    // Codificar el mensaje para URL
                                    val mensajeCodificado = java.net.URLEncoder.encode(mensaje, "UTF-8")

                                    // Crear la URL de WhatsApp con el mensaje
                                    val url = "https://wa.me/504${clienteData.telefono}?text=$mensajeCodificado"

                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse(url)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "El cliente no tiene número de teléfono registrado", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }

            // Resumen Financiero
            Text(
                "Resumen Financiero",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Métricas principales
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                EnhancedMetricCard(
                    label = "Total Prestado",
                    valor = totalPrestado,
                    icon = Icons.Default.AccountBalance,
                    color = Color(0xFF2196F3),
                    modifier = Modifier.weight(1f)
                )
                EnhancedMetricCard(
                    label = "Total Abonado",
                    valor = totalAbonado,
                    icon = Icons.Default.TrendingUp,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                EnhancedMetricCard(
                    label = "Saldo Pendiente",
                    valor = totalPendiente,
                    icon = Icons.Default.Schedule,
                    color = Color(0xFFFF9800),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Estado de Préstamos
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Estado de Préstamos",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        StatusCard(
                            label = "Activos",
                            count = prestamosActivos,
                            color = Color(0xFFFF5722),
                            modifier = Modifier.weight(1f)
                        )
                        StatusCard(
                            label = "Saldados",
                            count = prestamosSaldados,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 🆕 Sección de Acciones del Cliente
            Text(
                "Acciones del Cliente",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Botones de navegación en grid dinámico
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Primera fila
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    NavigationButton(
                        text = "Editar Cliente",
                        icon = Icons.Default.Edit,
                        color = Color(0xFF2196F3),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("EditarClienteScreen/$clienteId")
                    }

                    NavigationButton(
                        text = "Ver Detalles",
                        icon = Icons.Default.Info,
                        color = Color(0xFF9C27B0),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("DetalleClienteScreen/$clienteId")
                    }
                }

                // Segunda fila - Solo admin puede ver Asignar Cobrador
                if (rol == "admin") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NavigationButton(
                            text = "Asignar Cobrador",
                            icon = Icons.Default.Assignment,
                            color = Color(0xFFFF5722),
                            modifier = Modifier.weight(1f)
                        ) {
                            navController.navigate("AsignarCobradorScreen/$clienteId")
                        }

                        NavigationButton(
                            text = "Borrar Cliente",
                            icon = Icons.Default.Delete,
                            color = Color(0xFFD32F2F),
                            modifier = Modifier.weight(1f)
                        ) {
                            showDeleteDialog = true
                        }
                    }
                }

                // Botón de regresar
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    NavigationButton(
                        text = "Regresar",
                        icon = Icons.Default.ArrowBack,
                        color = Color(0xFF6200EA),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        navController.popBackStack()
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun NavigationButton(
    text: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = color
            ),
            shape = RoundedCornerShape(12.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = text,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun ClientInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.Gray
        )
        Text(
            text = value.ifEmpty { "No especificado" },
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = Color(0xFF2E7D32)
        )
    }
}

@Composable
fun ContactButton(
    text: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(12.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        Icon(icon, contentDescription = text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EnhancedMetricCard(
    label: String,
    valor: Double,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Gray
            )

            Text(
                "L. ${"%.2f".format(valor)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun StatusCard(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                count.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = color.copy(alpha = 0.8f)
            )
        }
    }
}

// 📝 Función helper para formatear fechas (si necesitas mostrarlas en la UI)
fun formatearFecha(timestamp: Timestamp?): String {
    return timestamp?.toDate()?.let {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(it)
    } ?: "Sin fecha"
}