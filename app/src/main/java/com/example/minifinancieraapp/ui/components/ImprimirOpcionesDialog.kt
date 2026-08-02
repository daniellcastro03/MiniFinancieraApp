package com.example.minifinancieraapp.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * La impresión ESC/POS directa por Bluetooth se probó extensamente (v9-v14) y
 * seguía congelando la impresora incluso con timeout, porque Android no deja
 * cancelar de verdad una llamada bloqueante nativa como `BluetoothSocket.connect()`
 * — `withTimeout` corta la corrutina, pero el hilo nativo bloqueado sigue
 * ocupando la conexión de todas formas. Por pedido explícito del usuario se
 * DESACTIVÓ por completo: ya no se intenta, nunca se toca el Bluetooth desde
 * la app. [onSoloPdf] (generar PDF + abrir con RawBT/visor/lo que haya
 * instalado) es ahora el único camino — es el que siempre funcionó confiable.
 *
 * Se deja este composable (en vez de borrarlo y tocar los ~10 sitios que lo
 * usan) para no tener que retocar cada pantalla: en cuanto entra en
 * composición, dispara [onSoloPdf] y se cierra solo, sin mostrar ningún
 * diálogo. [onImprimirDirecto] queda sin usar a propósito.
 */
@Composable
fun ImprimirOpcionesDialog(
    onImprimirDirecto: () -> Unit,
    onSoloPdf: () -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) {
        onSoloPdf()
        onDismiss()
    }
}

/**
 * Se mantiene solo para no romper los imports existentes; ya no se invoca
 * ([ImprimirOpcionesDialog] no llama más a [onImprimirDirecto]).
 */
@Deprecated("Impresión directa desactivada — ya no se usa.")
suspend fun intentarImprimirConRespaldo(
    context: Context,
    accionImprimirDirecto: (suspend () -> Boolean)?,
    accionSoloPdf: (() -> Unit)?
) {
    accionSoloPdf?.invoke()
}
