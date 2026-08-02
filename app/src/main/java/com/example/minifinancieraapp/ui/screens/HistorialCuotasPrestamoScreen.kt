package com.example.minifinancieraapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.capitalexpressapp.util.ReciboHelper
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

data class PagoCuota(
    val id: String = "",
    val fechaPago: String = "",
    val monto: Double = 0.0,
    val registradoPor: String = "",
    val cuota: String = "",
    val proximoPago: String? = null,
    val cliente: String = "",
    val prestamoId: String = "",
    val interesTotal: Double = 0.0,
    val mora: Double = 0.0,
    val lugar: String = "No especificado",
    val firma: String = "Capital Express",
    val tipoPago: String = "Efectivo",
    val saldoRestante: Double? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialCuotasPrestamoScreen(navController: NavController, prestamoId: String) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pagos by remember { mutableStateOf(listOf<PagoCuota>()) }
    var cargando by remember { mutableStateOf(true) }

    val fullDateFormatter = remember { SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()) }

    LaunchedEffect(prestamoId) {
        cargando = true
        try {
            val snapshot = db.collection("pagos")
                .whereEqualTo("prestamoId", prestamoId)
                .orderBy("fechaPago", Query.Direction.ASCENDING)
                .get()
                .await()

            pagos = snapshot.documents.mapNotNull { doc ->
                val fecha = doc.getTimestamp("fechaPago")?.toDate()?.let {
                    fullDateFormatter.format(it)
                } ?: "Sin fecha"

                PagoCuota(
                    id = doc.id,
                    fechaPago = fecha,
                    monto = doc.getDouble("monto") ?: 0.0,
                    mora = doc.getDouble("mora") ?: 0.0,
                    registradoPor = doc.getString("registradoPor") ?: "Desconocido",
                    cuota = doc.getString("cuotas") ?: "-",
                    proximoPago = doc.getString("proximoPago") ?: "-",
                    cliente = doc.getString("clienteNombre") ?: "-",
                    prestamoId = doc.getString("prestamoId") ?: "",
                    lugar = doc.getString("lugar") ?: "-",
                    firma = doc.getString("firma") ?: "-",
                    tipoPago = doc.getString("tipoPago") ?: "Efectivo",
                    saldoRestante = doc.getDouble("saldoRestante")
                )
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar cuotas", Toast.LENGTH_SHORT).show()
        } finally {
            cargando = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Cuotas del Préstamo") })
        }
    ) { padding ->
        if (cargando) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Historial de Cuotas", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(pagos, key = { it.id.ifEmpty { it.fechaPago } }) { pago ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (pago.mora > 0.0) Color(0xFFFFEBEE) else Color.White
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("🗓 Fecha: ${pago.fechaPago}", fontSize = 16.sp)
                            Text("💵 Monto: L. ${pago.monto.toInt()}")
                            if (pago.mora > 0.0)
                                Text("📛 Mora: L. ${pago.mora.toInt()}", color = Color.Red)
                            Text("👤 Cobrador: ${pago.registradoPor}")
                            Text("📄 Cuota: ${pago.cuota}")
                            Text("📆 Próximo pago: ${pago.proximoPago}")
                            Text("📍 Lugar: ${pago.lugar}")
                            Text("✍️ Firma: ${pago.firma}")
                            Text("💳 Tipo de pago: ${pago.tipoPago}")

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Button(onClick = {
                                    scope.launch {
                                        val saldoAntesConMora =
                                            (pago.saldoRestante ?: 0.0) + pago.monto + pago.mora
                                        val archivoPDF = ReciboHelper.generarReciboPDF(
                                            context = context,
                                            cliente = pago.cliente,
                                            prestamoId = pago.prestamoId,
                                            fecha = pago.fechaPago,
                                            montoPagado = (pago.monto + pago.mora).toInt().toString(),
                                            saldoAnterior = saldoAntesConMora,
                                            proximoPago = pago.proximoPago ?: "-",
                                            cuota = pago.cuota,
                                            cobrador = pago.registradoPor,
                                            lugar = pago.lugar,
                                            firma = pago.firma,
                                            tipoPago = pago.tipoPago,
                                            mora = pago.mora,
                                            saldoNuevoFijo = pago.saldoRestante,
                                            montoAplicadoCuota = pago.monto
                                        )
                                        archivoPDF?.let {
                                            ReciboHelper.compartirReciboPDF(context, it)
                                        }
                                    }
                                }) {
                                    Text("Compartir PDF")
                                }

                                Button(onClick = {
                                    scope.launch {
                                        // Antes esto llamaba a ReciboHelper.imprimirRecibo (Bluetooth
                                        // directo) — se desactivó en toda la app porque congelaba la
                                        // impresora sin forma confiable de evitarlo. Ahora va directo
                                        // al flujo de PDF/RawBT, igual que el resto de pantallas.
                                        val saldoAntesConMora =
                                            (pago.saldoRestante ?: 0.0) + pago.monto + pago.mora
                                        val archivoPDF = ReciboHelper.generarReciboPDF(
                                            context = context,
                                            cliente = pago.cliente,
                                            prestamoId = pago.prestamoId,
                                            fecha = pago.fechaPago,
                                            montoPagado = (pago.monto + pago.mora).toInt().toString(),
                                            saldoAnterior = saldoAntesConMora,
                                            proximoPago = pago.proximoPago ?: "-",
                                            cuota = pago.cuota,
                                            cobrador = pago.registradoPor,
                                            lugar = pago.lugar,
                                            firma = pago.firma,
                                            tipoPago = pago.tipoPago,
                                            mora = pago.mora,
                                            saldoNuevoFijo = pago.saldoRestante,
                                            montoAplicadoCuota = pago.monto
                                        )
                                        if (archivoPDF != null) {
                                            ReciboHelper.imprimirPDF(context, archivoPDF)
                                        }
                                    }
                                }) {
                                    Text("Imprimir")
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    val totalPagado = pagos.sumOf { it.monto + it.mora }
                    Text("Total abonado: L. ${totalPagado.toInt()}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
