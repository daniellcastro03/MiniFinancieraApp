package com.example.minifinancieraapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsignarCobradorScreen(navController: NavController, clienteId: String) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var listaUsuarios by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val seleccionados = remember { mutableStateListOf<String>() }

    var cargando by remember { mutableStateOf(true) }
    var guardando by remember { mutableStateOf(false) }

    // 🔄 Cargar cobradores y cliente actual
    LaunchedEffect(Unit) {
        try {
            cargando = true

            // 1️⃣ Cargar usuarios con rol cobrador
            val usuarios = db.collection("usuarios")
                .whereEqualTo("rol", "cobrador")
                .get().await()

            listaUsuarios = usuarios.documents.map {
                it.id to (it.getString("nombre") ?: "Sin nombre")
            }

            // 2️⃣ Cargar cliente y sus cobradores actuales
            val clienteDoc = db.collection("clientes").document(clienteId).get().await()
            val listaCobradores = when (val data = clienteDoc.get("cobradoresAsignados")) {
                is List<*> -> data.filterIsInstance<String>()
                else -> clienteDoc.getString("cobradorAsignado")?.let { listOf(it) } ?: emptyList()
            }

            seleccionados.clear()
            seleccionados.addAll(listaCobradores)

        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar datos: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            cargando = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Asignar Cobradores") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (cargando) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // Lista de cobradores disponibles
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(listaUsuarios) { (uid, nombre) ->
                        CobradorItemMultiple(
                            nombre = nombre,
                            isSelected = seleccionados.contains(uid),
                            onSelectionChange = { isSelected ->
                                if (isSelected) seleccionados.add(uid) else seleccionados.remove(uid)
                            }
                        )
                    }
                }

                // Botón Guardar
                Button(
                    onClick = {
                        scope.launch {
                            guardando = true
                            try {
                                // ✅ Permitir guardar aunque la lista esté vacía
                                db.collection("clientes").document(clienteId).update(
                                    mapOf(
                                        "cobradoresAsignados" to seleccionados,  // Puede quedar vacío
                                        "cobradorAsignado" to seleccionados.firstOrNull() // Será null si está vacío
                                    )
                                ).await()

                                // ✅ Actualizar todos los préstamos asociados a este cliente
                                val prestamosSnap = db.collection("prestamos")
                                    .whereEqualTo("clienteId", clienteId)
                                    .get().await()

                                prestamosSnap.documents.forEach { prestamo ->
                                    db.collection("prestamos").document(prestamo.id).update(
                                        mapOf(
                                            "cobradoresAsignados" to seleccionados,
                                            "cobradorAsignado" to seleccionados.firstOrNull()
                                        )
                                    ).await()
                                }

                                Toast.makeText(context, "✅ Asignación actualizada correctamente", Toast.LENGTH_SHORT).show()
                                navController.popBackStack()

                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al guardar: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                guardando = false
                            }
                        }
                    },
                    enabled = !guardando,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (guardando) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Guardando...")
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Guardar Asignación")
                    }
                }
            }
        }
    }
}

@Composable
private fun CobradorItemMultiple(
    nombre: String,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectionChange(!isSelected) },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = nombre,
                modifier = Modifier.weight(1f),
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Checkbox(
                checked = isSelected,
                onCheckedChange = onSelectionChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
