package com.example.capitalexpressapp.ui.screens

import android.app.DatePickerDialog
import android.content.Context
import android.widget.Toast
import com.example.capitalexpressapp.core.formatearLempiras
import com.example.capitalexpressapp.ui.theme.CEColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.example.capitalexpressapp.util.ReciboHelper
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(navController: NavController) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    var startDate by remember { mutableStateOf<Calendar?>(null) }
    var endDate by remember { mutableStateOf<Calendar?>(null) }

    var totalClientes by remember { mutableStateOf(0) }
    var totalCobros by remember { mutableStateOf(0) }
    var totalPrestado by remember { mutableStateOf(0.0) }
    var totalPagado by remember { mutableStateOf(0.0) }
    var totalPendiente by remember { mutableStateOf(0.0) }
    var totalInteres by remember { mutableStateOf(0.0) }
    var totalMoras by remember { mutableStateOf(0.0) }
    var cantidadMoras by remember { mutableStateOf(0) }
    var pagosFiltrados by remember { mutableStateOf(listOf<Pair<String, Double>>()) }

    val showDatePicker: (Context, Calendar?, (Calendar) -> Unit) -> Unit = { ctx, initial, onDateSelected ->
        val now = initial ?: Calendar.getInstance()
        DatePickerDialog(ctx, { _, y, m, d ->
            onDateSelected(Calendar.getInstance().apply { set(y, m, d) })
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    LaunchedEffect(startDate, endDate) {
        try {
            val clientes = db.collection("clientes").get().await()
            totalClientes = clientes.size()

            val prestamos = db.collection("prestamos").get().await()
            totalPrestado = prestamos.sumOf { it.getDouble("monto") ?: 0.0 }

            totalInteres = prestamos.sumOf {
                it.getDouble("interesTotal") ?: 0.0
            }

            val pagos = db.collection("pagos").get().await()

            val pagosValidados = pagos.mapNotNull {
                val monto = it.getDouble("monto") ?: return@mapNotNull null
                val mora = it.getDouble("mora") ?: 0.0
                val cobrador = it.getString("registradoPor") ?: "Desconocido"
                val fecha = when (val f = it.get("fechaPago")) {
                    is Timestamp -> Calendar.getInstance().apply { time = f.toDate() }
                    is String -> dateFormat.parse(f)?.let {
                        Calendar.getInstance().apply { time = it }
                    }
                    else -> null
                } ?: return@mapNotNull null

                if (startDate != null && endDate != null) {
                    if (fecha.time.before(startDate!!.time) || fecha.time.after(endDate!!.time)) return@mapNotNull null
                }

                Triple(cobrador, monto, mora)
            }

            pagosFiltrados = pagosValidados.map { it.first to it.second }
            totalPagado = pagosValidados.sumOf { it.second }
            totalMoras = pagosValidados.sumOf { it.third }
            cantidadMoras = pagosValidados.count { it.third > 0 }
            totalCobros = pagosValidados.size
            totalPendiente = totalPrestado + totalInteres - totalPagado

        } catch (e: Exception) {
            Toast.makeText(context, "Error cargando datos: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Capital Express Dashboard",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CEColors.Primary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // Header con gradiente
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .height(120.dp)
                ) {
                    // Fondo con gradiente
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        CEColors.ActionBlue,
                                        CEColors.Primary
                                    )
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Analytics,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Resumen Financiero",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Filtros de fecha mejorados
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Filtrar por fecha",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F2937)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { showDatePicker(context, startDate) { startDate = it } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                startDate?.let { "Desde: ${dateFormat.format(it.time)}" } ?: "Fecha Inicio",
                                fontSize = 12.sp
                            )
                        }

                        OutlinedButton(
                            onClick = { showDatePicker(context, endDate) { endDate = it } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                endDate?.let { "Hasta: ${dateFormat.format(it.time)}" } ?: "Fecha Fin",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Métricas principales en grid
            Text(
                "Métricas Principales",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937)
            )

            // Primera fila - Clientes y Cobros
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ModernInfoCard(
                    title = "Clientes",
                    value = totalClientes.toString(),
                    icon = Icons.Default.People,
                    color = Color(0xFF059669),
                    modifier = Modifier.weight(1f)
                )
                ModernInfoCard(
                    title = "Cobros",
                    value = totalCobros.toString(),
                    icon = Icons.Default.Receipt,
                    color = Color(0xFF3B82F6),
                    modifier = Modifier.weight(1f)
                )
            }

            // Segunda fila - Dinero
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ModernInfoCard(
                    title = "Prestado",
                    value = formatearLempiras(totalPrestado),
                    icon = Icons.Default.TrendingUp,
                    color = Color(0xFF8B5CF6),
                    modifier = Modifier.weight(1f)
                )
                ModernInfoCard(
                    title = "Pagado",
                    value = formatearLempiras(totalPagado),
                    icon = Icons.Default.CheckCircle,
                    color = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
            }

            // Tercera fila
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ModernInfoCard(
                    title = "Pendiente",
                    value = formatearLempiras(totalPendiente),
                    icon = Icons.Default.PendingActions,
                    color = Color(0xFFEF4444),
                    modifier = Modifier.weight(1f)
                )
                ModernInfoCard(
                    title = "Interés",
                    value = formatearLempiras(totalInteres),
                    icon = Icons.Default.Percent,
                    color = Color(0xFFF59E0B),
                    modifier = Modifier.weight(1f)
                )
            }

            // Cuarta fila - Moras
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ModernInfoCard(
                    title = "Moras Cobradas",
                    value = formatearLempiras(totalMoras),
                    icon = Icons.Default.Warning,
                    color = Color(0xFFDC2626),
                    modifier = Modifier.weight(1f)
                )
                ModernInfoCard(
                    title = "Cant. Moras",
                    value = cantidadMoras.toString(),
                    icon = Icons.Default.ErrorOutline,
                    color = Color(0xFFB91C1C),
                    modifier = Modifier.weight(1f)
                )
            }

            // Gráficos
            Text(
                "Análisis Visual",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937)
            )

            // Gráfico circular
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Distribución Financiera",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F2937)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    AndroidView(
                        factory = { PieChart(it).apply {
                            description.isEnabled = false
                            isDrawHoleEnabled = true
                            holeRadius = 40f
                            transparentCircleRadius = 45f
                            legend.isEnabled = true
                            legend.textSize = 12f
                        } },
                        update = { chart ->
                            val entries = listOf(
                                PieEntry(totalPagado.toFloat(), "Pagado"),
                                PieEntry(totalPendiente.toFloat(), "Pendiente"),
                                PieEntry(totalInteres.toFloat(), "Interés")
                            ).filter { it.value > 0 }

                            chart.data = PieData(PieDataSet(entries, "").apply {
                                colors = listOf(
                                    android.graphics.Color.parseColor("#10B981"),
                                    android.graphics.Color.parseColor("#EF4444"),
                                    android.graphics.Color.parseColor("#F59E0B")
                                )
                                valueTextSize = 14f
                                valueTextColor = android.graphics.Color.WHITE
                            })
                            chart.invalidate()
                        },
                        modifier = Modifier.height(280.dp).fillMaxWidth()
                    )
                }
            }

            // Gráfico de barras
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Cobros por Cobrador",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F2937)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    AndroidView(
                        factory = { BarChart(it).apply {
                            description.isEnabled = false
                            legend.isEnabled = false
                            xAxis.setDrawGridLines(false)
                            axisLeft.setDrawGridLines(true)
                            axisRight.isEnabled = false
                        } },
                        update = { chart ->
                            val dataMap = pagosFiltrados.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
                            val entries = dataMap.entries.mapIndexed { i, entry -> BarEntry(i.toFloat(), entry.value.toFloat()) }
                            val labels = dataMap.keys.toList()

                            chart.data = BarData(BarDataSet(entries, "Cobros").apply {
                                colors = listOf(
                                    android.graphics.Color.parseColor("#3B82F6"),
                                    android.graphics.Color.parseColor("#8B5CF6"),
                                    android.graphics.Color.parseColor("#10B981"),
                                    android.graphics.Color.parseColor("#F59E0B"),
                                    android.graphics.Color.parseColor("#EF4444")
                                )
                                valueTextSize = 12f
                                valueTextColor = android.graphics.Color.BLACK
                            })

                            chart.xAxis.apply {
                                valueFormatter = IndexAxisValueFormatter(labels)
                                granularity = 1f
                                setDrawLabels(true)
                                labelRotationAngle = -45f
                                textSize = 10f
                            }

                            chart.invalidate()
                        },
                        modifier = Modifier.height(300.dp).fillMaxWidth()
                    )
                }
            }

            // Botón de exportar mejorado
            Button(
                onClick = {
                    val file = ReciboHelper.generarResumenDashboardPDF(
                        context,
                        totalClientes,
                        totalCobros,
                        totalPrestado,
                        totalPagado,
                        totalPendiente,
                        totalInteres,
                        totalMoras,
                        cantidadMoras,
                        filtro = "${startDate?.let { dateFormat.format(it.time) } ?: "-"} a ${endDate?.let { dateFormat.format(it.time) } ?: "-"}"
                    )
                    if (file != null) ReciboHelper.compartirReciboPDF(context, file)
                    else Toast.makeText(context, "Error al generar PDF", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CEColors.Primary
                )
            ) {
                Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Exportar Resumen PDF",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun ModernInfoCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF6B7280),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun InfoCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFBBDEFB)),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Color(0xFF0D47A1))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

fun showDatePicker(context: android.content.Context, onDateSelected: (Calendar) -> Unit) {
    val calendar = Calendar.getInstance()
    DatePickerDialog(
        context,
        { _, y, m, d ->
            val selected = Calendar.getInstance()
            selected.set(y, m, d)
            onDateSelected(selected)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
fun DatePickerButton(
    label: String,
    selectedDate: Calendar,
    onDateSelected: (Calendar) -> Unit
) {
    val context = LocalContext.current
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val dateString = remember(selectedDate.timeInMillis) { formatter.format(selectedDate.time) }

    Button(onClick = {
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val newCal = Calendar.getInstance().apply { set(year, month, day) }
                onDateSelected(newCal)
            },
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH),
            selectedDate.get(Calendar.DAY_OF_MONTH)
        ).show()
    }) {
        Text("$label: $dateString")
    }
}