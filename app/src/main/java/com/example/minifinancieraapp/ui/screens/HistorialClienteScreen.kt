package com.example.minifinancieraapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class PrestamoHistorial(
    val monto: Double = 0.0,
    val fecha: String = "",
    val fechaExpiracion: String = "",
    val plazo: String = "",
    val mora: Double = 0.0,
    val id: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialClienteScreen(navController: NavController, clienteNombre: String) {
    val db = FirebaseFirestore.getInstance()
    var prestamos by remember { mutableStateOf(listOf<PrestamoHistorial>()) }
    var cargando by remember { mutableStateOf(true) }

    LaunchedEffect(clienteNombre) {
        val snapshot = db.collection("prestamos")
            .whereEqualTo("cliente", clienteNombre)
            .get()
            .await()

        prestamos = snapshot.documents.mapNotNull { doc ->
            PrestamoHistorial(
                monto = doc.getDouble("monto") ?: 0.0,
                fecha = doc.getString("fecha") ?: "",
                fechaExpiracion = doc.getString("fechaExpiracion") ?: "",
                plazo = doc.getString("plazo") ?: "",
                mora = doc.getDouble("mora") ?: 0.0,
                id = doc.id
            )
        }

        cargando = false
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Historial de $clienteNombre") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            if (cargando) {
                CircularProgressIndicator()
            } else if (prestamos.isEmpty()) {
                Text("Este cliente no tiene préstamos registrados.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(prestamos) { prestamo ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Monto: L. ${prestamo.monto}")
                                Text("Fecha: ${prestamo.fecha}")
                                Text("Vence: ${prestamo.fechaExpiracion}")
                                Text("Plazo: ${prestamo.plazo}")
                                Text("Mora: L. ${prestamo.mora}")
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(onClick = {
                                    navController.navigate("historialCuotas/${prestamo.id}")
                                }) {
                                    Text("Ver historial de cuotas")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}