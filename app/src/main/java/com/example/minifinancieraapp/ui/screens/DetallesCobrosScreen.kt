package com.example.capitalexpressapp.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.core.formatearLempiras
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class PrestamoItem(
    val id: String,
    val cliente: String,
    val monto: Double,
    val mora: Double,
    val total: Double,
    val saldo: Double,
    val estado: String,
    val fecha: String,
    val plazo: String,
    val proximoPago: String,
    val moraAplicada: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalleCobrosScreen(navController: NavController, cobrador: String) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("offline_cache", Context.MODE_PRIVATE)
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    var prestamos by remember { mutableStateOf(listOf<PrestamoItem>()) }
    var cargando by remember { mutableStateOf(true) }

    val formatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    LaunchedEffect(cobrador) {
        try {
            val snapshot = db.collection("prestamos")
                .whereEqualTo("estado", "activo")
                .whereEqualTo("cobradorAsignado", cobrador)
                .get().await()

            val nuevosPrestamos = snapshot.documents.mapNotNull { doc ->
                val id = doc.id
                val cliente = doc.getString("cliente") ?: return@mapNotNull null
                val monto = doc.getDouble("monto") ?: 0.0
                val interes = doc.getDouble("interes") ?: 0.0
                val mora = doc.getDouble("mora") ?: 3.0
                val cuotas = doc.getLong("cuotas")?.toInt() ?: 1
                val interesTotal = monto * (interes * cuotas / 100.0)
                val total = monto + interesTotal
                val saldo = doc.getDouble("saldo") ?: total
                val fecha = doc.getString("fecha") ?: "-"
                val estado = doc.getString("estado") ?: "activo"
                val plazo = doc.getString("plazo") ?: "-"
                val proximoPago = doc.getString("proximoPago") ?: "-"
                val moraAplicada = doc.getBoolean("moraAplicada") ?: false

                PrestamoItem(id, cliente, monto, mora, total, saldo, estado, fecha, plazo, proximoPago, moraAplicada)
            }

            prestamos = nuevosPrestamos
            guardarPrestamosEnCache(prefs, nuevosPrestamos)
        } catch (e: Exception) {
            prestamos = cargarPrestamosDesdeCache(prefs)
        } finally {
            cargando = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Detalle de Cobros") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            when {
                cargando -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                prestamos.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No hay préstamos activos asignados.")
                }

                else -> {
                    Text("Préstamos activos asignados a ti", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(prestamos, key = { it.id }) { prestamo ->
                            val fechaHoy = remember { Date() }
                            val fechaPago = runCatching { formatter.parse(prestamo.proximoPago) }.getOrNull()
                            val diasAtraso = fechaPago?.let {
                                val diff = fechaHoy.time - it.time
                                (diff / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(0)
                            } ?: 0
                            val semanasAtraso = diasAtraso / 7

                            val moraExtra = if (semanasAtraso >= 1 && !prestamo.moraAplicada) {
                                (prestamo.monto * prestamo.mora / 100) * semanasAtraso
                            } else 0.0

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (prestamo.cliente.isNotBlank() && prestamo.saldo > 0.0) {
                                            val ruta = "RegistrarPagoScreen/" +
                                                    "${Uri.encode(prestamo.cliente)}/" +
                                                    "${prestamo.id}/" +
                                                    "${prestamo.saldo}/" +
                                                    cobrador
                                            navController.navigate(ruta)
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "No se puede registrar pago: cliente vacío o sin saldo",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("👤 Cliente: ${prestamo.cliente}", fontWeight = FontWeight.Bold)
                                    Text("💰 Total a pagar: ${formatearLempiras(prestamo.total)}")
                                    Text("📉 Saldo restante: ${formatearLempiras(prestamo.saldo)}")
                                    Text("📆 Próximo pago: ${prestamo.proximoPago}")
                                    Text("📅 Fecha inicial: ${prestamo.fecha}")
                                    Text("📈 Plazo: ${prestamo.plazo}")

                                    if (moraExtra > 0) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "⚠️ Mora detectada: ${formatearLempiras(moraExtra)}",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        // ℹ️ El sistema de moras se gestiona desde la pantalla
                                        // de Cobros (NotificacionesScreen) para mantener
                                        // consistencia con saldo, totalPagar, saldoOriginal, etc.
                                        Text(
                                            "Para aplicar mora, accede a la pantalla de Cobros.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
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

fun guardarPrestamosEnCache(prefs: android.content.SharedPreferences, lista: List<PrestamoItem>) {
    val jsonArray = JSONArray()
    lista.forEach {
        val obj = JSONObject().apply {
            put("id", it.id)
            put("cliente", it.cliente)
            put("monto", it.monto)
            put("mora", it.mora)
            put("total", it.total)
            put("saldo", it.saldo)
            put("estado", it.estado)
            put("fecha", it.fecha)
            put("plazo", it.plazo)
            put("proximoPago", it.proximoPago)
            put("moraAplicada", it.moraAplicada)
        }
        jsonArray.put(obj)
    }
    prefs.edit().putString("prestamos_cache", jsonArray.toString()).apply()
}

fun cargarPrestamosDesdeCache(prefs: android.content.SharedPreferences): List<PrestamoItem> {
    val jsonString = prefs.getString("prestamos_cache", null) ?: return emptyList()
    val jsonArray = JSONArray(jsonString)
    val lista = mutableListOf<PrestamoItem>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        lista.add(
            PrestamoItem(
                id = obj.getString("id"),
                cliente = obj.getString("cliente"),
                monto = obj.getDouble("monto"),
                mora = obj.getDouble("mora"),
                total = obj.getDouble("total"),
                saldo = obj.getDouble("saldo"),
                estado = obj.getString("estado"),
                fecha = obj.getString("fecha"),
                plazo = obj.getString("plazo"),
                proximoPago = obj.getString("proximoPago"),
                moraAplicada = obj.getBoolean("moraAplicada")
            )
        )
    }
    return lista
}
