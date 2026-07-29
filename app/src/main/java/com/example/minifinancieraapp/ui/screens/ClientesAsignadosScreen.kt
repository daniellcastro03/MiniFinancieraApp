package com.example.minifinancieraapp.ui.screens

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

data class ClienteAsignado(
    val nombre: String,
    val telefono: String,
    val identidad: String,
    val foto: String,
    val tienePrestamo: Boolean,
    val cantidadAbonos: Int,
    val totalAbonado: Double,
    val ultimaFechaPago: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientesAsignadosScreen(navController: NavController, cobradorNombre: String) {
    val context = LocalContext.current
    var clientes by remember { mutableStateOf(listOf<ClienteAsignado>()) }

    LaunchedEffect(cobradorNombre) {
        val db = FirebaseFirestore.getInstance()
        val prefs = context.getSharedPreferences("offline_cache", Context.MODE_PRIVATE)

        try {
            val clientesDocs = db.collection("clientes")
                .whereEqualTo("cobradorAsignado", cobradorNombre)
                .get().await()

            val prestamos = db.collection("prestamos").get().await()
            val pagos = db.collection("pagos").get().await()

            val nuevosClientes = clientesDocs.documents.map { doc ->
                val nombre = doc.getString("nombre") ?: ""
                val telefono = doc.getString("telefono") ?: ""
                val identidad = doc.getString("identidad") ?: ""
                val foto = doc.getString("fotoPersonaUrl") ?: ""
                val clienteId = doc.id

                val tienePrestamo = prestamos.documents.any { it.getString("cliente") == nombre }
                val pagosCliente = pagos.documents.filter { it.getString("clienteId") == clienteId }
                val cantidadAbonos = pagosCliente.size
                val totalAbonado = pagosCliente.sumOf { it.getDouble("monto") ?: 0.0 }
                val ultimaFecha = pagosCliente.maxByOrNull { it.getString("fechaPago") ?: "" }?.getString("fechaPago") ?: "-"

                ClienteAsignado(nombre, telefono, identidad, foto, tienePrestamo, cantidadAbonos, totalAbonado, ultimaFecha)
            }

            clientes = nuevosClientes
            guardarClientesEnCache(prefs, nuevosClientes)

        } catch (e: Exception) {
            Log.e("OFFLINE", "Sin conexión. Cargando desde caché...")
            clientes = cargarClientesDesdeCache(prefs)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Clientes de $cobradorNombre") })
        }
    ) { padding ->
        LazyColumn(modifier = Modifier
            .padding(padding)
            .padding(16.dp)) {
            items(clientes, key = { it.identidad.ifBlank { it.nombre } }) { cliente ->
                Card(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cliente.nombre, fontWeight = FontWeight.Bold)
                            Text("Tel: ${cliente.telefono}", fontSize = 14.sp)
                            Text("ID: ${cliente.identidad}", fontSize = 13.sp)
                            Text(
                                if (cliente.tienePrestamo) "Con préstamo" else "Sin préstamo",
                                color = if (cliente.tienePrestamo) Color.Green else Color.Gray,
                                fontSize = 13.sp
                            )
                            Text("Abonos: ${cliente.cantidadAbonos}", fontSize = 13.sp)
                            Text("Total abonado: L. ${cliente.totalAbonado}", fontSize = 13.sp)
                            Text("Último pago: ${cliente.ultimaFechaPago}", fontSize = 13.sp)
                            TextButton(onClick = {
                                val intent = Intent(Intent.ACTION_DIAL)
                                intent.data = Uri.parse("tel:${cliente.telefono}")
                                context.startActivity(intent)
                            }) {
                                Text("Llamar", fontSize = 12.sp)
                            }
                        }

                        if (cliente.foto.isNotBlank()) {
                            Image(
                                painter = rememberAsyncImagePainter(
                                    model = coil.request.ImageRequest.Builder(context)
                                        .data(cliente.foto)
                                        .size(200, 200)
                                        .build()
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(64.dp).background(Color.LightGray)
                            )
                        }
                    }
                }
            }
        }
    }
}

fun guardarClientesEnCache(prefs: SharedPreferences, lista: List<ClienteAsignado>) {
    val jsonArray = JSONArray()
    lista.forEach {
        val obj = JSONObject().apply {
            put("nombre", it.nombre)
            put("telefono", it.telefono)
            put("identidad", it.identidad)
            put("foto", it.foto)
            put("tienePrestamo", it.tienePrestamo)
            put("cantidadAbonos", it.cantidadAbonos)
            put("totalAbonado", it.totalAbonado)
            put("ultimaFechaPago", it.ultimaFechaPago)
        }
        jsonArray.put(obj)
    }
    prefs.edit().putString("clientes_cache", jsonArray.toString()).apply()
}

fun cargarClientesDesdeCache(prefs: SharedPreferences): List<ClienteAsignado> {
    val jsonString = prefs.getString("clientes_cache", null) ?: return emptyList()
    val jsonArray = JSONArray(jsonString)
    val lista = mutableListOf<ClienteAsignado>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        lista.add(
            ClienteAsignado(
                nombre = obj.getString("nombre"),
                telefono = obj.getString("telefono"),
                identidad = obj.getString("identidad"),
                foto = obj.getString("foto"),
                tienePrestamo = obj.getBoolean("tienePrestamo"),
                cantidadAbonos = obj.getInt("cantidadAbonos"),
                totalAbonado = obj.getDouble("totalAbonado"),
                ultimaFechaPago = obj.getString("ultimaFechaPago")
            )
        )
    }
    return lista
}
