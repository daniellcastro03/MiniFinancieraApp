package com.example.minifinancieraapp.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditarClienteScreen(navController: NavController, clienteId: String) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val storage = FirebaseStorage.getInstance()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var loading by remember { mutableStateOf(true) }
    var fotosUris = remember { mutableStateMapOf<String, Uri?>() }
    var fotosUrls = remember { mutableStateMapOf<String, String>() }
    var fotoAmpliada by remember { mutableStateOf<String?>(null) }

    val camposTexto = remember {
        mutableStateMapOf(
            "nombre" to "",
            "telefono" to "",
            "identidad" to "",
            "direccionCasa" to "",
            "direccionNegocio" to "",
            "nombreEmpresa" to "",
            "estadoCivil" to "",
            "nombreEsposo" to "",
            "identidadEsposo" to "",
            "telefonoEsposo" to "",
            "refNombre1" to "",
            "refIdentidad1" to "",
            "refTelefono1" to "",
            "refParentesco1" to "",
            "refDireccion1" to "",
            "refNombre2" to "",
            "refIdentidad2" to "",
            "refTelefono2" to "",
            "refParentesco2" to "",
            "refDireccion2" to "",
            "garantiaTexto" to ""
        )
    }

    val launcherKeys = listOf(
        "fotoPersonaUrl", "fotoIdentidadFrenteUrl", "fotoIdentidadReversoUrl",
        "fotoCasaUrl", "fotoNegocioUrl", "fotoReciboLuzUrl",
        "fotoExtra1", "fotoExtra2", "fotoExtra3", "garantiaFoto"
    )

    val lanzadoresFotos = remember { mutableMapOf<String, ManagedActivityResultLauncher<String, Uri?>>() }

    launcherKeys.forEach { key ->
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            fotosUris[key] = uri
        }
        lanzadoresFotos[key] = launcher
    }

    var eliminarConfirmacion by remember { mutableStateOf(false) }

    LaunchedEffect(clienteId) {
        try {
            val doc = db.collection("clientes").document(clienteId).get().await()

            // Campos simples
            camposTexto.forEach { (k, _) -> camposTexto[k] = doc.getString(k) ?: "" }

            // Campos anidados
            val conyuge = doc.get("conyuge") as? Map<*, *>
            camposTexto["nombreEsposo"] = conyuge?.get("nombre") as? String ?: ""
            camposTexto["identidadEsposo"] = conyuge?.get("identidad") as? String ?: ""
            camposTexto["telefonoEsposo"] = conyuge?.get("telefono") as? String ?: ""

            val ref1 = doc.get("ref1") as? Map<*, *>
            camposTexto["refNombre1"] = ref1?.get("nombre") as? String ?: ""
            camposTexto["refIdentidad1"] = ref1?.get("identidad") as? String ?: ""
            camposTexto["refTelefono1"] = ref1?.get("telefono") as? String ?: ""
            camposTexto["refParentesco1"] = ref1?.get("parentesco") as? String ?: ""
            camposTexto["refDireccion1"] = ref1?.get("direccion") as? String ?: ""

            val ref2 = doc.get("ref2") as? Map<*, *>
            camposTexto["refNombre2"] = ref2?.get("nombre") as? String ?: ""
            camposTexto["refIdentidad2"] = ref2?.get("identidad") as? String ?: ""
            camposTexto["refTelefono2"] = ref2?.get("telefono") as? String ?: ""
            camposTexto["refParentesco2"] = ref2?.get("parentesco") as? String ?: ""
            camposTexto["refDireccion2"] = ref2?.get("direccion") as? String ?: ""

            camposTexto["garantiaTexto"] = doc.getString("garantia") ?: ""

            launcherKeys.forEach { key -> fotosUrls[key] = doc.getString(key) ?: "" }

        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Error al cargar cliente")
        } finally {
            loading = false
        }
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Editar Cliente") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Campos personales básicos
            camposTexto.forEach { (key, _) ->
                if (!key.startsWith("ref") && !key.contains("Esposo") && key != "garantiaTexto") {
                    OutlinedTextField(
                        value = camposTexto[key] ?: "",
                        onValueChange = { camposTexto[key] = it },
                        label = { Text(key.replaceFirstChar { it.uppercaseChar() }) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Cónyuge
            if (camposTexto["estadoCivil"]?.lowercase() == "casado") {
                Text("👫 Datos del Cónyuge", fontWeight = FontWeight.Bold)
                listOf("nombreEsposo", "identidadEsposo", "telefonoEsposo").forEach { key ->
                    OutlinedTextField(
                        value = camposTexto[key] ?: "",
                        onValueChange = { camposTexto[key] = it },
                        label = { Text(key.replace("Esposo", " Cónyuge").replaceFirstChar { it.uppercaseChar() }) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Referencias
            Text("👥 Referencias Personales", fontWeight = FontWeight.Bold)
            (1..2).forEach { i ->
                Text("Referencia $i", fontWeight = FontWeight.SemiBold)
                listOf("Nombre", "Identidad", "Telefono", "Parentesco", "Direccion").forEach { campo ->
                    val key = "ref${campo}$i"
                    OutlinedTextField(
                        value = camposTexto[key] ?: "",
                        onValueChange = { camposTexto[key] = it },
                        label = { Text(campo) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Garantía
            Text("🔒 Garantía", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = camposTexto["garantiaTexto"] ?: "",
                onValueChange = { camposTexto["garantiaTexto"] = it },
                label = { Text("Descripción de la garantía") },
                modifier = Modifier.fillMaxWidth()
            )

            // Fotos
            launcherKeys.forEach { key ->
                val label = when (key) {
                    "fotoPersonaUrl" -> "🧍 Foto del Cliente"
                    "fotoIdentidadFrenteUrl" -> "🪪 Identidad Frente"
                    "fotoIdentidadReversoUrl" -> "🪪 Identidad Reverso"
                    "fotoCasaUrl" -> "🏠 Foto Casa"
                    "fotoNegocioUrl" -> "🏪 Foto Negocio"
                    "fotoReciboLuzUrl" -> "💡 Recibo Luz"
                    "garantiaFoto" -> "📸 Foto Garantía"
                    else -> "📷 Foto Extra"
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label)
                    Image(
                        painter = rememberAsyncImagePainter(fotosUris[key] ?: fotosUrls[key]),
                        contentDescription = label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clickable { fotosUrls[key]?.let { fotoAmpliada = it } },
                        contentScale = ContentScale.Crop
                    )
                    Button(onClick = { lanzadoresFotos[key]?.launch("image/*") }) {
                        Text("Cambiar $label")
                    }
                }
            }

            // Diálogo de imagen ampliada
            fotoAmpliada?.let { url ->
                AlertDialog(
                    onDismissRequest = { fotoAmpliada = null },
                    confirmButton = {
                        TextButton(onClick = { fotoAmpliada = null }) { Text("Cerrar") }
                    },
                    text = {
                        Image(
                            painter = rememberAsyncImagePainter(url),
                            contentDescription = "Ampliada",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                )
            }

            // Botón guardar
            Button(onClick = {
                scope.launch {
                    try {
                        val nuevasUrls = mutableMapOf<String, String>()
                        launcherKeys.forEach { key ->
                            val uri = fotosUris[key]
                            if (uri != null) {
                                val ref = storage.reference.child("clientes/$clienteId/$key.jpg")
                                ref.putFile(uri).await()
                                nuevasUrls[key] = ref.downloadUrl.await().toString()
                            }
                        }

                        // Los nombres siempre se guardan en mayúsculas: consistencia
                        // en listas/recibos y requisito de la búsqueda por nombre.
                        val conyugeMap = mapOf(
                            "nombre" to camposTexto["nombreEsposo"].orEmpty().uppercase().trim(),
                            "identidad" to camposTexto["identidadEsposo"].orEmpty(),
                            "telefono" to camposTexto["telefonoEsposo"].orEmpty()
                        )

                        val ref1Map = mapOf(
                            "nombre" to camposTexto["refNombre1"].orEmpty().uppercase().trim(),
                            "identidad" to camposTexto["refIdentidad1"].orEmpty(),
                            "telefono" to camposTexto["refTelefono1"].orEmpty(),
                            "parentesco" to camposTexto["refParentesco1"].orEmpty(),
                            "direccion" to camposTexto["refDireccion1"].orEmpty()
                        )

                        val ref2Map = mapOf(
                            "nombre" to camposTexto["refNombre2"].orEmpty().uppercase().trim(),
                            "identidad" to camposTexto["refIdentidad2"].orEmpty(),
                            "telefono" to camposTexto["refTelefono2"].orEmpty(),
                            "parentesco" to camposTexto["refParentesco2"].orEmpty(),
                            "direccion" to camposTexto["refDireccion2"].orEmpty()
                        )

                        val datos = camposTexto
                            .filterKeys { !it.contains("Esposo") && !it.startsWith("ref") && it != "garantiaTexto" }
                            .toMap() +
                                launcherKeys.associateWith { nuevasUrls[it] ?: fotosUrls[it].orEmpty() } +
                                mapOf(
                                    "nombre" to camposTexto["nombre"].orEmpty().uppercase().trim(),
                                    "nombreEmpresa" to camposTexto["nombreEmpresa"].orEmpty().uppercase().trim(),
                                    "conyuge" to conyugeMap,
                                    "ref1" to ref1Map,
                                    "ref2" to ref2Map,
                                    "garantia" to camposTexto["garantiaTexto"].orEmpty()
                                )

                        db.collection("clientes").document(clienteId).update(datos).await()
                        snackbarHostState.showSnackbar("✅ Cliente actualizado")
                        navController.popBackStack()
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("❌ Error al guardar: ${e.message}")
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Guardar Cambios")
            }
        }
    }
}
