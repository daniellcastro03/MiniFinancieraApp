package com.example.minifinancieraapp.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

fun isOnline(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SincronizarSolicitudesPendientesScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var solicitudesPendientes by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var sincronizando by remember { mutableStateOf(false) }

    fun cargarPendientes() {
        val shared = context.getSharedPreferences("offline_data", Context.MODE_PRIVATE)
        val json = shared.getString("solicitudes_pendientes", "[]")
        val tipo = object : TypeToken<List<Map<String, Any>>>() {}.type
        solicitudesPendientes = Gson().fromJson(json, tipo)
    }

    LaunchedEffect(Unit) {
        cargarPendientes()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Sincronizar Solicitudes") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Solicitudes pendientes: ${solicitudesPendientes.size}")

            Button(
                onClick = {
                    if (!isOnline(context)) {
                        Toast.makeText(context, "Sin conexión a Internet", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    if (solicitudesPendientes.isEmpty()) {
                        Toast.makeText(context, "No hay solicitudes pendientes", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    scope.launch {
                        sincronizando = true
                        val db = FirebaseFirestore.getInstance()
                        val errores = mutableListOf<String>()

                        solicitudesPendientes.forEach { solicitud ->
                            try {
                                db.collection("solicitudes_prestamo").add(solicitud).await()
                            } catch (e: Exception) {
                                errores.add("❌ ${solicitud["cliente"] ?: "Desconocido"}: ${e.message}")
                            }
                        }

                        if (errores.isEmpty()) {
                            val editor = context.getSharedPreferences("offline_data", Context.MODE_PRIVATE).edit()
                            editor.remove("solicitudes_pendientes")
                            editor.apply()
                            snackbarHostState.showSnackbar("✅ Todo sincronizado con éxito")
                            solicitudesPendientes = emptyList()
                        } else {
                            snackbarHostState.showSnackbar("Algunas solicitudes fallaron:\n${errores.joinToString("\n")}")
                        }

                        sincronizando = false
                    }
                },
                enabled = !sincronizando
            ) {
                Text(if (sincronizando) "Sincronizando..." else "Sincronizar ahora")
            }
        }
    }
}
