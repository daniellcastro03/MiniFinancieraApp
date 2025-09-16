package com.example.minifinancieraapp.ui.screens

import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CobradoresScreen(navController: NavController) {
    var cobradores by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        val snapshot = FirebaseFirestore.getInstance()
            .collection("usuarios")
            .whereEqualTo("rol", "cobrador")
            .get()
            .await()

        cobradores = snapshot.documents.mapNotNull { it.getString("nombre") }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Listado de Cobradores") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            LazyColumn {
                items(cobradores) { nombre ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                navController.navigate("clientesAsignados/$nombre")
                            }
                    ) {
                        Text(nombre, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}
