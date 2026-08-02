package com.example.minifinancieraapp.ui.components

import android.Manifest
import android.content.Context
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
private fun tienePermisoBluetoothConnect(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * Intenta imprimir directo y, si falla, cae automáticamente a [accionSoloPdf].
 *
 * IMPORTANTE: se debe lanzar con el `scope` de la PANTALLA (rememberCoroutineScope
 * del composable dueño de la pantalla), nunca con el scope de este diálogo — el
 * diálogo se cierra (y su propio scope se cancela) apenas el usuario toca
 * "Imprimir", así que si la impresión corriera en el scope del diálogo se
 * cancelaría a mitad de camino ("The coroutine scope left the composition").
 */
suspend fun intentarImprimirConRespaldo(
    context: Context,
    accionImprimirDirecto: (suspend () -> Boolean)?,
    accionSoloPdf: (() -> Unit)?
) {
    val exito = try {
        accionImprimirDirecto?.invoke() ?: false
    } catch (e: Exception) {
        false
    }
    if (!exito) {
        Toast.makeText(
            context,
            "No se pudo imprimir directo, abriendo el PDF como respaldo…",
            Toast.LENGTH_SHORT
        ).show()
        accionSoloPdf?.invoke()
    }
}

/**
 * Diálogo compartido: antes de imprimir un recibo pregunta si se desea
 * imprimir directo a la impresora térmica Bluetooth (sin abrir nada externo)
 * o solo generar/abrir el PDF (flujo anterior con apps externas).
 *
 * En Android 12+ conectarse a un dispositivo Bluetooth vinculado requiere el
 * permiso runtime BLUETOOTH_CONNECT (ya declarado en el manifest, pero hay que
 * pedirlo en tiempo de ejecución) — si no está concedido, se solicita antes de
 * disparar [onImprimirDirecto].
 *
 * [onImprimirDirecto] es un simple disparador (`() -> Unit`): quien lo implementa
 * debe lanzar la corrutina con SU PROPIO scope de pantalla y usar
 * [intentarImprimirConRespaldo] para el respaldo automático a PDF — no lo hace
 * este diálogo, porque su scope se cancela apenas se cierra.
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
