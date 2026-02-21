package com.example.minifinancieraapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrearClienteScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()
    val storage = FirebaseStorage.getInstance().reference

    // Estados para todos los campos
    var nombre by remember { mutableStateOf("") }
    var identidad by remember { mutableStateOf("") }
    var telefono by remember { mutableStateOf("") }
    var nombreEmpresa by remember { mutableStateOf("") }
    var direccionCasa by remember { mutableStateOf("") }
    var direccionNegocio by remember { mutableStateOf("") }
    var garantia by remember { mutableStateOf("") }

    // Estados para validación
    var nombreError by remember { mutableStateOf(false) }
    var identidadError by remember { mutableStateOf(false) }
    var telefonoError by remember { mutableStateOf(false) }
    var identidadDuplicadaError by remember { mutableStateOf(false) }

    // Estado civil y cónyuge
    var estadoCivil by remember { mutableStateOf("Soltero") }
    var nombreConyuge by remember { mutableStateOf("") }
    var identidadConyuge by remember { mutableStateOf("") }
    var telefonoConyuge by remember { mutableStateOf("") }

    // Referencias
    var ref1Nombre by remember { mutableStateOf("") }
    var ref1Identidad by remember { mutableStateOf("") }
    var ref1Telefono by remember { mutableStateOf("") }
    var ref1Parentesco by remember { mutableStateOf("") }
    var ref1Direccion by remember { mutableStateOf("") }

    var ref2Nombre by remember { mutableStateOf("") }
    var ref2Identidad by remember { mutableStateOf("") }
    var ref2Telefono by remember { mutableStateOf("") }
    var ref2Parentesco by remember { mutableStateOf("") }
    var ref2Direccion by remember { mutableStateOf("") }

    // Estados para fotos
    val uris = remember { mutableStateMapOf<String, Uri?>() }
    val campos = listOf(
        "fotoCasaUrl" to "🏠 Foto de Casa",
        "fotoNegocioUrl" to "🏪 Foto de Negocio",
        "fotoPersonaUrl" to "👤 Foto del Cliente",
        "fotoIdentidadFrenteUrl" to "🆔 Identidad Frente",
        "fotoIdentidadReversoUrl" to "🆔 Identidad Reverso",
        "fotoReciboLuzUrl" to "💡 Recibo de Luz",
        "fotoGarantiaUrl" to "📋 Foto de la Garantía",
        "fotoExtra1" to "📸 Foto Extra 1",
        "fotoExtra2" to "📸 Foto Extra 2",
        "fotoExtra3" to "📸 Foto Extra 3"
    )

    val snackbar = remember { SnackbarHostState() }
    var isLoading by remember { mutableStateOf(false) }
    var progreso by remember { mutableStateOf("") }

    // Variables para cámara
    var fotoUriCamara by remember { mutableStateOf<Uri?>(null) }
    var tagActual by remember { mutableStateOf("") }

    // ✅ OPTIMIZACIÓN 1: Autenticar una sola vez al inicio
    LaunchedEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            try {
                auth.signInAnonymously().await()
            } catch (e: Exception) {
                // Manejar error silenciosamente
            }
        }
    }

    // Función para validar campos obligatorios
    fun validarCamposObligatorios(): Boolean {
        nombreError = nombre.isBlank()
        identidadError = identidad.isBlank()
        telefonoError = telefono.isBlank()
        return !nombreError && !identidadError && !telefonoError
    }

    // Verificar si la identidad ya existe
    suspend fun verificarIdentidadDuplicada(): Boolean {
        return try {
            val query = db.collection("clientes")
                .whereEqualTo("identidad", identidad.trim())
                .get()
                .await()

            !query.isEmpty
        } catch (e: Exception) {
            false
        }
    }

    // Función para crear archivo temporal
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
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    // Launcher para cámara
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && fotoUriCamara != null) {
            uris[tagActual] = fotoUriCamara
            Toast.makeText(context, "📸 Foto capturada exitosamente", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "❌ No se pudo capturar la foto", Toast.LENGTH_SHORT).show()
        }
    }

    // Launcher para permisos
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = crearArchivoTemporal()
            if (uri != null) {
                fotoUriCamara = uri
                cameraLauncher.launch(uri)
            } else {
                Toast.makeText(context, "❌ Error al crear archivo temporal", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "⚠️ Permiso de cámara necesario para tomar fotos", Toast.LENGTH_SHORT).show()
        }
    }

    // Función para tomar foto
    fun tomarFoto(tag: String) {
        tagActual = tag
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> {
                val uri = crearArchivoTemporal()
                if (uri != null) {
                    fotoUriCamara = uri
                    cameraLauncher.launch(uri)
                } else {
                    Toast.makeText(context, "❌ Error al crear archivo temporal", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Función para seleccionar desde galería
    @Composable
    fun seleccionarDesdeGaleria(tag: String) =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) uris[tag] = uri
        }

    val galeriaLaunchers = campos.associate { (key, _) -> key to seleccionarDesdeGaleria(key) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("✨ Crear Nuevo Cliente") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card para datos personales
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "👤 Datos Personales",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = nombre,
                        onValueChange = {
                            nombre = it
                            if (nombreError) nombreError = false
                        },
                        label = { Text("Nombre *") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        isError = nombreError,
                        supportingText = if (nombreError) {
                            { Text("El nombre es obligatorio", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = identidad,
                        onValueChange = {
                            identidad = it
                            if (identidadError) identidadError = false
                            if (identidadDuplicadaError) identidadDuplicadaError = false
                        },
                        label = { Text("Identidad *") },
                        isError = identidadError || identidadDuplicadaError,
                        supportingText = if (identidadError) {
                            { Text("La identidad es obligatoria", color = MaterialTheme.colorScheme.error) }
                        } else if (identidadDuplicadaError) {
                            { Text("Ya existe un cliente con esta identidad", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = telefono,
                        onValueChange = {
                            telefono = it
                            if (telefonoError) telefonoError = false
                        },
                        label = { Text("Teléfono *") },
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                        isError = telefonoError,
                        supportingText = if (telefonoError) {
                            { Text("El teléfono es obligatorio", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Card para datos de trabajo
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "💼 Datos de Trabajo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        nombreEmpresa,
                        { nombreEmpresa = it },
                        label = { Text("Empresa") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        direccionCasa,
                        { direccionCasa = it },
                        label = { Text("Dirección Casa") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        direccionNegocio,
                        { direccionNegocio = it },
                        label = { Text("Dirección Negocio") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        garantia,
                        { garantia = it },
                        label = { Text("Garantía") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Card para estado civil
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "💍 Estado Civil",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf("Soltero", "Casado").forEach {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = estadoCivil == it,
                                    onClick = { estadoCivil = it }
                                )
                                Text(it)
                            }
                        }
                    }

                    if (estadoCivil == "Casado") {
                        Text(
                            "Datos del Cónyuge:",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedTextField(
                            nombreConyuge,
                            { nombreConyuge = it },
                            label = { Text("Nombre del Cónyuge") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            identidadConyuge,
                            { identidadConyuge = it },
                            label = { Text("Identidad del Cónyuge") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            telefonoConyuge,
                            { telefonoConyuge = it },
                            label = { Text("Teléfono del Cónyuge") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Card para referencias
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "📞 Referencias Personales",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        "Referencia 1:",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    OutlinedTextField(ref1Nombre, { ref1Nombre = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ref1Identidad, { ref1Identidad = it }, label = { Text("Identidad") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ref1Telefono, { ref1Telefono = it }, label = { Text("Teléfono") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ref1Parentesco, { ref1Parentesco = it }, label = { Text("Parentesco") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ref1Direccion, { ref1Direccion = it }, label = { Text("Dirección") }, modifier = Modifier.fillMaxWidth())

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )

                    Text(
                        "Referencia 2:",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    OutlinedTextField(ref2Nombre, { ref2Nombre = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ref2Identidad, { ref2Identidad = it }, label = { Text("Identidad") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ref2Telefono, { ref2Telefono = it }, label = { Text("Teléfono") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ref2Parentesco, { ref2Parentesco = it }, label = { Text("Parentesco") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ref2Direccion, { ref2Direccion = it }, label = { Text("Dirección") }, modifier = Modifier.fillMaxWidth())
                }
            }

            // Card para fotos
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "📸 Documentos y Fotos",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Selecciona las fotos necesarias para el expediente del cliente",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    campos.forEach { (tag, label) ->
                        FotoPickerAvanzado(
                            label = label,
                            uri = uris[tag],
                            onSeleccionar = { galeriaLaunchers[tag]?.launch("image/*") },
                            onTomar = { tomarFoto(tag) }
                        )
                    }
                }
            }

            // Mensaje informativo
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = "⚠️ Los campos marcados con (*) son obligatorios",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // ✅ OPTIMIZACIÓN 2: Mostrar progreso detallado
            if (isLoading && progreso.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            progreso,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Botón de guardar
            Button(
                onClick = {
                    if (validarCamposObligatorios()) {
                        isLoading = true
                        progreso = "Verificando identidad..."

                        scope.launch {
                            try {
                                // Verificar si la identidad ya existe
                                val identidadExiste = verificarIdentidadDuplicada()
                                if (identidadExiste) {
                                    identidadDuplicadaError = true
                                    isLoading = false
                                    progreso = ""
                                    snackbar.showSnackbar("⚠️ Ya existe un cliente con este número de identidad")
                                    return@launch
                                }

                                // ✅ OPTIMIZACIÓN 3: Subir fotos en paralelo
                                progreso = "Subiendo fotos..."

                                suspend fun subirFoto(uri: Uri?, nombreArchivo: String): String {
                                    return uri?.let {
                                        try {
                                            val timestamp = System.currentTimeMillis()
                                            val fileName = "${timestamp}_${nombreArchivo}.jpg"
                                            val ref = storage.child("clientes/$fileName")

                                            ref.putFile(it).await()
                                            ref.downloadUrl.await().toString()
                                        } catch (e: Exception) {
                                            ""
                                        }
                                    } ?: ""
                                }

                                // 🚀 SUBIR TODAS LAS FOTOS EN PARALELO
                                val fotosUrls = campos.map { (tag, _) ->
                                    async {
                                        tag to subirFoto(uris[tag], tag)
                                    }
                                }.awaitAll().toMap()

                                val fotosSubidas = fotosUrls.values.count { it.isNotEmpty() }

                                // ✅ OPTIMIZACIÓN 4: Preparar datos primero, guardar después
                                progreso = "Guardando datos..."

                                val data = mutableMapOf<String, Any>(
                                    "nombre" to nombre,
                                    "identidad" to identidad.trim(),
                                    "telefono" to telefono,
                                    "nombreEmpresa" to nombreEmpresa,
                                    "direccionCasa" to direccionCasa,
                                    "direccionNegocio" to direccionNegocio,
                                    "garantia" to garantia,
                                    "estado" to "activo",
                                    "estadoCivil" to estadoCivil,
                                    "fechaCreacion" to System.currentTimeMillis(),
                                    "ref1" to mapOf(
                                        "nombre" to ref1Nombre,
                                        "identidad" to ref1Identidad,
                                        "telefono" to ref1Telefono,
                                        "parentesco" to ref1Parentesco,
                                        "direccion" to ref1Direccion
                                    ),
                                    "ref2" to mapOf(
                                        "nombre" to ref2Nombre,
                                        "identidad" to ref2Identidad,
                                        "telefono" to ref2Telefono,
                                        "parentesco" to ref2Parentesco,
                                        "direccion" to ref2Direccion
                                    )
                                )

                                // Añadir datos del cónyuge si está casado
                                if (estadoCivil == "Casado") {
                                    data["conyuge"] = mapOf(
                                        "nombre" to nombreConyuge,
                                        "identidad" to identidadConyuge,
                                        "telefono" to telefonoConyuge
                                    )
                                }

                                // Añadir URLs de fotos
                                fotosUrls.forEach { (tag, url) ->
                                    data[tag] = url
                                }

                                // Guardar en Firestore
                                val docRef = db.collection("clientes").document()
                                data["uid"] = docRef.id
                                docRef.set(data).await()

                                progreso = "¡Completado!"
                                Toast.makeText(context, "✅ Cliente creado exitosamente con $fotosSubidas fotos", Toast.LENGTH_SHORT).show()
                                navController.popBackStack()

                            } catch (e: Exception) {
                                Toast.makeText(context, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show()
                                snackbar.showSnackbar("❌ Error: ${e.message}")
                            } finally {
                                isLoading = false
                                progreso = ""
                            }
                        }
                    } else {
                        scope.launch {
                            snackbar.showSnackbar("⚠️ Por favor complete todos los campos obligatorios")
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Guardando...")
                    }
                } else {
                    Text(
                        "💾 Guardar Cliente",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun FotoPickerAvanzado(
    label: String,
    uri: Uri?,
    onSeleccionar: () -> Unit,
    onTomar: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                label,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (uri != null) {
                Image(
                    painter = rememberAsyncImagePainter(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(uri)
                            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
                            .build(),
                        error = painterResource(id = android.R.drawable.ic_dialog_alert)
                    ),
                    contentDescription = label,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                        )
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Sin foto",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSeleccionar,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📱 Galería")
                }

                OutlinedButton(
                    onClick = onTomar,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = "Cámara",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("📷 Cámara")
                }
            }
        }
    }
}