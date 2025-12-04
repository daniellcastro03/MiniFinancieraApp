package com.example.capitalexpressapp.ui.screens

import android.app.DatePickerDialog
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.capitalexpressapp.util.NetworkUtils.isInternetAvailable
import com.example.minifinancieraapp.ui.models.PagoItem
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

enum class EstadoFiltro { TODOS, ACTIVOS, SALDADOS }

data class CuotaPendiente(
    val numeroCuota: Int,
    val fechaProgramada: String,
    val montoPendiente: Double,
    val montoPagado: Double,
    val porcentajePagado: Double,
    val esVencida: Boolean,
    val diasAtraso: Int,
    val esProxima: Boolean = false
)

data class UsuarioItem(
    val id: String,
    val nombre: String,
    val rol: String
)

class PrestamoStateManager {
    companion object {
        private val _prestamoUpdates = mutableMapOf<String, MutableState<Double>>()

        fun getSaldoState(prestamoId: String): MutableState<Double> {
            return _prestamoUpdates.getOrPut(prestamoId) { mutableStateOf(0.0) }
        }

        fun updateSaldo(prestamoId: String, nuevoSaldo: Double) {
            _prestamoUpdates[prestamoId]?.value = nuevoSaldo
        }

        fun notifyPrestamoUpdate(prestamoId: String, db: FirebaseFirestore, context: Context) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
                    val nuevoSaldo = prestamoDoc.getDouble("saldo") ?: 0.0
                    updateSaldo(prestamoId, nuevoSaldo)

                    val prefs = context.getSharedPreferences("prestamo_updates", Context.MODE_PRIVATE)
                    prefs.edit().putString("last_update_${prestamoId}", System.currentTimeMillis().toString()).apply()

                } catch (e: Exception) {
                    Log.e("PrestamoStateManager", "Error actualizando saldo: ${e.message}")
                }
            }
        }
    }
}

private fun calcularFechaCuotaPendiente(fechaInicio: Date, plazo: String, numeroCuota: Int): String {
    val calendar = Calendar.getInstance().apply { time = fechaInicio }
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    when (plazo.lowercase()) {
        "diario" -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota)
        "lunes a sábado" -> {
            repeat(numeroCuota) {
                do {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
        }
        "semanal" -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 7)
        "quincenal" -> calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 15)
        "mensual" -> calendar.add(Calendar.MONTH, numeroCuota)
        "bimestral" -> calendar.add(Calendar.MONTH, numeroCuota * 2)
        else -> calendar.add(Calendar.MONTH, numeroCuota)
    }

    return dateFormat.format(calendar.time)
}

private fun calcularDiasAtraso(fechaProgramada: Date, fechaActual: Date): Int {
    val diffMillis = fechaActual.time - fechaProgramada.time
    val dias = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
    return dias.coerceAtLeast(0)
}

private suspend fun obtenerCuotasPendientes(
    db: FirebaseFirestore,
    prestamoId: String
): List<CuotaPendiente> {
    return try {
        val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()

        val cuotasTotales = prestamoDoc.getLong("cuotas")?.toInt() ?: 0
        val cuotaMonto = prestamoDoc.getDouble("cuota") ?: 0.0
        val plazo = prestamoDoc.getString("plazo") ?: "semanal"
        val fechaInicio = prestamoDoc.getTimestamp("fecha")?.toDate() ?: Date()
        val estado = prestamoDoc.getString("estado") ?: "activo"
        val proximoPagoStr = prestamoDoc.getString("proximoPago")

        if (estado.lowercase() == "saldado" || proximoPagoStr == "saldado") {
            return emptyList()
        }

        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId)
            .get().await()

        val cuotasPagadas = mutableMapOf<Int, Double>()

        for (pago in pagosSnapshot.documents) {
            val cuotasCubiertas = pago.get("cuotasCubiertas") as? List<*>

            if (cuotasCubiertas != null && cuotasCubiertas.isNotEmpty()) {
                cuotasCubiertas.forEach { cuotaData ->
                    if (cuotaData is Map<*, *>) {
                        val numeroCuota = (cuotaData["numeroCuota"] as? Number)?.toInt() ?: 0
                        val montoAplicado = (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0

                        if (numeroCuota > 0) {
                            cuotasPagadas[numeroCuota] =
                                (cuotasPagadas[numeroCuota] ?: 0.0) + montoAplicado
                        }
                    }
                }
            } else {
                val numeroCuota = when {
                    pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                    pago.contains("cuota") -> {
                        val cuotaStr = pago.getString("cuota") ?: "1"
                        cuotaStr.toIntOrNull() ?: 1
                    }
                    else -> 1
                }
                val montoPago = pago.getDouble("monto") ?: 0.0
                val moraPago = pago.getDouble("mora") ?: 0.0
                val montoTotal = montoPago + moraPago

                cuotasPagadas[numeroCuota] =
                    (cuotasPagadas[numeroCuota] ?: 0.0) + montoTotal
            }
        }

        val cuotasPendientes = mutableListOf<CuotaPendiente>()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val hoy = Date()

        var proximaCuotaNumero: Int? = null
        for (i in 1..cuotasTotales) {
            val montoPagado = cuotasPagadas[i] ?: 0.0
            val montoPendiente = (cuotaMonto - montoPagado).coerceAtLeast(0.0)

            if (montoPendiente > 0.01) {
                proximaCuotaNumero = i
                break
            }
        }

        for (i in 1..cuotasTotales) {
            val montoPagado = cuotasPagadas[i] ?: 0.0
            val montoPendiente = (cuotaMonto - montoPagado).coerceAtLeast(0.0)

            if (montoPendiente > 0.01) {
                val fechaProgramada = calcularFechaCuotaPendiente(fechaInicio, plazo, i)
                val fechaDate = try {
                    dateFormat.parse(fechaProgramada) ?: Date()
                } catch (e: Exception) {
                    Date()
                }

                val diasAtraso = calcularDiasAtraso(fechaDate, hoy)
                val esVencida = diasAtraso > 0
                val esProxima = i == proximaCuotaNumero

                cuotasPendientes.add(
                    CuotaPendiente(
                        numeroCuota = i,
                        fechaProgramada = fechaProgramada,
                        montoPendiente = montoPendiente,
                        montoPagado = montoPagado,
                        porcentajePagado = if (cuotaMonto > 0) (montoPagado / cuotaMonto) * 100 else 0.0,
                        esVencida = esVencida,
                        diasAtraso = diasAtraso,
                        esProxima = esProxima
                    )
                )
            }
        }

        cuotasPendientes

    } catch (e: Exception) {
        Log.e("CuotasPendientes", "Error: ${e.message}", e)
        emptyList()
    }
}

private suspend fun obtenerTodasLasCuotasPendientes(
    db: FirebaseFirestore,
    filtroCliente: String = "",
    filtroCobradorId: String = ""
): List<Pair<String, List<CuotaPendiente>>> {
    return try {
        val prestamosSnapshot = db.collection("prestamos")
            .whereEqualTo("estado", "activo")
            .get().await()

        val resultado = mutableListOf<Pair<String, List<CuotaPendiente>>>()

        for (prestamoDoc in prestamosSnapshot.documents) {
            val prestamoId = prestamoDoc.id
            val clienteNombre = prestamoDoc.getString("cliente") ?: continue
            val cobradorAsignadoId = prestamoDoc.getString("cobrador") ?: ""

            // Aplicar filtro de cliente
            val cumpleFiltroCliente = filtroCliente.isBlank() ||
                    clienteNombre.contains(filtroCliente, ignoreCase = true)

            if (!cumpleFiltroCliente) continue

            // Comparar el cobradorId del préstamo con el filtro
            val cumpleFiltroCobrador = if (filtroCobradorId.isBlank()) {
                true
            } else {
                cobradorAsignadoId == filtroCobradorId
            }

            if (!cumpleFiltroCobrador) continue

            // Obtener cuotas pendientes del préstamo
            val cuotasPendientes = obtenerCuotasPendientes(db, prestamoId)

            if (cuotasPendientes.isNotEmpty()) {
                resultado.add(Pair(clienteNombre, cuotasPendientes))
            }
        }

        resultado

    } catch (e: Exception) {
        Log.e("TodasCuotasPendientes", "Error: ${e.message}", e)
        emptyList()
    }
}

fun formatearLempiras(valor: Double): String {
    return "L. ${String.format("%.2f", valor)}"
}

private fun procesarDocumentoPagoMejorado(
    doc: com.google.firebase.firestore.DocumentSnapshot,
    usuariosMap: Map<String, String>,
    prestamos: Map<String, Map<String, Any?>>,
    formatter: SimpleDateFormat
): PagoItem? {
    try {
        val prestamoId = doc.getString("prestamoId") ?: return null
        val prestamo = prestamos[prestamoId] ?: return null
        val clienteNombre = prestamo["cliente"] as? String ?: "Cliente Desconocido"

        // 🔥 SOLUCIÓN CORRECTA: El campo se llama "nombreCobrador", no "cobrador"
        val cobradorDelPago = doc.getString("nombreCobrador") ?: doc.getString("cobrador") ?: ""
        val cobradorDelPrestamo = prestamo["cobrador"] as? String ?: prestamo["nombreCobrador"] as? String ?: ""

        // Usar el del PAGO primero, si no existe usar el del préstamo
        val cobradorValor = cobradorDelPago.ifEmpty { cobradorDelPrestamo }

        // Determinar si es un ID o un nombre
        val esUnId = cobradorValor.contains("-") && cobradorValor.length > 30

        val cobradorId: String
        val cobradorNombre: String

        if (cobradorValor.isEmpty()) {
            cobradorId = ""
            cobradorNombre = "Sin asignar"
        } else if (esUnId) {
            // Es un ID, buscar el nombre
            cobradorId = cobradorValor
            cobradorNombre = usuariosMap[cobradorId] ?: cobradorValor
        } else {
            // Es un nombre, buscar el ID
            val idEncontrado = usuariosMap.entries.find {
                it.value.equals(cobradorValor, ignoreCase = true)
            }?.key

            cobradorId = idEncontrado ?: cobradorValor
            cobradorNombre = cobradorValor
        }

        val saldoActual = when (val saldoVal = prestamo["saldo"]) {
            is Double -> saldoVal
            is Long -> saldoVal.toDouble()
            is String -> saldoVal.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

        // 🔥 CORRECCIÓN CRÍTICA: El campo puede llamarse "fechaPago" o "fecha"
        // Intentar primero "fechaPago", luego "fecha" como fallback
        val timestamp = doc.getTimestamp("fechaPago")
            ?: doc.getTimestamp("fecha")
            ?: run {
                Log.w("ProcesarPago", "⚠️ Pago ${doc.id} no tiene campo 'fechaPago' ni 'fecha', usando fecha actual")
                Timestamp.now()
            }

        val fechaDate = timestamp.toDate()
        val fechaStr = formatter.format(fechaDate)
        val horaFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val horaStr = horaFormat.format(fechaDate)
        val fechaCompleta = "$fechaStr $horaStr"

        // 🔥 LOG DE DEBUGGING (descomentar si necesitas verificar)
        /*
        Log.d("ProcesarPago", """
            📋 Pago procesado:
            • ID: ${doc.id}
            • Cliente: $clienteNombre
            • Fecha original (Timestamp): $timestamp
            • Fecha formateada: $fechaCompleta
            • Cobrador: $cobradorNombre
        """.trimIndent())
        */

        val numeroCuotaVal = try {
            when {
                doc.contains("numeroCuota") -> {
                    when (val valor = doc.get("numeroCuota")) {
                        is Long -> valor.toInt()
                        is Int -> valor
                        is Double -> valor.toInt()
                        is String -> valor.toIntOrNull()
                        else -> null
                    }
                }
                doc.contains("cuota") -> {
                    when (val valor = doc.get("cuota")) {
                        is Long -> valor.toInt()
                        is Int -> valor
                        is Double -> valor.toInt()
                        is String -> valor.toIntOrNull()
                        else -> null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }

        val metodoPagoStr = doc.getString("metodoPago") ?: doc.getString("tipoPago") ?: "Efectivo"

        val numeroPrestamoStr = try {
            when (val valor = doc.get("numeroPrestamo") ?: prestamo["numeroPrestamo"]) {
                is String -> valor
                is Long -> valor.toString()
                is Int -> valor.toString()
                is Double -> valor.toInt().toString()
                null -> ""
                else -> valor.toString()
            }
        } catch (e: Exception) {
            ""
        }

        // 🔥 OBTENER EL MONTO (puede estar en "monto" o en otro campo)
        val monto = doc.getDouble("monto") ?: run {
            Log.w("ProcesarPago", "⚠️ Pago ${doc.id} no tiene campo 'monto'")
            0.0
        }

        return PagoItem(
            docId = doc.id,
            cliente = clienteNombre,
            cobrador = cobradorNombre,
            cobradorId = cobradorId,
            monto = monto,
            mora = doc.getDouble("mora") ?: 0.0,
            fecha = fechaCompleta,
            prestamoId = prestamoId,
            numeroCuota = numeroCuotaVal,
            cuota = numeroCuotaVal?.toString() ?: "",
            metodoPago = metodoPagoStr,
            tipoPago = metodoPagoStr,
            notas = doc.getString("notas"),
            timestamp = timestamp,
            saldoRestante = saldoActual,
            numeroPrestamo = numeroPrestamoStr
        )
    } catch (e: Exception) {
        Log.e("ProcesarPago", "❌ Error procesando pago ${doc.id}: ${e.message}", e)
        return null
    }
}

private suspend fun eliminarPagoCorregido(
    context: Context,
    db: FirebaseFirestore,
    pago: PagoItem,
    onSuccess: () -> Unit
) {
    try {
        if (!isInternetAvailable(context)) {
            Toast.makeText(context, "No hay conexión a internet", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("EliminarPago", """
            🗑️ Iniciando eliminación de pago:
            • Cliente: ${pago.cliente}
            • Préstamo ID: ${pago.prestamoId}
            • Pago ID: ${pago.docId}
            • Monto: L. ${pago.monto}
            • Mora: L. ${pago.mora}
        """.trimIndent())

        val prestamoRef = db.collection("prestamos").document(pago.prestamoId)
        val pagoRef = db.collection("pagos").document(pago.docId)

        // 🔥 CORRECCIÓN CRÍTICA: Obtener TODOS los pagos ANTES de la transacción
        val todosLosPagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", pago.prestamoId)
            .get()
            .await() // ✅ AWAIT aquí, FUERA de la transacción

        Log.d("EliminarPago", "📦 Total pagos del préstamo: ${todosLosPagosSnapshot.size()}")

        // Ahora sí, ejecutar la transacción
        db.runTransaction { transaction ->
            // 1. Obtener documentos actuales
            val prestamoSnapshot = transaction.get(prestamoRef)
            val pagoSnapshot = transaction.get(pagoRef)

            if (!prestamoSnapshot.exists()) {
                throw Exception("Préstamo no encontrado")
            }
            if (!pagoSnapshot.exists()) {
                throw Exception("Pago no encontrado")
            }

            Log.d("EliminarPago", "✅ Documentos encontrados en Firestore")

            // 2. Calcular el nuevo saldo
            val montoTotal = pago.monto + pago.mora
            val saldoActual = prestamoSnapshot.getDouble("saldo") ?: 0.0
            val nuevoSaldo = saldoActual + montoTotal

            Log.d("EliminarPago", """
                💰 Cálculo de saldo:
                • Saldo actual: L. $saldoActual
                • Monto a devolver: L. $montoTotal (${pago.monto} + ${pago.mora})
                • Nuevo saldo: L. $nuevoSaldo
            """.trimIndent())

            // 3. Obtener cuotas cubiertas por este pago
            val cuotasCubiertas = pagoSnapshot.get("cuotasCubiertas") as? List<*>
            val cuotasPagadasMap = mutableMapOf<Int, Double>()

            if (cuotasCubiertas != null && cuotasCubiertas.isNotEmpty()) {
                Log.d("EliminarPago", "📋 Procesando ${cuotasCubiertas.size} cuotas cubiertas...")

                cuotasCubiertas.forEach { cuotaData ->
                    if (cuotaData is Map<*, *>) {
                        val numeroCuota = (cuotaData["numeroCuota"] as? Number)?.toInt() ?: 0
                        val montoAplicado = (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0
                        if (numeroCuota > 0) {
                            cuotasPagadasMap[numeroCuota] = montoAplicado
                            Log.d("EliminarPago", "  • Cuota #$numeroCuota: L. $montoAplicado")
                        }
                    }
                }
            } else {
                // Pago antiguo sin cuotasCubiertas
                val numeroCuota = pago.numeroCuota ?: 1
                cuotasPagadasMap[numeroCuota] = montoTotal
                Log.d("EliminarPago", "📋 Pago sin cuotasCubiertas, usando cuota #$numeroCuota")
            }

            // 4. Calcular próxima cuota pendiente
            val cuotaMonto = prestamoSnapshot.getDouble("cuota") ?: 0.0
            val cuotasTotales = prestamoSnapshot.getLong("cuotas")?.toInt() ?: 0

            Log.d("EliminarPago", """
                🔢 Información de cuotas:
                • Cuota monto: L. $cuotaMonto
                • Cuotas totales: $cuotasTotales
            """.trimIndent())

            // 🔥 Calcular cuotas pagadas SIN el pago que vamos a eliminar
            val cuotasPagadasTotales = mutableMapOf<Int, Double>()

            // Usar el snapshot que obtuvimos ANTES de la transacción
            todosLosPagosSnapshot.documents.forEach { docPago ->
                if (docPago.id != pago.docId) { // ✅ Excluir el pago que vamos a eliminar
                    val cuotasCubiertasOtroPago = docPago.get("cuotasCubiertas") as? List<*>

                    if (cuotasCubiertasOtroPago != null && cuotasCubiertasOtroPago.isNotEmpty()) {
                        cuotasCubiertasOtroPago.forEach { cuotaData ->
                            if (cuotaData is Map<*, *>) {
                                val num = (cuotaData["numeroCuota"] as? Number)?.toInt() ?: 0
                                val monto = (cuotaData["montoAplicado"] as? Number)?.toDouble() ?: 0.0
                                if (num > 0) {
                                    cuotasPagadasTotales[num] = (cuotasPagadasTotales[num] ?: 0.0) + monto
                                }
                            }
                        }
                    } else {
                        // Pago antiguo sin cuotasCubiertas
                        val num = when {
                            docPago.contains("numeroCuota") -> docPago.getLong("numeroCuota")?.toInt() ?: 1
                            docPago.contains("cuota") -> {
                                val cuotaStr = docPago.getString("cuota") ?: "1"
                                cuotaStr.toIntOrNull() ?: 1
                            }
                            else -> 1
                        }
                        val monto = (docPago.getDouble("monto") ?: 0.0) + (docPago.getDouble("mora") ?: 0.0)
                        cuotasPagadasTotales[num] = (cuotasPagadasTotales[num] ?: 0.0) + monto
                    }
                }
            }

            Log.d("EliminarPago", "📊 Cuotas pagadas (sin este pago): ${cuotasPagadasTotales.size}")

            // 5. Encontrar la primera cuota pendiente
            var proximaCuota = 1
            for (i in 1..cuotasTotales) {
                val pagadoTotal = cuotasPagadasTotales[i] ?: 0.0
                if (pagadoTotal < cuotaMonto - 0.01) {
                    proximaCuota = i
                    break
                }
            }

            Log.d("EliminarPago", "🎯 Próxima cuota pendiente: #$proximaCuota")

            // 6. Determinar estado del préstamo
            val estadoNuevo = if (nuevoSaldo > 0.01) "activo" else "saldado"
            val proximoPagoStr = if (nuevoSaldo > 0.01) proximaCuota.toString() else "saldado"

            Log.d("EliminarPago", """
                📝 Actualizando préstamo:
                • Estado: $estadoNuevo
                • Próximo pago: $proximoPagoStr
                • Nuevo saldo: L. $nuevoSaldo
            """.trimIndent())

            // 7. Actualizar préstamo
            transaction.update(prestamoRef, mapOf(
                "saldo" to nuevoSaldo,
                "estado" to estadoNuevo,
                "proximoPago" to proximoPagoStr
            ))

            // 8. Eliminar pago
            transaction.delete(pagoRef)

            Log.d("EliminarPago", "✅ Transacción completada exitosamente")

        }.await() // ✅ AWAIT en la transacción

        // 9. Notificar actualización del estado
        PrestamoStateManager.notifyPrestamoUpdate(pago.prestamoId, db, context)

        Log.d("EliminarPago", "🎉 Pago eliminado correctamente")
        Toast.makeText(context, "Pago eliminado correctamente", Toast.LENGTH_SHORT).show()
        onSuccess()

    } catch (e: Exception) {
        Log.e("EliminarPago", "❌ Error al eliminar pago", e)
        Toast.makeText(
            context,
            "Error al eliminar: ${e.message}",
            Toast.LENGTH_LONG
        ).show()
    }
}

private suspend fun reimprimirRecibo(context: Context, pago: PagoItem) {
    try {
        val db = FirebaseFirestore.getInstance()
        val prestamoDoc = db.collection("prestamos").document(pago.prestamoId).get().await()

        val cliente = prestamoDoc.getString("cliente") ?: ""

        val numeroPrestamoStr = pago.numeroPrestamo.ifEmpty {
            prestamoDoc.getString("numeroPrestamo")
                ?: prestamoDoc.getLong("numeroPrestamo")?.toString()
                ?: ""
        }

        val prestamoIdParaPDF = if (numeroPrestamoStr.isNotEmpty()) {
            "Préstamo N° $numeroPrestamoStr"
        } else {
            "Préstamo"
        }

        val fecha = pago.fecha
        val montoPagado = pago.monto.toString()
        val saldoAnterior = (pago.saldoRestante ?: 0.0) + pago.monto + pago.mora
        val proximoPago = prestamoDoc.getString("proximoPago") ?: ""
        val cuota = pago.numeroCuota?.toString() ?: pago.cuota.ifEmpty { "1" }
        val cobrador = pago.cobrador
        val lugar = pago.lugar.ifEmpty { "Capital Express" }
        val firma = pago.firma.ifEmpty { "" }
        val tipoPago = pago.metodoPago.ifEmpty { pago.tipoPago }
        val mora = pago.mora
        val saldoNuevoFijo = pago.saldoRestante

        val archivo = ReciboHelper.generarReciboPDF(
            context = context,
            cliente = cliente,
            prestamoId = prestamoIdParaPDF,
            fecha = fecha,
            montoPagado = montoPagado,
            saldoAnterior = saldoAnterior,
            proximoPago = proximoPago,
            cuota = cuota,
            cobrador = cobrador,
            lugar = lugar,
            firma = firma,
            tipoPago = tipoPago,
            mora = mora,
            saldoNuevoFijo = saldoNuevoFijo
        )

        if (archivo != null && archivo.exists()) {
            val printed = ReciboHelper.imprimirPDF(context, archivo)
            if (!printed) {
                ReciboHelper.compartirReciboPDF(context, archivo)
            }
            Toast.makeText(context, "Recibo generado", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No se pudo generar el recibo", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Log.e("ReimprimirRecibo", "Error: ${e.message}", e)
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialPagosScreen(navController: NavController, rol: String) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    var pagos by remember { mutableStateOf(listOf<PagoItem>()) }
    var pagosFiltrados by remember { mutableStateOf(listOf<PagoItem>()) }
    var cuotasPendientesPorCliente by remember { mutableStateOf(listOf<Pair<String, List<CuotaPendiente>>>()) }
    var mostrandoPendientes by remember { mutableStateOf(false) }
    var cargandoPendientes by remember { mutableStateOf(false) }
    var usuarios by remember { mutableStateOf(listOf<UsuarioItem>()) }
    var filtroCliente by remember { mutableStateOf("") }
    var filtroCobradorId by remember { mutableStateOf("") }
    var filtroCobradorNombre by remember { mutableStateOf("") }
    var fechaInicio by remember { mutableStateOf<Date?>(null) }
    var fechaFin by remember { mutableStateOf<Date?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var mostrarFiltros by remember { mutableStateOf(false) }
    var pagoAEliminar by remember { mutableStateOf<PagoItem?>(null) }
    var estadoFiltro by remember { mutableStateOf(EstadoFiltro.TODOS) }
    var mostrarPreview by remember { mutableStateOf(false) }
    var previewPagos by remember { mutableStateOf<List<PagoItem>>(emptyList()) }
    var previewPeriodo by remember { mutableStateOf("Todos los registros") }
    var previewFechaInicio by remember { mutableStateOf<Date?>(null) }
    var previewFechaFin by remember { mutableStateOf<Date?>(null) }

    val formatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    fun aplicarFiltros() {
        pagosFiltrados = pagos.filter { pago ->
            // FILTRO CLIENTE
            val cumpleFiltroCliente = filtroCliente.isBlank() ||
                    pago.cliente.contains(filtroCliente, ignoreCase = true)

            // FILTRO COBRADOR
            val cumpleFiltroCobrador = if (filtroCobradorId.isBlank() && filtroCobradorNombre.isBlank()) {
                true
            } else {
                pago.cobradorId == filtroCobradorId ||
                        pago.cobradorId.equals(filtroCobradorNombre, ignoreCase = true) ||
                        pago.cobrador.equals(filtroCobradorNombre, ignoreCase = true) ||
                        pago.cobrador.contains(filtroCobradorNombre, ignoreCase = true) ||
                        pago.cobradorId.contains(filtroCobradorId, ignoreCase = true)
            }

            // 🔥 FILTRO FECHA - VERSIÓN COMPLETAMENTE CORREGIDA Y SIMPLIFICADA
            val cumpleFiltroFecha = if (fechaInicio == null || fechaFin == null) {
                true
            } else {
                try {
                    // Extraer SOLO la parte de fecha del string "01/12/2024 02:30 PM"
                    val partes = pago.fecha.trim().split(" ")
                    val fechaStr = if (partes.isNotEmpty()) partes[0] else pago.fecha

                    // Parsear la fecha del pago
                    val fechaPagoDate = formatter.parse(fechaStr)

                    if (fechaPagoDate != null) {
                        // 🔥 SOLUCIÓN: Normalizar TODAS las fechas a medianoche (00:00:00.000)
                        val calPago = Calendar.getInstance().apply {
                            time = fechaPagoDate
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        val calInicio = Calendar.getInstance().apply {
                            time = fechaInicio!!
                            set(Calendar.HOUR_OF_DAY, 0)  // ✅ Medianoche
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        // 🔥 CAMBIO CRÍTICO: fechaFin también a medianoche
                        val calFin = Calendar.getInstance().apply {
                            time = fechaFin!!
                            set(Calendar.HOUR_OF_DAY, 0)  // ✅ CAMBIO: antes era 23
                            set(Calendar.MINUTE, 0)        // ✅ CAMBIO: antes era 59
                            set(Calendar.SECOND, 0)        // ✅ CAMBIO: antes era 59
                            set(Calendar.MILLISECOND, 0)   // ✅ CAMBIO: antes era 999
                        }

                        // Obtener tiempos en milisegundos
                        val fechaPago = calPago.time
                        val fechaIni = calInicio.time
                        val fechaFn = calFin.time

                        // 🔥 Comparación exacta: fechaPago >= fechaInicio Y fechaPago <= fechaFin
                        val resultado = fechaPago >= fechaIni && fechaPago <= fechaFn

                        // 🔥 LOG DE DEBUGGING MEJORADO (descomentar para diagnosticar)
                        /*
                        if (!resultado) {
                            Log.d("FiltroFechaDetalle", """
                                ❌ EXCLUIDO:
                                Cliente: ${pago.cliente}
                                Fecha original: "${pago.fecha}"
                                Fecha extraída: "$fechaStr"
                                Fecha parseada: ${formatter.format(fechaPago)}
                                Rango filtro: ${formatter.format(fechaIni)} a ${formatter.format(fechaFn)}
                                Millis - Pago: ${fechaPago.time}, Inicio: ${fechaIni.time}, Fin: ${fechaFn.time}
                                Comparación: ${fechaPago.time} >= ${fechaIni.time} = ${fechaPago >= fechaIni}
                                Comparación: ${fechaPago.time} <= ${fechaFn.time} = ${fechaPago <= fechaFn}
                            """.trimIndent())
                        }
                        */

                        resultado
                    } else {
                        Log.w("FiltroFecha", "⚠️ No se pudo parsear fecha: '$fechaStr' del pago: ${pago.cliente}")
                        false // Excluir si no se puede parsear
                    }
                } catch (e: Exception) {
                    Log.e("FiltroFecha", "❌ Error parseando fecha del pago ${pago.cliente}: ${e.message}")
                    false // Excluir en caso de error
                }
            }

            // FILTRO ESTADO
            val cumpleEstado = when (estadoFiltro) {
                EstadoFiltro.TODOS -> true
                EstadoFiltro.ACTIVOS -> (pago.saldoRestante ?: 0.0) > 0.01
                EstadoFiltro.SALDADOS -> (pago.saldoRestante ?: 0.0) <= 0.01
            }

            // Resultado final del filtro
            cumpleFiltroCliente && cumpleFiltroCobrador && cumpleFiltroFecha && cumpleEstado
        }

        // 🔥 LOG DE RESUMEN (mantener activo para verificar)
        Log.d("AplicarFiltros", """
        ═══════════════════════════════════════════════════
        📊 RESULTADO DE FILTROS:
        ───────────────────────────────────────────────────
        • Total pagos disponibles: ${pagos.size}
        • Pagos después de filtrar: ${pagosFiltrados.size}
        • Filtro de fechas: ${if (fechaInicio != null && fechaFin != null)
            "ACTIVO (${formatter.format(fechaInicio)} → ${formatter.format(fechaFin)})"
        else "INACTIVO"}
        • Filtro de cliente: ${if (filtroCliente.isNotBlank()) "\"$filtroCliente\"" else "NINGUNO"}
        • Filtro de cobrador: ${if (filtroCobradorNombre.isNotBlank()) "\"$filtroCobradorNombre\"" else "NINGUNO"}
        • Estado: $estadoFiltro
        ═══════════════════════════════════════════════════
    """.trimIndent())

        // 🔥 VERIFICACIÓN: Mostrar primeros 3 pagos incluidos
        if (pagosFiltrados.isNotEmpty() && fechaInicio != null && fechaFin != null) {
            Log.d("AplicarFiltros", """
            ✅ PRIMEROS PAGOS INCLUIDOS:
            ${pagosFiltrados.take(3).joinToString("\n") { pago ->
                val fechaStr = pago.fecha.split(" ")[0]
                "   • ${pago.cliente} - $fechaStr - ${formatearLempiras(pago.monto)}"
            }}
        """.trimIndent())
        }

        // 🔥 VERIFICACIÓN: Mostrar pagos excluidos si hay filtro de fecha activo
        if (fechaInicio != null && fechaFin != null && pagosFiltrados.size < pagos.size) {
            val excluidos = pagos.filter { pago ->
                !pagosFiltrados.contains(pago)
            }.take(3)

            if (excluidos.isNotEmpty()) {
                Log.d("AplicarFiltros", """
                ⚠️ EJEMPLOS DE PAGOS EXCLUIDOS:
                ${excluidos.joinToString("\n") { pago ->
                    val fechaStr = pago.fecha.split(" ")[0]
                    "   • ${pago.cliente} - $fechaStr - ${formatearLempiras(pago.monto)}"
                }}
            """.trimIndent())
            }
        }
    }

    fun limpiarFiltros() {
        filtroCliente = ""
        filtroCobradorId = ""
        filtroCobradorNombre = ""
        fechaInicio = null
        fechaFin = null
        estadoFiltro = EstadoFiltro.TODOS
        pagosFiltrados = pagos
    }

    fun cargarUsuarios() {
        scope.launch {
            try {
                if (isInternetAvailable(context)) {
                    val snapshot = db.collection("usuarios").get().await()
                    usuarios = snapshot.documents.mapNotNull { doc ->
                        val nombre = doc.getString("nombre") ?: return@mapNotNull null
                        val rolUsuario = doc.getString("rol") ?: return@mapNotNull null
                        if (rolUsuario in listOf("cobrador", "admin")) {
                            UsuarioItem(id = doc.id, nombre = nombre, rol = rolUsuario)
                        } else null
                    }.sortedBy { it.nombre }

                    // 🔥 LOG DE USUARIOS CARGADOS
                    Log.d("CargarUsuarios", """
                    👥 USUARIOS CARGADOS:
                    ${usuarios.joinToString("\n") { usuario ->
                        "  • ${usuario.nombre} -> ${usuario.id.take(12)}..."
                    }}
                """.trimIndent())
                }
            } catch (e: Exception) {
                Log.e("CargarUsuarios", "Error: ${e.message}")
            }
        }
    }

    fun cargarPagos() {
        scope.launch {
            try {
                cargando = true
                val prefs = context.getSharedPreferences("offline_data", Context.MODE_PRIVATE)

                if (isInternetAvailable(context)) {
                    Log.d("CargarPagos", "🔵 Iniciando carga...")

                    val usuariosDeferred = async {
                        db.collection("usuarios").get().await()
                            .associateBy({ it.id }, { it.getString("nombre") ?: it.id })
                    }

                    val prestamosDeferred = async {
                        db.collection("prestamos").get().await()
                            .associateBy({ it.id }, { it.data })
                    }

                    val pagosDeferred = async {
                        val todosPagos = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
                        var lastDoc: com.google.firebase.firestore.DocumentSnapshot? = null
                        val batchSize = 500L
                        var hasMore = true

                        while (hasMore) {
                            val query = if (lastDoc == null) {
                                db.collection("pagos").limit(batchSize)
                            } else {
                                db.collection("pagos").startAfter(lastDoc).limit(batchSize)
                            }

                            val snapshot = query.get().await()

                            if (snapshot.documents.isEmpty()) {
                                hasMore = false
                            } else {
                                todosPagos.addAll(snapshot.documents)
                                lastDoc = snapshot.documents.lastOrNull()

                                if (snapshot.documents.size < batchSize) {
                                    hasMore = false
                                }
                            }
                        }
                        todosPagos
                    }

                    val usuariosMap = usuariosDeferred.await()
                    val prestamos = prestamosDeferred.await()
                    val todosLosDocs = pagosDeferred.await()

                    Log.d("CargarPagos", """
                    📊 DATOS CARGADOS:
                    - Usuarios: ${usuariosMap.size}
                    - Préstamos: ${prestamos.size}
                    - Pagos: ${todosLosDocs.size}
                """.trimIndent())

                    // 🔥 DIAGNÓSTICO: Analizar el campo "nombreCobrador" en TODOS los pagos
                    val valoresCobradorEnPagos = todosLosDocs
                        .mapNotNull { it.getString("nombreCobrador") ?: it.getString("cobrador") }
                        .filter { it.isNotEmpty() }
                        .groupBy { it }
                        .mapValues { it.value.size }
                        .toList()
                        .sortedByDescending { it.second }

                    Log.d("CargarPagos", """
                    🔍 VALORES DE 'nombreCobrador' EN PAGOS:
                    ${if (valoresCobradorEnPagos.isEmpty()) {
                        "¡CAMPO 'nombreCobrador' VACÍO O NO EXISTE EN TODOS LOS PAGOS!"
                    } else {
                        valoresCobradorEnPagos.take(10).joinToString("\n") { (cobrador, cantidad) ->
                            "  • '$cobrador' -> $cantidad pagos"
                        }
                    }}
                """.trimIndent())

                    // 🔥 Buscar específicamente pagos con "Figueroa"
                    val pagosFigueroaDirectos = todosLosDocs.filter { doc ->
                        val cobrador = doc.getString("nombreCobrador") ?: doc.getString("cobrador") ?: ""
                        cobrador.contains("Figueroa", ignoreCase = true)
                    }

                    Log.d("CargarPagos", """
                    💰 PAGOS CON "FIGUEROA" EN nombreCobrador:
                    - Total encontrados: ${pagosFigueroaDirectos.size}
                    ${pagosFigueroaDirectos.take(3).joinToString("\n") { doc ->
                        val prestamoId = doc.getString("prestamoId") ?: ""
                        val cliente = prestamos[prestamoId]?.get("cliente") as? String ?: "Sin cliente"
                        val nombreCobrador = doc.getString("nombreCobrador") ?: ""
                        val monto = doc.getDouble("monto") ?: 0.0
                        "  • Cliente: $cliente | Monto: L$monto | nombreCobrador: '$nombreCobrador'"
                    }}
                """.trimIndent())

                    // 🔥 DIAGNÓSTICO: Buscar préstamos de José Figueroa
                    val prestamosFigueroa = prestamos.filter { (_, data) ->
                        val cobrador = data?.get("cobrador") as? String ?: ""
                        cobrador.contains("Figueroa", ignoreCase = true) ||
                                cobrador.contains("993c0dff", ignoreCase = true)
                    }

                    Log.d("CargarPagos", """
                    🏦 PRÉSTAMOS CON FIGUEROA ASIGNADO:
                    - Total préstamos: ${prestamosFigueroa.size}
                """.trimIndent())

                    pagos = todosLosDocs.mapNotNull { doc ->
                        try {
                            procesarDocumentoPagoMejorado(doc, usuariosMap, prestamos, formatter)
                        } catch (e: Exception) {
                            Log.e("CargarPagos", "Error procesando: ${e.message}")
                            null
                        }
                    }.sortedByDescending {
                        try {
                            formatter.parse(it.fecha.split(" ")[0])?.time ?: 0L
                        } catch (_: Exception) { 0L }
                    }

                    Log.d("CargarPagos", """
                    ✅ PROCESAMIENTO COMPLETO:
                    - Total pagos procesados: ${pagos.size}
                    - Pagos de Figueroa: ${pagos.count {
                        it.cobrador.contains("Figueroa", ignoreCase = true) ||
                                it.cobradorId.contains("993c0dff", ignoreCase = true)
                    }}
                    
                    Primeros 3 pagos de Figueroa:
                    ${pagos.filter { it.cobrador.contains("Figueroa", ignoreCase = true) }
                        .take(3)
                        .joinToString("\n") { pago ->
                            "  • Cliente: ${pago.cliente} | Cobrador: ${pago.cobrador} | ID: ${pago.cobradorId.take(12)}..."
                        }
                    }
                """.trimIndent())

                    prefs.edit().putString("historial_pagos", Gson().toJson(pagos)).apply()
                } else {
                    val json = prefs.getString("historial_pagos", "[]") ?: "[]"
                    pagos = try {
                        Gson().fromJson(json, Array<PagoItem>::class.java).toList()
                    } catch (_: Exception) { emptyList() }
                    Toast.makeText(context, "Modo offline", Toast.LENGTH_SHORT).show()
                }

                pagosFiltrados = pagos

            } catch (e: Exception) {
                Log.e("CargarPagos", "ERROR: ${e.message}", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                cargando = false
            }
        }
    }

    fun cargarCuotasPendientes() {
        scope.launch {
            try {
                cargandoPendientes = true

                if (isInternetAvailable(context)) {
                    cuotasPendientesPorCliente = obtenerTodasLasCuotasPendientes(
                        db = db,
                        filtroCliente = filtroCliente,
                        filtroCobradorId = filtroCobradorId
                    )
                } else {
                    Toast.makeText(context, "Requiere conexión para ver pendientes", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                cargandoPendientes = false
            }
        }
    }

    LaunchedEffect(Unit) {
        cargarUsuarios()
        cargarPagos()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial de Pagos", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0061A7),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { mostrarFiltros = !mostrarFiltros }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filtros", tint = Color.White)
                    }
                    IconButton(onClick = {
                        if (mostrandoPendientes) {
                            cargarCuotasPendientes()
                        } else {
                            cargarPagos()
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        if (cargando) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFF0061A7), strokeWidth = 3.dp)
                    Text("Cargando historial...", color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = filtroCliente,
                        onValueChange = {
                            filtroCliente = it
                            aplicarFiltros()
                        },
                        label = { Text("Buscar cliente...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (filtroCliente.isNotEmpty()) {
                                IconButton(onClick = {
                                    filtroCliente = ""
                                    aplicarFiltros()
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0061A7),
                            focusedLabelColor = Color(0xFF0061A7),
                            focusedLeadingIconColor = Color(0xFF0061A7)
                        )
                    )
                }

                if (mostrarFiltros) {
                    item {
                        FiltrosCard(
                            filtroCliente = filtroCliente,
                            onFiltroClienteChange = { filtroCliente = it; aplicarFiltros() },
                            usuarios = usuarios,
                            filtroCobradorNombre = filtroCobradorNombre,
                            onCobradorChange = { id, nombre ->
                                filtroCobradorId = id
                                filtroCobradorNombre = nombre
                                Log.d("CambioCobradorFiltro", "Nuevo cobrador: $nombre (ID: $id)")
                                aplicarFiltros()
                            },
                            fechaInicio = fechaInicio,
                            fechaFin = fechaFin,
                            onFechasChange = { inicio, fin ->
                                fechaInicio = inicio
                                fechaFin = fin
                                aplicarFiltros()
                            },
                            estadoFiltro = estadoFiltro,
                            onEstadoChange = {
                                estadoFiltro = it
                                aplicarFiltros()
                            },
                            onLimpiar = { limpiarFiltros() },
                            pagosFiltrados = pagosFiltrados,
                            formatter = formatter,
                            context = context,
                            scope = scope,
                            onPreview = { pagosSel, fIni, fFin, periodo ->
                                previewPagos = pagosSel
                                previewFechaInicio = fIni
                                previewFechaFin = fFin
                                previewPeriodo = periodo
                                mostrarPreview = true
                            }
                        )
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FilterChip(
                                selected = !mostrandoPendientes,
                                onClick = { mostrandoPendientes = false },
                                label = { Text("Pagos Realizados (${pagosFiltrados.size})") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF4CAF50).copy(alpha = 0.18f),
                                    selectedLabelColor = Color(0xFF2E7D32),
                                    selectedLeadingIconColor = Color(0xFF2E7D32)
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            FilterChip(
                                selected = mostrandoPendientes,
                                onClick = {
                                    mostrandoPendientes = true
                                    if (cuotasPendientesPorCliente.isEmpty()) {
                                        cargarCuotasPendientes()
                                    }
                                },
                                label = { Text("Pagos Pendientes") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Schedule,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFFF9800).copy(alpha = 0.18f),
                                    selectedLabelColor = Color(0xFFE65100),
                                    selectedLeadingIconColor = Color(0xFFE65100)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (mostrandoPendientes) {
                    item {
                        Button(
                            onClick = {
                                if (cuotasPendientesPorCliente.isEmpty()) {
                                    Toast.makeText(context, "No hay pagos pendientes para exportar", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                scope.launch {
                                    try {
                                        val archivo = ReciboHelper.generarResumenCuotasPendientesPDF(
                                            context = context,
                                            cuotasPorCliente = cuotasPendientesPorCliente,
                                            filtroCliente = filtroCliente,
                                            filtroCobradorNombre = filtroCobradorNombre
                                        )
                                        if (archivo != null && archivo.exists()) {
                                            val printed = ReciboHelper.imprimirPDF(context, archivo)
                                            if (!printed) ReciboHelper.compartirReciboPDF(context, archivo)
                                            Toast.makeText(context, "PDF de pendientes generado", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "No se pudo generar el PDF", Toast.LENGTH_LONG).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("ExportarPendientes", "Error: ${e.message}", e)
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF9800)
                            )
                        ) {
                            Icon(
                                Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Exportar Pendientes a PDF")
                        }
                    }

                    if (cargandoPendientes) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(40.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(color = Color(0xFF0061A7))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Cargando cuotas pendientes...", color = Color.Gray)
                                }
                            }
                        }
                    } else if (cuotasPendientesPorCliente.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(40.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("✅", fontSize = 48.sp)
                                    Text(
                                        "¡No hay pagos pendientes!",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = Color(0xFF2E7D32)
                                    )
                                    Text(
                                        "Todos los préstamos están al día",
                                        fontSize = 14.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    } else {
                        cuotasPendientesPorCliente.forEach { (cliente, cuotas) ->
                            item {
                                Text(
                                    cliente,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0061A7),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }

                            items(cuotas) { cuota ->
                                CuotaPendienteCard(cuota)
                            }
                        }
                    }
                } else {
                    if (pagosFiltrados.isNotEmpty()) {
                        item { StatsCard(pagosFiltrados) }
                    }

                    if (pagosFiltrados.isEmpty()) {
                        item { EmptyStateCard() }
                    } else {
                        items(items = pagosFiltrados, key = { it.docId }) { pago ->
                            PagoCard(
                                pago = pago,
                                rol = rol,
                                onEliminar = { pagoAEliminar = pago },
                                onReimprimir = {
                                    scope.launch { reimprimirRecibo(context, pago) }
                                }
                            )
                        }
                    }
                }
            }
        }

        pagoAEliminar?.let { pago ->
            ConfirmDeleteDialog(
                pago = pago,
                db = db,
                context = context,
                onConfirm = {
                    scope.launch {
                        eliminarPagoCorregido(context, db, pago) {
                            cargarPagos()
                            pagoAEliminar = null
                        }
                    }
                },
                onDismiss = { pagoAEliminar = null }
            )
        }

        if (mostrarPreview) {
            ReportePagosPreview(
                pagos = previewPagos,
                periodo = previewPeriodo,
                fechaInicio = previewFechaInicio,
                fechaFin = previewFechaFin,
                esSaldados = estadoFiltro == EstadoFiltro.SALDADOS,
                onCerrar = { mostrarPreview = false },
                onExportar = { pagosAExportar, fi, ff, periodoStr, esSaldados ->
                    scope.launch {
                        try {
                            val archivo = if (esSaldados) {
                                // PDF para préstamos saldados
                                ReciboHelper.generarResumenPrestamosSaldadosPDF(
                                    context = context,
                                    pagos = pagosAExportar,
                                    fechaInicio = fi,
                                    fechaFin = ff,
                                    periodo = periodoStr
                                )
                            } else {
                                // PDF normal de pagos
                                ReciboHelper.generarResumenPagosPDF(
                                    context = context,
                                    pagos = pagosAExportar,
                                    fechaInicio = fi ?: Date(0),
                                    fechaFin = ff ?: Date(),
                                    periodo = periodoStr
                                )
                            }

                            if (archivo != null && archivo.exists()) {
                                val printed = ReciboHelper.imprimirPDF(context, archivo)
                                if (!printed) ReciboHelper.compartirReciboPDF(context, archivo)
                                val mensaje = if (esSaldados) "PDF de préstamos saldados generado" else "PDF generado"
                                Toast.makeText(context, mensaje, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No se pudo generar el PDF", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Log.e("ExportarPDF", "Error: ${e.message}", e)
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }
}

// ============================================================================
// PARTE 2: COMPOSABLES - Añadir al final del archivo HistorialPagosScreen.kt
// ============================================================================

@Composable
fun StatsCard(pagos: List<PagoItem>) {
    val totalRecaudado = pagos.sumOf { it.monto }
    val totalMora = pagos.sumOf { it.mora }
    val totalGeneral = totalRecaudado + totalMora
    val cantidadPagos = pagos.size
    val prestamosUnicos = pagos.distinctBy { it.prestamoId }.size
    val pagosActivos = pagos.count { (it.saldoRestante ?: 0.0) > 0.01 }
    val pagosSaldados = pagos.count { (it.saldoRestante ?: 0.0) <= 0.01 }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0061A7)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Assessment,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    "Resumen de Pagos",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.3f))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "TOTAL RECAUDADO",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        formatearLempiras(totalGeneral),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatItem(
                        label = "Capital",
                        value = formatearLempiras(totalRecaudado),
                        icon = Icons.Default.Money
                    )
                    StatItem(
                        label = "Mora",
                        value = formatearLempiras(totalMora),
                        icon = Icons.Default.Warning,
                        valueColor = Color(0xFFFFB74D)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatItem(
                        label = "Total Pagos",
                        value = cantidadPagos.toString(),
                        icon = Icons.Default.Receipt
                    )
                    StatItem(
                        label = "Préstamos",
                        value = prestamosUnicos.toString(),
                        icon = Icons.Default.CreditCard
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    color = Color(0xFF4CAF50).copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF81C784),
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                pagosSaldados.toString(),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "Saldados",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    color = Color(0xFFFF9800).copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = Color(0xFFFFB74D),
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                pagosActivos.toString(),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "Activos",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueColor: Color = Color.White
) {
    Surface(
        color = Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    label,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Text(
                    value,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor
                )
            }
        }
    }
}

@Composable
fun EmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color.Gray.copy(alpha = 0.4f)
            )

            Text(
                "No se encontraron pagos",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Text(
                "Intenta ajustar los filtros de búsqueda\no verifica que existan pagos registrados",
                fontSize = 14.sp,
                color = Color.Gray.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Surface(
                color = Color(0xFFE3F2FD),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color(0xFF0061A7)
                        )
                        Column {
                            Text(
                                "Sugerencias:",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0061A7)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "• Limpia los filtros activos\n• Amplía el rango de fechas\n• Verifica el cobrador seleccionado",
                                fontSize = 12.sp,
                                color = Color(0xFF424242),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PagoCard(
    pago: PagoItem,
    rol: String,
    onEliminar: () -> Unit,
    onReimprimir: () -> Unit
) {
    var mostrarOpciones by remember { mutableStateOf(false) }
    val esSaldado = (pago.saldoRestante ?: 0.0) <= 0.01

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { mostrarOpciones = !mostrarOpciones },
        colors = CardDefaults.cardColors(
            containerColor = if (esSaldado) Color(0xFFE8F5E9) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pago.cliente,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0061A7)
                    )

                    if (pago.numeroPrestamo.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Tag,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color.Gray
                            )
                            Text(
                                "Préstamo N° ${pago.numeroPrestamo}",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color.Gray
                        )
                        Text(
                            pago.cobrador,
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CalendarToday,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color.Gray
                        )
                        Text(
                            pago.fecha,
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    pago.numeroCuota?.let { cuota ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Numbers,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color.Gray
                            )
                            Text(
                                "Cuota #$cuota",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatearLempiras(pago.monto),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                    if (pago.mora > 0) {
                        Text(
                            "+ ${formatearLempiras(pago.mora)} mora",
                            fontSize = 12.sp,
                            color = Color(0xFFFF5722)
                        )
                    }
                    if (esSaldado) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFF4CAF50),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "SALDADO",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        pago.saldoRestante?.let { saldo ->
                            if (saldo > 0.01) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Saldo: ${formatearLempiras(saldo)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFFFF9800),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            if (!pago.notas.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Notes,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.Gray
                    )
                    Text(
                        pago.notas,
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (mostrarOpciones) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onReimprimir,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF0061A7)
                        )
                    ) {
                        Icon(
                            Icons.Default.Print,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reimprimir", fontSize = 13.sp)
                    }

                    if (rol == "admin") {
                        OutlinedButton(
                            onClick = onEliminar,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFD32F2F)
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Eliminar", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CuotaPendienteCard(cuota: CuotaPendiente) {
    val backgroundColor = when {
        cuota.esProxima && !cuota.esVencida -> Color(0xFFFFF3E0)
        cuota.esVencida && cuota.diasAtraso > 7 -> Color(0xFFFFEBEE)
        cuota.esVencida -> Color(0xFFFFF8E1)
        cuota.montoPagado > 0 -> Color(0xFFE8F5E8)
        else -> Color.White
    }

    val badgeColor = when {
        cuota.esProxima && !cuota.esVencida -> Color(0xFFFBC02D)
        cuota.esVencida && cuota.diasAtraso > 7 -> Color(0xFFD32F2F)
        cuota.esVencida -> Color(0xFFFF9800)
        cuota.montoPagado > 0 -> Color(0xFF4CAF50)
        else -> Color(0xFF757575)
    }

    val badgeText = when {
        cuota.esProxima && !cuota.esVencida -> "PRÓXIMA"
        cuota.esVencida && cuota.diasAtraso > 7 -> "VENCIDA (${cuota.diasAtraso}d)"
        cuota.esVencida -> "Vencida (${cuota.diasAtraso}d)"
        cuota.montoPagado > 0 -> "Parcial"
        else -> "Pendiente"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Cuota #${cuota.numeroCuota}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0061A7)
                        )
                        Surface(
                            color = badgeColor,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                badgeText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CalendarToday,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color.Gray
                        )
                        Text(
                            "Vence: ${cuota.fechaProgramada}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatearLempiras(cuota.montoPendiente),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD32F2F)
                    )
                    if (cuota.montoPagado > 0) {
                        Text(
                            "Pagado: ${formatearLempiras(cuota.montoPagado)}",
                            fontSize = 11.sp,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (cuota.montoPagado > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Progreso",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        Text(
                            "${String.format("%.1f", cuota.porcentajePagado)}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (cuota.porcentajePagado / 100).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Color(0xFF4CAF50),
                        trackColor = Color(0xFFE0E0E0),
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmDeleteDialog(
    pago: PagoItem,
    db: FirebaseFirestore,
    context: Context,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                "Confirmar Eliminación",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column {
                Text(
                    "¿Estás seguro de eliminar este pago?",
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InfoRow("Cliente", pago.cliente)
                        InfoRow("Monto", formatearLempiras(pago.monto + pago.mora))
                        InfoRow("Fecha", pago.fecha)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "⚠️ Esta acción no se puede deshacer y afectará el saldo del préstamo.",
                    fontSize = 12.sp,
                    color = Color(0xFFD32F2F),
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F)
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Eliminar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = Color.Gray)
            }
        }
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = Color.Gray,
            fontWeight = FontWeight.Medium
        )
        Text(
            value,
            fontSize = 12.sp,
            color = Color(0xFF0061A7),
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltrosCard(
    filtroCliente: String,
    onFiltroClienteChange: (String) -> Unit,
    usuarios: List<UsuarioItem>,
    filtroCobradorNombre: String,
    onCobradorChange: (String, String) -> Unit,
    fechaInicio: Date?,
    fechaFin: Date?,
    onFechasChange: (Date?, Date?) -> Unit,
    estadoFiltro: EstadoFiltro,
    onEstadoChange: (EstadoFiltro) -> Unit,
    onLimpiar: () -> Unit,
    pagosFiltrados: List<PagoItem>,
    formatter: SimpleDateFormat,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onPreview: (List<PagoItem>, Date?, Date?, String) -> Unit
) {
    var expandedCobrador by remember { mutableStateOf(false) }
    var mostrarDatePicker by remember { mutableStateOf(false) }
    var seleccionandoFechaInicio by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Filtros de Búsqueda",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0061A7)
                )
                IconButton(
                    onClick = onLimpiar,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Limpiar filtros",
                        tint = Color(0xFFFF5722)
                    )
                }
            }

            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

            OutlinedTextField(
                value = filtroCliente,
                onValueChange = onFiltroClienteChange,
                label = { Text("Buscar por cliente") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (filtroCliente.isNotEmpty()) {
                        IconButton(onClick = { onFiltroClienteChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF0061A7),
                    focusedLabelColor = Color(0xFF0061A7)
                )
            )

            ExposedDropdownMenuBox(
                expanded = expandedCobrador,
                onExpandedChange = { expandedCobrador = it }
            ) {
                OutlinedTextField(
                    value = filtroCobradorNombre,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Filtrar por cobrador") },
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null)
                    },
                    trailingIcon = {
                        Row {
                            if (filtroCobradorNombre.isNotEmpty()) {
                                IconButton(onClick = { onCobradorChange("", "") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                                }
                            }
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCobrador)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0061A7),
                        focusedLabelColor = Color(0xFF0061A7)
                    )
                )

                ExposedDropdownMenu(
                    expanded = expandedCobrador,
                    onDismissRequest = { expandedCobrador = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Todos los cobradores") },
                        onClick = {
                            onCobradorChange("", "")
                            expandedCobrador = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    )
                    HorizontalDivider()
                    usuarios.forEach { usuario ->
                        DropdownMenuItem(
                            text = { Text(usuario.nombre) },
                            onClick = {
                                onCobradorChange(usuario.id, usuario.nombre)
                                expandedCobrador = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null)
                            }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Rango de fechas",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF0061A7)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            seleccionandoFechaInicio = true
                            mostrarDatePicker = true
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (fechaInicio != null) Color(0xFF0061A7) else Color.Gray
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                fechaInicio?.let { formatter.format(it) } ?: "Inicio",
                                fontSize = 12.sp
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            seleccionandoFechaInicio = false
                            mostrarDatePicker = true
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (fechaFin != null) Color(0xFF0061A7) else Color.Gray
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                fechaFin?.let { formatter.format(it) } ?: "Fin",
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val hoy = Date()
                                onFechasChange(hoy, hoy)
                            },
                            label = { Text("Hoy", fontSize = 12.sp) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val cal = Calendar.getInstance()
                                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                                val inicio = cal.time
                                cal.add(Calendar.DAY_OF_WEEK, 6)
                                val fin = cal.time
                                onFechasChange(inicio, fin)
                            },
                            label = { Text("Esta semana", fontSize = 12.sp) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val cal = Calendar.getInstance()
                                cal.set(Calendar.DAY_OF_MONTH, 1)
                                val inicio = cal.time
                                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                                val fin = cal.time
                                onFechasChange(inicio, fin)
                            },
                            label = { Text("Este mes", fontSize = 12.sp) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Estado del préstamo",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF0061A7)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = estadoFiltro == EstadoFiltro.TODOS,
                        onClick = { onEstadoChange(EstadoFiltro.TODOS) },
                        label = { Text("Todos", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = estadoFiltro == EstadoFiltro.ACTIVOS,
                        onClick = { onEstadoChange(EstadoFiltro.ACTIVOS) },
                        label = { Text("Activos", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.TrendingUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF2196F3).copy(alpha = 0.15f),
                            selectedLabelColor = Color(0xFF1976D2)
                        )
                    )
                    FilterChip(
                        selected = estadoFiltro == EstadoFiltro.SALDADOS,
                        onClick = { onEstadoChange(EstadoFiltro.SALDADOS) },
                        label = { Text("Saldados", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF4CAF50).copy(alpha = 0.15f),
                            selectedLabelColor = Color(0xFF2E7D32)
                        )
                    )
                }
            }

            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

            Button(
                onClick = {
                    if (pagosFiltrados.isEmpty()) {
                        Toast.makeText(
                            context,
                            "No hay pagos para exportar",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@Button
                    }
                    val periodo = when {
                        fechaInicio != null && fechaFin != null ->
                            "${formatter.format(fechaInicio)} - ${formatter.format(fechaFin)}"
                        else -> "Todos los registros"
                    }

                    // Siempre usar el preview
                    onPreview(pagosFiltrados, fechaInicio, fechaFin, periodo)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (estadoFiltro == EstadoFiltro.SALDADOS) {
                        Color(0xFF059669)
                    } else {
                        Color(0xFF0061A7)
                    }
                )
            ) {
                Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (estadoFiltro == EstadoFiltro.SALDADOS) {
                        "PDF Saldados"
                    } else {
                        "Generar PDF"
                    }
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFF5F5F5),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = null,
                        tint = Color(0xFF0061A7),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${pagosFiltrados.size} registro(s) encontrado(s)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF0061A7)
                    )
                }
            }
        }
    }

    if (mostrarDatePicker) {
        val calendar = Calendar.getInstance()
        if (seleccionandoFechaInicio && fechaInicio != null) {
            calendar.time = fechaInicio
        } else if (!seleccionandoFechaInicio && fechaFin != null) {
            calendar.time = fechaFin
        }

        DatePickerDialog(
            context,
            { _, year, month, day ->
                val cal = Calendar.getInstance()
                cal.set(year, month, day, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val selectedDate = cal.time

                if (seleccionandoFechaInicio) {
                    onFechasChange(selectedDate, fechaFin)
                } else {
                    onFechasChange(fechaInicio, selectedDate)
                }
                mostrarDatePicker = false
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            setOnCancelListener { mostrarDatePicker = false }
            show()
        }
    }
}

@Composable
fun ReportePagosPreview(
    pagos: List<PagoItem>,
    periodo: String,
    fechaInicio: Date?,
    fechaFin: Date?,
    esSaldados: Boolean = false,
    onCerrar: () -> Unit,
    onExportar: (List<PagoItem>, Date?, Date?, String, Boolean) -> Unit
) {
    val totalRecaudado = pagos.sumOf { it.monto + it.mora }
    val totalMora = pagos.sumOf { it.mora }
    val totalPagos = pagos.size

    AlertDialog(
        onDismissRequest = onCerrar,
        icon = {
            Icon(
                Icons.Default.Assessment,
                contentDescription = null,
                tint = if (esSaldados) Color(0xFF059669) else Color(0xFF0061A7),
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                if (esSaldados) "Vista Previa - Préstamos Saldados" else "Vista Previa del Reporte",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "PERÍODO",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        Text(
                            periodo,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (esSaldados) Color(0xFF059669) else Color(0xFF0061A7)
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (esSaldados) Color(0xFF059669) else Color(0xFF0061A7)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (esSaldados) {
                            // Estadísticas para préstamos saldados
                            val prestamosUnicos = pagos.distinctBy { it.prestamoId }.size

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Préstamos Saldados:",
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                                Text(
                                    prestamosUnicos.toString(),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Total Recaudado:",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Text(
                                formatearLempiras(totalRecaudado),
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Total Mora:",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Text(
                                formatearLempiras(totalMora),
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Pagos Registrados:",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Text(
                                totalPagos.toString(),
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (esSaldados) "PRÉSTAMOS EN EL REPORTE" else "ÚLTIMOS PAGOS EN EL REPORTE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (esSaldados) {
                            // Mostrar préstamos únicos
                            val prestamosUnicos = pagos.distinctBy { it.prestamoId }

                            prestamosUnicos.take(5).forEach { pago ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            pago.cliente,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF059669)
                                        )
                                        if (pago.numeroPrestamo.isNotEmpty()) {
                                            Text(
                                                "Nº ${pago.numeroPrestamo}",
                                                fontSize = 10.sp,
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                    Surface(
                                        color = Color(0xFF059669),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            "SALDADO",
                                            modifier = Modifier.padding(
                                                horizontal = 8.dp,
                                                vertical = 4.dp
                                            ),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                                if (pago != prestamosUnicos.take(5).last()) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = Color.LightGray.copy(alpha = 0.3f)
                                    )
                                }
                            }

                            if (prestamosUnicos.size > 5) {
                                Text(
                                    "... y ${prestamosUnicos.size - 5} préstamos más",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(top = 8.dp),
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        } else {
                            // Mostrar pagos individuales
                            pagos.take(5).forEach { pago ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            pago.cliente,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF0061A7)
                                        )
                                        Text(
                                            pago.fecha.split(" ")[0],
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    Text(
                                        formatearLempiras(pago.monto + pago.mora),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                                if (pago != pagos.take(5).last()) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = Color.LightGray.copy(alpha = 0.3f)
                                    )
                                }
                            }

                            if (pagos.size > 5) {
                                Text(
                                    "... y ${pagos.size - 5} pagos más",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(top = 8.dp),
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onExportar(pagos, fechaInicio, fechaFin, periodo, esSaldados) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (esSaldados) Color(0xFF059669) else Color(0xFF0061A7)
                )
            ) {
                Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Exportar PDF")
            }
        },
        dismissButton = {
            TextButton(onClick = onCerrar) {
                Text("Cancelar", color = Color.Gray)
            }
        }
    )
}