package com.example.minifinancieraapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.capitalexpressapp.util.ReciboHelper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

fun calcularProximaFechaPago(plazo: String, cuotasCubiertas: Int, fechaActual: Calendar): String {
    val proximoCal = fechaActual.clone() as Calendar
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    when (plazo) {
        "Diario" -> {
            // Incluye domingos
            proximoCal.add(Calendar.DAY_OF_YEAR, cuotasCubiertas)
        }
        "Lunes a Sábado" -> {
            // Excluye domingos - avanzar día por día evitando domingos
            var diasAvanzados = 0
            while (diasAvanzados < cuotasCubiertas) {
                proximoCal.add(Calendar.DAY_OF_YEAR, 1)
                if (proximoCal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
                    diasAvanzados++
                }
            }
        }
        "Semanal" -> {
            proximoCal.add(Calendar.WEEK_OF_YEAR, cuotasCubiertas)
        }
        "Quincenal" -> {
            proximoCal.add(Calendar.DAY_OF_YEAR, 15 * cuotasCubiertas)
        }
        "Mensual" -> {
            val diaOriginal = proximoCal.get(Calendar.DAY_OF_MONTH)
            proximoCal.add(Calendar.MONTH, cuotasCubiertas)
            val maxDay = proximoCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            proximoCal.set(Calendar.DAY_OF_MONTH, diaOriginal.coerceAtMost(maxDay))
        }
        "Bimestral" -> {
            val diaOriginal = proximoCal.get(Calendar.DAY_OF_MONTH)
            proximoCal.add(Calendar.MONTH, 2 * cuotasCubiertas)
            val maxDay = proximoCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            proximoCal.set(Calendar.DAY_OF_MONTH, diaOriginal.coerceAtMost(maxDay))
        }
        else -> {
            // Fallback para plazos no reconocidos
            proximoCal.add(Calendar.MONTH, cuotasCubiertas)
        }
    }

    return formatter.format(proximoCal.time)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditarPagoScreen(
    navController: NavController,
    prestamoId: String,
    cuota: String,
    clienteNombre: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()

    var montoAbono by remember { mutableStateOf("") }
    var lugar by remember { mutableStateOf("") }
    var firmaPrestamista by remember { mutableStateOf("") }
    var tipoPago by remember { mutableStateOf("Efectivo") }
    var mora by remember { mutableStateOf("") }
    var fecha by remember { mutableStateOf("") }
    var archivoPDF: File? by remember { mutableStateOf(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Variables para información del préstamo
    var plazoOriginal by remember { mutableStateOf("") }
    var cuotaEstimada by remember { mutableStateOf(0.0) }
    var saldoActual by remember { mutableStateOf(0.0) }

    val opcionesPago = listOf("Efectivo", "Transferencia")
    var expandedTipoPago by remember { mutableStateOf(false) }

    val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    LaunchedEffect(Unit) {
        try {
            isLoading = true

            // Cargar información del préstamo
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
            if (prestamoDoc.exists()) {
                plazoOriginal = prestamoDoc.getString("plazo") ?: ""
                cuotaEstimada = prestamoDoc.getDouble("cuota") ?: 0.0
                // "saldo" es el campo de saldo vivo que usa el resto de la app
                // (Cuotas, VerPrestamo, Notificaciones, RegistrarPago); "saldoAnterior"
                // es el monto original del préstamo al desembolsarlo, no el saldo actual.
                saldoActual = prestamoDoc.getDouble("saldo") ?: 0.0

                if (saldoActual == 0.0) {
                    saldoActual = prestamoDoc.getDouble("totalPagar") ?: 0.0
                }
            }

            // Cargar información del pago
            val snapshot = db.collection("pagos")
                .whereEqualTo("prestamoId", prestamoId)
                .whereEqualTo("cuotas", cuota)
                .whereEqualTo("clienteNombre", clienteNombre)
                .get().await()

            val doc = snapshot.documents.firstOrNull()
            if (doc != null) {
                montoAbono = doc.getDouble("monto")?.toString() ?: ""
                lugar = doc.getString("lugar") ?: ""
                firmaPrestamista = doc.getString("firma") ?: ""
                tipoPago = doc.getString("tipoPago") ?: "Efectivo"
                mora = doc.getDouble("mora")?.toString() ?: ""
                fecha = when (val raw = doc.get("fechaPago")) {
                    is com.google.firebase.Timestamp -> formatter.format(raw.toDate())
                    is String -> raw
                    else -> ""
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar pago: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editar Pago") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Información del préstamo
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("Información del Préstamo", style = MaterialTheme.typography.titleMedium)
                        Text("Cliente: $clienteNombre")
                        Text("Préstamo ID: $prestamoId")
                        Text("Cuota: $cuota")
                        Text("Plazo: $plazoOriginal")
                        Text("Cuota estimada: L. ${"%.2f".format(cuotaEstimada)}")
                        Text("Fecha: $fecha")
                    }
                }

                OutlinedTextField(
                    value = montoAbono,
                    onValueChange = { montoAbono = it },
                    label = { Text("Monto del abono") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                OutlinedTextField(
                    value = mora,
                    onValueChange = { mora = it },
                    label = { Text("Mora adicional") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                OutlinedTextField(
                    value = lugar,
                    onValueChange = { lugar = it },
                    label = { Text("Lugar de emisión") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = firmaPrestamista,
                    onValueChange = { firmaPrestamista = it },
                    label = { Text("Firma del prestamista") },
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = expandedTipoPago,
                    onExpandedChange = { expandedTipoPago = !expandedTipoPago }
                ) {
                    OutlinedTextField(
                        value = tipoPago,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tipo de pago") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedTipoPago) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedTipoPago,
                        onDismissRequest = { expandedTipoPago = false }
                    ) {
                        opcionesPago.forEach { opcion ->
                            DropdownMenuItem(
                                text = { Text(opcion) },
                                onClick = {
                                    tipoPago = opcion
                                    expandedTipoPago = false
                                }
                            )
                        }
                    }
                }

                // Mostrar cálculo del pago
                val nuevoMonto = montoAbono.toDoubleOrNull() ?: 0.0
                val nuevaMora = mora.toDoubleOrNull() ?: 0.0
                val totalPago = nuevoMonto + nuevaMora

                if (totalPago > 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text("Resumen del Pago", style = MaterialTheme.typography.titleMedium)
                            Text("Monto: L. ${"%.2f".format(nuevoMonto)}")
                            Text("Mora: L. ${"%.2f".format(nuevaMora)}")
                            Text("Total: L. ${"%.2f".format(totalPago)}")
                            if (cuotaEstimada > 0) {
                                val cuotasCubiertas = (totalPago / cuotaEstimada).toInt().coerceAtLeast(1)
                                Text("Cuotas cubiertas: $cuotasCubiertas")
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                isLoading = true
                                val nuevoMonto = montoAbono.toDoubleOrNull() ?: 0.0
                                val nuevaMora = mora.toDoubleOrNull() ?: 0.0
                                val totalPago = nuevoMonto + nuevaMora

                                if (totalPago <= 0) {
                                    Toast.makeText(context, "El monto debe ser mayor a 0", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }

                                // Ubicar el pago exacto que se está editando
                                val pagosRef = db.collection("pagos")
                                    .whereEqualTo("prestamoId", prestamoId)
                                    .whereEqualTo("cuotas", cuota)
                                    .whereEqualTo("clienteNombre", clienteNombre)
                                    .get().await()
                                val docId = pagosRef.documents.firstOrNull()?.id

                                if (docId == null) {
                                    Toast.makeText(context, "Pago no encontrado", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }

                                val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()
                                val montoBase = prestamoDoc.getDouble("monto") ?: 0.0
                                val interesTotalBase = prestamoDoc.getDouble("interesTotal")
                                    ?: prestamoDoc.getDouble("interes") ?: 0.0
                                val totalPagarDoc = prestamoDoc.getDouble("totalPagar") ?: (montoBase + interesTotalBase)
                                val moraActualDoc = prestamoDoc.getDouble("mora") ?: 0.0
                                val cuotaEstimacion = prestamoDoc.getDouble("cuota") ?: 0.0
                                val plazo = prestamoDoc.getString("plazo") ?: "Mensual"

                                // Recalcular el saldo desde CERO con todos los pagos del préstamo
                                // (misma fórmula que VerPrestamoScreen: saldo = base - cuotasPagadas + moraPendiente),
                                // usando el monto/mora YA EDITADOS para este pago en particular. Restar
                                // solo el total editado de un "saldo" leído a mitad de camino duplicaba
                                // la porción no editada del pago -este recálculo completo lo evita-.
                                val todosPagos = db.collection("pagos")
                                    .whereEqualTo("prestamoId", prestamoId)
                                    .get().await()
                                var totalCuotasPagadas = 0.0
                                var totalMoraPagada = 0.0
                                for (p in todosPagos.documents) {
                                    val esEstePago = p.id == docId
                                    totalCuotasPagadas += if (esEstePago) nuevoMonto else (p.getDouble("monto") ?: 0.0)
                                    totalMoraPagada += if (esEstePago) nuevaMora else (p.getDouble("mora") ?: 0.0)
                                }
                                var moraHistorica = moraActualDoc
                                if (moraActualDoc > 0 && moraActualDoc < totalMoraPagada) {
                                    moraHistorica = totalMoraPagada + moraActualDoc
                                }
                                val moraPendiente = (moraHistorica - totalMoraPagada).coerceAtLeast(0.0)
                                val nuevoSaldo = (totalPagarDoc - totalCuotasPagadas + moraPendiente).coerceAtLeast(0.0)

                                val cuotasCubiertas = if (cuotaEstimacion > 0) {
                                    (totalPago / cuotaEstimacion).toInt().coerceAtLeast(1)
                                } else {
                                    1
                                }
                                val calendario = Calendar.getInstance()
                                val proximaFecha = calcularProximaFechaPago(plazo, cuotasCubiertas, calendario)

                                db.collection("pagos").document(docId).update(
                                    mapOf(
                                        "monto" to nuevoMonto,
                                        "mora" to nuevaMora,
                                        "lugar" to lugar,
                                        "firma" to firmaPrestamista,
                                        "tipoPago" to tipoPago,
                                        "saldoRestante" to nuevoSaldo,
                                        "fechaActualizacion" to com.google.firebase.Timestamp.now()
                                    )
                                ).await()

                                val updateData = mutableMapOf<String, Any>(
                                    "saldo" to nuevoSaldo,
                                    "montoPagado" to (totalCuotasPagadas + totalMoraPagada),
                                    "estado" to if (nuevoSaldo <= 0.01) "saldado" else "activo",
                                    "fechaActualizacion" to com.google.firebase.Timestamp.now()
                                )

                                if (nuevoSaldo > 0.01) {
                                    updateData["proximoPago"] = proximaFecha
                                } else {
                                    updateData["proximoPago"] = ""
                                    updateData["fechaCancelacion"] = com.google.firebase.Timestamp.now()
                                }

                                db.collection("prestamos").document(prestamoId).update(updateData).await()

                                Toast.makeText(context, "Pago actualizado correctamente", Toast.LENGTH_SHORT).show()
                                navController.popBackStack()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Guardar Cambios")
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val nuevoMonto = montoAbono.toDoubleOrNull() ?: 0.0
                                val nuevaMora = mora.toDoubleOrNull() ?: 0.0

                                archivoPDF = ReciboHelper.generarReciboPDF(
                                    context = context,
                                    cliente = clienteNombre,
                                    prestamoId = prestamoId,
                                    fecha = fecha,
                                    montoPagado = nuevoMonto.toString(),
                                    saldoAnterior = saldoActual,
                                    proximoPago = "-",
                                    cuota = cuota,
                                    cobrador = firmaPrestamista,
                                    lugar = lugar,
                                    firma = firmaPrestamista,
                                    tipoPago = tipoPago,
                                    mora = nuevaMora
                                )
                                archivoPDF?.let {
                                    ReciboHelper.imprimirPDF(context, it)
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al imprimir: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("🖨️ Imprimir Recibo")
                }
            }
        }
    }
}