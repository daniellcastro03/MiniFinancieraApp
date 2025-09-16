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
import com.example.capitalexpressapp.util.NetworkUtils.isInternetAvailable
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SincronizarAbonosPendientesScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var cantidad by remember { mutableStateOf(0) }
    var sincronizados by remember { mutableStateOf(0) }
    var mostrandoResultado by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Sincronizar Abonos Pendientes") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Presiona el botón para subir los abonos guardados sin conexión.")

            Button(
                onClick = {
                    scope.launch {
                        val prefs = context.getSharedPreferences("offline_data", Context.MODE_PRIVATE)
                        val gson = Gson()
                        val json = prefs.getString("abonos_pendientes", "[]")
                        val tipo = object : TypeToken<MutableList<Map<String, Any>>>() {}.type
                        val lista = gson.fromJson<MutableList<Map<String, Any>>>(json, tipo)

                        cantidad = lista.size
                        sincronizados = 0

                        if (!isInternetAvailable(context)) {
                            snackbarHostState.showSnackbar("Sin conexión a internet")
                            return@launch
                        }

                        if (lista.isEmpty()) {
                            snackbarHostState.showSnackbar("No hay abonos pendientes por sincronizar.")
                            return@launch
                        }

                        val db = FirebaseFirestore.getInstance()
                        val nuevos = mutableListOf<Map<String, Any>>()

                        for (item in lista) {
                            try {
                                db.collection("pagos").add(item).await()
                                sincronizados++
                            } catch (e: Exception) {
                                nuevos.add(item)
                            }
                        }

                        prefs.edit().putString("abonos_pendientes", gson.toJson(nuevos)).apply()
                        mostrandoResultado = true
                    }
                }
            ) {
                Text("🔄 Sincronizar Abonos")
            }

            if (mostrandoResultado) {
                Text("Intentados: $cantidad")
                Text("Exitosos: $sincronizados")
                Text("Fallidos: ${cantidad - sincronizados}")
            }
        }
    }
}
