package com.example.minifinancieraapp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalleClienteScreen(navController: NavController, clienteId: String) {
    val db = FirebaseFirestore.getInstance()
    var cliente by remember { mutableStateOf<Map<String, Any>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var fotoAmpliada by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(clienteId) {
        try {
            val snapshot = db.collection("clientes").document(clienteId).get().await()
            cliente = snapshot.data
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detalles del Cliente", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0061A7))
            )
        }
    ) { padding ->
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            cliente?.let { c ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("🧍 Información Básica", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Nombre", c["nombre"])
                    InfoRow("Identidad", c["identidad"])
                    InfoRow("Teléfono", c["telefono"])
                    InfoRow("Empresa", c["nombreEmpresa"])
                    InfoRow("Dirección Casa", c["direccionCasa"])
                    InfoRow("Dirección Negocio", c["direccionNegocio"])
                    InfoRow("Garantía", c["garantia"])

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    if ((c["estadoCivil"] as? String)?.lowercase() == "casado") {
                        Text("👫 Datos del Cónyuge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        val conyuge = c["conyuge"] as? Map<*, *>
                        InfoRow("Nombre", conyuge?.get("nombre"))
                        InfoRow("Identidad", conyuge?.get("identidad"))
                        InfoRow("Teléfono", conyuge?.get("telefono"))
                        Divider(modifier = Modifier.padding(vertical = 12.dp))
                    }

                    Text("👥 Referencias Personales", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    listOf("ref1" to "Referencia 1", "ref2" to "Referencia 2").forEach { (clave, titulo) ->
                        val ref = c[clave] as? Map<*, *>
                        Text(titulo, fontWeight = FontWeight.SemiBold)
                        InfoRow("Nombre", ref?.get("nombre"))
                        InfoRow("Identidad", ref?.get("identidad"))
                        InfoRow("Teléfono", ref?.get("telefono"))
                        InfoRow("Parentesco", ref?.get("parentesco"))
                        InfoRow("Dirección", ref?.get("direccion"))
                        Spacer(Modifier.height(8.dp))
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    Text("📸 Galería de Fotos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    val fotosConEtiqueta = listOf(
                        "fotoPersonaUrl" to "🧍 Foto del Cliente",
                        "fotoCasaUrl" to "🏠 Foto de la Casa",
                        "fotoNegocioUrl" to "🏪 Foto del Negocio",
                        "fotoIdentidadFrenteUrl" to "🪪 Identidad (Frente)",
                        "fotoIdentidadReversoUrl" to "🪪 Identidad (Reverso)",
                        "fotoReciboLuzUrl" to "💡 Recibo de Luz",
                        "fotoGarantiaUrl" to "📜 Foto de la Garantía",
                        "fotoExtra1" to "📷 Foto Extra 1",
                        "fotoExtra2" to "📷 Foto Extra 2",
                        "fotoExtra3" to "📷 Foto Extra 3"
                    )

                    fotosConEtiqueta.forEach { (key, titulo) ->
                        val url = c[key] as? String
                        if (!url.isNullOrEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(titulo, fontWeight = FontWeight.SemiBold)
                                Image(
                                    painter = rememberAsyncImagePainter(url),
                                    contentDescription = titulo,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clickable { fotoAmpliada = url },
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    fotoAmpliada?.let { url ->
                        AlertDialog(
                            onDismissRequest = { fotoAmpliada = null },
                            confirmButton = {
                                TextButton(onClick = { fotoAmpliada = null }) {
                                    Text("Cerrar")
                                }
                            },
                            text = {
                                Image(
                                    painter = rememberAsyncImagePainter(url),
                                    contentDescription = "Foto ampliada",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 500.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: Any?) {
    Text("$label: ${value ?: "-"}", modifier = Modifier.padding(bottom = 2.dp))
}
