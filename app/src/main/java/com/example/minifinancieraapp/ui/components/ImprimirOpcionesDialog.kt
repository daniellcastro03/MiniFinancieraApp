package com.example.minifinancieraapp.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Diálogo compartido: antes de imprimir un recibo pregunta si se desea
 * imprimir directo a la impresora térmica Bluetooth (sin abrir nada externo)
 * o solo generar/abrir el PDF (flujo anterior con apps externas).
 */
@Composable
fun ImprimirOpcionesDialog(
    onImprimirDirecto: () -> Unit,
    onSoloPdf: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Print,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        },
        title = { Text("¿Cómo desea imprimir?") },
        text = {
            Text("Puede imprimir el recibo directo en la impresora térmica conectada por Bluetooth, o solo generar el PDF para verlo o compartirlo.")
        },
        confirmButton = {
            TextButton(onClick = onImprimirDirecto) { Text("Imprimir") }
        },
        dismissButton = {
            TextButton(onClick = onSoloPdf) { Text("Solo PDF") }
        }
    )
}
