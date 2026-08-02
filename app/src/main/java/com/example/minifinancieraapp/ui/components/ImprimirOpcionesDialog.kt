package com.example.minifinancieraapp.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * true si la app ya tiene permiso para usar Bluetooth clásico (conectar/listar
 * dispositivos vinculados). En Android < 12 este permiso no existe como runtime
 * permission (BLUETOOTH/BLUETOOTH_ADMIN son de instalación), así que siempre es true.
 */
private fun tienePermisoBluetoothConnect(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * Diálogo compartido: antes de imprimir un recibo pregunta si se desea
 * imprimir directo a la impresora térmica Bluetooth (sin abrir nada externo)
 * o solo generar/abrir el PDF (flujo anterior con apps externas).
 *
 * En Android 12+ conectarse a un dispositivo Bluetooth vinculado requiere el
 * permiso runtime BLUETOOTH_CONNECT (ya declarado en el manifest, pero hay que
 * pedirlo en tiempo de ejecución) — si no está concedido, se solicita antes de
 * invocar [onImprimirDirecto].
 */
@Composable
fun ImprimirOpcionesDialog(
    onImprimirDirecto: () -> Unit,
    onSoloPdf: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val solicitarPermisoBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) {
            onImprimirDirecto()
        } else {
            Toast.makeText(
                context,
                "Se necesita el permiso de Bluetooth para imprimir directo en la térmica",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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
            TextButton(onClick = {
                if (tienePermisoBluetoothConnect(context)) {
                    onImprimirDirecto()
                } else {
                    solicitarPermisoBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }) { Text("Imprimir") }
        },
        dismissButton = {
            TextButton(onClick = onSoloPdf) { Text("Solo PDF") }
        }
    )
}
