package com.example.capitalexpressapp.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.example.capitalexpressapp.ui.theme.CEColors
import com.example.capitalexpressapp.ui.theme.PremiumCard
import com.example.capitalexpressapp.ui.theme.ceTextFieldColors
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
    var passwordVisible by remember { mutableStateOf(false) }
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
            title = { Text("Usuario creado correctamente", color = CEColors.Primary, fontWeight = FontWeight.Bold) },
            text = { Text("El usuario se registró con éxito.", color = CEColors.OnSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    guardadoExitoso = false
                    navController.popBackStack()
                }) {
                    Text("Aceptar", color = CEColors.ActionBlue, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crear Usuario", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CEColors.Primary)
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = CEColors.Surface
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // FOTO DE PERFIL
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .border(4.dp, CEColors.SurfaceContainerHighest, CircleShape)
                        .background(CEColors.SurfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (fotoUri.value != null) {
                        Image(
                            painter = rememberAsyncImagePainter(fotoUri.value),
                            contentDescription = "Foto de perfil",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    } else {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = CEColors.Outline,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(CEColors.ActionBlue)
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }

            OutlinedButton(
                onClick = { launcher.launch("image/*") },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CEColors.ActionBlue),
                border = androidx.compose.foundation.BorderStroke(1.dp, CEColors.ActionBlue)
            ) {
                Text("Seleccionar Foto de Perfil", fontWeight = FontWeight.Medium)
            }

            // FORMULARIO
            PremiumCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedTextField(
                        nombre, { nombre = it },
                        label = { Text("Nombre completo") },
                        colors = ceTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        codigo, { codigo = it },
                        label = { Text("Código único") },
                        colors = ceTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Contraseña") },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = CEColors.Outline
                                )
                            }
                        },
                        colors = ceTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        identidad, { identidad = it },
                        label = { Text("Identidad") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = ceTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        telefono, { telefono = it },
                        label = { Text("Número de Teléfono") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = ceTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        direccion, { direccion = it },
                        label = { Text("Dirección") },
                        minLines = 3,
                        colors = ceTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(
                            readOnly = true,
                            value = rol,
                            onValueChange = {},
                            label = { Text("Rol") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            colors = ceTextFieldColors(),
                            shape = RoundedCornerShape(12.dp),
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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CEColors.ActionBlue),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CEColors.ActionBlue)
                        ) {
                            Text("Cancelar", fontWeight = FontWeight.Bold)
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
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            enabled = !isLoading,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CEColors.Primary)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Guardar Usuario", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
