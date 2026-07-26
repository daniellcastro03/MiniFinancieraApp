package com.example.minifinancieraapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

// Data class para préstamo
data class PrestamoGlobal(
    val cliente: String = "",
    val monto: Double = 0.0,
    val fecha: String = "",
    val fechaExpiracion: String = "",
    val plazo: String = "",
    val mora: Double = 0.0,
    val prestamoId: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialGlobalPrestamosScreen(navController: NavController) {
    val db = FirebaseFirestore.getInstance()
    var prestamos by remember { mutableStateOf(listOf<PrestamoGlobal>()) }
    var cargando by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val snapshot = db.collection("prestamos").get().await()
        prestamos = snapshot.documents.mapNotNull { doc ->
            PrestamoGlobal(
                cliente = doc.getString("cliente") ?: "",
                monto = doc.getDouble("monto") ?: 0.0,
                fecha = doc.getString("fecha") ?: "",
                fechaExpiracion = doc.getString("fechaExpiracion") ?: "",
                plazo = doc.getString("plazo") ?: "",
                mora = doc.getDouble("mora") ?: 0.0,
                prestamoId = doc.id
            )
        }.sortedByDescending { it.fecha }

        cargando = false
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Historial de Préstamos Global") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            if (cargando) {
                CircularProgressIndicator()
            } else if (prestamos.isEmpty()) {
                Text("No hay préstamos registrados.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(prestamos) { prestamo ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Cliente: ${prestamo.cliente}", fontSize = 16.sp)
                                Text("Monto: L. ${prestamo.monto}", fontSize = 14.sp)
                                Text("Fecha: ${prestamo.fecha}", fontSize = 14.sp)
                                Text("Vence: ${prestamo.fechaExpiracion}", fontSize = 14.sp)
                                Text("Plazo: ${prestamo.plazo}", fontSize = 14.sp)
                                Text("Mora: L. ${prestamo.mora}", fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = {
                                        navController.navigate("DetalleCobrosScreen/${prestamo.cliente}/${prestamo.prestamoId}")
                                    }) {
                                        Text("Abonar")
                                    }
                                    Button(onClick = {
                                        navController.navigate("HistorialPagosScreen")
                                    }) {
                                        Text("Historial")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
