package com.example.capitalexpressapp.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditarUsuarioScreen(navController: NavController, userId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val nombre = remember { mutableStateOf("") }
    val codigo = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    val telefono = remember { mutableStateOf("") }
    val rol = remember { mutableStateOf("") }

    val fotoUri = remember { mutableStateOf<Uri?>(null) }
    val fotoActual = remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var guardadoExitoso by remember { mutableStateOf(false) }

    val roles = listOf("admin", "cobrador")
    val launcherFoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        fotoUri.value = it
    }

    val db = FirebaseFirestore.getInstance()
    val storage = FirebaseStorage.getInstance()

    LaunchedEffect(userId) {
        try {
            val doc = db.collection("usuarios").document(userId).get().await()
            nombre.value = doc.getString("nombre") ?: ""
            codigo.value = doc.getString("codigo") ?: ""
            password.value = doc.getString("password") ?: ""
            rol.value = doc.getString("rol") ?: ""
            telefono.value = doc.getString("telefono") ?: ""
            fotoActual.value = doc.getString("fotoUrl") ?: ""
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar("Error al cargar datos") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editar Usuario") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0061A7), titleContentColor = Color.White)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(nombre.value, { nombre.value = it }, label = { Text("Nombre completo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(codigo.value, { codigo.value = it }, label = { Text("Código único") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password.value, { password.value = it }, label = { Text("Contraseña") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(telefono.value, { telefono.value = it }, label = { Text("Teléfono") }, modifier = Modifier.fillMaxWidth())

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = rol.value,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Rol") },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    roles.forEach {
                        DropdownMenuItem(text = { Text(it) }, onClick = {
                            rol.value = it
                            expanded = false
                        })
                    }
                }
            }

            Button(
                onClick = { launcherFoto.launch("image/*") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A7))
            ) {
                Text("Cambiar Foto de Perfil", color = Color.White)
            }

            if (fotoUri.value != null) {
                Image(
                    painter = rememberAsyncImagePainter(fotoUri.value),
                    contentDescription = null,
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.Gray, CircleShape)
                )
            } else if (fotoActual.value.isNotBlank()) {
                Image(
                    painter = rememberAsyncImagePainter(fotoActual.value),
                    contentDescription = null,
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.Gray, CircleShape)
                )
            }

            Button(
                onClick = {
                    if (nombre.value.isBlank() || codigo.value.isBlank() || password.value.isBlank() || rol.value.isBlank()) {
                        scope.launch { snackbarHostState.showSnackbar("Completa todos los campos") }
                        return@Button
                    }

                    scope.launch {
                        try {
                            val duplicado = db.collection("usuarios")
                                .whereEqualTo("codigo", codigo.value)
                                .get()
                                .await()
                                .documents
                                .any { it.id != userId }

                            if (duplicado) {
                                snackbarHostState.showSnackbar("Este código ya está en uso por otro usuario.")
                                return@launch
                            }

                            fun subirFoto(uri: Uri?, callback: (String) -> Unit) {
                                if (uri != null) {
                                    val ref = storage.reference.child("usuarios/$userId/foto.jpg")
                                    ref.putFile(uri).addOnSuccessListener {
                                        ref.downloadUrl.addOnSuccessListener { url ->
                                            callback(url.toString())
                                        }
                                    }.addOnFailureListener {
                                        callback(fotoActual.value)
                                    }
                                } else {
                                    callback(fotoActual.value)
                                }
                            }

                            subirFoto(fotoUri.value) { url ->
                                val datosActualizados = mapOf(
                                    "nombre" to nombre.value,
                                    "codigo" to codigo.value,
                                    "password" to password.value,
                                    "telefono" to telefono.value,
                                    "rol" to rol.value,
                                    "fotoUrl" to url
                                )

                                db.collection("usuarios").document(userId).update(datosActualizados)
                                    .addOnSuccessListener { guardadoExitoso = true }
                                    .addOnFailureListener {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Error al actualizar")
                                        }
                                    }
                            }
                        } catch (e: Exception) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Error: ${e.localizedMessage}")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A7))
            ) {
                Text("Guardar Cambios", color = Color.White)
            }
        }

        if (guardadoExitoso) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Usuario actualizado") },
                text = { Text("La información del usuario se guardó correctamente.") },
                confirmButton = {
                    TextButton(onClick = {
                        guardadoExitoso = false
                        navController.popBackStack()
                    }) {
                        Text("Aceptar")
                    }
                }
            )
        }
    }
}
