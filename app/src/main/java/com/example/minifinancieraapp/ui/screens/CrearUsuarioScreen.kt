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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrearUsuarioScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var nombre by remember { mutableStateOf("") }
    var codigo by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rol by remember { mutableStateOf("admin") }
    var direccion by remember { mutableStateOf("") }
    var identidad by remember { mutableStateOf("") }
    var telefono by remember { mutableStateOf("") }

    val fotoUri = remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        fotoUri.value = it
    }

    val roles = listOf("admin", "cobrador")
    var expanded by remember { mutableStateOf(false) }
    var guardadoExitoso by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    if (guardadoExitoso) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("✅ Usuario creado correctamente") },
            text = { Text("El usuario se registró con éxito.") },
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crear Usuario", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0061A7))
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(nombre, { nombre = it }, label = { Text("Nombre completo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(codigo, { codigo = it }, label = { Text("Código único") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Contraseña") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(identidad, { identidad = it }, label = { Text("Identidad") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(direccion, { direccion = it }, label = { Text("Dirección") }, modifier = Modifier.fillMaxWidth())

            OutlinedTextField(
                value = telefono,
                onValueChange = { telefono = it },
                label = { Text("Número de Teléfono") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    readOnly = true,
                    value = rol,
                    onValueChange = {},
                    label = { Text("Rol") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    roles.forEach {
                        DropdownMenuItem(text = { Text(it) }, onClick = {
                            rol = it
                            expanded = false
                        })
                    }
                }
            }

            Button(
                onClick = { launcher.launch("image/*") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A7))
            ) {
                Text("Seleccionar Foto de Perfil", color = Color.White)
            }

            fotoUri.value?.let {
                Image(
                    painter = rememberAsyncImagePainter(it),
                    contentDescription = null,
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.Gray, CircleShape)
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        if (
                            nombre.isBlank() || codigo.isBlank() || password.isBlank() ||
                            rol.isBlank() || telefono.isBlank() || telefono.length < 8
                        ) {
                            snackbarHostState.showSnackbar("Completa todos los campos correctamente.")
                            isLoading = false
                            return@launch
                        }

                        try {
                            FirebaseApp.initializeApp(context)
                            val db = FirebaseFirestore.getInstance()
                            val storage = FirebaseStorage.getInstance()

                            val existe = db.collection("usuarios")
                                .whereEqualTo("codigo", codigo)
                                .get()
                                .await()
                                .isEmpty.not()

                            if (existe) {
                                snackbarHostState.showSnackbar("Este código ya está registrado.")
                                isLoading = false
                                return@launch
                            }

                            val usuarioId = UUID.randomUUID().toString()

                            val guardarUsuario = { fotoUrl: String ->
                                val userMap = mapOf(
                                    "nombre" to nombre,
                                    "codigo" to codigo,
                                    "password" to password,
                                    "rol" to rol,
                                    "direccion" to direccion,
                                    "identidad" to identidad,
                                    "telefono" to telefono,
                                    "fotoUrl" to fotoUrl
                                )

                                db.collection("usuarios").document(usuarioId).set(userMap)
                                    .addOnSuccessListener {
                                        nombre = ""; codigo = ""; password = ""; rol = "admin"
                                        direccion = ""; identidad = ""; fotoUri.value = null; telefono = ""
                                        guardadoExitoso = true
                                    }
                                    .addOnFailureListener {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Error al guardar usuario")
                                        }
                                    }.addOnCompleteListener { isLoading = false }
                            }

                            if (fotoUri.value != null) {
                                val ref = storage.reference.child("usuarios/$usuarioId/foto.jpg")
                                ref.putFile(fotoUri.value!!).addOnSuccessListener {
                                    ref.downloadUrl.addOnSuccessListener { uri ->
                                        guardarUsuario(uri.toString())
                                    }.addOnFailureListener {
                                        guardarUsuario("")
                                        isLoading = false
                                    }
                                }.addOnFailureListener {
                                    guardarUsuario("")
                                    isLoading = false
                                }
                            } else {
                                guardarUsuario("")
                            }

                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Error inesperado: ${e.localizedMessage}")
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A7))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                } else {
                    Text("Guardar Usuario", color = Color.White)
                }
            }
        }
    }
}
