package com.example.minifinancieraapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.capitalexpressapp.core.formatearLempiras
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.minifinancieraapp.ui.models.PagoItem
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InformeClienteScreen(navController: NavController, clienteId: String) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prestamos by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var cliente by remember { mutableStateOf<Map<String, Any>?>(null) }
    var incluirInfo by remember { mutableStateOf(true) }
    var pagosPorPrestamo by remember { mutableStateOf<Map<String, List<PagoItem>>>(emptyMap()) }

    LaunchedEffect(clienteId) {
        try {
            val clienteDoc = db.collection("clientes").document(clienteId).get().await()
            cliente = clienteDoc.data?.plus("id" to clienteDoc.id)
            val nombreCliente = cliente?.get("nombre")?.toString() ?: return@LaunchedEffect

            val prestamosSnap = db.collection("prestamos")
                .whereEqualTo("cliente", nombreCliente)
                .get().await()

            prestamos = prestamosSnap.documents.mapNotNull { it.data?.plus("id" to it.id) }

            val pagos = mutableMapOf<String, List<PagoItem>>()
            for (prestamo in prestamos) {
                val prestamoId = prestamo["id"]?.toString() ?: continue
                val pagosSnap = db.collection("pagos")
                    .whereEqualTo("prestamoId", prestamoId)
                    .get().await()

                val pagosList = pagosSnap.documents.mapNotNull {
                    PagoItem(
                        docId = it.id,
                        cliente = it["cliente"]?.toString() ?: "",
                        prestamoId = prestamoId,
                        fecha = it["fecha"]?.toString() ?: return@mapNotNull null,
                        monto = (it["monto"] as? Number)?.toDouble() ?: 0.0,
                        mora = (it["mora"] as? Number)?.toDouble() ?: 0.0,
                        interesTotal = (it["interesTotal"] as? Number)?.toDouble() ?: 0.0,
                        cuota = it["cuota"]?.toString() ?: "",
                        cobrador = it["cobrador"]?.toString() ?: ""
                    )
                }

                val producto = prestamo["producto"]?.toString() ?: "Préstamo"
                pagos[producto] = pagosList
            }

            pagosPorPrestamo = pagos

        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar datos", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Informe Completo") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = incluirInfo, onCheckedChange = { incluirInfo = it })
                Text("Incluir Mi Información")
            }

            Button(
                onClick = {
                    scope.launch {
                        val clienteNombre = cliente?.get("nombre")?.toString() ?: "Desconocido"
                        val direccion = cliente?.get("direccionCasa")?.toString() ?: "Sin dirección"

                        val listaPrestamos = prestamos.map {
                            val fecha = it["fecha"]?.toString() ?: ""
                            val producto = it["producto"]?.toString() ?: "Préstamo"
                            val monto = (it["monto"] as? Number)?.toDouble() ?: 0.0
                            Triple(fecha, producto, monto)
                        }

                        val file = ReciboHelper.generarInformeClientePDF(
                            context = context,
                            clienteNombre = clienteNombre,
                            direccion = direccion,
                            prestamos = listaPrestamos,
                            pagosPorPrestamo = pagosPorPrestamo,
                            incluirInfoCliente = incluirInfo
                        )

                        if (file != null) {
                            ReciboHelper.compartirReciboPDF(context, file)
                        } else {
                            Toast.makeText(context, "Error generando PDF", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Exportar informe a PDF")
            }

            Spacer(Modifier.height(16.dp))

            prestamos.forEach { prestamo ->
                val producto = prestamo["producto"]?.toString() ?: "Préstamo"
                val monto = (prestamo["monto"] as? Number)?.toDouble() ?: 0.0
                val saldo = (prestamo["saldo"] as? Number)?.toDouble() ?: 0.0
                val pagado = monto - saldo

                Text("Producto: $producto", style = MaterialTheme.typography.titleSmall)
                Text("Total: ${formatearLempiras(monto)}")
                Text("Pagado: ${formatearLempiras(pagado)}")
                Text("Pendiente: ${formatearLempiras(saldo)}")

                pagosPorPrestamo[producto]?.let { pagos ->
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Fecha", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        Text("Comentario", modifier = Modifier.weight(2f), style = MaterialTheme.typography.labelSmall)
                        Text("Pago", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        Text("Resta", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    }

                    var acumulado = 0.0
                    pagos.forEachIndexed { index, pago ->
                        acumulado += pago.monto
                        val resta = monto - acumulado
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(pago.fecha, modifier = Modifier.weight(1f), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                            Text(pago.cuota, modifier = Modifier.weight(2f), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                            Text(formatearLempiras(pago.monto), modifier = Modifier.weight(1f), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                            Text(formatearLempiras(resta), modifier = Modifier.weight(1f), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
