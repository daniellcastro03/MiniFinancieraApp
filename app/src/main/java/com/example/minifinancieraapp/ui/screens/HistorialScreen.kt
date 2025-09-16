package com.example.minifinancieraapp.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Historial de Cobros") })
        }
    ) {
        Text("Aquí se mostrará el historial completo", modifier = Modifier.padding(it))
    }
}
