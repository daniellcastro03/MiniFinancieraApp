package com.example.minifinancieraapp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAdminScreen(navController: NavController) {
    var selectedTab by remember { mutableStateOf(0) }

    val tabs = listOf("Clientes", "Carpetas", "Cobros", "Dashboard")
    val icons = listOf(Icons.Default.Person, Icons.Default.MailOutline, Icons.Default.Check, Icons.Default.MoreVert)

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title) }
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(text = tabs[selectedTab]) }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> ClientesAdminView()
                1 -> CarpetasAdminView()
                2 -> CobrosAdminView()
                3 -> DashboardAdminView()
            }
        }
    }
}

@Composable
fun ClientesAdminView() {
    Text("Pantalla de Clientes (por implementar)", modifier = Modifier.padding(16.dp))
}

@Composable
fun CarpetasAdminView() {
    Text("Pantalla de Carpetas (por implementar)", modifier = Modifier.padding(16.dp))
}

@Composable
fun CobrosAdminView() {
    Text("Pantalla de Detalle de Cobros (por implementar)", modifier = Modifier.padding(16.dp))
}

@Composable
fun DashboardAdminView() {
    Text("Dashboard General (por implementar)", modifier = Modifier.padding(16.dp))
}
