package com.example.minifinancieraapp.ui.screens

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.example.capitalexpressapp.core.formatearLempiras
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.minifinancieraapp.ui.components.ImprimirOpcionesDialog
import com.example.minifinancieraapp.ui.components.intentarImprimirConRespaldo
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.UUID
import kotlin.math.roundToInt

// ─────────────────────────────────────────────
//  PALETA — idéntica a CrearPrestamoScreen
// ─────────────────────────────────────────────
private object EP {
    val Navy       = Color(0xFF0A1628)
    val GradStart  = Color(0xFF0A1628)
    val GradEnd    = Color(0xFF1A3A6B)
    val Blue       = Color(0xFF1A56DB)
    val BlueSoft   = Color(0xFF3B82F6)
    val BlueLight  = Color(0xFFEFF6FF)

    val Green      = Color(0xFF10B981)
    val GreenSoft  = Color(0xFFECFDF5)
    val Red        = Color(0xFFEF4444)
    val RedSoft    = Color(0xFFFEF2F2)
    val Orange     = Color(0xFFF97316)
    val OrangeSoft = Color(0xFFFFF7ED)
    val Amber      = Color(0xFFF59E0B)
    val AmberSoft  = Color(0xFFFFFBEB)

    val Surface    = Color(0xFFF8FAFC)
    val Card       = Color(0xFFFFFFFF)
    val Border     = Color(0xFFE2E8F0)
    val Text       = Color(0xFF0F172A)
    val TextSec    = Color(0xFF64748B)
    val TextMuted  = Color(0xFF94A3B8)
}

// Función para calcular días efectivos según el tipo de plazo
fun calcularDiasEfectivosEditar(plazo: String, cuotas: Int, fechaInicio: Calendar): Int {
    return when (plazo) {
        "Diario"         -> cuotas
        "Lunes a Sábado" -> contarDiasSinDomingosEditar(cuotas, fechaInicio)
        "Semanal"        -> contarDiasSinDomingosEditar(cuotas * 6, fechaInicio)
        "Quincenal"      -> cuotas * 15
        "Mensual"        -> cuotas * 30
        "Bimestral"      -> cuotas * 60
        else             -> cuotas * 30
    }
}

private fun contarDiasSinDomingosEditar(diasNecesarios: Int, fechaInicio: Calendar): Int {
    var diasEfectivos = 0
    var diasTotales   = 0
    val fecha = fechaInicio.clone() as Calendar
    while (diasEfectivos < diasNecesarios) {
        if (fecha.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) diasEfectivos++
        diasTotales++
        fecha.add(Calendar.DAY_OF_YEAR, 1)
    }
    return diasTotales
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditarPrestamoScreen(navController: NavController, prestamoId: String) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val db        = FirebaseFirestore.getInstance()
    val storage   = FirebaseStorage.getInstance()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // Estados principales
    var cliente         by remember { mutableStateOf("") }
    var monto           by remember { mutableStateOf("") }
    var interesMensual   by remember { mutableStateOf("") }
    var cuotas           by remember { mutableStateOf("") }
    var mora             by remember { mutableStateOf("") }
    var interesTotal      by remember { mutableStateOf("") }
    var usarInteresMensual by remember { mutableStateOf(true) }

    var selectedPlazo   by remember { mutableStateOf("Semanal") }
    var lugar           by remember { mutableStateOf("") }
    var firma           by remember { mutableStateOf("") }
    var observaciones   by remember { mutableStateOf("") }
    var estado          by remember { mutableStateOf("activo") }
    var isLoading       by remember { mutableStateOf(false) }
    var isLoadingDatos   by remember { mutableStateOf(true) }
    var reciboGenerado  by remember { mutableStateOf<File?>(null) }
    var fechaGuardada   by remember { mutableStateOf("") }

    // Diálogo "Imprimir directo o solo PDF"
    var mostrarDialogoImprimir by remember { mutableStateOf(false) }
    var accionImprimirDirecto by remember { mutableStateOf<(suspend () -> Boolean)?>(null) }
    var accionSoloPdf by remember { mutableStateOf<(() -> Unit)?>(null) }

    var currentUid      by remember { mutableStateOf("") }
    var nombreCobrador  by remember { mutableStateOf("") }
    var numeroCobrador  by remember { mutableStateOf("") }

    var fechaSeleccionada by remember { mutableStateOf(Calendar.getInstance()) }
    var fotosSeleccionadas by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var fotoUriCamara    by remember { mutableStateOf<Uri?>(null) }

    // ✅ NUEVO: estado financiero real (fix del bug de saldo)
    var montoPagadoReal    by remember { mutableStateOf(0.0) }     // calculado desde la colección "pagos"
    var saldoPendienteTexto by remember { mutableStateOf("") }     // editable, precargado con el valor real
    var saldoCalculadoAuto  by remember { mutableStateOf(0.0) }    // valor "verdad" recalculado en vivo

    // ── Auth ──────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        try {
            val auth    = com.google.firebase.auth.FirebaseAuth.getInstance()
            val session = com.example.minifinancieraapp.util.SessionManager(context)
            var uid: String? = auth.currentUser?.uid
            if (uid.isNullOrEmpty()) uid = session.getUid()
            if (!uid.isNullOrEmpty()) {
                currentUid = uid
                val userDoc = db.collection("usuarios").document(uid).get().await()
                nombreCobrador = userDoc.getString("nombre") ?: "Administrador"
                numeroCobrador = userDoc.getString("telefono") ?: "N/D"
            } else {
                nombreCobrador = "Administrador"
                numeroCobrador = "N/D"
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar datos del usuario: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Cámara / galería ─────────────────────────────────────────────────
    val crearArchivoTemporal = remember {
        {
            try {
                val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val dir  = context.getExternalFilesDir("Pictures")?.also { if (!it.exists()) it.mkdirs() }
                val file = File.createTempFile("JPEG_${ts}_", ".jpg", dir)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) { null }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) fotosSeleccionadas = fotosSeleccionadas + uris
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && fotoUriCamara != null) {
            fotosSeleccionadas = fotosSeleccionadas + listOf(fotoUriCamara!!)
            Toast.makeText(context, "Foto capturada exitosamente", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No se pudo capturar la foto", Toast.LENGTH_SHORT).show()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            crearArchivoTemporal()?.let { uri -> fotoUriCamara = uri; cameraLauncher.launch(uri) }
                ?: Toast.makeText(context, "Error al crear archivo temporal", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permiso de cámara necesario para tomar fotos", Toast.LENGTH_SHORT).show()
        }
    }

    // ── CARGA DE DATOS (con fix: monto pagado real desde "pagos") ─────────
    LaunchedEffect(prestamoId) {
        isLoadingDatos = true
        try {
            val doc = db.collection("prestamos").document(prestamoId).get().await()
            cliente        = doc.getString("cliente") ?: ""
            monto          = doc.getDouble("monto")?.toString() ?: ""
            interesMensual = doc.getDouble("interesMensual")?.toString() ?: ""
            cuotas         = doc.getLong("cuotas")?.toString() ?: ""
            mora           = doc.getDouble("mora")?.toString() ?: ""
            selectedPlazo  = doc.getString("plazo") ?: "Semanal"
            lugar          = doc.getString("lugar") ?: ""
            firma          = doc.getString("firma") ?: ""
            observaciones  = doc.getString("observaciones") ?: ""
            estado         = doc.getString("estado") ?: "activo"

            val usarInteresMensualDoc = doc.getBoolean("usarInteresMensual")
            if (usarInteresMensualDoc != null) {
                usarInteresMensual = usarInteresMensualDoc
                if (usarInteresMensual) {
                    interesMensual = doc.getDouble("interesMensual")?.toString() ?: ""
                    interesTotal   = ""
                } else {
                    interesTotal = doc.getDouble("interesTotalFijo")?.toString() ?: ""
                    interesMensual = ""
                }
            } else {
                val interesTotalDoc  = doc.getDouble("interesTotal") ?: 0.0
                val interesMensualDoc = doc.getDouble("interesMensual") ?: 0.0
                if (interesTotalDoc > 0 && interesMensualDoc == 0.0) {
                    usarInteresMensual = false
                    interesTotal = interesTotalDoc.toString()
                } else {
                    usarInteresMensual = true
                    interesMensual = interesMensualDoc.toString()
                }
            }

            try {
                doc.getTimestamp("fecha")?.let { fechaSeleccionada.time = it.toDate() }
            } catch (_: Exception) { }

            val fotosUrls = doc.get("fotos") as? List<String> ?: emptyList()
            fotosSeleccionadas = fotosUrls.map { Uri.parse(it) }

            // ✅ FIX PRINCIPAL: calcular el monto REALMENTE pagado desde "pagos",
            // no confiar en el campo "montoPagado"/"saldo" guardado en el préstamo.
            val pagosSnap = db.collection("pagos")
                .whereEqualTo("prestamoId", prestamoId)
                .get().await()
            var pagadoReal = 0.0
            for (pago in pagosSnap.documents) {
                pagadoReal += (pago.getDouble("monto") ?: 0.0) + (pago.getDouble("mora") ?: 0.0)
            }
            montoPagadoReal = pagadoReal

            // Saldo real = totalPagar (base) + mora acumulada - lo que ya se pagó.
            val totalPagarBase = doc.getDouble("totalPagar")
                ?: ((doc.getDouble("monto") ?: 0.0) + (doc.getDouble("interesTotal") ?: 0.0))
            val moraAcumulada  = doc.getDouble("mora") ?: 0.0
            val saldoReal = (totalPagarBase + moraAcumulada - montoPagadoReal).coerceAtLeast(0.0)
            saldoCalculadoAuto  = saldoReal
            saldoPendienteTexto = "%.2f".format(saldoReal)

        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar datos: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isLoadingDatos = false
        }
    }

    // ── Cálculos en vivo (idénticos a Crear) ───────────────────────────────
    val montoDouble        = monto.toDoubleOrNull() ?: 0.0
    val interesPct         = interesMensual.toDoubleOrNull() ?: 0.0
    val cuotasInt           = cuotas.toIntOrNull() ?: 1
    val interesTotalDouble  = interesTotal.toDoubleOrNull() ?: 0.0
    val moraDouble          = mora.toDoubleOrNull() ?: 0.0

    val fechaInicio    = fechaSeleccionada.clone() as Calendar
    val diasEfectivos  = calcularDiasEfectivosEditar(selectedPlazo, cuotasInt, fechaInicio)
    val mesesAprox     = diasEfectivos / 30.0

    val proximoCal = (fechaSeleccionada.clone() as Calendar).also { cal ->
        when (selectedPlazo) {
            "Diario"         -> cal.add(Calendar.DAY_OF_YEAR, 1)
            "Lunes a Sábado" -> { do { cal.add(Calendar.DAY_OF_YEAR, 1) } while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) }
            "Semanal"        -> cal.add(Calendar.DAY_OF_YEAR, 7)
            "Quincenal"      -> cal.add(Calendar.DAY_OF_YEAR, 15)
            "Mensual"        -> cal.add(Calendar.MONTH, 1)
            "Bimestral"      -> cal.add(Calendar.MONTH, 2)
        }
    }
    val proximoPagoString    = formatter.format(proximoCal.time)
    val proximoPagoTimestamp = Timestamp(proximoCal.time)

    val (interesCalculado, totalAPagar, cuotaEstimada) = when {
        !usarInteresMensual && interesTotalDouble > 0 -> {
            val total = montoDouble + interesTotalDouble
            Triple(interesTotalDouble, total, if (cuotasInt > 0) (total / cuotasInt).roundToInt().toDouble() else 0.0)
        }
        usarInteresMensual && interesPct > 0 -> {
            val intMes = montoDouble * (interesPct / 100)
            val intCalc = when (selectedPlazo) {
                "Mensual"        -> intMes * cuotasInt
                "Lunes a Sábado" -> intMes * (diasEfectivos / 6.0 / 4.0)
                "Semanal"        -> intMes * (cuotasInt / 4.0)
                else             -> intMes * (diasEfectivos / 30.0)
            }
            val total = montoDouble + intCalc
            Triple(intCalc, total, if (cuotasInt > 0) (total / cuotasInt).roundToInt().toDouble() else 0.0)
        }
        else -> Triple(0.0, montoDouble, montoDouble / cuotasInt.coerceAtLeast(1))
    }

    // ✅ Saldo real recalculado en vivo cada vez que cambian monto/interés/cuotas/mora,
    // SIEMPRE en base al pago real ya registrado (montoPagadoReal), no a lo que diga
    // el documento. El campo de texto se re-sincroniza solo si el usuario no lo ha
    // tocado manualmente (para permitir corrección manual si es necesario).
    val totalConMoraNuevo = totalAPagar + moraDouble
    val saldoRealVivo = (totalConMoraNuevo - montoPagadoReal).coerceAtLeast(0.0)

    var saldoTocadoManualmente by remember { mutableStateOf(false) }
    LaunchedEffect(totalAPagar, moraDouble, montoPagadoReal) {
        if (!saldoTocadoManualmente) {
            saldoCalculadoAuto = saldoRealVivo
            saldoPendienteTexto = "%.2f".format(saldoRealVivo)
        }
    }

    val descripcionPlazo = when (selectedPlazo) {
        "Diario" -> "Incluye domingos"
        "Lunes a Sábado" -> "Excluye domingos"
        "Semanal" -> "Cada 7 días"
        "Quincenal" -> "Cada 15 días"
        "Mensual" -> "Cada 30 días"
        "Bimestral" -> "Cada 60 días"
        else -> ""
    }

    // ── UI ──────────────────────────────────────────────────────────────
    Scaffold(
        containerColor = EP.Surface,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(EP.GradStart, EP.GradEnd)))
            ) {
                Row(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Editar Préstamo",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = (-0.3).sp
                        )
                        Text(
                            "Corrige los datos del crédito",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    ) { padding ->

        if (isLoadingDatos) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = EP.Blue) }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── SECCIÓN 1: CLIENTE (solo lectura) ─────────
            FormSectionEditar(numero = "1", title = "Cliente", icon = Icons.Default.Person, color = EP.Blue) {
                CPTextFieldEditar(
                    value = cliente, onValueChange = {}, label = "Cliente",
                    icon = Icons.Default.Person, enabled = false
                )
            }

            // ── SECCIÓN 2: MONTO Y PLAZO ──────────────────
            FormSectionEditar(numero = "2", title = "Monto y Plazo", icon = Icons.Default.AttachMoney, color = EP.Green) {
                CPTextFieldEditar(
                    value = monto, onValueChange = { monto = it }, label = "Monto del préstamo",
                    prefix = "L.", keyboardType = KeyboardType.Number, icon = Icons.Default.MonetizationOn
                )
                Spacer(Modifier.height(4.dp))
                Text("Frecuencia de pago", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = EP.TextSec)
                Spacer(Modifier.height(6.dp))
                PlazoSelectorEditar(
                    opciones = listOf("Diario", "Lunes a Sábado", "Semanal", "Quincenal", "Mensual", "Bimestral"),
                    seleccionado = selectedPlazo,
                    onSeleccionar = { selectedPlazo = it }
                )
                Spacer(Modifier.height(4.dp))
                CPTextFieldEditar(
                    value = cuotas, onValueChange = { cuotas = it }, label = "Número de cuotas",
                    keyboardType = KeyboardType.Number, icon = Icons.Outlined.TableRows
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, day -> fechaSeleccionada = Calendar.getInstance().also { it.set(year, month, day) } },
                            fechaSeleccionada.get(Calendar.YEAR),
                            fechaSeleccionada.get(Calendar.MONTH),
                            fechaSeleccionada.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EP.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EP.Blue)
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fecha de inicio: ${formatter.format(fechaSeleccionada.time)}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = EP.TextMuted)
                }
            }

            // ── SECCIÓN 3: INTERÉS ────────────────────────
            FormSectionEditar(numero = "3", title = "Interés", icon = Icons.Default.Percent, color = Color(0xFF8B5CF6)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(EP.Border.copy(alpha = 0.4f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ToggleTabEditar("% Mensual", usarInteresMensual, Modifier.weight(1f)) {
                        usarInteresMensual = true; interesTotal = ""
                    }
                    ToggleTabEditar("Total fijo (L.)", !usarInteresMensual, Modifier.weight(1f)) {
                        usarInteresMensual = false; interesMensual = ""
                    }
                }
                Spacer(Modifier.height(10.dp))
                AnimatedContent(targetState = usarInteresMensual, label = "interes") { usarMensual ->
                    if (usarMensual) {
                        CPTextFieldEditar(
                            value = interesMensual, onValueChange = { interesMensual = it },
                            label = "Porcentaje mensual", suffix = "%",
                            keyboardType = KeyboardType.Number, icon = Icons.Default.Percent
                        )
                    } else {
                        CPTextFieldEditar(
                            value = interesTotal, onValueChange = { interesTotal = it },
                            label = "Interés total fijo", prefix = "L.",
                            keyboardType = KeyboardType.Number, icon = Icons.Default.MonetizationOn,
                            supportingText = "Se suma directamente al monto"
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                CPTextFieldEditar(
                    value = mora, onValueChange = { mora = it }, label = "Mora acumulada / diaria (L.)",
                    prefix = "L.", keyboardType = KeyboardType.Number, icon = Icons.Outlined.Warning,
                    supportingText = "Este valor afecta directamente el saldo pendiente real de abajo"
                )
            }

            // ── SECCIÓN 4: ESTADO FINANCIERO REAL (FIX DEL BUG) ──
            FormSectionEditar(numero = "4", title = "Estado financiero real", icon = Icons.Default.AccountBalanceWallet, color = EP.Red) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(EP.GreenSoft)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Pagado hasta ahora", fontSize = 11.sp, color = EP.Green.copy(alpha = 0.75f), fontWeight = FontWeight.Medium)
                        Text(formatearLempiras(montoPagadoReal), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = EP.Green)
                    }
                    Icon(Icons.Default.Verified, contentDescription = null, tint = EP.Green, modifier = Modifier.size(28.dp))
                }
                Text(
                    "Calculado desde los pagos reales registrados — no editable aquí.",
                    fontSize = 11.sp, color = EP.TextMuted
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = saldoPendienteTexto,
                    onValueChange = {
                        saldoPendienteTexto = it
                        saldoTocadoManualmente = true
                    },
                    label = { Text("Saldo pendiente real (L.)") },
                    prefix = { Text("L.", color = EP.TextSec, fontSize = 13.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (saldoTocadoManualmente) {
                            IconButton(onClick = {
                                saldoTocadoManualmente = false
                                saldoPendienteTexto = "%.2f".format(saldoRealVivo)
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Recalcular automáticamente", tint = EP.Red, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    supportingText = {
                        Text(
                            "Auto-calculado: L. ${"%.2f".format(saldoRealVivo)} " +
                                    "(Total a pagar + mora − pagado real)",
                            fontSize = 11.sp, color = EP.TextMuted
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EP.Red,
                        focusedLabelColor  = EP.Red
                    )
                )

                val saldoFinalPreview = saldoPendienteTexto.toDoubleOrNull() ?: saldoRealVivo
                val estadoPreview = if (saldoFinalPreview <= 0.01) "SALDADO" else "ACTIVO"
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (saldoFinalPreview <= 0.01) EP.GreenSoft else EP.OrangeSoft)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Estado que se guardará:", fontSize = 12.sp, color = EP.TextSec)
                    Text(
                        estadoPreview,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = if (saldoFinalPreview <= 0.01) EP.Green else EP.Orange
                    )
                }
            }

            // ── SECCIÓN 5: DETALLES ADICIONALES ───────────
            FormSectionEditar(numero = "5", title = "Detalles adicionales", icon = Icons.Default.Description, color = EP.Amber) {
                CPTextFieldEditar(value = lugar, onValueChange = { lugar = it }, label = "Lugar", icon = Icons.Default.LocationOn)
                Spacer(Modifier.height(4.dp))
                CPTextFieldEditar(value = firma, onValueChange = { firma = it }, label = "Firma", icon = Icons.Default.Draw)
                Spacer(Modifier.height(4.dp))
                CPTextFieldEditar(value = observaciones, onValueChange = { observaciones = it }, label = "Observaciones", icon = Icons.Default.Notes, maxLines = 3)
            }

            // ── SECCIÓN 6: FOTOS ───────────────────────────
            FormSectionEditar(numero = "6", title = "Documentos / Fotos", icon = Icons.Default.PhotoCamera, color = EP.TextSec) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
                                PackageManager.PERMISSION_GRANTED -> {
                                    crearArchivoTemporal()?.let { uri -> fotoUriCamara = uri; cameraLauncher.launch(uri) }
                                        ?: Toast.makeText(context, "Error al crear archivo temporal", Toast.LENGTH_SHORT).show()
                                }
                                else -> permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EP.Border)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp), tint = EP.Blue)
                        Spacer(Modifier.width(6.dp))
                        Text("Cámara", fontSize = 13.sp, color = EP.Text)
                    }
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EP.Border)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp), tint = EP.Blue)
                        Spacer(Modifier.width(6.dp))
                        Text("Galería", fontSize = 13.sp, color = EP.Text)
                    }
                }

                AnimatedVisibility(visible = fotosSeleccionadas.isNotEmpty()) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        Text("${fotosSeleccionadas.size} foto(s) adjunta(s)", fontSize = 12.sp, color = EP.TextSec, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(fotosSeleccionadas.withIndex().toList()) { (index, uri) ->
                                Box(modifier = Modifier.size(72.dp)) {
                                    Image(
                                        painter = rememberAsyncImagePainter(uri),
                                        contentDescription = "Foto ${index + 1}",
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    IconButton(
                                        onClick = { fotosSeleccionadas = fotosSeleccionadas.filterIndexed { i, _ -> i != index } },
                                        modifier = Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(EP.Red)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Eliminar", tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── RESUMEN ────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = EP.Navy)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Resumen del préstamo", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text("Total a pagar (nuevo)", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                            Text(formatearLempiras(totalAPagar), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Saldo pendiente real", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                            Text(
                                formatearLempiras(saldoPendienteTexto.toDoubleOrNull() ?: saldoRealVivo),
                                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = EP.BlueSoft
                            )
                        }
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Cuota estimada", fontSize = 10.sp, color = Color.White.copy(alpha = 0.45f))
                            Text(formatearLempiras(cuotaEstimada), fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Cuotas", fontSize = 10.sp, color = Color.White.copy(alpha = 0.45f))
                            Text("$cuotasInt ($selectedPlazo)", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(
                        "Días efectivos: $diasEfectivos ≈ ${"%.1f".format(mesesAprox)} meses · $descripcionPlazo",
                        fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        "Próximo pago: $proximoPagoString",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = EP.BlueSoft
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── BOTÓN GUARDAR ───────────────────────────────
            Button(
                onClick = {
                    if (cliente.isBlank() || montoDouble <= 0 || cuotasInt <= 0) {
                        Toast.makeText(context, "Verifica los datos ingresados", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isLoading = true

                    scope.launch {
                        try {
                            // Subir fotos (igual que antes)
                            val fotosValidas = fotosSeleccionadas.filter { uri ->
                                try {
                                    if (uri.toString().startsWith("http")) true
                                    else {
                                        val ins = context.contentResolver.openInputStream(uri)
                                        val ok = ins != null
                                        ins?.close()
                                        ok
                                    }
                                } catch (e: Exception) { false }
                            }
                            val urlsFotos = mutableListOf<String>()
                            var fotosConError = 0
                            for (uri in fotosValidas) {
                                try {
                                    if (uri.toString().startsWith("http")) {
                                        urlsFotos.add(uri.toString())
                                    } else {
                                        val ins = context.contentResolver.openInputStream(uri)
                                        if (ins != null) {
                                            ins.close()
                                            val ref = storage.reference.child("prestamos/${UUID.randomUUID()}.jpg")
                                            ref.putFile(uri).await()
                                            urlsFotos.add(ref.downloadUrl.await().toString())
                                        } else fotosConError++
                                    }
                                } catch (e: Exception) { fotosConError++ }
                            }
                            if (fotosConError > 0) {
                                Toast.makeText(context, "$fotosConError fotos no pudieron procesarse.", Toast.LENGTH_LONG).show()
                            }

                            // ✅ FIX: recalcular monto pagado real justo antes de guardar,
                            // para no basarse en datos desactualizados.
                            val pagosSnapFinal = db.collection("pagos")
                                .whereEqualTo("prestamoId", prestamoId).get().await()
                            var montoPagadoFinalReal = 0.0
                            for (pago in pagosSnapFinal.documents) {
                                montoPagadoFinalReal += (pago.getDouble("monto") ?: 0.0) + (pago.getDouble("mora") ?: 0.0)
                            }

                            // Saldo final: respeta lo que el admin haya dejado en el campo,
                            // pero si no lo tocó, usa el valor recalculado con el pago más reciente.
                            val totalConMoraFinal = totalAPagar + moraDouble
                            val saldoAutoFinal = (totalConMoraFinal - montoPagadoFinalReal).coerceAtLeast(0.0)
                            val saldoFinal = if (saldoTocadoManualmente)
                                (saldoPendienteTexto.toDoubleOrNull() ?: saldoAutoFinal).coerceAtLeast(0.0)
                            else saldoAutoFinal

                            // ✅ FIX PRINCIPAL DEL BUG: el estado se decide según el saldo REAL,
                            // nunca se asume "activo" ni se sobreescribe el saldo con el total bruto.
                            val estadoFinal = when {
                                estado == "eliminado" -> "eliminado"
                                saldoFinal <= 0.01     -> "saldado"
                                else                    -> "activo"
                            }
                            val montoPagadoParaGuardar = (totalConMoraFinal - saldoFinal).coerceAtLeast(0.0)

                            val fecha = formatter.format(fechaSeleccionada.time)

                            val prestamoActualizado = mutableMapOf<String, Any>(
                                "cliente" to cliente,
                                "monto" to montoDouble,
                                "interes" to interesCalculado,
                                "interesMensual" to if (usarInteresMensual) interesPct else 0.0,
                                "interesTotal" to if (!usarInteresMensual) interesTotalDouble else interesCalculado,
                                "usarInteresMensual" to usarInteresMensual,
                                "interesTotalFijo" to if (!usarInteresMensual) interesTotalDouble else 0.0,
                                "mora" to moraDouble,
                                "totalPagar" to totalAPagar, // base sin mora, consistente con el resto de la app
                                "cuota" to cuotaEstimada,
                                "cuotas" to cuotasInt,
                                "plazo" to selectedPlazo,
                                "fecha" to Timestamp(fechaSeleccionada.time),
                                "lugar" to lugar,
                                "firma" to firma,
                                "cobrador" to nombreCobrador,
                                "numeroCobrador" to numeroCobrador,
                                "cobradorUid" to currentUid,
                                "proximoPago" to (if (estadoFinal == "saldado") "saldado" else proximoPagoTimestamp),
                                "estado" to estadoFinal,
                                "observaciones" to observaciones,
                                "fotos" to urlsFotos,
                                "diasEfectivos" to diasEfectivos.toDouble(),
                                // ✅ ya NO se pisa con totalAPagar a ciegas: se guarda el saldo real
                                "saldo" to saldoFinal,
                                "montoPagado" to montoPagadoParaGuardar,
                                "fechaUltimaActualizacion" to Timestamp.now()
                            )
                            if (estadoFinal == "saldado") {
                                prestamoActualizado["fechaSaldado"] = Timestamp.now()
                                prestamoActualizado["fechaCancelacion"] = Timestamp.now()
                            }

                            db.collection("prestamos").document(prestamoId).update(prestamoActualizado).await()

                            // Generar recibo actualizado
                            try {
                                val reciboFile = ReciboHelper.generarReciboPrestamoPDF(
                                    context = context,
                                    cliente = com.example.minifinancieraapp.ui.models.ClienteModel(
                                        nombre = cliente, telefono = "", direccionCasa = lugar
                                    ),
                                    monto = montoDouble,
                                    interesTotal = interesCalculado,
                                    mora = moraDouble,
                                    cuotas = cuotasInt,
                                    fecha = fecha,
                                    lugar = lugar,
                                    numeroCobrador = numeroCobrador,
                                    numeroPrestamo = prestamoId,
                                    nombreCobrador = nombreCobrador,
                                    fechaProximoPago = proximoPagoString
                                )
                                if (reciboFile != null && reciboFile.exists()) {
                                    reciboGenerado = reciboFile
                                    fechaGuardada = fecha
                                    accionImprimirDirecto = {
                                        ReciboHelper.imprimirReciboPrestamoDirecto(
                                            context = context,
                                            cliente = cliente,
                                            telefono = "",
                                            monto = montoDouble,
                                            interesTotal = interesCalculado,
                                            mora = moraDouble,
                                            cuotas = cuotasInt,
                                            fecha = fecha,
                                            lugar = lugar,
                                            numeroCobrador = numeroCobrador,
                                            numeroPrestamo = prestamoId,
                                            nombreCobrador = nombreCobrador,
                                            fechaProximoPago = proximoPagoString
                                        )
                                    }
                                    accionSoloPdf = {
                                        ReciboHelper.imprimirPDF(context, reciboFile)
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", reciboFile)
                                        context.startActivity(Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "application/pdf"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(Intent.EXTRA_TEXT, "Aquí está el recibo del préstamo actualizado desde Capital Express.")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }, "Compartir recibo actualizado con:"
                                        ))
                                    }
                                    mostrarDialogoImprimir = true
                                    Toast.makeText(context, "Préstamo actualizado correctamente. Saldo real: L. ${"%.2f".format(saldoFinal)}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Préstamo actualizado. Saldo real: L. ${"%.2f".format(saldoFinal)}", Toast.LENGTH_LONG).show()
                                }
                            } catch (reciboError: Exception) {
                                Toast.makeText(context, "Préstamo actualizado. Error al imprimir: ${reciboError.message}", Toast.LENGTH_LONG).show()
                            }

                            navController.popBackStack()

                        } catch (e: Exception) {
                            Toast.makeText(context, "Error al actualizar: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EP.Blue)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Guardando...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Actualizar e Imprimir", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            reciboGenerado?.let { archivo ->
                OutlinedButton(
                    onClick = {
                        try {
                            accionImprimirDirecto = {
                                ReciboHelper.imprimirReciboPrestamoDirecto(
                                    context = context,
                                    cliente = cliente,
                                    telefono = "",
                                    monto = montoDouble,
                                    interesTotal = interesCalculado,
                                    mora = moraDouble,
                                    cuotas = cuotasInt,
                                    fecha = fechaGuardada,
                                    lugar = lugar,
                                    numeroCobrador = numeroCobrador,
                                    numeroPrestamo = prestamoId,
                                    nombreCobrador = nombreCobrador,
                                    fechaProximoPago = proximoPagoString
                                )
                            }
                            accionSoloPdf = {
                                ReciboHelper.imprimirPDF(context, archivo)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo)
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_TEXT, "Aquí está el recibo del préstamo actualizado desde Capital Express.")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, "Compartir recibo con:"
                                ))
                            }
                            mostrarDialogoImprimir = true
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error al reimprimir: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EP.Border)
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp), tint = EP.TextSec)
                    Spacer(Modifier.width(8.dp))
                    Text("Reimprimir recibo", color = EP.TextSec, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (mostrarDialogoImprimir) {
        ImprimirOpcionesDialog(
            onImprimirDirecto = {
                mostrarDialogoImprimir = false
                scope.launch { intentarImprimirConRespaldo(context, accionImprimirDirecto, accionSoloPdf) }
            },
            onSoloPdf = {
                mostrarDialogoImprimir = false
                accionSoloPdf?.invoke()
            },
            onDismiss = { mostrarDialogoImprimir = false }
        )
    }
}

// ─────────────────────────────────────────────
//  COMPONENTES REUTILIZABLES (duplicados de Crear
//  porque están en un paquete distinto y son private allá)
// ─────────────────────────────────────────────
@Composable
private fun FormSectionEditar(
    numero: String,
    title: String,
    icon: ImageVector,
    color: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = EP.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 14.dp)
            ) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(numero, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = color)
                }
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = EP.Text)
            }
            content()
        }
    }
}

@Composable
private fun CPTextFieldEditar(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    prefix: String? = null,
    suffix: String? = null,
    supportingText: String? = null,
    maxLines: Int = 1,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = EP.TextSec) },
        prefix = prefix?.let { { Text(it, color = EP.TextSec, fontSize = 13.sp) } },
        suffix = suffix?.let { { Text(it, color = EP.TextSec, fontSize = 13.sp) } },
        supportingText = supportingText?.let { { Text(it, fontSize = 11.sp, color = EP.TextMuted) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        maxLines = maxLines,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = EP.Blue,
            unfocusedBorderColor = EP.Border,
            focusedLabelColor = EP.Blue
        )
    )
}

@Composable
private fun ToggleTabEditar(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) EP.Card else Color.Transparent)
            .then(if (selected) Modifier.shadow(2.dp, RoundedCornerShape(10.dp)) else Modifier)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) EP.Blue else EP.TextSec)
    }
}

@Composable
private fun PlazoSelectorEditar(
    opciones: List<String>,
    seleccionado: String,
    onSeleccionar: (String) -> Unit
) {
    val filas = opciones.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        filas.forEach { fila ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fila.forEach { opcion ->
                    val selected = seleccionado == opcion
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) EP.Blue else EP.Surface)
                            .border(1.dp, if (selected) EP.Blue else EP.Border, RoundedCornerShape(10.dp))
                            .clickable { onSeleccionar(opcion) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            opcion, fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else EP.TextSec,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(3 - fila.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}