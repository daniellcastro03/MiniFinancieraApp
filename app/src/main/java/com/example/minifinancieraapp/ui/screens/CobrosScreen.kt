package com.example.minifinancieraapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CobrosScreen(navController: NavController) {
    val db = FirebaseFirestore.getInstance()
    var clientesConPrestamo by remember { mutableStateOf(listOf<Pair<String, String>>()) }

    LaunchedEffect(Unit) {
        val prestamosSnapshot = db.collection("prestamos").get().await()
        clientesConPrestamo = prestamosSnapshot.documents.mapNotNull {
            val cliente = it.getString("cliente") ?: return@mapNotNull null
            val prestamoId = it.id
            cliente to prestamoId
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Cobros") })
    }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            items(clientesConPrestamo, key = { it.second }) { (cliente, prestamoId) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable {
                            navController.navigate("DetalleCobrosScreen/${cliente}/${prestamoId}")
                        }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Cliente: $cliente")
                        Text("Préstamo ID: $prestamoId", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

