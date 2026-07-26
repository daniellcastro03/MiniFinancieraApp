package com.example.capitalexpressapp.core

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ActualizacionDialog(
    actualizacion: ActualizacionDisponible,
    descargando: Boolean,
    progreso: Float,
    onActualizar: () -> Unit,
    onDespues: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!descargando) onDespues() },
        title = { Text("Actualización disponible (v${actualizacion.version})", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (actualizacion.notas.isNotBlank()) {
                    Text(actualizacion.notas)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (descargando) {
                    LinearProgressIndicator(
                        progress = { progreso },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onActualizar, enabled = !descargando) {
                Text(if (descargando) "Descargando..." else "Actualizar ahora")
            }
        },
        dismissButton = {
            TextButton(onClick = onDespues, enabled = !descargando) {
                Text("Después")
            }
        }
    )
}
