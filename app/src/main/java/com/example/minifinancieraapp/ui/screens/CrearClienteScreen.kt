package com.example.minifinancieraapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.example.capitalexpressapp.ui.theme.CEColors
import com.example.capitalexpressapp.ui.theme.PremiumCard
import com.example.capitalexpressapp.ui.theme.ceTextFieldColors
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

private data class CampoFoto(val key: String, val label: String, val icon: ImageVector)

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
    val campos = remember {
        listOf(
            CampoFoto("fotoCasaUrl", "Foto de Casa", Icons.Default.Home),
            CampoFoto("fotoNegocioUrl", "Foto de Negocio", Icons.Default.Storefront),
            CampoFoto("fotoPersonaUrl", "Foto del Cliente", Icons.Default.Person),
            CampoFoto("fotoIdentidadFrenteUrl", "Identidad Frente", Icons.Default.Badge),
            CampoFoto("fotoIdentidadReversoUrl", "Identidad Reverso", Icons.Default.Badge),
            CampoFoto("fotoReciboLuzUrl", "Recibo de Luz", Icons.Default.Bolt),
            CampoFoto("fotoGarantiaUrl", "Foto de la Garantía", Icons.Default.Description),
            CampoFoto("fotoExtra1", "Foto Extra 1", Icons.Default.AddAPhoto),
            CampoFoto("fotoExtra2", "Foto Extra 2", Icons.Default.AddAPhoto),
            CampoFoto("fotoExtra3", "Foto Extra 3", Icons.Default.AddAPhoto)
        )
    }

    val snackbar = remember { SnackbarHostState() }
    var isLoading by remember { mutableStateOf(false) }
    var progreso by remember { mutableStateOf("") }

    // Variables para cámara
    var fotoUriCamara by remember { mutableStateOf<Uri?>(null) }
    var tagActual by remember { mutableStateOf("") }

    // Autenticar una sola vez al inicio
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
            Toast.makeText(context, "Foto capturada exitosamente", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No se pudo capturar la foto", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "Error al crear archivo temporal", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Permiso de cámara necesario para tomar fotos", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "Error al crear archivo temporal", Toast.LENGTH_SHORT).show()
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

    val galeriaLaunchers = campos.associate { it.key to seleccionarDesdeGaleria(it.key) }

    fun guardarCliente() {
        if (validarCamposObligatorios()) {
            isLoading = true
            progreso = "Verificando identidad..."

            scope.launch {
                try {
                    val identidadExiste = verificarIdentidadDuplicada()
                    if (identidadExiste) {
                        identidadDuplicadaError = true
                        isLoading = false
                        progreso = ""
                        snackbar.showSnackbar("Ya existe un cliente con este número de identidad")
                        return@launch
                    }

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

                    val fotosUrls = campos.map { campo ->
                        async {
                            campo.key to subirFoto(uris[campo.key], campo.key)
                        }
                    }.awaitAll().toMap()

                    val fotosSubidas = fotosUrls.values.count { it.isNotEmpty() }

                    progreso = "Guardando datos..."

                    // Los nombres siempre se guardan en mayúsculas: consistencia
                    // en listas/recibos y requisito de la búsqueda por nombre.
                    val data = mutableMapOf<String, Any>(
                        "nombre" to nombre.uppercase().trim(),
                        "identidad" to identidad.trim(),
                        "telefono" to telefono,
                        "nombreEmpresa" to nombreEmpresa.uppercase().trim(),
                        "direccionCasa" to direccionCasa,
                        "direccionNegocio" to direccionNegocio,
                        "garantia" to garantia,
                        "estado" to "activo",
                        "estadoCivil" to estadoCivil,
                        "fechaCreacion" to System.currentTimeMillis(),
                        "ref1" to mapOf(
                            "nombre" to ref1Nombre.uppercase().trim(),
                            "identidad" to ref1Identidad,
                            "telefono" to ref1Telefono,
                            "parentesco" to ref1Parentesco,
                            "direccion" to ref1Direccion
                        ),
                        "ref2" to mapOf(
                            "nombre" to ref2Nombre.uppercase().trim(),
                            "identidad" to ref2Identidad,
                            "telefono" to ref2Telefono,
                            "parentesco" to ref2Parentesco,
                            "direccion" to ref2Direccion
                        )
                    )

                    if (estadoCivil == "Casado") {
                        data["conyuge"] = mapOf(
                            "nombre" to nombreConyuge.uppercase().trim(),
                            "identidad" to identidadConyuge,
                            "telefono" to telefonoConyuge
                        )
                    }

                    fotosUrls.forEach { (tag, url) ->
                        data[tag] = url
                    }

                    val docRef = db.collection("clientes").document()
                    data["uid"] = docRef.id
                    docRef.set(data).await()

                    progreso = "¡Completado!"
                    Toast.makeText(context, "Cliente creado exitosamente con $fotosSubidas fotos", Toast.LENGTH_SHORT).show()
                    navController.popBackStack()

                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    snackbar.showSnackbar("Error: ${e.message}")
                } finally {
                    isLoading = false
                    progreso = ""
                }
            }
        } else {
            scope.launch {
                snackbar.showSnackbar("Por favor complete todos los campos obligatorios")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crear Nuevo Cliente", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CEColors.Primary)
            )
        },
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 8.dp) {
                Button(
                    onClick = { guardarCliente() },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = CEColors.ActionBlue)
                ) {
                    if (isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            Text(if (progreso.isNotEmpty()) progreso else "Guardando...", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Guardar Cliente", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = CEColors.Surface
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Datos Personales
            SeccionFormulario(titulo = "Datos Personales", icon = Icons.Default.Person) {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = {
                        nombre = it
                        if (nombreError) nombreError = false
                    },
                    label = { Text("Nombre *") },
                    isError = nombreError,
                    supportingText = if (nombreError) {
                        { Text("El nombre es obligatorio", color = CEColors.Error) }
                    } else null,
                    colors = ceTextFieldColors(),
                    shape = RoundedCornerShape(12.dp),
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
                        { Text("La identidad es obligatoria", color = CEColors.Error) }
                    } else if (identidadDuplicadaError) {
                        { Text("Ya existe un cliente con esta identidad", color = CEColors.Error) }
                    } else null,
                    colors = ceTextFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = telefono,
                    onValueChange = {
                        telefono = it
                        if (telefonoError) telefonoError = false
                    },
                    label = { Text("Teléfono *") },
                    isError = telefonoError,
                    supportingText = if (telefonoError) {
                        { Text("El teléfono es obligatorio", color = CEColors.Error) }
                    } else null,
                    colors = ceTextFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Datos de Trabajo
            SeccionFormulario(titulo = "Datos de Trabajo", icon = Icons.Default.Work) {
                OutlinedTextField(nombreEmpresa, { nombreEmpresa = it }, label = { Text("Empresa") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(direccionCasa, { direccionCasa = it }, label = { Text("Dirección Casa") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(direccionNegocio, { direccionNegocio = it }, label = { Text("Dirección Negocio") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(garantia, { garantia = it }, label = { Text("Garantía") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            }

            // Estado Civil
            SeccionFormulario(titulo = "Estado Civil", icon = Icons.Default.Favorite) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("Soltero", "Casado").forEach {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = estadoCivil == it,
                                onClick = { estadoCivil = it },
                                colors = RadioButtonDefaults.colors(selectedColor = CEColors.ActionBlue)
                            )
                            Text(it, color = CEColors.OnSurface, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                if (estadoCivil == "Casado") {
                    Text("Datos del Cónyuge:", color = CEColors.Primary, fontWeight = FontWeight.Bold)
                    OutlinedTextField(nombreConyuge, { nombreConyuge = it }, label = { Text("Nombre del Cónyuge") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(identidadConyuge, { identidadConyuge = it }, label = { Text("Identidad del Cónyuge") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(telefonoConyuge, { telefonoConyuge = it }, label = { Text("Teléfono del Cónyuge") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                }
            }

            // Referencias Personales
            SeccionFormulario(titulo = "Referencias Personales", icon = Icons.Default.Group) {
                Text("Referencia 1", color = CEColors.ActionBlue, fontWeight = FontWeight.Bold)
                OutlinedTextField(ref1Nombre, { ref1Nombre = it }, label = { Text("Nombre") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ref1Identidad, { ref1Identidad = it }, label = { Text("Identidad") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ref1Telefono, { ref1Telefono = it }, label = { Text("Teléfono") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ref1Parentesco, { ref1Parentesco = it }, label = { Text("Parentesco") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ref1Direccion, { ref1Direccion = it }, label = { Text("Dirección") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = CEColors.OutlineVariant)

                Text("Referencia 2", color = CEColors.ActionBlue, fontWeight = FontWeight.Bold)
                OutlinedTextField(ref2Nombre, { ref2Nombre = it }, label = { Text("Nombre") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ref2Identidad, { ref2Identidad = it }, label = { Text("Identidad") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ref2Telefono, { ref2Telefono = it }, label = { Text("Teléfono") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ref2Parentesco, { ref2Parentesco = it }, label = { Text("Parentesco") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ref2Direccion, { ref2Direccion = it }, label = { Text("Dirección") }, colors = ceTextFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            }

            // Documentos y Fotos
            SeccionFormulario(titulo = "Documentos y Fotos", icon = Icons.Default.PhotoCamera) {
                Text(
                    "Selecciona las fotos necesarias para el expediente del cliente",
                    color = CEColors.OnSurfaceVariant,
                    fontSize = 13.sp
                )
                campos.forEach { campo ->
                    FotoPickerAvanzado(
                        label = campo.label,
                        icon = campo.icon,
                        uri = uris[campo.key],
                        onSeleccionar = { galeriaLaunchers[campo.key]?.launch("image/*") },
                        onTomar = { tomarFoto(campo.key) }
                    )
                }
            }

            // Mensaje informativo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CEColors.ErrorContainer)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = CEColors.Error)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Los campos marcados con (*) son obligatorios",
                    color = CEColors.OnSurface,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SeccionFormulario(
    titulo: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    PremiumCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = CEColors.ActionBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(titulo, fontWeight = FontWeight.Bold, color = CEColors.Primary, fontSize = 17.sp)
            }
            HorizontalDivider(color = CEColors.OutlineVariant)
            content()
        }
    }
}

@Composable
fun FotoPickerAvanzado(
    label: String,
    icon: ImageVector,
    uri: Uri?,
    onSeleccionar: () -> Unit,
    onTomar: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CEColors.SurfaceContainerLow)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CEColors.Primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.Bold, color = CEColors.Primary, fontSize = 13.sp)
        }

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
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text("Sin foto", color = CEColors.Outline, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onSeleccionar,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CEColors.ActionBlue),
                border = androidx.compose.foundation.BorderStroke(1.dp, CEColors.OutlineVariant)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Galería", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onTomar,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CEColors.ActionBlue),
                border = androidx.compose.foundation.BorderStroke(1.dp, CEColors.OutlineVariant)
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Cámara", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cámara", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
