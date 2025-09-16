package com.example.capitalexpressapp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrestamosCobradorScreen(navController: NavController, cobradorNombre: String) {
    val db = FirebaseFirestore.getInstance()
    var prestamos by remember { mutableStateOf(listOf<PrestamoCobradorModel>()) }
    var cargando by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val snapshot = db.collection("prestamos")
            .whereEqualTo("cobradorAsignado", cobradorNombre)
            .get().await()

        prestamos = snapshot.documents.mapNotNull { doc ->
            PrestamoCobradorModel(
                id = doc.id,
                cliente = doc.getString("cliente") ?: "",
                monto = doc.getDouble("monto") ?: 0.0,
                saldo = doc.getDouble("saldo") ?: 0.0,
                fecha = doc.getString("fecha") ?: "",
                estado = doc.getString("estado") ?: "",
            )
        }
        cargando = false
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Préstamos de $cobradorNombre") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            if (cargando) {
                CircularProgressIndicator()
            } else if (prestamos.isEmpty()) {
                Text("No hay préstamos asignados a este cobrador.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(prestamos) { prestamo ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navController.navigate("PerfilClienteScreen/${prestamo.cliente}")
                                }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Cliente: ${prestamo.cliente}", fontWeight = FontWeight.Bold)
                                Text("Monto: L. ${prestamo.monto}")
                                Text("Saldo: L. ${prestamo.saldo}")
                                Text("Estado: ${prestamo.estado}")
                                Text("Fecha: ${prestamo.fecha}")
                            }
                        }
                    }
                }
            }
        }
    }
}

// Modelo de préstamo
data class PrestamoCobradorModel(
    val id: String,
    val cliente: String,
    val monto: Double,
    val saldo: Double,
    val fecha: String,
    val estado: String
)
