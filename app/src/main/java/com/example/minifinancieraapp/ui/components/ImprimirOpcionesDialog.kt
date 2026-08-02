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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

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
 * intentar imprimir.
 *
 * [onImprimirDirecto] debe devolver `true` si logró imprimir. Si devuelve `false`
 * (falla de conexión, impresora apagada, etc.) o el permiso de Bluetooth es
 * denegado, el diálogo cae automáticamente a [onSoloPdf] como respaldo — así
 * el usuario siempre termina con algo (recibo impreso o PDF abierto), nunca
 * se queda sin nada por un fallo silencioso.
 */
@Composable
fun ImprimirOpcionesDialog(
    onImprimirDirecto: suspend () -> Boolean,
    onSoloPdf: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun intentarImprimirDirecto() {
        scope.launch {
            val exito = try {
                onImprimirDirecto()
            } catch (e: Exception) {
                false
            }
            if (!exito) {
                Toast.makeText(
                    context,
                    "No se pudo imprimir directo, abriendo el PDF como respaldo…",
                    Toast.LENGTH_SHORT
                ).show()
                onSoloPdf()
            }
        }
    }

    val solicitarPermisoBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) {
            intentarImprimirDirecto()
        } else {
            Toast.makeText(
                context,
                "Permiso de Bluetooth denegado, abriendo el PDF como respaldo…",
                Toast.LENGTH_LONG
            ).show()
            onSoloPdf()
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
            Text("Puede imprimir el recibo directo en la impresora térmica conectada por Bluetooth, o solo generar el PDF para verlo o compartirlo. Si la impresión directa falla, se abrirá el PDF automáticamente.")
        },
        confirmButton = {
            TextButton(onClick = {
                if (tienePermisoBluetoothConnect(context)) {
                    intentarImprimirDirecto()
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
