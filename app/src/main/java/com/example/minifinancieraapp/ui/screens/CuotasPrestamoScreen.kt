package com.example.minifinancieraapp.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.util.ReciboHelper
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

// ===================== MODELO ACTUALIZADO =====================
data class CuotaAmortizacion(
    val numero: Int,
    val fecha: String,
    val capital: Double,
    val interes: Double,
    val total: Double,
    val descripcion: String = "",
    var pagado: Boolean = false,
    var montoParcialPagado: Double = 0.0, // NUEVO: Para cuotas parciales
    var esParcial: Boolean = false // NUEVO: Indica si es pago parcial
) {
    val montoRestante: Double get() = (total - montoParcialPagado).coerceAtLeast(0.0)
    val porcentajePagado: Double get() = if (total > 0) (montoParcialPagado / total) * 100 else 0.0
}

suspend fun recalcularCuotasCompleto(
    db: FirebaseFirestore,
    prestamoId: String
): List<CuotaAmortizacion> {
    return try {
        // 1. Obtener datos frescos del préstamo
        val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
        val monto = prestamoDoc.getDouble("monto") ?: 0.0
        val cuotasNum = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
        val plazo = prestamoDoc.getString("plazo") ?: "Mensual"
        val fechaTimestamp = prestamoDoc.getTimestamp("fecha")
        val fechaInicio = fechaTimestamp?.toDate() ?: Date()
        val interesTotal = prestamoDoc.getDouble("interes") ?: prestamoDoc.getDouble("interesTotal") ?: 0.0

        // Normalizar plazo
        val plazoNormalizado = plazo.lowercase()

        // 2. Reconstruir plan de cuotas base
        val capitalPorCuota = if (cuotasNum > 0) monto / cuotasNum else 0.0
        val interesPorCuota = if (cuotasNum > 0) interesTotal / cuotasNum else 0.0

        val capitalEntero = capitalPorCuota.toInt()
        val capitalResiduo = monto - (capitalEntero * cuotasNum)
        val interesEntero = interesPorCuota.toInt()
        val interesResiduo = interesTotal - (interesEntero * cuotasNum)

        val planCuotasBase = mutableListOf<CuotaAmortizacion>()
        for (i in 0 until cuotasNum) {
            val capitalCuota = if (i == cuotasNum - 1) capitalEntero + capitalResiduo else capitalEntero.toDouble()
            val interesCuota = if (i == cuotasNum - 1) interesEntero + interesResiduo else interesEntero.toDouble()
            val fechaCuota = calcularFechaCuota(fechaInicio, plazoNormalizado, i + 1)

            planCuotasBase.add(
                CuotaAmortizacion(
                    numero = i + 1,
                    fecha = fechaCuota,
                    capital = capitalCuota,
                    interes = interesCuota,
                    total = capitalCuota + interesCuota
                )
            )
        }

        // 3. Aplicar estado de pagos
        calcularEstadoCuotasConParciales(db, prestamoId, planCuotasBase)

    } catch (e: Exception) {
        Log.e("RecalcularCuotas", "Error recalculando cuotas: ${e.message}", e)
        emptyList()
    }
}
// ===================== FUNCIONES UNIFICADAS (IGUALES A RegistrarPago) =====================

/** Función unificada para calcular fechas de cuotas con el mismo algoritmo de RegistrarPago */
private fun calcularFechaCuota(fechaInicio: Date, plazo: String, numeroCuota: Int): String {
    val calendar = Calendar.getInstance().apply { time = fechaInicio }
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // Para la primera cuota, calcular desde la fecha de inicio
    when (plazo.lowercase()) {
        "diario" -> {
            calendar.add(Calendar.DAY_OF_YEAR, numeroCuota)
        }
        "lunes a sábado" -> {
            repeat(numeroCuota) {
                do {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            }
        }
        "semanal" -> {
            calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 7)
        }
        "quincenal" -> {
            calendar.add(Calendar.DAY_OF_YEAR, numeroCuota * 15)
        }
        "mensual" -> {
            calendar.add(Calendar.MONTH, numeroCuota)
        }
        "bimestral" -> {
            calendar.add(Calendar.MONTH, numeroCuota * 2)
        }
        else -> {
            calendar.add(Calendar.MONTH, numeroCuota) // Default mensual
        }
    }

    return dateFormat.format(calendar.time)
}

/** Función corregida: Lee proximoPago de manera más robusta */
private fun leerProximoPagoProgramado(
    doc: com.google.firebase.firestore.DocumentSnapshot
): String? {
    val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    return try {
        // Intentar leer como Timestamp primero (más común en Firestore)
        doc.getTimestamp("proximoPago")?.toDate()?.let {
            return fmt.format(it)
        }

        // Luego como Date
        doc.getDate("proximoPago")?.let {
            return fmt.format(it)
        }

        // Finalmente como String
        doc.getString("proximoPago")?.let {
            return it
        }

        null
    } catch (e: Exception) {
        Log.w("CuotasScreen", "Error leyendo proximoPago: ${e.message}")
        null
    }
}

// ===================== NUEVOS HELPERS PARA CUOTAS PARCIALES =====================

/** NUEVA FUNCIÓN: Calcula el estado de pagos por cuota considerando pagos parciales */
suspend fun calcularEstadoCuotasConParciales(
    db: FirebaseFirestore,
    prestamoId: String,
    cuotasBase: List<CuotaAmortizacion>
): List<CuotaAmortizacion> {
    return try {
        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId)
            .get().await()

        // Mapa para acumular pagos por cuota
        val pagosPorCuota = mutableMapOf<Int, Double>()
        val cuotasMarcadasManualmente = mutableSetOf<Int>() // NUEVO: Para trackear cuotas marcadas manualmente
        val fechasPagoPorCuota = mutableMapOf<Int, String>()
        val fmtPago = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        // Procesar todos los pagos y acumular por cuota
        for (pago in pagosSnapshot.documents) {
            val numeroCuotaInicial = when {
                pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                pago.contains("cuota") -> pago.getLong("cuota")?.toInt() ?: 1
                else -> 1
            }

            val cuotasCubiertas = pago.getLong("cuotasCubiertas")?.toInt() ?: 1
            val montoPago = pago.getDouble("monto") ?: 0.0
            val moraPago = pago.getDouble("mora") ?: 0.0
            val montoTotal = montoPago + moraPago

            // CORRECCIÓN: Detectar si es un pago manual del admin (monto 0.0 pero debería marcar como pagada)
            val esManual = pago.getString("metodoPago") == "Manual (Admin)" ||
                    pago.getString("observaciones")?.contains("manualmente") == true

            // Extraer fecha de pago
            val fechaPagoStr: String? = when (val fp = pago.get("fechaPago")) {
                is Timestamp -> fmtPago.format(fp.toDate())
                is Date -> fmtPago.format(fp)
                is String -> fp
                else -> null
            }

            Log.d("CuotasScreen", """
                Procesando pago:
                - Cuota inicial: $numeroCuotaInicial
                - Cuotas cubiertas: $cuotasCubiertas
                - Monto: L. ${String.format("%.2f", montoPago)}
                - Mora: L. ${String.format("%.2f", moraPago)}
                - Total: L. ${String.format("%.2f", montoTotal)}
                - Es manual: $esManual
            """.trimIndent())

            // LÓGICA MEJORADA: Distribuir el pago entre las cuotas
            if (cuotasCubiertas == 1) {
                // Pago a una sola cuota específica
                if (esManual && montoTotal == 0.0) {
                    // CORRECCIÓN: Para pagos manuales con monto 0, marcar la cuota como completamente pagada
                    cuotasMarcadasManualmente.add(numeroCuotaInicial)
                    Log.d("CuotasScreen", "Cuota $numeroCuotaInicial marcada manualmente como pagada")
                } else {
                    pagosPorCuota[numeroCuotaInicial] = (pagosPorCuota[numeroCuotaInicial] ?: 0.0) + montoTotal
                }
                fechaPagoStr?.let { fechasPagoPorCuota[numeroCuotaInicial] = it }

            } else if (cuotasCubiertas > 1) {
                // Pago que cubre múltiples cuotas - distribuir inteligentemente
                var montoRestante = montoTotal

                // Primero completar cuotas parciales existentes
                for (i in 0 until cuotasCubiertas) {
                    val numCuota = numeroCuotaInicial + i
                    if (numCuota <= cuotasBase.size && montoRestante > 0) {
                        val cuotaBase = cuotasBase.find { it.numero == numCuota }
                        if (cuotaBase != null) {
                            val yaAbonado = pagosPorCuota[numCuota] ?: 0.0
                            val faltante = (cuotaBase.total - yaAbonado).coerceAtLeast(0.0)
                            val abonoACuota = minOf(montoRestante, faltante)

                            pagosPorCuota[numCuota] = yaAbonado + abonoACuota
                            montoRestante -= abonoACuota

                            fechaPagoStr?.let { fechasPagoPorCuota[numCuota] = it }

                            Log.d("CuotasScreen", "Cuota $numCuota: abonado L. ${String.format("%.2f", abonoACuota)}, acumulado L. ${String.format("%.2f", pagosPorCuota[numCuota])}")
                        }
                    }
                }

                // Si sobra dinero, aplicar exceso a la última cuota del rango
                if (montoRestante > 0.01) { // Tolerancia para decimales
                    val ultimaCuota = numeroCuotaInicial + cuotasCubiertas - 1
                    pagosPorCuota[ultimaCuota] = (pagosPorCuota[ultimaCuota] ?: 0.0) + montoRestante
                    Log.d("CuotasScreen", "Exceso de L. ${String.format("%.2f", montoRestante)} aplicado a cuota $ultimaCuota")
                }
            }
        }

        // Actualizar cuotas con el estado calculado
        cuotasBase.map { cuotaBase ->
            val montoPagado = pagosPorCuota[cuotaBase.numero] ?: 0.0
            val fueMarcadaManualmente = cuotasMarcadasManualmente.contains(cuotaBase.numero) // NUEVO

            // CORRECCIÓN: Si fue marcada manualmente, considerar como completamente pagada
            val completamentePagada = fueMarcadaManualmente || (montoPagado >= cuotaBase.total - 0.01) // Tolerancia de 1 centavo
            val esParcial = !fueMarcadaManualmente && montoPagado > 0.01 && !completamentePagada

            cuotaBase.copy(
                // CORRECCIÓN: Si fue marcada manualmente, usar el total de la cuota como monto pagado para efectos de visualización
                montoParcialPagado = if (fueMarcadaManualmente) cuotaBase.total else montoPagado,
                pagado = completamentePagada,
                esParcial = esParcial
            )
        }.also { cuotasActualizadas ->
            Log.d("CuotasScreen", "=== RESUMEN DE CUOTAS ===")
            cuotasActualizadas.forEach { cuota ->
                when {
                    cuota.pagado -> Log.d("CuotasScreen", "Cuota ${cuota.numero}: COMPLETA (L. ${String.format("%.2f", cuota.montoParcialPagado)})")
                    cuota.esParcial -> Log.d("CuotasScreen", "Cuota ${cuota.numero}: PARCIAL (L. ${String.format("%.2f", cuota.montoParcialPagado)} de L. ${String.format("%.2f", cuota.total)})")
                    else -> Log.d("CuotasScreen", "Cuota ${cuota.numero}: PENDIENTE")
                }
            }
        }

    } catch (e: Exception) {
        Log.e("CuotasScreen", "Error calculando estado de cuotas: ${e.message}", e)
        cuotasBase
    }
}

// Función corregida: Lee la fecha programada "proximoPago" de manera más robusta
suspend fun obtenerFechaProgramadaActual(
    db: FirebaseFirestore,
    prestamoId: String
): String? {
    return try {
        val doc = db.collection("prestamos").document(prestamoId).get().await()
        leerProximoPagoProgramado(doc)
    } catch (e: Exception) {
        Log.e("CuotasScreen", "Error obteniendo fecha programada: ${e.message}")
        null
    }
}

// Función para encontrar la próxima cuota sin pagar (actualizada para parciales)
suspend fun encontrarProximaCuotaSinPagar(
    db: FirebaseFirestore,
    prestamoId: String,
    todasLasCuotas: List<CuotaAmortizacion>
): String? {
    return try {
        val cuotasSinPagar = todasLasCuotas.filter {
            !it.pagado && it.descripcion != "Mora"
        }
        if (cuotasSinPagar.isNotEmpty()) {
            // Priorizar cuotas parciales, luego por número
            val proximaCuota = cuotasSinPagar
                .sortedWith(compareBy<CuotaAmortizacion> { !it.esParcial }.thenBy { it.numero })
                .first()
            proximaCuota.fecha
        } else {
            "saldado"
        }
    } catch (e: Exception) {
        Log.e("CuotasScreen", "Error encontrando próxima cuota sin pagar: ${e.message}")
        null
    }
}

// ✅ FUNCIÓN CORREGIDA: Registrar abono parcial con actualización completa del préstamo
suspend fun registrarAbonoParcialCorregido(
    db: FirebaseFirestore,
    context: android.content.Context,
    prestamoId: String,
    cuota: CuotaAmortizacion,
    montoAbono: Double,
    nombreCobrador: String,
    nombreCliente: String,
    numeroPrestamo: Int,
    lugar: String = "El Paraíso, Danlí",
    metodoPago: String = "Efectivo"
): Boolean {
    return try {
        val fechaActual = Timestamp.now()
        val fechaFormateada = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(fechaActual.toDate())

        // Validar que el abono no exceda el monto restante
        val montoRestante = cuota.montoRestante
        if (montoAbono > montoRestante) {
            Toast.makeText(context, "El abono no puede exceder el monto restante: L. ${String.format("%.2f", montoRestante)}", Toast.LENGTH_LONG).show()
            return false
        }

        // Calcular nuevo estado de la cuota
        val nuevoMontoPagado = cuota.montoParcialPagado + montoAbono
        val quedaCompleta = nuevoMontoPagado >= cuota.total - 0.01 // Tolerancia de 1 centavo

        // Obtener datos actuales del préstamo
        val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
        val saldoActual = prestamoDoc.getDouble("saldo") ?: 0.0
        val montoPagadoTotal = (prestamoDoc.getDouble("montoPagado") ?: 0.0) + montoAbono
        val nuevoSaldo = (saldoActual - montoAbono).coerceAtLeast(0.0)
        val cuotasTotales = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
        val plazo = prestamoDoc.getString("plazo") ?: "semanal"
        val fechaInicio = prestamoDoc.getTimestamp("fecha")?.toDate() ?: Date()

        // ✅ CALCULAR PRÓXIMA FECHA CORRECTAMENTE PARA CUOTAS PARCIALES
        val proximoPago = if (quedaCompleta) {
            // Si completa la cuota, buscar la siguiente cuota pendiente
            val estadoCuotasActualizado = obtenerEstadoCuotasDetallado(db, prestamoId)
            val cuotaEstimada = prestamoDoc.getDouble("cuota") ?: 0.0

            // Simular que esta cuota queda pagada
            val estadoSimulado = estadoCuotasActualizado.toMutableMap()
            estadoSimulado[cuota.numero] = cuota.total

            // Encontrar la siguiente cuota sin pagar
            var proximaCuotaSinPagar: Int? = null
            for (i in 1..cuotasTotales) {
                val montoPagadoEnCuota = estadoSimulado[i] ?: 0.0
                if (montoPagadoEnCuota < cuotaEstimada - 0.01) {
                    proximaCuotaSinPagar = i
                    break
                }
            }

            if (proximaCuotaSinPagar != null) {
                calcularFechaCuota(fechaInicio, plazo, proximaCuotaSinPagar)
            } else {
                "saldado"
            }
        } else {
            // Si sigue siendo parcial, mantener la fecha de esta cuota
            cuota.fecha
        }

        // ✅ GENERAR RECIBO PARA ABONO PARCIAL
        val pdfFile = ReciboHelper.generarReciboPDF(
            context = context,
            cliente = nombreCliente,
            prestamoId = "Préstamo Nº $numeroPrestamo",
            fecha = fechaFormateada,
            montoPagado = montoAbono.toString(),
            saldoAnterior = saldoActual,
            proximoPago = proximoPago,
            cuota = "${cuota.numero} (${if (quedaCompleta) "Completa Parcial" else "Abono Parcial"})",
            cobrador = nombreCobrador,
            lugar = lugar,
            firma = nombreCobrador,
            tipoPago = metodoPago,
            mora = 0.0
        )

        val pdfGenerado = pdfFile != null && pdfFile.exists()

        // ✅ INTENTAR IMPRIMIR AUTOMÁTICAMENTE
        var pdfImpreso = false
        if (pdfGenerado) {
            try {
                ReciboHelper.imprimirPDF(context, pdfFile!!)
                pdfImpreso = true
                Toast.makeText(context, "✅ Recibo impreso correctamente", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                pdfImpreso = false
                Toast.makeText(context, "⚠️ Error al imprimir. Compartiendo...", Toast.LENGTH_SHORT).show()
            }
            ReciboHelper.compartirReciboPDF(context, pdfFile!!)
        }

        // ✅ REGISTRAR EL PAGO PARCIAL CON INFORMACIÓN COMPLETA
        val pagoData = mapOf<String, Any>(
            "clienteId" to (prestamoDoc.getString("clienteId") ?: ""),
            "clienteNombre" to nombreCliente,
            "prestamoId" to prestamoId,
            "numeroPrestamo" to numeroPrestamo,
            "monto" to montoAbono,
            "mora" to 0.0,
            "fechaPago" to fechaActual,
            "registradoPor" to nombreCobrador,
            "nombreCobrador" to nombreCobrador,
            "numeroCuota" to cuota.numero,
            "cuota" to cuota.numero,
            "cuotasCubiertas" to if (quedaCompleta) 1 else 0, // 1 si completa, 0 si es parcial
            "saldoRestante" to nuevoSaldo,
            "lugar" to lugar,
            "firma" to nombreCobrador,
            "metodoPago" to metodoPago,
            "plazo" to plazo,
            "pdfGenerado" to pdfGenerado,
            "pdfImpreso" to pdfImpreso,
            "fechaProgramadaOriginal" to (obtenerFechaProgramadaActual(db, prestamoId) ?: ""),
            "proximaFechaProgramada" to proximoPago,
            "pagoTardio" to false, // Los abonos parciales desde cuotas no son tardíos
            // ✅ CAMPOS ESPECÍFICOS PARA ABONOS PARCIALES
            "esAbonoParcial" to !quedaCompleta,
            "completaCuotaParcial" to quedaCompleta,
            "teniaAbonoParcialPrevio" to (cuota.esParcial),
            "montoAnteriorEnCuota" to cuota.montoParcialPagado,
            "montoNuevoEnCuota" to nuevoMontoPagado,
            "montoRestanteCuota" to (cuota.total - nuevoMontoPagado).coerceAtLeast(0.0),
            "etiquetaCuota" to "${cuota.numero} (${if (quedaCompleta) "Completa Parcial" else "Abono Parcial"})",
            "observaciones" to when {
                quedaCompleta -> "Cuota ${cuota.numero} completada con abono parcial desde tabla de cuotas"
                cuota.esParcial -> "Abono adicional a cuota ${cuota.numero} desde tabla de cuotas"
                else -> "Primer abono parcial a cuota ${cuota.numero} desde tabla de cuotas"
            }
        )

        // ✅ TRANSACCIÓN COMPLETA
        val batch = db.batch()

        // Guardar en pagos
        val pagoRef = db.collection("pagos").document()
        batch.set(pagoRef, pagoData)

        // Guardar en historial
        val historialRef = db.collection("historial").document()
        batch.set(historialRef, pagoData)

        // Guardar en historialGlobal
        val historialGlobalRef = db.collection("historialGlobal").document()
        batch.set(historialGlobalRef, pagoData)

        // ✅ ACTUALIZAR PRÉSTAMO COMPLETAMENTE
        val actualizacionPrestamo = mutableMapOf<String, Any>(
            "saldo" to nuevoSaldo,
            "montoPagado" to montoPagadoTotal,
            "fechaUltimaActualizacion" to fechaActual,
            "ultimoPago" to fechaFormateada,
            "proximoPago" to proximoPago
        )

        // Si el préstamo queda saldado, marcarlo
        if (nuevoSaldo <= 0.01) {
            actualizacionPrestamo["estado"] = "saldado"
            actualizacionPrestamo["proximoPago"] = "saldado"
        } else {
            actualizacionPrestamo["estado"] = "activo"
        }

        batch.update(db.collection("prestamos").document(prestamoId), actualizacionPrestamo)

        // ✅ ACTUALIZAR CLIENTE
        val clienteId = prestamoDoc.getString("clienteId") ?: ""
        if (clienteId.isNotEmpty()) {
            val actualizacionCliente = mapOf<String, Any>(
                "ultimaActividad" to fechaActual,
                "fechaUltimaActualizacion" to fechaActual
            )
            batch.update(db.collection("clientes").document(clienteId), actualizacionCliente)
        }

        batch.commit().await()

        // ✅ LOG DETALLADO
        Log.d("AbonoParcialCorregido", """
            ✅ ABONO PARCIAL REGISTRADO CORRECTAMENTE:
            - Cuota: ${cuota.numero}
            - Abono: L. ${String.format("%.2f", montoAbono)}
            - Monto anterior en cuota: L. ${String.format("%.2f", cuota.montoParcialPagado)}
            - Monto nuevo en cuota: L. ${String.format("%.2f", nuevoMontoPagado)}
            - Resta en cuota: L. ${String.format("%.2f", (cuota.total - nuevoMontoPagado).coerceAtLeast(0.0))}
            - Cuota completa: $quedaCompleta
            - Nuevo saldo préstamo: L. ${String.format("%.2f", nuevoSaldo)}
            - Próxima fecha: $proximoPago
            - PDF generado: $pdfGenerado
            - PDF impreso: $pdfImpreso
        """.trimIndent())

        true
    } catch (e: Exception) {
        Log.e("AbonoParcialCorregido", "Error registrando abono parcial: ${e.message}", e)
        Toast.makeText(context, "❌ Error al registrar abono parcial: ${e.message}", Toast.LENGTH_LONG).show()
        false
    }
}

// ✅ FUNCIÓN AUXILIAR: Obtener estado detallado de cuotas (igual que en RegistrarPago)
private suspend fun obtenerEstadoCuotasDetallado(
    db: FirebaseFirestore,
    prestamoId: String
): Map<Int, Double> {
    return try {
        val pagosSnapshot = db.collection("pagos")
            .whereEqualTo("prestamoId", prestamoId)
            .get().await()

        val pagosPorCuota = mutableMapOf<Int, Double>()

        for (pago in pagosSnapshot.documents) {
            val numeroCuotaInicial = when {
                pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                pago.contains("cuota") -> pago.getLong("cuota")?.toInt() ?: 1
                else -> 1
            }

            val cuotasCubiertas = pago.getLong("cuotasCubiertas")?.toInt() ?: 1
            val montoPago = pago.getDouble("monto") ?: 0.0
            val moraPago = pago.getDouble("mora") ?: 0.0
            val montoTotal = montoPago + moraPago

            // Distribuir el pago entre las cuotas afectadas
            for (i in 0 until cuotasCubiertas) {
                val numCuota = numeroCuotaInicial + i
                pagosPorCuota[numCuota] = (pagosPorCuota[numCuota] ?: 0.0) + montoTotal
            }
        }

        pagosPorCuota
    } catch (e: Exception) {
        Log.e("EstadoCuotas", "Error obteniendo estado de cuotas: ${e.message}")
        emptyMap()
    }
}

// ===================== UI =====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuotasPrestamoScreen(prestamoId: String, navController: NavController, uid: String, rol: String) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val dec = DecimalFormat("#,##0.00")

    var cuotas by remember { mutableStateOf(listOf<CuotaAmortizacion>()) }
    var cargando by remember { mutableStateOf(true) }
    var esActivo by remember { mutableStateOf(true) }
    var estaSaldado by remember { mutableStateOf(false) }

    var totalCapital by remember { mutableStateOf(0.0) }
    var totalInteres by remember { mutableStateOf(0.0) }
    var moraAplicada by remember { mutableStateOf(0.0) }
    var moraPagada by remember { mutableStateOf(false) }
    var nombreCobrador by remember { mutableStateOf("") }
    var nombreCliente by remember { mutableStateOf("") }
    var descripcionPlazo by remember { mutableStateOf("") }
    var proximoPagoProgramado by remember { mutableStateOf<String?>(null) }
    var numeroPrestamo by remember { mutableStateOf(0) }

    LaunchedEffect(prestamoId) {
        cargando = true
        try {
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
            val estado = prestamoDoc.getString("estado") ?: "activo"
            esActivo = estado == "activo"

            nombreCliente = prestamoDoc.getString("cliente") ?: "Cliente"
            numeroPrestamo = prestamoDoc.getLong("numeroPrestamo")?.toInt() ?: 0
            val monto = prestamoDoc.getDouble("monto") ?: 0.0
            val cuotasNum = prestamoDoc.getLong("cuotas")?.toInt() ?: 1
            val plazo = prestamoDoc.getString("plazo") ?: "Mensual"
            val fechaTimestamp = prestamoDoc.getTimestamp("fecha")
            val fechaInicio = fechaTimestamp?.toDate() ?: Date()

            // Puede venir en distintos campos; normalizamos
            val interesTotal = prestamoDoc.getDouble("interes") ?: prestamoDoc.getDouble("interesTotal") ?: 0.0
            totalCapital = monto
            totalInteres = interesTotal

            // Normalizar texto de plazo para helpers (igual que RegistrarPago)
            val plazoNormalizado = plazo.lowercase()

            // Descripción coherente con el cálculo
            descripcionPlazo = when (plazoNormalizado) {
                "diario" -> "Diario (incluye domingos)"
                "lunes a sábado" -> "Lunes a Sábado (sin domingos)"
                "semanal" -> "Semanal (cada 7 días)"
                "quincenal" -> "Quincenal (cada 15 días)"
                "mensual" -> "Mensual (cada mes calendario)"
                "bimestral" -> "Bimestral (cada 2 meses calendario)"
                else -> plazo
            }

            // Usar la función corregida para obtener fecha programada
            proximoPagoProgramado = obtenerFechaProgramadaActual(db, prestamoId)

            Log.d("CuotasScreen", """
                === DATOS DEL PRÉSTAMO ===
                - Cliente: $nombreCliente
                - Capital: L. ${String.format("%.2f", monto)}
                - Interés total: L. ${String.format("%.2f", interesTotal)}
                - TOTAL A PAGAR: L. ${String.format("%.2f", monto + interesTotal)}
                - Plazo: $plazo ($plazoNormalizado)
                - Cuotas: $cuotasNum
                - Fecha inicio: ${formatter.format(fechaInicio)}
                - Próximo pago programado: $proximoPagoProgramado
            """.trimIndent())

            // Plan de cuotas usando la función unificada
            val capitalPorCuota = if (cuotasNum > 0) monto / cuotasNum else 0.0
            val interesPorCuota = if (cuotasNum > 0) totalInteres / cuotasNum else 0.0

            // Evitar errores por acumulación de decimales
            val capitalEntero = capitalPorCuota.toInt()
            val capitalResiduo = monto - (capitalEntero * cuotasNum)

            val interesEntero = interesPorCuota.toInt()
            val interesResiduo = totalInteres - (interesEntero * cuotasNum)

            val planCuotas = mutableListOf<CuotaAmortizacion>()
            for (i in 0 until cuotasNum) {
                val capitalCuota = if (i == cuotasNum - 1) capitalEntero + capitalResiduo else capitalEntero.toDouble()
                val interesCuota = if (i == cuotasNum - 1) interesEntero + interesResiduo else interesEntero.toDouble()

                // Usar la función unificada para calcular fechas
                val fechaCuota = calcularFechaCuota(fechaInicio, plazoNormalizado, i + 1)

                planCuotas.add(
                    CuotaAmortizacion(
                        numero = i + 1,
                        fecha = fechaCuota,
                        capital = capitalCuota,
                        interes = interesCuota,
                        total = capitalCuota + interesCuota
                    )
                )
            }

            // === USAR NUEVA FUNCIÓN PARA CALCULAR ESTADO CON PARCIALES ===
            cuotas = calcularEstadoCuotasConParciales(db, prestamoId, planCuotas)

            // Manejo de mora (igual que antes)
            val moraValor = prestamoDoc.getDouble("mora") ?: 0.0
            val moraActiva = moraValor > 0.0 && !moraPagada
            moraAplicada = if (moraActiva) moraValor else 0.0

            if (moraActiva) {
                cuotas = cuotas + CuotaAmortizacion(
                    numero = cuotas.size + 1,
                    fecha = "Aplicada (mora)",
                    capital = 0.0,
                    interes = 0.0,
                    total = moraValor,
                    descripcion = "Mora",
                    pagado = moraPagada
                )
            }

            // Verificar si está saldado (actualizado para parciales)
            val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
            val todasPagadas = cuotasNormales.all { it.pagado }
            val moraCobrada = moraAplicada == 0.0 || moraPagada
            estaSaldado = todasPagadas && moraCobrada

            if (estaSaldado && esActivo) {
                db.collection("prestamos").document(prestamoId).update("estado", "saldado").await()
                esActivo = false
            }

            // Nombre del cobrador
            val usuarioDoc = db.collection("usuarios").document(uid).get().await()
            nombreCobrador = usuarioDoc.getString("nombre") ?: uid

            Log.d("CuotasScreen", """
                === RESUMEN FINAL ===
                - Cuotas totales: ${cuotas.filter { it.descripcion != "Mora" }.size}
                - Cuotas completamente pagadas: ${cuotas.filter { it.descripcion != "Mora" && it.pagado }.size}
                - Cuotas parcialmente pagadas: ${cuotas.filter { it.descripcion != "Mora" && it.esParcial }.size}
                - Mora aplicada: L. ${String.format("%.2f", moraAplicada)}
                - Mora pagada: $moraPagada
                - Estado saldado: $estaSaldado
            """.trimIndent())

        } catch (e: Exception) {
            Log.e("CuotasScreen", "Error al cargar datos: ${e.message}", e)
            Toast.makeText(context, "Error al cargar datos: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            cargando = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tabla de Amortización", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0061A7))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            if (cargando) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // ---- Cabecera ACTUALIZADA ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Información del Préstamo", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Cliente: $nombreCliente")
                        Text("Tipo de plazo: $descripcionPlazo")
                        proximoPagoProgramado?.let {
                            Text(
                                "Próximo pago programado: $it",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1976D2)
                            )
                        }
                        Text("Número de cuotas: ${cuotas.filter { it.descripcion != "Mora" }.size}")
                        Text("Capital: L. ${dec.format(totalCapital)}")
                        Text("Interés Total: L. ${dec.format(totalInteres)}")

                        val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                        val cuotasPagadas = cuotasNormales.count { it.pagado }
                        val cuotasParciales = cuotasNormales.count { it.esParcial }
                        val totalCuotas = cuotasNormales.size

                        // PROGRESO MEJORADO con info de parciales
                        Text(
                            "Progreso: $cuotasPagadas completas + $cuotasParciales parciales de $totalCuotas cuotas",
                            fontWeight = FontWeight.Medium,
                            color = when {
                                cuotasPagadas == totalCuotas -> Color(0xFF4CAF50)
                                cuotasParciales > 0 -> Color(0xFFFF9800)
                                else -> Color(0xFF1976D2)
                            }
                        )

                        if (moraAplicada > 0.0) {
                            Text(
                                "Mora aplicada: L. ${dec.format(moraAplicada)}",
                                color = if (moraPagada) Color(0xFF4CAF50) else Color.Red,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            "Total a pagar: L. ${dec.format(totalCapital + totalInteres + moraAplicada)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ---- Exportar PDF ----
                Button(
                    onClick = {
                        val pdfFile = ReciboHelper.generarCuotasPDF(
                            context = context,
                            cliente = nombreCliente,
                            prestamoId = prestamoId,
                            cuotas = cuotas,
                            totalCapital = totalCapital,
                            totalInteres = totalInteres,
                            mora = moraAplicada,
                            fechaExportacion = formatter.format(Date())
                        )
                        if (pdfFile != null) {
                            ReciboHelper.compartirReciboPDF(context, pdfFile)
                            Toast.makeText(context, "PDF generado correctamente", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Error al generar PDF", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text("Exportar Cuotas en PDF", color = Color.White)
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (estaSaldado) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFD4F6D4))) {
                        Text(
                            "Este préstamo está completamente saldado",
                            modifier = Modifier.padding(16.dp),
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // ---- Lista de cuotas ACTUALIZADA CON ABONOS PARCIALES ----
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cuotas) { cuota ->
                        var mostrarDialogo by remember { mutableStateOf(false) }
                        var mostrarDialogoAbonoParcial by remember { mutableStateOf(false) }
                        var montoAbonoParcial by remember { mutableStateOf("") }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    cuota.pagado -> Color(0xFFD0F0C0) // Verde para completas
                                    cuota.esParcial -> Color(0xFFFFF3E0) // Naranja claro para parciales
                                    else -> Color.White // Blanco para pendientes
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Cuota ${cuota.numero}" + if (cuota.descripcion.isNotEmpty()) " (${cuota.descripcion})" else "",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            when {
                                                cuota.pagado -> Icons.Default.CheckCircle
                                                cuota.esParcial -> Icons.Default.WbCloudy
                                                else -> Icons.Default.HourglassBottom
                                            },
                                            contentDescription = null,
                                            tint = when {
                                                cuota.pagado -> Color(0xFF388E3C)
                                                cuota.esParcial -> Color(0xFFFF9800)
                                                else -> Color.Gray
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            when {
                                                cuota.pagado -> "Completa"
                                                cuota.esParcial -> "Parcial (${String.format("%.0f", cuota.porcentajePagado)}%)"
                                                else -> "Pendiente"
                                            },
                                            color = when {
                                                cuota.pagado -> Color(0xFF388E3C)
                                                cuota.esParcial -> Color(0xFFFF9800)
                                                else -> Color.Gray
                                            },
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                if (cuota.descripcion != "Mora") Text("Fecha: ${cuota.fecha}")

                                if (cuota.capital > 0) Text("Capital: L. ${dec.format(cuota.capital)}")
                                if (cuota.interes > 0) Text("Interés: L. ${dec.format(cuota.interes)}")

                                Text(
                                    "Total: L. ${dec.format(cuota.total)}",
                                    fontWeight = FontWeight.Bold,
                                    color = if (cuota.descripcion == "Mora") Color.Red else Color.Black
                                )

                                // MOSTRAR INFORMACIÓN DE PAGOS PARCIALES
                                if (cuota.esParcial) {
                                    Text(
                                        "Pagado: L. ${dec.format(cuota.montoParcialPagado)}",
                                        color = Color(0xFF388E3C),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "Resta: L. ${dec.format(cuota.montoRestante)}",
                                        color = Color(0xFFFF9800),
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                // NUEVO: Botón para abono parcial (disponible para admin y cobrador)
                                if (!cuota.pagado && esActivo && (rol == "admin" || rol == "cobrador") && cuota.descripcion != "Mora") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Botón de abono parcial
                                        Button(
                                            onClick = { mostrarDialogoAbonoParcial = true },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                                        ) {
                                            Text(
                                                if (cuota.esParcial) "Abonar más" else "Abono Parcial",
                                                color = Color.White,
                                                fontSize = 12.sp
                                            )
                                        }

                                        // Botón marcar como pagada completa (solo admin)
                                        if (rol == "admin") {
                                            Button(
                                                onClick = { mostrarDialogo = true },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0061A7))
                                            ) {
                                                Text(
                                                    "Marcar completa",
                                                    color = Color.White,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }

                                    // Diálogo para abono parcial
                                    if (mostrarDialogoAbonoParcial) {
                                        AlertDialog(
                                            onDismissRequest = {
                                                mostrarDialogoAbonoParcial = false
                                                montoAbonoParcial = ""
                                            },
                                            title = { Text("Abono Parcial - Cuota ${cuota.numero}") },
                                            text = {
                                                Column {
                                                    Text("Total de la cuota: L. ${dec.format(cuota.total)}")
                                                    if (cuota.esParcial) {
                                                        Text("Ya pagado: L. ${dec.format(cuota.montoParcialPagado)}")
                                                        Text(
                                                            "Resta por pagar: L. ${dec.format(cuota.montoRestante)}",
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFFFF9800)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    OutlinedTextField(
                                                        value = montoAbonoParcial,
                                                        onValueChange = { montoAbonoParcial = it },
                                                        label = { Text("Monto a abonar") },
                                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                }
                                            },
                                            confirmButton = {
                                                TextButton(
                                                    onClick = {
                                                        val monto = montoAbonoParcial.toDoubleOrNull()
                                                        if (monto != null && monto > 0) {
                                                            mostrarDialogoAbonoParcial = false
                                                            montoAbonoParcial = ""

                                                            scope.launch {
                                                                try {
                                                                    val exito = registrarAbonoParcialCorregido(
                                                                        db = db,
                                                                        context = context,
                                                                        prestamoId = prestamoId,
                                                                        cuota = cuota,
                                                                        montoAbono = monto,
                                                                        nombreCobrador = nombreCobrador,
                                                                        nombreCliente = nombreCliente,
                                                                        numeroPrestamo = numeroPrestamo
                                                                    )

                                                                    if (exito) {
                                                                        Toast.makeText(
                                                                            context,
                                                                            "✅ Abono parcial registrado correctamente",
                                                                            Toast.LENGTH_SHORT
                                                                        ).show()

                                                                        // ✅ RECALCULAR CUOTAS COMPLETO
                                                                        cuotas = recalcularCuotasCompleto(db, prestamoId)

                                                                        // ✅ TAMBIÉN ACTUALIZAR LA MORA SI EXISTE
                                                                        val prestamoDocActualizado = db.collection("prestamos").document(prestamoId).get().await()
                                                                        val moraValor = prestamoDocActualizado.getDouble("mora") ?: 0.0
                                                                        val moraActiva = moraValor > 0.0 && !moraPagada
                                                                        moraAplicada = if (moraActiva) moraValor else 0.0

                                                                        if (moraActiva) {
                                                                            cuotas = cuotas + CuotaAmortizacion(
                                                                                numero = cuotas.size + 1,
                                                                                fecha = "Aplicada (mora)",
                                                                                capital = 0.0,
                                                                                interes = 0.0,
                                                                                total = moraValor,
                                                                                descripcion = "Mora",
                                                                                pagado = moraPagada
                                                                            )
                                                                        }

                                                                        // ✅ VERIFICAR SI ESTÁ SALDADO
                                                                        val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                                                                        val todasPagadas = cuotasNormales.all { it.pagado }
                                                                        val moraCobrada = moraAplicada == 0.0 || moraPagada
                                                                        estaSaldado = todasPagadas && moraCobrada

                                                                        if (estaSaldado && esActivo) {
                                                                            db.collection("prestamos").document(prestamoId).update("estado", "saldado").await()
                                                                            esActivo = false
                                                                        }

                                                                        // ✅ ACTUALIZAR FECHA PROGRAMADA
                                                                        proximoPagoProgramado = obtenerFechaProgramadaActual(db, prestamoId)

                                                                        Log.d("AbonoParcialUI", "✅ Interfaz actualizada correctamente")
                                                                    }
                                                                } catch (e: Exception) {
                                                                    Log.e("AbonoParcial", "Error: ${e.message}", e)
                                                                    Toast.makeText(
                                                                        context,
                                                                        "❌ Error al registrar abono parcial",
                                                                        Toast.LENGTH_LONG
                                                                    ).show()
                                                                }
                                                            }
                                                        } else {
                                                            Toast.makeText(
                                                                context,
                                                                "Ingresa un monto válido",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                ) {
                                                    Text("Registrar Abono", color = Color(0xFF0061A7))
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(
                                                    onClick = {
                                                        mostrarDialogoAbonoParcial = false
                                                        montoAbonoParcial = ""
                                                    }
                                                ) {
                                                    Text("Cancelar")
                                                }
                                            }
                                        )
                                    }
                                }

                                // ---- Marcar manualmente como pagada (Admin) - CORREGIDO ----
                                if (mostrarDialogo) {
                                    AlertDialog(
                                        onDismissRequest = { mostrarDialogo = false },
                                        title = { Text("Confirmar acción") },
                                        text = { Text("¿Marcar esta cuota como pagada manualmente?\nSe registrará un pago administrativo para control.") },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                mostrarDialogo = false

                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            // CORRECCIÓN: Crear registro de pago administrativo con valor real de la cuota
                                                            val montoAPagar = if (cuota.descripcion == "Mora") {
                                                                cuota.total // Para mora, todo el monto va como mora
                                                            } else {
                                                                cuota.total // Para cuotas normales, todo como monto
                                                            }

                                                            val abonoManual = mapOf(
                                                                "prestamoId" to prestamoId,
                                                                // CORRECCIÓN CLAVE: Usar el monto real de la cuota en lugar de 0.0
                                                                "monto" to if (cuota.descripcion == "Mora") 0.0 else montoAPagar,
                                                                "mora" to if (cuota.descripcion == "Mora") montoAPagar else 0.0,
                                                                "fechaPago" to Timestamp.now(),
                                                                "registradoPor" to nombreCobrador,
                                                                "numeroCuota" to cuota.numero,
                                                                "cuota" to cuota.numero,
                                                                "cuotasCubiertas" to 1,
                                                                "saldoRestante" to "manual",
                                                                "lugar" to "Marcado manualmente",
                                                                "firma" to nombreCobrador,
                                                                "metodoPago" to "Manual (Admin)", // Identificador clave para reconocer pagos manuales
                                                                "clienteNombre" to nombreCliente,
                                                                "observaciones" to "Marcado manualmente por administrador - Cuota ${cuota.numero}"
                                                            )

                                                            val batch = db.batch()
                                                            val pagosRef = db.collection("pagos").document()
                                                            val historialRef = db.collection("historial").document()
                                                            val historialGlobalRef = db.collection("historialGlobal").document()

                                                            batch.set(pagosRef, abonoManual)
                                                            batch.set(historialRef, abonoManual)
                                                            batch.set(historialGlobalRef, abonoManual)

                                                            // CORRECCIÓN: Actualizar también el saldo del préstamo
                                                            val prestamoRef = db.collection("prestamos").document(prestamoId)
                                                            val prestamoSnap = prestamoRef.get().await()
                                                            val saldoActual = prestamoSnap.getDouble("saldo") ?: 0.0
                                                            val montoPagadoActual = prestamoSnap.getDouble("montoPagado") ?: 0.0

                                                            val nuevoSaldo = (saldoActual - montoAPagar).coerceAtLeast(0.0)
                                                            val nuevoMontoPagado = montoPagadoActual + montoAPagar

                                                            batch.update(prestamoRef, mapOf(
                                                                "saldo" to nuevoSaldo,
                                                                "montoPagado" to nuevoMontoPagado,
                                                                "fechaUltimaActualizacion" to Timestamp.now()
                                                            ))

                                                            batch.commit().await()

                                                            Log.d("CuotasScreen", """
                                                                CUOTA MARCADA MANUALMENTE:
                                                                - Cuota ${cuota.numero}: L. ${String.format("%.2f", montoAPagar)}
                                                                - Nuevo saldo: L. ${String.format("%.2f", nuevoSaldo)}
                                                                - Nuevo monto pagado: L. ${String.format("%.2f", nuevoMontoPagado)}
                                                            """.trimIndent())
                                                        }

                                                        Toast.makeText(context, "Cuota marcada como pagada exitosamente", Toast.LENGTH_SHORT).show()

                                                        // Recalcular estado completo de cuotas usando la función corregida
                                                        val cuotasBase = cuotas.map { it.copy(montoParcialPagado = 0.0, pagado = false, esParcial = false) }
                                                        cuotas = calcularEstadoCuotasConParciales(db, prestamoId, cuotasBase)

                                                        // Actualizar próximo pago
                                                        scope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                val proximaFecha = encontrarProximaCuotaSinPagar(db, prestamoId, cuotas)
                                                                if (proximaFecha != null) {
                                                                    db.collection("prestamos").document(prestamoId)
                                                                        .update("proximoPago", proximaFecha).await()
                                                                    proximoPagoProgramado = proximaFecha
                                                                }
                                                            }
                                                        }

                                                        // Verificar si está completamente saldado
                                                        val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                                                        val todasPagadas = cuotasNormales.all { it.pagado }
                                                        val moraCobrada = moraAplicada == 0.0 || cuotas.any { it.descripcion == "Mora" && it.pagado }

                                                        if (todasPagadas && moraCobrada && esActivo) {
                                                            scope.launch {
                                                                withContext(Dispatchers.IO) {
                                                                    db.collection("prestamos").document(prestamoId)
                                                                        .update("estado", "saldado").await()
                                                                }
                                                                estaSaldado = true
                                                                esActivo = false
                                                            }
                                                        }

                                                    } catch (e: Exception) {
                                                        Log.e("CuotasScreen", "Error al marcar pago: ${e.message}", e)
                                                        Toast.makeText(context, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }

                                            }) { Text("Confirmar", color = Color.Red) }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { mostrarDialogo = false }) { Text("Cancelar") }
                                        }
                                    )
                                }

                                // ---- Deshacer pago (Admin) - ACTUALIZADO ----
                                if ((cuota.pagado || cuota.esParcial) && esActivo && rol == "admin") {
                                    var mostrarDialogoDeshacer by remember { mutableStateOf(false) }

                                    Button(
                                        onClick = { mostrarDialogoDeshacer = true },
                                        modifier = Modifier.padding(top = 8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                                    ) {
                                        Text(
                                            if (cuota.esParcial) "Deshacer pagos parciales (Admin)" else "Deshacer pago (Admin)",
                                            color = Color.White
                                        )
                                    }

                                    if (mostrarDialogoDeshacer) {
                                        AlertDialog(
                                            onDismissRequest = { mostrarDialogoDeshacer = false },
                                            title = { Text("Confirmar acción") },
                                            text = {
                                                Text(
                                                    if (cuota.esParcial)
                                                        "Esto eliminará todos los pagos parciales (L. ${dec.format(cuota.montoParcialPagado)}) de esta cuota y restaurará el saldo completo."
                                                    else
                                                        "Esto eliminará los registros de pago de esta cuota y restaurará el saldo."
                                                )
                                            },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    mostrarDialogoDeshacer = false

                                                    scope.launch {
                                                        var montoTotalRestaurado = 0.0 // DECLARAR AQUÍ - FUERA DE withContext

                                                        try {
                                                            withContext(Dispatchers.IO) {
                                                                // Buscar TODOS los pagos que afecten esta cuota
                                                                val pagosQuery1 = db.collection("pagos")
                                                                    .whereEqualTo("prestamoId", prestamoId)
                                                                    .get().await()

                                                                val pagosAEliminar = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()

                                                                // Filtrar pagos que afecten esta cuota específica
                                                                for (pago in pagosQuery1.documents) {
                                                                    val numeroCuotaInicial = when {
                                                                        pago.contains("numeroCuota") -> pago.getLong("numeroCuota")?.toInt() ?: 1
                                                                        pago.contains("cuota") -> pago.getLong("cuota")?.toInt() ?: 1
                                                                        else -> 1
                                                                    }
                                                                    val cuotasCubiertas = pago.getLong("cuotasCubiertas")?.toInt() ?: 1
                                                                    val rangoCuotas = numeroCuotaInicial until (numeroCuotaInicial + cuotasCubiertas)

                                                                    // Si este pago afecta la cuota que queremos deshacer
                                                                    if (cuota.numero in rangoCuotas) {
                                                                        val montoPago = pago.getDouble("monto") ?: 0.0
                                                                        val moraPago = pago.getDouble("mora") ?: 0.0
                                                                        val montoTotal = montoPago + moraPago

                                                                        // Calcular qué parte de este pago corresponde a nuestra cuota
                                                                        val montoPorCuota = if (cuotasCubiertas > 1) {
                                                                            montoTotal / cuotasCubiertas
                                                                        } else {
                                                                            montoTotal
                                                                        }

                                                                        montoTotalRestaurado += montoPorCuota
                                                                        pagosAEliminar.add(pago)

                                                                        Log.d("CuotasScreen", "Pago a eliminar: ${pago.id}, monto total: L. ${String.format("%.2f", montoTotal)}, parte de cuota ${cuota.numero}: L. ${String.format("%.2f", montoPorCuota)}")
                                                                    }
                                                                }

                                                                val batch = db.batch()

                                                                // Eliminar los pagos identificados
                                                                pagosAEliminar.forEach { pago ->
                                                                    batch.delete(pago.reference)
                                                                }

                                                                // Eliminar de historial e historialGlobal
                                                                pagosAEliminar.forEach { pago ->
                                                                    val histQuery = db.collection("historial")
                                                                        .whereEqualTo("prestamoId", prestamoId)
                                                                        .whereEqualTo("fechaPago", pago.get("fechaPago"))
                                                                        .get().await()
                                                                    histQuery.documents.forEach { batch.delete(it.reference) }

                                                                    val globQuery = db.collection("historialGlobal")
                                                                        .whereEqualTo("prestamoId", prestamoId)
                                                                        .whereEqualTo("fechaPago", pago.get("fechaPago"))
                                                                        .get().await()
                                                                    globQuery.documents.forEach { batch.delete(it.reference) }
                                                                }

                                                                // Restaurar saldo del préstamo
                                                                val prestamoRef = db.collection("prestamos").document(prestamoId)
                                                                val prestamoSnap = prestamoRef.get().await()
                                                                val saldoActual = prestamoSnap.getDouble("saldo") ?: 0.0
                                                                val montoPagadoActual = prestamoSnap.getDouble("montoPagado") ?: 0.0

                                                                val nuevoSaldo = saldoActual + montoTotalRestaurado
                                                                val nuevoMontoPagado = (montoPagadoActual - montoTotalRestaurado).coerceAtLeast(0.0)

                                                                val actualizacionPrestamo = mapOf(
                                                                    "saldo" to nuevoSaldo,
                                                                    "montoPagado" to nuevoMontoPagado,
                                                                    "estado" to if (nuevoSaldo > 0.0) "activo" else "saldado",
                                                                    "fechaUltimaActualizacion" to Timestamp.now()
                                                                )

                                                                batch.update(prestamoRef, actualizacionPrestamo)
                                                                batch.commit().await()

                                                                Log.d("CuotasScreen", """
                                                                    PAGOS DESHECHOS EXITOSAMENTE:
                                                                    - Monto total restaurado: L. ${String.format("%.2f", montoTotalRestaurado)}
                                                                    - Nuevo saldo: L. ${String.format("%.2f", nuevoSaldo)}
                                                                    - Nuevo monto pagado: L. ${String.format("%.2f", nuevoMontoPagado)}
                                                                """.trimIndent())
                                                            }

                                                            Toast.makeText(
                                                                context,
                                                                "Pagos deshechos correctamente (L. ${dec.format(montoTotalRestaurado)} restaurado)",
                                                                Toast.LENGTH_LONG
                                                            ).show()

                                                            // Recalcular todas las cuotas desde cero
                                                            val cuotasBase = cuotas.map {
                                                                it.copy(
                                                                    montoParcialPagado = 0.0,
                                                                    pagado = false,
                                                                    esParcial = false
                                                                )
                                                            }
                                                            cuotas = calcularEstadoCuotasConParciales(db, prestamoId, cuotasBase)

                                                            if (cuota.descripcion == "Mora") moraPagada = false

                                                            // Actualizar próximo pago y estado
                                                            scope.launch {
                                                                withContext(Dispatchers.IO) {
                                                                    val proximaFecha = encontrarProximaCuotaSinPagar(db, prestamoId, cuotas)
                                                                    if (proximaFecha != null) {
                                                                        db.collection("prestamos").document(prestamoId)
                                                                            .update("proximoPago", proximaFecha).await()
                                                                        proximoPagoProgramado = proximaFecha
                                                                    }
                                                                }
                                                            }

                                                            // Verificar estado de saldado
                                                            val cuotasNormales = cuotas.filter { it.descripcion != "Mora" }
                                                            val todasPagadasDespues = cuotasNormales.all { it.pagado }

                                                            if (estaSaldado && !todasPagadasDespues) {
                                                                estaSaldado = false
                                                                esActivo = true
                                                            }

                                                        } catch (e: Exception) {
                                                            Log.e("CuotasScreen", "Error al deshacer pagos: ${e.message}", e)
                                                            Toast.makeText(context, "Error al deshacer: ${e.message}", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }) { Text("Sí, deshacer", color = Color.Red) }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { mostrarDialogoDeshacer = false }) { Text("Cancelar") }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ---- Botones de navegación ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Regresar")
                    }

                    Button(
                        onClick = {
                            // Navegar de vuelta a registrar pago si es necesario
                            navController.popBackStack()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) {
                        Text("Registrar Pago", color = Color.White)
                    }
                }
            }
        }
    }
}