package com.example.minifinancieraapp.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrarCobradorScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()
    val storage = FirebaseStorage.getInstance()

    var nombre by remember { mutableStateOf("") }
    var codigo by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var identidad by remember { mutableStateOf("") }
    var direccion by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var imageUrl by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        imageUri = uri
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Registrar Cobrador") })
    }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre completo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = codigo, onValueChange = { codigo = it }, label = { Text("Código único") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = identidad, onValueChange = { identidad = it }, label = { Text("Identidad") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = direccion, onValueChange = { direccion = it }, label = { Text("Dirección") }, modifier = Modifier.fillMaxWidth())

            Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Seleccionar foto del cobrador")
            }

            imageUri?.let {
                Image(
                    painter = rememberAsyncImagePainter(it),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp)
                )
            }

            Button(onClick = {
                if (nombre.isBlank() || codigo.isBlank() || password.isBlank() || identidad.isBlank() || direccion.isBlank()) {
                    Toast.makeText(context, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val filename = "cobradores/${UUID.randomUUID()}.jpg"
                val ref = storage.reference.child(filename)

                scope.launch {
                    imageUri?.let { uri ->
                        ref.putFile(uri)
                            .continueWithTask { ref.downloadUrl }
                            .addOnSuccessListener { url ->
                                imageUrl = url.toString()

                                db.collection("usuarios")
                                    .whereEqualTo("codigo", codigo)
                                    .get()
                                    .addOnSuccessListener { result ->
                                        if (result.isEmpty) {
                                            val cobrador = hashMapOf(
                                                "nombre" to nombre,
                                                "codigo" to codigo,
                                                "password" to password,
                                                "rol" to "cobrador",
                                                "identidad" to identidad,
                                                "direccion" to direccion,
                                                "fotoUrl" to imageUrl
                                            )

                                            db.collection("usuarios").add(cobrador)
                                                .addOnSuccessListener {
                                                    Toast.makeText(context, "Cobrador registrado", Toast.LENGTH_SHORT).show()
                                                    navController.popBackStack()
                                                }
                                                .addOnFailureListener {
                                                    Toast.makeText(context, "Error al guardar", Toast.LENGTH_SHORT).show()
                                                }
                                        } else {
                                            Toast.makeText(context, "El código ya está en uso", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            }
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Guardar Cobrador")
            }
        }
    }
}
