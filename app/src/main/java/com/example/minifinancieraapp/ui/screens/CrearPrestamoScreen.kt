package com.example.capitalexpressapp.ui.screens

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.example.capitalexpressapp.ui.components.PrimaryTextField
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.minifinancieraapp.ui.models.ClienteModel
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// Función para verificar préstamos activos de un cliente
suspend fun verificarPrestamosActivos(db: FirebaseFirestore, clienteId: String): Pair<Int, List<String>> {
    return try {
        val snapshot = db.collection("prestamos")
            .whereEqualTo("clienteId", clienteId)
            .whereEqualTo("estado", "activo")
            .get()
            .await()

        // Filtrar préstamos que realmente tienen saldo pendiente
        val prestamosConSaldo = snapshot.documents.filter { doc ->
            val saldo = doc.getDouble("saldo") ?: 0.0
            val totalPagar = doc.getDouble("totalPagar") ?: 0.0
            val montoPagado = doc.getDouble("montoPagado") ?: 0.0

            // Considerar activo si tiene saldo > 0 Y aún no está completamente pagado
            saldo > 0.0 && montoPagado < totalPagar
        }

        val numerosPrestamo = prestamosConSaldo.mapNotNull { doc ->
            doc.id
        }

        Pair(prestamosConSaldo.size, numerosPrestamo)
    } catch (e: Exception) {
        Pair(0, emptyList())
    }
}

// Función para contar días excluyendo domingos
fun contarDiasSinDomingos(diasNecesarios: Int, fechaInicio: Calendar = Calendar.getInstance()): Int {
    var diasEfectivos = 0
    var diasTotales = 0
    val fecha = fechaInicio.clone() as Calendar

    while (diasEfectivos < diasNecesarios) {
        val diaSemana = fecha.get(Calendar.DAY_OF_WEEK)
        if (diaSemana != Calendar.SUNDAY) {
            diasEfectivos++
        }
        diasTotales++
        fecha.add(Calendar.DAY_OF_YEAR, 1)
    }
    return diasTotales
}

// Función corregida para calcular días efectivos según el tipo de plazo
fun calcularDiasEfectivos(plazo: String, cuotas: Int, fechaInicio: Calendar): Int {
    return when (plazo) {
        "Diario" -> {
            cuotas
        }
        "Lunes a Sábado" -> {
            contarDiasSinDomingos(cuotas, fechaInicio)
        }
        "Semanal" -> {
            contarDiasSinDomingos(cuotas * 6, fechaInicio)
        }
        "Quincenal" -> {
            cuotas * 15
        }
        "Mensual" -> {
            cuotas * 30
        }
        "Bimestral" -> {
            cuotas * 60
        }
        else -> cuotas * 30
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrearPrestamoScreen(navController: NavController, clienteId: String) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // Estados de autenticación del cobrador actual
    var currentUid by remember { mutableStateOf("") }
    var nombreCobrador by remember { mutableStateOf("") }
    var numeroCobrador by remember { mutableStateOf("") }
    var isLoadingAuth by remember { mutableStateOf(true) }

    // Valores del formulario
    val clientes = remember { mutableStateListOf<ClienteModel>() }
    var selectedCliente by remember { mutableStateOf<ClienteModel?>(null) }
    var monto by remember { mutableStateOf("") }
    var interesMensual by remember { mutableStateOf("") }
    var cuotas by remember { mutableStateOf("") }

    // Estado para préstamos activos del cliente seleccionado
    var prestamosActivosCliente by remember { mutableStateOf(0) }

    var interesTotal by remember { mutableStateOf("") }
    var usarInteresMensual by remember { mutableStateOf(true) }

    var mora by remember { mutableStateOf("") }
    var selectedPlazo by remember { mutableStateOf("Semanal") }
    var lugar by remember { mutableStateOf("") }
    var firma by remember { mutableStateOf("") }
    var observaciones by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var reciboGenerado by remember { mutableStateOf<File?>(null) }

    // Variables para fecha
    var fechaSeleccionada by remember { mutableStateOf(Calendar.getInstance()) }

    // Variables para fotos
    var fotosSeleccionadas by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var fotoUriCamara by remember { mutableStateOf<Uri?>(null) }

    // Obtener datos del cobrador actual
    LaunchedEffect(Unit) {
        try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val session = com.example.minifinancieraapp.util.SessionManager(context)

            var uid: String? = auth.currentUser?.uid
            if (uid.isNullOrEmpty()) uid = session.getUid()

            if (!uid.isNullOrEmpty()) {
                currentUid = uid
                val userDoc = db.collection("usuarios").document(uid).get().await()
                nombreCobrador = userDoc.getString("nombre") ?: "Administrador"
                numeroCobrador = userDoc.getString("telefono") ?: "N/D"
            } else {
                nombreCobrador = "Administrador"
                numeroCobrador = "N/D"
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar datos del usuario: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isLoadingAuth = false
        }
    }

    // Verificar préstamos activos cuando cambie el cliente
    LaunchedEffect(selectedCliente) {
        if (selectedCliente != null) {
            scope.launch {
                val (activos, _) = verificarPrestamosActivos(db, selectedCliente!!.id)
                prestamosActivosCliente = activos
            }
        } else {
            prestamosActivosCliente = 0
        }
    }

    // Crear archivo temporal para cámara
    val crearArchivoTemporal = remember {
        {
            try {
                val timeStamp =
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val imageFileName = "JPEG_${timeStamp}_"
                val storageDir = context.getExternalFilesDir("Pictures")

                if (storageDir != null && !storageDir.exists()) {
                    storageDir.mkdirs()
                }

                val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    imageFile
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    // Launchers para fotos
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            fotosSeleccionadas = fotosSeleccionadas + uris
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && fotoUriCamara != null) {
            fotosSeleccionadas = fotosSeleccionadas + listOf(fotoUriCamara!!)
            Toast.makeText(context, "Foto capturada exitosamente", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No se pudo capturar la foto", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = crearArchivoTemporal()
            if (uri != null) {
                fotoUriCamara = uri
                cameraLauncher.launch(uri)
            } else {
                Toast.makeText(context, "Error al crear archivo temporal", Toast.LENGTH_SHORT)
                    .show()
            }
        } else {
            Toast.makeText(
                context,
                "Permiso de cámara necesario para tomar fotos",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Cargar clientes
    LaunchedEffect(Unit) {
        try {
            val snapshot = db.collection("clientes").get().await()
            clientes.clear()
            val clientesList = snapshot.documents.mapNotNull { doc ->
                val nombre = doc.getString("nombre") ?: return@mapNotNull null
                val telefono = doc.getString("telefono") ?: ""
                val direccion = doc.getString("direccionCasa") ?: ""
                ClienteModel(
                    id = doc.id,
                    nombre = nombre,
                    telefono = telefono,
                    direccionCasa = direccion,
                    identidad = doc.getString("identidad") ?: ""
                )
            }
            clientes.addAll(clientesList)
        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar clientes: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    if (isLoadingAuth) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {

        // TÍTULO
        Text(
            text = "Crear Préstamo",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )

        // Dropdown de búsqueda de cliente
        BuscarClienteDropdownMejorado(clientes, selectedCliente) { selectedCliente = it }

        Spacer(modifier = Modifier.height(8.dp))

        // Card informativo sobre préstamos activos del cliente
        if (selectedCliente != null && prestamosActivosCliente > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (prestamosActivosCliente) {
                        1 -> Color(0xFFFFF3E0) // Naranja claro para advertencia
                        else -> Color(0xFFFFEBEE) // Rojo claro para máximo
                    }
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (prestamosActivosCliente == 1) Icons.Default.Warning else Icons.Default.Block,
                            contentDescription = null,
                            tint = if (prestamosActivosCliente == 1) Color(0xFFE65100) else Color(0xFFD32F2F),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Préstamos activos del cliente",
                            fontWeight = FontWeight.Bold,
                            color = if (prestamosActivosCliente == 1) Color(0xFFE65100) else Color(0xFFD32F2F)
                        )
                    }

                    Text(
                        when (prestamosActivosCliente) {
                            1 -> "AVISO: ${selectedCliente!!.nombre} tiene 1 préstamo activo. Después de este tendrá 2 (máximo permitido)."
                            else -> "BLOQUEADO: ${selectedCliente!!.nombre} ya tiene $prestamosActivosCliente préstamos activos. No se puede crear más."
                        },
                        color = if (prestamosActivosCliente == 1) Color(0xFFE65100) else Color(0xFFD32F2F),
                        fontSize = 14.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = monto,
            onValueChange = { monto = it },
            label = { Text("Monto") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Configuración de Interés
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Configuración de Interés", fontWeight = FontWeight.Bold)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = usarInteresMensual,
                        onClick = {
                            usarInteresMensual = true
                            interesTotal = ""
                        }
                    )
                    Text("Interés mensual %")

                    Spacer(modifier = Modifier.width(16.dp))

                    RadioButton(
                        selected = !usarInteresMensual,
                        onClick = {
                            usarInteresMensual = false
                            interesMensual = ""
                        }
                    )
                    Text("Interés total fijo")
                }

                if (usarInteresMensual) {
                    OutlinedTextField(
                        value = interesMensual,
                        onValueChange = { interesMensual = it },
                        label = { Text("Interés mensual %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = interesTotal,
                        onValueChange = { interesTotal = it },
                        label = { Text("Interés Total (L.)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("El interés total que se agregará al monto", fontSize = 12.sp) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = cuotas,
            onValueChange = { cuotas = it },
            label = { Text("Cuotas") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // CAMPO DE MORA
        OutlinedTextField(
            value = mora,
            onValueChange = { mora = it },
            label = { Text("Mora diaria (L.)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Monto que se cobrará por día de retraso", fontSize = 12.sp) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Campo de fecha con calendario
        OutlinedTextField(
            value = formatter.format(fechaSeleccionada.time),
            onValueChange = { },
            label = { Text("Fecha") },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            trailingIcon = {
                IconButton(onClick = {
                    val datePickerDialog = DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            fechaSeleccionada.set(Calendar.YEAR, year)
                            fechaSeleccionada.set(Calendar.MONTH, month)
                            fechaSeleccionada.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                        },
                        fechaSeleccionada.get(Calendar.YEAR),
                        fechaSeleccionada.get(Calendar.MONTH),
                        fechaSeleccionada.get(Calendar.DAY_OF_MONTH)
                    )
                    datePickerDialog.show()
                }) {
                    Icon(Icons.Default.DateRange, contentDescription = "Seleccionar fecha")
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // CAMPOS ADICIONALES
        OutlinedTextField(
            value = lugar,
            onValueChange = { lugar = it },
            label = { Text("Lugar") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = firma,
            onValueChange = { firma = it },
            label = { Text("Firma") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = observaciones,
            onValueChange = { observaciones = it },
            label = { Text("Observaciones") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Botones para fotos
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
                        PackageManager.PERMISSION_GRANTED -> {
                            val uri = crearArchivoTemporal()
                            if (uri != null) {
                                fotoUriCamara = uri
                                cameraLauncher.launch(uri)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Error al crear archivo temporal",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        else -> {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Camera, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Cámara")
            }

            Button(
                onClick = {
                    galleryLauncher.launch("image/*")
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Galería")
            }
        }

        // Mostrar fotos seleccionadas
        if (fotosSeleccionadas.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Fotos seleccionadas (${fotosSeleccionadas.size}):", fontWeight = FontWeight.Bold)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                items(fotosSeleccionadas.withIndex().toList()) { (index, uri) ->
                    Card(
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box {
                            Image(
                                painter = rememberAsyncImagePainter(uri),
                                contentDescription = "Foto ${index + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = {
                                    fotosSeleccionadas =
                                        fotosSeleccionadas.filterIndexed { i, _ -> i != index }
                                },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Eliminar",
                                    tint = Color.Red,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Plazo", fontWeight = FontWeight.Bold)

        // RADIO BUTTONS PARA PLAZOS
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("Diario", "Lunes a Sábado", "Semanal").forEach { plazo ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        RadioButton(
                            selected = selectedPlazo == plazo,
                            onClick = { selectedPlazo = plazo })
                        Text(plazo, fontSize = 12.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("Quincenal", "Mensual", "Bimestral").forEach { plazo ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        RadioButton(
                            selected = selectedPlazo == plazo,
                            onClick = { selectedPlazo = plazo })
                        Text(plazo, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // CÁLCULOS
        val montoDouble = monto.toDoubleOrNull() ?: 0.0
        val interesPct = interesMensual.toDoubleOrNull() ?: 0.0
        val cuotasInt = cuotas.toIntOrNull() ?: 1
        val interesTotalDouble = interesTotal.toDoubleOrNull() ?: 0.0
        val moraDouble = mora.toDoubleOrNull() ?: 0.0

        val fechaInicio = fechaSeleccionada.clone() as Calendar
        val diasEfectivos = calcularDiasEfectivos(selectedPlazo, cuotasInt, fechaInicio)
        val mesesAproximados = diasEfectivos / 30.0

        // Calcular próximo pago
        val proximoCal = fechaSeleccionada.clone() as Calendar
        when (selectedPlazo) {
            "Diario" -> {
                proximoCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            "Lunes a Sábado" -> {
                do {
                    proximoCal.add(Calendar.DAY_OF_YEAR, 1)
                } while (proximoCal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
            "Semanal" -> {
                proximoCal.add(Calendar.DAY_OF_YEAR, 7)
            }
            "Quincenal" -> {
                proximoCal.add(Calendar.DAY_OF_YEAR, 15)
            }
            "Mensual" -> {
                proximoCal.add(Calendar.MONTH, 1)
            }
            "Bimestral" -> {
                proximoCal.add(Calendar.MONTH, 2)
            }
        }
        val proximoPagoString = formatter.format(proximoCal.time)
        val proximoPagoTimestamp = Timestamp(proximoCal.time)

        // Cálculo de intereses
        val (interesCalculado, totalAPagar, cuotaEstimada) = if (!usarInteresMensual && interesTotalDouble > 0) {
            val total = montoDouble + interesTotalDouble
            val cuota = if (cuotasInt > 0) (total / cuotasInt).roundToInt().toDouble() else 0.0
            Triple(interesTotalDouble, total, cuota)
        } else if (usarInteresMensual && interesPct > 0) {
            val interesMensualMonto = montoDouble * (interesPct / 100)

            val interesCalculadoTotal = when (selectedPlazo) {
                "Mensual" -> {
                    interesMensualMonto * cuotasInt
                }
                "Lunes a Sábado" -> {
                    val semanasLaborables = diasEfectivos / 6.0
                    val mesesEquivalentes = semanasLaborables / 4.0
                    interesMensualMonto * mesesEquivalentes
                }
                "Semanal" -> {
                    val semanasTotal = cuotasInt.toDouble()
                    val mesesEquivalentes = semanasTotal / 4.0
                    interesMensualMonto * mesesEquivalentes
                }
                else -> {
                    interesMensualMonto * (diasEfectivos / 30.0)
                }
            }

            val total = montoDouble + interesCalculadoTotal
            val cuota = if (cuotasInt > 0) (total / cuotasInt).roundToInt().toDouble() else 0.0

            Triple(interesCalculadoTotal, total, cuota)
        } else {
            Triple(0.0, montoDouble, montoDouble / cuotasInt.coerceAtLeast(1))
        }

        val descripcionPlazo = when (selectedPlazo) {
            "Diario" -> "Incluye domingos"
            "Lunes a Sábado" -> "Excluye domingos"
            "Semanal" -> "Cada 7 días"
            "Quincenal" -> "Cada 15 días"
            "Mensual" -> "Cada 30 días"
            "Bimestral" -> "Cada 60 días"
            else -> ""
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Resumen del préstamo", fontWeight = FontWeight.Bold)
                Text("Monto solicitado: L. ${"%.2f".format(montoDouble)}")
                if (!usarInteresMensual && interesTotalDouble > 0) {
                    Text("Interés Total fijo: L. ${"%.2f".format(interesTotalDouble)}")
                } else if (usarInteresMensual) {
                    Text("Interés aplicado: ${"%.1f".format(interesPct)}% mensual")
                }
                Text("Días efectivos: $diasEfectivos días ≈ ${"%.2f".format(mesesAproximados)} meses")
                Text("Interés total: L. ${"%.2f".format(interesCalculado)}")
                Text("Total a pagar: L. ${"%.2f".format(totalAPagar)}")
                Text("Cuotas: $cuotasInt ($selectedPlazo - $descripcionPlazo)")
                Text("Cuota estimada: L. ${"%.0f".format(cuotaEstimada)}")
                Text("Próximo pago: $proximoPagoString", fontWeight = FontWeight.Medium, color = Color(0xFF1976D2))
                if (moraDouble > 0) {
                    Text("Mora diaria: L. ${"%.2f".format(moraDouble)}", color = Color(0xFFD32F2F))
                }
                if (fotosSeleccionadas.isNotEmpty()) {
                    Text("Fotos adjuntas: ${fotosSeleccionadas.size}")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (selectedCliente == null || montoDouble <= 0 || cuotasInt <= 0) {
                    Toast.makeText(context, "Verifica los datos ingresados", Toast.LENGTH_SHORT)
                        .show()
                    return@Button
                }

                if (!usarInteresMensual && interesTotalDouble <= 0 && (usarInteresMensual && interesPct <= 0)) {
                    Toast.makeText(context, "Debe configurar un interés válido", Toast.LENGTH_SHORT)
                        .show()
                    return@Button
                }

                isLoading = true

                scope.launch {
                    try {
                        // VALIDACIÓN: Verificar préstamos activos
                        val (prestamosActivos, numerosPrestamo) = verificarPrestamosActivos(db, selectedCliente!!.id)

                        if (prestamosActivos >= 2) {
                            isLoading = false
                            Toast.makeText(
                                context,
                                "BLOQUEADO: El cliente ${selectedCliente!!.nombre} ya tiene $prestamosActivos préstamos activos. No se pueden crear más de 2 préstamos activos por cliente.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }

                        // Mostrar información de préstamos activos existentes
                        if (prestamosActivos == 1) {
                            Toast.makeText(
                                context,
                                "AVISO: Este cliente ya tiene 1 préstamo activo. Después de este tendrá 2 préstamos (máximo permitido).",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        // ⭐⭐⭐ GENERAR NÚMERO ÚNICO DE PRÉSTAMO ⭐⭐⭐
                        val numeroPrestamoUnico = com.example.capitalexpressapp.util.PrestamoNumberHelper.generarNumeroPrestamo(
                            clienteNombre = selectedCliente!!.nombre,
                            db = db
                        )

                        val docRef = db.collection("prestamos").document()
                        val prestamoId = docRef.id
                        val fecha = formatter.format(fechaSeleccionada.time)

                        val prestamo = hashMapOf(
                            "id" to prestamoId,
                            "numeroPrestamo" to numeroPrestamoUnico,
                            "cliente" to selectedCliente!!.nombre,
                            "clienteId" to selectedCliente!!.id,
                            "monto" to montoDouble,
                            "interes" to interesCalculado,
                            "interesMensual" to if (usarInteresMensual) interesPct else 0.0,
                            "interesTotal" to if (!usarInteresMensual) interesTotalDouble else interesCalculado,
                            "usarInteresMensual" to usarInteresMensual,
                            "interesTotalFijo" to if (!usarInteresMensual) interesTotalDouble else 0.0,
                            "mora" to moraDouble,
                            "totalPagar" to totalAPagar,
                            "cuota" to cuotaEstimada,
                            "cuotas" to cuotasInt,
                            "plazo" to selectedPlazo,
                            "fecha" to Timestamp(fechaSeleccionada.time),
                            "fechaCreacion" to Timestamp(Date()),
                            "lugar" to lugar,
                            "firma" to firma,
                            "cobrador" to nombreCobrador,
                            "numeroCobrador" to numeroCobrador,
                            "cobradorUid" to currentUid,
                            "cobradoresAsignados" to listOf(currentUid), // ✅ AGREGAR ESTO - Array de cobradores
                            "prestamoId" to prestamoId,
                            "proximoPago" to proximoPagoTimestamp,
                            "montoPagado" to 0.0,
                            "saldoAnterior" to montoDouble,
                            "estado" to "activo",
                            "observaciones" to observaciones,
                            "fotos" to fotosSeleccionadas.map { it.toString() },
                            "diasEfectivos" to diasEfectivos.toDouble(),
                            "saldo" to totalAPagar,
                            "eliminado" to false // ✅ AGREGAR ESTO - Para el filtro de eliminados
                        )

                        docRef.set(prestamo).await()

                        // Mostrar confirmación con información de préstamos totales
                        val nuevoTotal = prestamosActivos + 1
                        Toast.makeText(
                            context,
                            "Préstamo creado exitosamente.\nNúmero: $numeroPrestamoUnico\nCliente ahora tiene $nuevoTotal préstamo(s) activo(s).",
                            Toast.LENGTH_LONG
                        ).show()

                        try {
                            val reciboFile = ReciboHelper.generarReciboPrestamoPDF(
                                context = context,
                                cliente = selectedCliente!!,
                                monto = montoDouble,
                                interesTotal = interesCalculado,
                                mora = moraDouble,
                                cuotas = cuotasInt,
                                fecha = fecha,
                                lugar = lugar,
                                numeroCobrador = numeroCobrador,
                                numeroPrestamo = numeroPrestamoUnico, // ⭐ USAR NUEVO NÚMERO
                                nombreCobrador = nombreCobrador,
                                fechaProximoPago = proximoPagoString
                            )

                            if (reciboFile != null && reciboFile.exists()) {
                                reciboGenerado = reciboFile

                                ReciboHelper.imprimirPDF(context, reciboFile)

                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    reciboFile
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Aquí está el recibo del préstamo generado desde Capital Express."
                                    )
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        intent,
                                        "Compartir recibo con:"
                                    )
                                )

                                Toast.makeText(
                                    context,
                                    "Préstamo guardado, impreso y listo para compartir",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Préstamo guardado, pero error al generar recibo",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                        } catch (reciboError: Exception) {
                            Toast.makeText(
                                context,
                                "Préstamo guardado. Error al imprimir: ${reciboError.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            reciboError.printStackTrace()
                        }

                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Error al guardar préstamo: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        e.printStackTrace()
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && (selectedCliente == null || prestamosActivosCliente < 2)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (selectedCliente != null && prestamosActivosCliente >= 2)
                            "Bloqueado (2+ préstamos activos)"
                        else
                            "Guardar e Imprimir"
                    )
                }
            }
        }

        reciboGenerado?.let { archivo ->
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    try {
                        ReciboHelper.imprimirPDF(context, archivo)

                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            archivo
                        )

                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Aquí está el recibo del préstamo generado desde Capital Express."
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Compartir recibo con:"))

                        Toast.makeText(
                            context,
                            "Reimprimiendo y compartiendo recibo...",
                            Toast.LENGTH_SHORT
                        ).show()

                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Error al reimprimir: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        e.printStackTrace()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))
            ) {
                Icon(Icons.Default.Print, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reimprimir recibo", color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuscarClienteDropdownMejorado(
    clientes: List<ClienteModel>,
    selectedCliente: ClienteModel?,
    onClienteSeleccionado: (ClienteModel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var textoBusqueda by remember { mutableStateOf(selectedCliente?.nombre ?: "") }
    var textoBusquedaInterna by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(textoBusquedaInterna) {
        kotlinx.coroutines.delay(300)
        textoBusqueda = textoBusquedaInterna
    }

    val clientesFiltrados = if (textoBusqueda.isBlank()) {
        emptyList()
    } else {
        clientes.filter {
            it.nombre.contains(textoBusqueda, ignoreCase = true) ||
                    it.identidad.contains(textoBusqueda, ignoreCase = true)
        }.take(10)
    }

    LaunchedEffect(selectedCliente) {
        if (selectedCliente != null && selectedCliente.nombre.isNotEmpty()) {
            textoBusquedaInterna = selectedCliente.nombre
            textoBusqueda = selectedCliente.nombre
        }
    }

    Column {
        OutlinedTextField(
            value = textoBusquedaInterna,
            onValueChange = { newText ->
                textoBusquedaInterna = newText
                if (newText.isNotEmpty() && newText.length >= 2) {
                    expanded = true
                } else {
                    expanded = false
                }
            },
            label = { Text("Buscar cliente *") },
            placeholder = { Text("Escriba nombre o identidad...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Buscar",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                if (textoBusquedaInterna.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            textoBusquedaInterna = ""
                            textoBusqueda = ""
                            expanded = false
                            onClienteSeleccionado(ClienteModel())
                        }
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Limpiar"
                        )
                    }
                }
            },
            isError = textoBusqueda.length >= 2 && clientesFiltrados.isEmpty(),
            supportingText = {
                when {
                    textoBusqueda.isEmpty() -> {
                        Text("Escriba al menos 2 caracteres para buscar")
                    }
                    textoBusqueda.length >= 2 && clientesFiltrados.isEmpty() -> {
                        Text(
                            "No se encontraron clientes",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    textoBusqueda.length >= 2 && clientesFiltrados.isNotEmpty() -> {
                        Text(
                            "${clientesFiltrados.size} cliente(s) encontrado(s)",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    else -> {
                        Text("Continúe escribiendo...")
                    }
                }
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
        ) {
            if (clientesFiltrados.isEmpty() && textoBusqueda.length >= 2) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "No se encontraron clientes",
                            color = MaterialTheme.colorScheme.outline
                        )
                    },
                    onClick = { },
                    enabled = false
                )
            } else {
                clientesFiltrados.forEach { cliente ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    cliente.nombre,
                                    fontWeight = FontWeight.Medium
                                )
                                if (cliente.identidad.isNotEmpty()) {
                                    Text(
                                        "ID: ${cliente.identidad}",
                                        color = MaterialTheme.colorScheme.outline,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        },
                        onClick = {
                            textoBusquedaInterna = cliente.nombre
                            textoBusqueda = cliente.nombre
                            expanded = false
                            focusManager.clearFocus()
                            onClienteSeleccionado(cliente)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}