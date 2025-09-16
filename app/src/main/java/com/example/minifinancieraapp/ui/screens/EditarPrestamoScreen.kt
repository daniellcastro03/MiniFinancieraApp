package com.example.minifinancieraapp.ui.screens

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
import com.example.capitalexpressapp.util.ReciboHelper
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.UUID
import kotlin.math.roundToInt

// Función para calcular días efectivos según el tipo de plazo
fun calcularDiasEfectivos(plazo: String, cuotas: Int, fechaInicio: Calendar): Int {
    return when (plazo) {
        "Diario" -> {
            // Diario incluye domingos
            cuotas
        }
        "Lunes a Sábado" -> {
            // Lunes a Sábado excluye domingos
            contarDiasSinDomingos(cuotas, fechaInicio)
        }
        "Semanal" -> {
            // Semanal: 7 días por cuota, pero excluyendo domingos del cálculo
            contarDiasSinDomingos(cuotas * 6, fechaInicio) // 6 días laborables por semana
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
fun EditarPrestamoScreen(navController: NavController, prestamoId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()
    val storage = FirebaseStorage.getInstance()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // Estados principales
    var cliente by remember { mutableStateOf("") }
    var monto by remember { mutableStateOf("") }
    var interesMensual by remember { mutableStateOf("") }
    var cuotas by remember { mutableStateOf("") }
    var mora by remember { mutableStateOf("") }

    // 🔧 NUEVO: Estados para el sistema de interés mejorado
    var interesTotal by remember { mutableStateOf("") }
    var usarInteresMensual by remember { mutableStateOf(true) }
    var interesTotalFijo by remember { mutableStateOf("") } // Campo original guardado

    var selectedPlazo by remember { mutableStateOf("Semanal") }
    var lugar by remember { mutableStateOf("") }
    var firma by remember { mutableStateOf("") }
    var observaciones by remember { mutableStateOf("") }
    var estado by remember { mutableStateOf("activo") }
    var isLoading by remember { mutableStateOf(false) }
    var reciboGenerado by remember { mutableStateOf<File?>(null) }

    // Estados de autenticación del cobrador actual
    var currentUid by remember { mutableStateOf("") }
    var nombreCobrador by remember { mutableStateOf("") }
    var numeroCobrador by remember { mutableStateOf("") }

    // Variables para fecha
    var fechaSeleccionada by remember { mutableStateOf(Calendar.getInstance()) }

    // Variables para fotos
    var fotosSeleccionadas by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var fotoUriCamara by remember { mutableStateOf<Uri?>(null) }

    // 🔧 Obtener datos del cobrador actual
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
        }
    }

    // Crear archivo temporal para cámara
    val crearArchivoTemporal = remember {
        {
            try {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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
                Toast.makeText(context, "Error al crear archivo temporal", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Permiso de cámara necesario para tomar fotos", Toast.LENGTH_SHORT).show()
        }
    }

    // 🔧 CARGAR DATOS CON EL NUEVO SISTEMA
    LaunchedEffect(prestamoId) {
        try {
            val doc = db.collection("prestamos").document(prestamoId).get().await()
            cliente = doc.getString("cliente") ?: ""
            monto = doc.getDouble("monto")?.toString() ?: ""
            interesMensual = doc.getDouble("interesMensual")?.toString() ?: ""
            cuotas = doc.getLong("cuotas")?.toString() ?: ""
            mora = doc.getDouble("mora")?.toString() ?: ""
            selectedPlazo = doc.getString("plazo") ?: "Semanal"
            lugar = doc.getString("lugar") ?: ""
            firma = doc.getString("firma") ?: ""
            observaciones = doc.getString("observaciones") ?: ""
            estado = doc.getString("estado") ?: "activo"

            // 🔧 CARGAR EL SISTEMA DE INTERÉS
            val usarInteresMensualDoc = doc.getBoolean("usarInteresMensual")
            if (usarInteresMensualDoc != null) {
                usarInteresMensual = usarInteresMensualDoc
                if (usarInteresMensual) {
                    interesMensual = doc.getDouble("interesMensual")?.toString() ?: ""
                    interesTotal = ""
                } else {
                    interesTotalFijo = doc.getDouble("interesTotalFijo")?.toString() ?: ""
                    interesTotal = interesTotalFijo
                    interesMensual = ""
                }
            } else {
                // Compatibilidad con préstamos antiguos
                val interesTotalDoc = doc.getDouble("interesTotal") ?: 0.0
                val interesMensualDoc = doc.getDouble("interesMensual") ?: 0.0

                if (interesTotalDoc > 0 && interesMensualDoc == 0.0) {
                    usarInteresMensual = false
                    interesTotal = interesTotalDoc.toString()
                    interesTotalFijo = interesTotal
                } else {
                    usarInteresMensual = true
                    interesMensual = interesMensualDoc.toString()
                }
            }

            // Cargar fecha
            try {
                val timestamp = doc.getTimestamp("fecha")
                if (timestamp != null) {
                    fechaSeleccionada.time = timestamp.toDate()
                }
            } catch (e: Exception) {
                try {
                    val fechaString = doc.getString("fecha")
                    if (fechaString != null) {
                        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        val fechaParseada = dateFormat.parse(fechaString)
                        if (fechaParseada != null) {
                            fechaSeleccionada.time = fechaParseada
                        }
                    }
                } catch (parseException: Exception) {
                    Toast.makeText(context, "Advertencia: No se pudo cargar la fecha original", Toast.LENGTH_SHORT).show()
                }
            }

            // Cargar fotos
            val fotosUrls = doc.get("fotos") as? List<String> ?: emptyList()
            fotosSeleccionadas = fotosUrls.map { Uri.parse(it) }

        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar datos: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editar Préstamo") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // TÍTULO
            Text(
                text = "Editar Préstamo",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            // Cliente (solo lectura)
            OutlinedTextField(
                value = cliente,
                onValueChange = { },
                label = { Text("Cliente") },
                modifier = Modifier.fillMaxWidth(),
                enabled = false
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Monto
            OutlinedTextField(
                value = monto,
                onValueChange = { monto = it },
                label = { Text("Monto") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 🔧 NUEVO SISTEMA DE CONFIGURACIÓN DE INTERÉS
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

            // Cuotas
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
                                    Toast.makeText(context, "Error al crear archivo temporal", Toast.LENGTH_SHORT).show()
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
                                        fotosSeleccionadas = fotosSeleccionadas.filterIndexed { i, _ -> i != index }
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

            // RADIO BUTTONS CON LA NUEVA OPCIÓN "Lunes a Sábado"
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

            // 🔧 CÁLCULOS CON EL SISTEMA NUEVO
            val montoDouble = monto.toDoubleOrNull() ?: 0.0
            val interesPct = interesMensual.toDoubleOrNull() ?: 0.0
            val cuotasInt = cuotas.toIntOrNull() ?: 1
            val interesTotalDouble = interesTotal.toDoubleOrNull() ?: 0.0
            val moraDouble = mora.toDoubleOrNull() ?: 0.0

            val fechaInicio = fechaSeleccionada.clone() as Calendar
            val diasEfectivos = calcularDiasEfectivos(selectedPlazo, cuotasInt, fechaInicio)
            val mesesAproximados = diasEfectivos / 30.0

            // CÁLCULO DE PRÓXIMO PAGO CORREGIDO
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

            // 🔧 LÓGICA CORREGIDA PARA CÁLCULO DE INTERESES (IGUAL QUE CREAR)
            val (interesCalculado, totalAPagar, cuotaEstimada) = if (!usarInteresMensual && interesTotalDouble > 0) {
                // Usar interés total fijo
                val total = montoDouble + interesTotalDouble
                val cuota = if (cuotasInt > 0) (total / cuotasInt).roundToInt().toDouble() else 0.0
                Triple(interesTotalDouble, total, cuota)
            } else if (usarInteresMensual && interesPct > 0) {
                // Calcular interés basado en porcentaje mensual
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

            Spacer(modifier = Modifier.height(16.dp))

            // 🔧 BOTÓN GUARDAR CON NUEVA LÓGICA
            Button(
                onClick = {
                    if (cliente.isBlank() || montoDouble <= 0 || cuotasInt <= 0) {
                        Toast.makeText(context, "Verifica los datos ingresados", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    if (!usarInteresMensual && interesTotalDouble <= 0 && (usarInteresMensual && interesPct <= 0)) {
                        Toast.makeText(context, "Debe configurar un interés válido", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    isLoading = true

                    scope.launch {
                        try {
                            // Filtrar fotos válidas
                            val fotosValidas = fotosSeleccionadas.filter { uri ->
                                try {
                                    if (uri.toString().startsWith("http")) {
                                        true
                                    } else {
                                        val inputStream = context.contentResolver.openInputStream(uri)
                                        val isValid = inputStream != null
                                        inputStream?.close()
                                        isValid
                                    }
                                } catch (e: Exception) {
                                    false
                                }
                            }

                            val fecha = formatter.format(fechaSeleccionada.time)

                            // Subir fotos nuevas
                            val urlsFotos = mutableListOf<String>()
                            var fotosConError = 0

                            for (uri in fotosValidas) {
                                try {
                                    if (uri.toString().startsWith("http")) {
                                        // Si ya es una URL, mantenerla
                                        urlsFotos.add(uri.toString())
                                    } else {
                                        // Verificar si la URI es válida antes de subirla
                                        val inputStream = context.contentResolver.openInputStream(uri)
                                        if (inputStream != null) {
                                            inputStream.close()
                                            // Si podemos abrir el stream, la URI es válida
                                            val ref = storage.reference.child("prestamos/${UUID.randomUUID()}.jpg")
                                            ref.putFile(uri).await()
                                            val url = ref.downloadUrl.await().toString()
                                            urlsFotos.add(url)
                                        } else {
                                            // Si no podemos abrir el stream, skip esta foto
                                            fotosConError++
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Si hay error con esta foto específica, continuar con las demás
                                    fotosConError++
                                }
                            }

                            if (fotosConError > 0) {
                                Toast.makeText(context, "Se procesaron las fotos válidas. $fotosConError fotos no pudieron procesarse.", Toast.LENGTH_LONG).show()
                            }

                            // 🔧 ACTUALIZAR PRÉSTAMO CON NUEVA ESTRUCTURA
                            val prestamoActualizado = hashMapOf(
                                "cliente" to cliente,
                                "monto" to montoDouble,
                                "interes" to interesCalculado,
                                "interesMensual" to if (usarInteresMensual) interesPct else 0.0,
                                "interesTotal" to if (!usarInteresMensual) interesTotalDouble else interesCalculado,
                                "usarInteresMensual" to usarInteresMensual, // Campo para saber qué tipo de interés usar
                                "interesTotalFijo" to if (!usarInteresMensual) interesTotalDouble else 0.0,
                                "mora" to moraDouble,
                                "totalPagar" to totalAPagar,
                                "cuota" to cuotaEstimada,
                                "cuotas" to cuotasInt,
                                "plazo" to selectedPlazo,
                                "fecha" to Timestamp(fechaSeleccionada.time),
                                "lugar" to lugar,
                                "firma" to firma,
                                "cobrador" to nombreCobrador,
                                "numeroCobrador" to numeroCobrador,
                                "cobradorUid" to currentUid,
                                "proximoPago" to proximoPagoTimestamp, // Usar timestamp calculado correctamente
                                "estado" to estado,
                                "observaciones" to observaciones,
                                "fotos" to urlsFotos,
                                "diasEfectivos" to diasEfectivos.toDouble(),
                                "saldo" to totalAPagar // Actualizar el saldo también
                            )

                            // Actualizar en Firestore
                            db.collection("prestamos").document(prestamoId).update(prestamoActualizado).await()

                            // Generar recibo actualizado
                            try {
                                val reciboFile = ReciboHelper.generarReciboPrestamoPDF(
                                    context = context,
                                    cliente = com.example.minifinancieraapp.ui.models.ClienteModel(
                                        nombre = cliente,
                                        telefono = "",
                                        direccionCasa = lugar
                                    ),
                                    monto = montoDouble,
                                    interesTotal = interesCalculado,
                                    mora = moraDouble,
                                    cuotas = cuotasInt,
                                    fecha = fecha,
                                    lugar = lugar,
                                    numeroCobrador = numeroCobrador,
                                    numeroPrestamo = prestamoId,
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
                                            "Aquí está el recibo del préstamo actualizado desde Capital Express."
                                        )
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(
                                            intent,
                                            "Compartir recibo actualizado con:"
                                        )
                                    )

                                    Toast.makeText(
                                        context,
                                        "Préstamo actualizado correctamente, impreso y listo para compartir",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Préstamo actualizado, pero error al generar recibo",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                            } catch (reciboError: Exception) {
                                Toast.makeText(
                                    context,
                                    "Préstamo actualizado. Error al imprimir: ${reciboError.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                                reciboError.printStackTrace()
                            }

                            navController.popBackStack()

                        } catch (e: Exception) {
                            Toast.makeText(context, "Error al actualizar: ${e.message}", Toast.LENGTH_LONG).show()
                            e.printStackTrace()
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
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
                        Text("Actualizar e Imprimir")
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
                                    "Aquí está el recibo del préstamo actualizado desde Capital Express."
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
    }}