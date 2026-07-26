package com.example.capitalexpressapp.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Chequea actualizaciones contra GitHub Releases y muestra el diálogo si hay una disponible.
 * Se re-chequea cada vez que cambia [chequeoManualId] -usarlo para disparar un chequeo manual
 * desde un botón, ej. incrementando un contador en el caller-. El primer chequeo (id 0) es
 * silencioso: si no hay actualización, no interrumpe al usuario. Los chequeos manuales
 * (id > 0) además avisan cuando no hay nada nuevo, vía [onSinActualizaciones].
 */
@Composable
fun ActualizacionOverlay(
    chequeoManualId: Int = 0,
    onSinActualizaciones: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var actualizacion by remember { mutableStateOf<ActualizacionDisponible?>(null) }
    var descargando by remember { mutableStateOf(false) }
    var progreso by remember { mutableStateOf(0f) }

    LaunchedEffect(chequeoManualId) {
        val resultado = ActualizacionService.buscarActualizacion()
        if (resultado != null) {
            actualizacion = resultado
        } else if (chequeoManualId > 0) {
            onSinActualizaciones?.invoke()
        }
    }

    val disponible = actualizacion
    if (disponible != null) {
        ActualizacionDialog(
            actualizacion = disponible,
            descargando = descargando,
            progreso = progreso,
            onActualizar = {
                descargando = true
                scope.launch {
                    try {
                        val archivo = ActualizacionService.descargarApk(context, disponible) { progreso = it }
                        ActualizacionService.instalarApk(context, archivo)
                    } catch (e: Exception) {
                        // Descarga/instalación falló (sin internet a mitad de camino, etc.):
                        // no hay nada que mostrarle al usuario que no sea reintentar después.
                    } finally {
                        descargando = false
                        actualizacion = null
                    }
                }
            },
            onDespues = { actualizacion = null }
        )
    }
}
