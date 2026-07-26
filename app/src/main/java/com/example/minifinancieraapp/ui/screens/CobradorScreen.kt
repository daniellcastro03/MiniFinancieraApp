package com.example.capitalexpressapp.ui.screens

import android.net.Uri
import android.widget.Toast
import com.example.capitalexpressapp.core.formatearLempiras
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class ClienteCobradorModel(
    val nombre: String,
    val empresa: String,
    val telefono: String,
    val prestamosActivos: Int,
    val saldoPendiente: Double,
    val prestamosIds: List<String>,
    val pagosRecientes: List<PagoResumenModel> = emptyList()
)

data class PagoResumenModel(
    val monto: Double,
    val fecha: String,
    val cuota: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CobradorScreen(navController: NavController, uid: String) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    var clientes by remember { mutableStateOf(listOf<ClienteCobradorModel>()) }
    var cargando by remember { mutableStateOf(true) }

    LaunchedEffect(uid) {
        try {
            val clientesSnapshot = db.collection("clientes")
                .whereEqualTo("cobradorAsignado", uid)
                .get().await()

            val prestamosSnapshot = db.collection("prestamos")
                .whereEqualTo("estado", "activo")
                .get().await()

            val pagosSnapshot = db.collection("pagos").get().await()

            clientes = clientesSnapshot.documents.map { doc ->
                val nombre = doc.getString("nombre") ?: ""
                val telefono = doc.getString("telefono") ?: ""
                val empresa = doc.getString("nombreEmpresa") ?: ""

                val prestamosCliente = prestamosSnapshot.documents.filter {
                    it.getString("cliente") == nombre && it.getString("cobradorAsignado") == uid
                }

                val prestamosActivos = prestamosCliente.size
                val saldoTotal = prestamosCliente.sumOf { it.getDouble("saldo") ?: 0.0 }
                val ids = prestamosCliente.map { it.id }

                val pagosCliente = pagosSnapshot.documents.filter {
                    it.getString("clienteNombre") == nombre
                }.sortedByDescending {
                    it.getTimestamp("fechaPago")?.toDate()
                }.take(3).mapNotNull {
                    val monto = it.getDouble("monto") ?: return@mapNotNull null
                    val fecha = it.getTimestamp("fechaPago")?.toDate()?.let {
                        android.text.format.DateFormat.format("dd/MM/yyyy", it).toString()
                    } ?: "Sin fecha"
                    val cuota = it.getString("cuotas") ?: "-"
                    PagoResumenModel(monto, fecha, cuota)
                }

                ClienteCobradorModel(
                    nombre = nombre,
                    empresa = empresa,
                    telefono = telefono,
                    prestamosActivos = prestamosActivos,
                    saldoPendiente = saldoTotal,
                    prestamosIds = ids,
                    pagosRecientes = pagosCliente
                )
            }
        } catch (e: Exception) {
            clientes = emptyList()
            Toast.makeText(context, "Error al cargar clientes", Toast.LENGTH_SHORT).show()
        } finally {
            cargando = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Clientes Asignados") })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("crearCliente") },
                containerColor = Color(0xFF0061A7),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Nuevo Cliente")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            if (cargando) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (clientes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay clientes asignados.", color = Color.Gray)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(clientes) { cliente ->
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = cliente.nombre,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        navController.navigate("PerfilClienteScreen/${cliente.nombre}")
                                    }
                                )
                                Text("📞 ${cliente.telefono}")
                                Text("🏢 ${cliente.empresa}")
                                Text("📋 Préstamos activos: ${cliente.prestamosActivos}")
                                Text(
                                    "💰 Saldo pendiente: ${formatearLempiras(cliente.saldoPendiente)}",
                                    color = Color.Red
                                )

                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        if (cliente.prestamosIds.isNotEmpty()) {
                                            val prestamoId = cliente.prestamosIds.first()
                                            val ruta = "RegistrarPagoScreen/" +
                                                    "${Uri.encode(cliente.nombre)}/" +
                                                    "$prestamoId/" +
                                                    "${cliente.saldoPendiente}/" +
                                                    uid
                                            navController.navigate(ruta)
                                        } else {
                                            Toast.makeText(context, "Sin préstamos para cobrar", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Ver Perfil y Cobrar")
                                }

                                if (cliente.pagosRecientes.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Divider()
                                    Text("📜 Últimos pagos:", fontWeight = FontWeight.Bold)
                                    cliente.pagosRecientes.forEach { pago ->
                                        Text("💸 ${formatearLempiras(pago.monto)} | 📄 Cuota ${pago.cuota} | 📅 ${pago.fecha}")
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
