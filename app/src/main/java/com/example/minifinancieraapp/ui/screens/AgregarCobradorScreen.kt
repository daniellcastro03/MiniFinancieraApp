package com.example.minifinancieraapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgregarCobradorScreen(navController: NavController) {
    val nombre = remember { mutableStateOf("") }
    val codigo = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agregar Cobrador") },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = nombre.value,
                onValueChange = { nombre.value = it },
                label = { Text("Nombre del cobrador") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = codigo.value,
                onValueChange = { codigo.value = it },
                label = { Text("Código único") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password.value,
                onValueChange = { password.value = it },
                label = { Text("Contraseña") },
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (nombre.value.isBlank() || codigo.value.isBlank() || password.value.isBlank()) {
                        Toast.makeText(context, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    scope.launch {
                        val exists = db.collection("usuarios")
                            .whereEqualTo("codigo", codigo.value)
                            .get()
                            .await()
                            .isEmpty.not()

                        if (exists) {
                            Toast.makeText(context, "Ese código ya está en uso", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        val nuevoCobrador = mapOf(
                            "nombre" to nombre.value,
                            "codigo" to codigo.value,
                            "password" to password.value,
                            "rol" to "cobrador"
                        )

                        db.collection("usuarios").add(nuevoCobrador)
                            .addOnSuccessListener {
                                Toast.makeText(context, "Cobrador registrado", Toast.LENGTH_SHORT).show()
                                navController.popBackStack()
                            }
                            .addOnFailureListener {
                                Toast.makeText(context, "Error al registrar cobrador", Toast.LENGTH_SHORT).show()
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Guardar")
            }
        }
    }
}
