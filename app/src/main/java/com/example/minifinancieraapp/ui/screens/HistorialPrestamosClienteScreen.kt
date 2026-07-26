package com.example.minifinancieraapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.capitalexpressapp.core.formatearLempiras
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class PrestamoItem(
    val id: String,
    val monto: Double,
    val fecha: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialPrestamosClienteScreen(
    navController: NavController,
    clienteNombre: String
) {
    val db = FirebaseFirestore.getInstance()
    var prestamos by remember { mutableStateOf(listOf<PrestamoItem>()) }

    LaunchedEffect(clienteNombre) {
        val snapshot = db.collection("prestamos")
            .whereEqualTo("cliente", clienteNombre)
            .get().await()

        prestamos = snapshot.documents.mapNotNull { doc ->
            val id = doc.id
            val monto = doc.getDouble("monto") ?: return@mapNotNull null
            val fecha = doc.getString("fecha") ?: return@mapNotNull null
            PrestamoItem(id, monto, fecha)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Préstamos de $clienteNombre") })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (prestamos.isNotEmpty()) {
                        val ultimoPrestamo = prestamos.last()
                        navController.navigate("detalleCobro/NA/$clienteNombre/${ultimoPrestamo.id}")
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nuevo Cobro")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            items(prestamos) { prestamo ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("ID: ${prestamo.id}")
                        Text("Monto: ${formatearLempiras(prestamo.monto)}")
                        Text("Fecha: ${prestamo.fecha}")

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = {
                                navController.navigate("verPerfilCliente/$clienteNombre")
                            }) {
                                Icon(Icons.Default.Info, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Ver Perfil")
                            }

                            TextButton(onClick = {
                                // Navegación corregida
                                navController.navigate("HistorialCuotasPrestamoScreen/${prestamo.id}")
                            }) {
                                Icon(Icons.Default.List, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Ver Cuotas")
                            }
                        }
                    }
                }
            }
        }
    }
}
