package com.example.capitalexpressapp.ui.screens

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import com.example.capitalexpressapp.core.formatearLempiras
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
import androidx.compose.ui.platform.LocalFocusManager
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
import com.example.capitalexpressapp.util.PrestamoNumberHelper
import com.example.capitalexpressapp.util.ReciboHelper
import com.example.minifinancieraapp.ui.components.ImprimirOpcionesDialog
import com.example.minifinancieraapp.ui.models.ClienteModel
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// ─────────────────────────────────────────────
//  PALETA — coherente con NotificacionesScreen
// ─────────────────────────────────────────────
private object CP {
    val Navy       = Color(0xFF0A1628)
    val NavyMid    = Color(0xFF0F2044)
    val Blue       = Color(0xFF1A56DB)
    val BlueSoft   = Color(0xFF3B82F6)
    val BlueLight  = Color(0xFFEFF6FF)
    val GradStart  = Color(0xFF0A1628)
    val GradEnd    = Color(0xFF1A3A6B)

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

// ─────────────────────────────────────────────
//  HELPERS (sin cambios en lógica)
// ─────────────────────────────────────────────
suspend fun verificarPrestamosActivos(db: FirebaseFirestore, clienteId: String): Pair<Int, List<String>> {
    return try {
        val snapshot = db.collection("prestamos")
            .whereEqualTo("clienteId", clienteId)
            .whereEqualTo("estado", "activo")
            .get().await()

        val prestamosConSaldo = snapshot.documents.filter { doc ->
            val saldo      = doc.getDouble("saldo") ?: 0.0
            val totalPagar = doc.getDouble("totalPagar") ?: 0.0
            val montoPagado = doc.getDouble("montoPagado") ?: 0.0
            saldo > 0.0 && montoPagado < totalPagar
        }
        Pair(prestamosConSaldo.size, prestamosConSaldo.mapNotNull { it.id })
    } catch (e: Exception) {
        Pair(0, emptyList())
    }
}

fun contarDiasSinDomingos(diasNecesarios: Int, fechaInicio: Calendar = Calendar.getInstance()): Int {
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

fun calcularDiasEfectivos(plazo: String, cuotas: Int, fechaInicio: Calendar): Int =
    when (plazo) {
        "Diario"        -> cuotas
        "Lunes a Sábado"-> contarDiasSinDomingos(cuotas, fechaInicio)
        "Semanal"       -> contarDiasSinDomingos(cuotas * 6, fechaInicio)
        "Quincenal"     -> cuotas * 15
        "Mensual"       -> cuotas * 30
        "Bimestral"     -> cuotas * 60
        else            -> cuotas * 30
    }

// ─────────────────────────────────────────────
//  PANTALLA PRINCIPAL
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrearPrestamoScreen(navController: NavController, clienteId: String) {
    val context     = LocalContext.current
    val db          = FirebaseFirestore.getInstance()
    val scope       = rememberCoroutineScope()
    val formatter   = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val scrollState = rememberScrollState()

    // Auth
    var currentUid      by remember { mutableStateOf("") }
    var nombreCobrador  by remember { mutableStateOf("") }
    var numeroCobrador  by remember { mutableStateOf("") }
    var isLoadingAuth   by remember { mutableStateOf(true) }

    // Formulario
    val clientes = remember { mutableStateListOf<ClienteModel>() }
    var selectedCliente by remember { mutableStateOf<ClienteModel?>(null) }
    var prestamosActivosCliente by remember { mutableStateOf(0) }

    var monto          by remember { mutableStateOf("") }
    var interesMensual by remember { mutableStateOf("") }
    var interesTotal   by remember { mutableStateOf("") }
    var usarInteresMensual by remember { mutableStateOf(true) }
    var cuotas         by remember { mutableStateOf("") }
    var mora           by remember { mutableStateOf("") }
    var selectedPlazo  by remember { mutableStateOf("Semanal") }
    var lugar          by remember { mutableStateOf("") }
    var firma          by remember { mutableStateOf("") }
    var garantia       by remember { mutableStateOf("") }  // ✅ campo añadido
    var observaciones  by remember { mutableStateOf("") }
    var isLoading      by remember { mutableStateOf(false) }
    var reciboGenerado by remember { mutableStateOf<File?>(null) }
    var fechaSeleccionada by remember { mutableStateOf(Calendar.getInstance()) }

    // Datos del último préstamo guardado, necesarios para el botón "Reimprimir"
    var fechaGuardada by remember { mutableStateOf("") }
    var numeroPrestamoGuardado by remember { mutableStateOf("") }
    var proximoPagoGuardado by remember { mutableStateOf("") }

    // Diálogo "Imprimir directo o solo PDF"
    var mostrarDialogoImprimir by remember { mutableStateOf(false) }
    var accionImprimirDirecto by remember { mutableStateOf<(suspend () -> Boolean)?>(null) }
    var accionSoloPdf by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Fotos
    var fotosSeleccionadas by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var fotoUriCamara      by remember { mutableStateOf<Uri?>(null) }

    // Auth
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
            Toast.makeText(context, "Error al cargar usuario: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isLoadingAuth = false
        }
    }

    // Clientes
    LaunchedEffect(Unit) {
        try {
            val snapshot = db.collection("clientes").get().await()
            clientes.clear()
            clientes.addAll(snapshot.documents.mapNotNull { doc ->
                val nombre = doc.getString("nombre") ?: return@mapNotNull null
                ClienteModel(
                    id            = doc.id,
                    nombre        = nombre,
                    telefono      = doc.getString("telefono") ?: "",
                    direccionCasa = doc.getString("direccionCasa") ?: "",
                    identidad     = doc.getString("identidad") ?: ""
                )
            })
        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar clientes: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Préstamos activos al cambiar cliente
    LaunchedEffect(selectedCliente) {
        prestamosActivosCliente = if (selectedCliente != null) {
            verificarPrestamosActivos(db, selectedCliente!!.id).first
        } else 0
    }

    // Cámara / galería
    val crearArchivoTemporal = remember {
        {
            try {
                val ts    = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val dir   = context.getExternalFilesDir("Pictures")?.also { if (!it.exists()) it.mkdirs() }
                val file  = File.createTempFile("JPEG_${ts}_", ".jpg", dir)
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
        } else {
            Toast.makeText(context, "No se pudo capturar la foto", Toast.LENGTH_SHORT).show()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            crearArchivoTemporal()?.let { uri -> fotoUriCamara = uri; cameraLauncher.launch(uri) }
        } else {
            Toast.makeText(context, "Permiso de cámara requerido", Toast.LENGTH_SHORT).show()
        }
    }

    if (isLoadingAuth) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CP.Blue)
        }
        return
    }

    // ─── CÁLCULOS ───────────────────────────
    val montoDouble        = monto.toDoubleOrNull() ?: 0.0
    val interesPct         = interesMensual.toDoubleOrNull() ?: 0.0
    val cuotasInt          = cuotas.toIntOrNull() ?: 1
    val interesTotalDouble = interesTotal.toDoubleOrNull() ?: 0.0
    val moraDouble         = mora.toDoubleOrNull() ?: 0.0

    val fechaInicio    = fechaSeleccionada.clone() as Calendar
    val diasEfectivos  = calcularDiasEfectivos(selectedPlazo, cuotasInt, fechaInicio)
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

    // ─── UI ──────────────────────────────────
    Scaffold(
        containerColor = CP.Surface,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(CP.GradStart, CP.GradEnd)))
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
                            "Nuevo Préstamo",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = (-0.3).sp
                        )
                        Text(
                            "Completa los datos del crédito",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── SECCIÓN 1: CLIENTE ────────────────
            FormSection(
                numero = "1",
                title = "Cliente",
                icon = Icons.Default.Person,
                color = CP.Blue
            ) {
                BuscarClienteDropdownMejorado(
                    clientes = clientes,
                    selectedCliente = selectedCliente,
                    onClienteSeleccionado = { selectedCliente = it }
                )

                // Banner de advertencia / bloqueo
                AnimatedVisibility(visible = selectedCliente != null && prestamosActivosCliente > 0) {
                    val bloqueado = prestamosActivosCliente >= 2
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (bloqueado) CP.RedSoft else CP.OrangeSoft)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            if (bloqueado) Icons.Default.Block else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (bloqueado) CP.Red else CP.Orange,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            if (bloqueado)
                                "BLOQUEADO: ${selectedCliente?.nombre} ya tiene $prestamosActivosCliente préstamos activos (máximo 2)."
                            else
                                "AVISO: ${selectedCliente?.nombre} tiene 1 préstamo activo. Este será el máximo permitido.",
                            fontSize = 13.sp,
                            color = if (bloqueado) CP.Red else CP.Orange,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ── SECCIÓN 2: MONTO ─────────────────
            FormSection(
                numero = "2",
                title = "Monto y Plazo",
                icon = Icons.Default.AttachMoney,
                color = CP.Green
            ) {
                CPTextField(
                    value = monto,
                    onValueChange = { monto = it },
                    label = "Monto del préstamo",
                    prefix = "L.",
                    keyboardType = KeyboardType.Number,
                    icon = Icons.Default.MonetizationOn
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Selector de plazo
                Text(
                    "Frecuencia de pago",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CP.TextSec
                )
                Spacer(modifier = Modifier.height(6.dp))
                PlazoSelector(
                    opciones = listOf("Diario", "Lunes a Sábado", "Semanal", "Quincenal", "Mensual", "Bimestral"),
                    seleccionado = selectedPlazo,
                    onSeleccionar = { selectedPlazo = it }
                )

                Spacer(modifier = Modifier.height(4.dp))
                CPTextField(
                    value = cuotas,
                    onValueChange = { cuotas = it },
                    label = "Número de cuotas",
                    keyboardType = KeyboardType.Number,
                    icon = Icons.Outlined.TableRows
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Fecha
                val datePickerColor = ButtonDefaults.outlinedButtonColors(contentColor = CP.Blue)
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                fechaSeleccionada = Calendar.getInstance().also {
                                    it.set(year, month, day)
                                }
                            },
                            fechaSeleccionada.get(Calendar.YEAR),
                            fechaSeleccionada.get(Calendar.MONTH),
                            fechaSeleccionada.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CP.Border),
                    colors = datePickerColor
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Fecha de inicio: ${formatter.format(fechaSeleccionada.time)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = CP.TextMuted)
                }
            }

            // ── SECCIÓN 3: INTERÉS ────────────────
            FormSection(
                numero = "3",
                title = "Interés",
                icon = Icons.Default.Percent,
                color = Color(0xFF8B5CF6)
            ) {
                // Toggle tipo de interés
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CP.Border.copy(alpha = 0.4f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ToggleTab(
                        text = "% Mensual",
                        selected = usarInteresMensual,
                        modifier = Modifier.weight(1f),
                        onClick = { usarInteresMensual = true; interesTotal = "" }
                    )
                    ToggleTab(
                        text = "Total fijo (L.)",
                        selected = !usarInteresMensual,
                        modifier = Modifier.weight(1f),
                        onClick = { usarInteresMensual = false; interesMensual = "" }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                AnimatedContent(targetState = usarInteresMensual, label = "interes") { usarMensual ->
                    if (usarMensual) {
                        CPTextField(
                            value = interesMensual,
                            onValueChange = { interesMensual = it },
                            label = "Porcentaje mensual",
                            suffix = "%",
                            keyboardType = KeyboardType.Number,
                            icon = Icons.Default.Percent
                        )
                    } else {
                        CPTextField(
                            value = interesTotal,
                            onValueChange = { interesTotal = it },
                            label = "Interés total fijo",
                            prefix = "L.",
                            keyboardType = KeyboardType.Number,
                            icon = Icons.Default.MonetizationOn,
                            supportingText = "Se suma directamente al monto"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                CPTextField(
                    value = mora,
                    onValueChange = { mora = it },
                    label = "Mora diaria por retraso",
                    prefix = "L.",
                    keyboardType = KeyboardType.Number,
                    icon = Icons.Outlined.Warning,
                    supportingText = "Monto cobrado por cada día de mora"
                )
            }

            // ── SECCIÓN 4: DETALLES ADICIONALES ──
            FormSection(
                numero = "4",
                title = "Detalles adicionales",
                icon = Icons.Default.Description,
                color = CP.Amber
            ) {
                CPTextField(value = lugar,        onValueChange = { lugar = it },        label = "Lugar",        icon = Icons.Default.LocationOn)
                Spacer(Modifier.height(4.dp))
                CPTextField(value = firma,        onValueChange = { firma = it },        label = "Firma",        icon = Icons.Default.Draw)
                Spacer(Modifier.height(4.dp))
                CPTextField(value = garantia,     onValueChange = { garantia = it },     label = "Garantía",     icon = Icons.Default.Security)     // ✅ campo añadido
                Spacer(Modifier.height(4.dp))
                CPTextField(value = observaciones, onValueChange = { observaciones = it }, label = "Observaciones", icon = Icons.Default.Notes, maxLines = 3)
            }

            // ── SECCIÓN 5: FOTOS ─────────────────
            FormSection(
                numero = "5",
                title = "Documentos / Fotos",
                icon = Icons.Default.PhotoCamera,
                color = CP.TextSec
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Cámara
                    OutlinedButton(
                        onClick = {
                            when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
                                PackageManager.PERMISSION_GRANTED -> {
                                    crearArchivoTemporal()?.let { uri ->
                                        fotoUriCamara = uri; cameraLauncher.launch(uri)
                                    } ?: Toast.makeText(context, "Error al crear archivo", Toast.LENGTH_SHORT).show()
                                }
                                else -> permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CP.Border)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp), tint = CP.Blue)
                        Spacer(Modifier.width(6.dp))
                        Text("Cámara", fontSize = 13.sp, color = CP.Text)
                    }
                    // Galería
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CP.Border)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp), tint = CP.Blue)
                        Spacer(Modifier.width(6.dp))
                        Text("Galería", fontSize = 13.sp, color = CP.Text)
                    }
                }

                AnimatedVisibility(visible = fotosSeleccionadas.isNotEmpty()) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${fotosSeleccionadas.size} foto(s) adjunta(s)",
                            fontSize = 12.sp,
                            color = CP.TextSec,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(fotosSeleccionadas.withIndex().toList()) { (index, uri) ->
                                Box(modifier = Modifier.size(72.dp)) {
                                    Image(
                                        painter = rememberAsyncImagePainter(uri),
                                        contentDescription = "Foto ${index + 1}",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    IconButton(
                                        onClick = { fotosSeleccionadas = fotosSeleccionadas.filterIndexed { i, _ -> i != index } },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(CP.Red)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Eliminar", tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── RESUMEN ───────────────────────────
            ResumenCard(
                montoDouble     = montoDouble,
                interesCalculado = interesCalculado,
                totalAPagar     = totalAPagar,
                cuotasInt       = cuotasInt,
                cuotaEstimada   = cuotaEstimada,
                selectedPlazo   = selectedPlazo,
                diasEfectivos   = diasEfectivos,
                mesesAprox      = mesesAprox,
                proximoPagoString = proximoPagoString,
                moraDouble      = moraDouble,
                fotosCount      = fotosSeleccionadas.size,
                usarInteresMensual = usarInteresMensual,
                interesPct      = interesPct,
                interesTotalDouble = interesTotalDouble
            )

            // ── BOTÓN GUARDAR ─────────────────────
            val bloqueado = selectedCliente != null && prestamosActivosCliente >= 2
            Button(
                onClick = {
                    if (selectedCliente == null || montoDouble <= 0 || cuotasInt <= 0) {
                        Toast.makeText(context, "Verifica los datos ingresados", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isLoading = true
                    scope.launch {
                        try {
                            val (activos, _) = verificarPrestamosActivos(db, selectedCliente!!.id)
                            if (activos >= 2) {
                                isLoading = false
                                Toast.makeText(context, "BLOQUEADO: El cliente ya tiene $activos préstamos activos (máximo 2).", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            if (activos == 1) {
                                Toast.makeText(context, "AVISO: Este cliente ya tiene 1 préstamo activo. Este será el segundo y último permitido.", Toast.LENGTH_LONG).show()
                            }

                            val numeroPrestamoUnico = PrestamoNumberHelper.generarNumeroPrestamo(
                                clienteNombre = selectedCliente!!.nombre,
                                db = db
                            )

                            val docRef    = db.collection("prestamos").document()
                            val prestamoId = docRef.id
                            val fecha      = formatter.format(fechaSeleccionada.time)

                            // ✅ FIX: cobradoresAsignados nunca guarda UID vacío
                            val cobradoresAsignados = if (currentUid.isNotBlank()) listOf(currentUid) else emptyList<String>()

                            val prestamo = hashMapOf(
                                "id"               to prestamoId,
                                "numeroPrestamo"   to numeroPrestamoUnico,
                                "cliente"          to selectedCliente!!.nombre,
                                "clienteId"        to selectedCliente!!.id,
                                "monto"            to montoDouble,
                                "interes"          to interesCalculado,
                                "interesMensual"   to if (usarInteresMensual) interesPct else 0.0,
                                "interesTotal"     to interesCalculado,
                                "usarInteresMensual" to usarInteresMensual,
                                "interesTotalFijo" to if (!usarInteresMensual) interesTotalDouble else 0.0,
                                "mora"             to moraDouble,
                                "totalPagar"       to totalAPagar,
                                "cuota"            to cuotaEstimada,
                                "cuotas"           to cuotasInt,
                                "plazo"            to selectedPlazo,
                                "fecha"            to Timestamp(fechaSeleccionada.time),
                                "fechaCreacion"    to Timestamp(Date()),
                                "lugar"            to lugar,
                                "firma"            to firma,
                                "garantia"         to garantia,       // ✅ campo añadido
                                "observaciones"    to observaciones,
                                "cobrador"         to nombreCobrador,
                                "numeroCobrador"   to numeroCobrador,
                                "cobradorUid"      to currentUid,
                                "cobradorAsignado" to currentUid,     // ✅ campo alineado con SolicitudesAdminScreen
                                "cobradoresAsignados" to cobradoresAsignados, // ✅ nunca [""]
                                "prestamoId"       to prestamoId,
                                "proximoPago"      to proximoPagoTimestamp,
                                "montoPagado"      to 0.0,
                                "saldoAnterior"    to montoDouble,
                                "estado"           to "activo",
                                "fotos"            to fotosSeleccionadas.map { it.toString() },
                                "diasEfectivos"    to diasEfectivos.toDouble(),
                                "saldo"            to totalAPagar,
                                "eliminado"        to false
                            )

                            docRef.set(prestamo).await()

                            Toast.makeText(context, "✅ Préstamo creado\nN° $numeroPrestamoUnico", Toast.LENGTH_LONG).show()

                            try {
                                val reciboFile = ReciboHelper.generarReciboPrestamoPDF(
                                    context          = context,
                                    cliente          = selectedCliente!!,
                                    monto            = montoDouble,
                                    interesTotal     = interesCalculado,
                                    mora             = moraDouble,
                                    cuotas           = cuotasInt,
                                    fecha            = fecha,
                                    lugar            = lugar,
                                    numeroCobrador   = numeroCobrador,
                                    numeroPrestamo   = numeroPrestamoUnico,
                                    nombreCobrador   = nombreCobrador,
                                    fechaProximoPago = proximoPagoString
                                )
                                if (reciboFile != null && reciboFile.exists()) {
                                    reciboGenerado = reciboFile
                                    fechaGuardada = fecha
                                    numeroPrestamoGuardado = numeroPrestamoUnico
                                    proximoPagoGuardado = proximoPagoString
                                    accionImprimirDirecto = {
                                        ReciboHelper.imprimirReciboPrestamoDirecto(
                                            context          = context,
                                            cliente          = selectedCliente!!.nombre,
                                            telefono         = selectedCliente!!.telefono,
                                            monto            = montoDouble,
                                            interesTotal     = interesCalculado,
                                            mora             = moraDouble,
                                            cuotas           = cuotasInt,
                                            fecha            = fecha,
                                            lugar            = lugar,
                                            numeroCobrador   = numeroCobrador,
                                            numeroPrestamo   = numeroPrestamoUnico,
                                            nombreCobrador   = nombreCobrador,
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
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }, "Compartir recibo"
                                        ))
                                    }
                                    mostrarDialogoImprimir = true
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Préstamo guardado. Error al imprimir: ${e.message}", Toast.LENGTH_LONG).show()
                            }

                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled  = !isLoading && !bloqueado,
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = if (bloqueado) CP.TextMuted else CP.Blue
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Guardando...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(
                        if (bloqueado) Icons.Default.Block else Icons.Default.Print,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (bloqueado) "Bloqueado — 2 préstamos activos" else "Guardar e Imprimir",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Reimprimir
            reciboGenerado?.let { archivo ->
                OutlinedButton(
                    onClick = {
                        try {
                            accionImprimirDirecto = {
                                ReciboHelper.imprimirReciboPrestamoDirecto(
                                    context          = context,
                                    cliente          = selectedCliente?.nombre ?: "",
                                    telefono         = selectedCliente?.telefono ?: "",
                                    monto            = montoDouble,
                                    interesTotal     = interesCalculado,
                                    mora             = moraDouble,
                                    cuotas           = cuotasInt,
                                    fecha            = fechaGuardada,
                                    lugar            = lugar,
                                    numeroCobrador   = numeroCobrador,
                                    numeroPrestamo   = numeroPrestamoGuardado,
                                    nombreCobrador   = nombreCobrador,
                                    fechaProximoPago = proximoPagoGuardado
                                )
                            }
                            accionSoloPdf = {
                                ReciboHelper.imprimirPDF(context, archivo)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo)
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, "Compartir recibo"
                                ))
                            }
                            mostrarDialogoImprimir = true
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error al reimprimir: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape    = RoundedCornerShape(14.dp),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, CP.Border)
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp), tint = CP.TextSec)
                    Spacer(Modifier.width(8.dp))
                    Text("Reimprimir recibo", color = CP.TextSec, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (mostrarDialogoImprimir) {
        ImprimirOpcionesDialog(
            onImprimirDirecto = {
                mostrarDialogoImprimir = false
                accionImprimirDirecto?.invoke() ?: false
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
//  COMPONENTES REUTILIZABLES
// ─────────────────────────────────────────────

@Composable
private fun FormSection(
    numero: String,
    title: String,
    icon: ImageVector,
    color: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CP.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header de sección
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        numero,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = color
                    )
                }
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = CP.Text)
            }
            content()
        }
    }
}

@Composable
private fun CPTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    prefix: String? = null,
    suffix: String? = null,
    supportingText: String? = null,
    maxLines: Int = 1
) {
    OutlinedTextField(
        value            = value,
        onValueChange    = onValueChange,
        label            = { Text(label, fontSize = 13.sp) },
        leadingIcon      = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = CP.TextSec) },
        prefix           = prefix?.let { { Text(it, color = CP.TextSec, fontSize = 13.sp) } },
        suffix           = suffix?.let { { Text(it, color = CP.TextSec, fontSize = 13.sp) } },
        supportingText   = supportingText?.let { { Text(it, fontSize = 11.sp, color = CP.TextMuted) } },
        keyboardOptions  = KeyboardOptions(keyboardType = keyboardType),
        maxLines         = maxLines,
        modifier         = Modifier.fillMaxWidth(),
        shape            = RoundedCornerShape(12.dp),
        colors           = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = CP.Blue,
            unfocusedBorderColor = CP.Border,
            focusedLabelColor    = CP.Blue
        )
    )
}

@Composable
private fun ToggleTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CP.Card else Color.Transparent)
            .then(if (selected) Modifier.shadow(2.dp, RoundedCornerShape(10.dp)) else Modifier)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize   = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color      = if (selected) CP.Blue else CP.TextSec
        )
    }
}

@Composable
private fun PlazoSelector(
    opciones: List<String>,
    seleccionado: String,
    onSeleccionar: (String) -> Unit
) {
    // Dos filas de 3
    val filas = opciones.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        filas.forEach { fila ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fila.forEach { opcion ->
                    val selected = seleccionado == opcion
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) CP.Blue else CP.Surface)
                            .border(
                                width = 1.dp,
                                color = if (selected) CP.Blue else CP.Border,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSeleccionar(opcion) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            opcion,
                            fontSize   = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color      = if (selected) Color.White else CP.TextSec,
                            textAlign  = TextAlign.Center,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                    }
                }
                // Relleno si la fila tiene menos de 3
                repeat(3 - fila.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ResumenCard(
    montoDouble: Double, interesCalculado: Double, totalAPagar: Double,
    cuotasInt: Int, cuotaEstimada: Double, selectedPlazo: String,
    diasEfectivos: Int, mesesAprox: Double, proximoPagoString: String,
    moraDouble: Double, fotosCount: Int, usarInteresMensual: Boolean,
    interesPct: Double, interesTotalDouble: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(18.dp),
        colors   = CardDefaults.cardColors(containerColor = CP.Navy)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Resumen del préstamo",
                fontSize   = 15.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White
            )

            // Monto grande
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.Bottom
            ) {
                Column {
                    Text("Total a pagar", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                    Text(
                        formatearLempiras(totalAPagar),
                        fontSize   = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color      = Color.White
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Cuota estimada", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                    Text(
                        formatearLempiras(cuotaEstimada),
                        fontSize   = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color      = CP.BlueSoft
                    )
                }
            }

            Divider(color = Color.White.copy(alpha = 0.1f))

            // Detalles en grid 2x2
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ResumenItem("Monto",         formatearLempiras(montoDouble),         modifier = Modifier.weight(1f))
                ResumenItem("Interés total", formatearLempiras(interesCalculado),    modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ResumenItem("Cuotas",        "$cuotasInt ($selectedPlazo)",               modifier = Modifier.weight(1f))
                ResumenItem("Días efectivos","$diasEfectivos ≈ ${"%.1f".format(mesesAprox)} meses", modifier = Modifier.weight(1f))
            }

            // Próximo pago destacado
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = CP.BlueSoft, modifier = Modifier.size(16.dp))
                    Text("Primer pago", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                }
                Text(proximoPagoString, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CP.BlueSoft)
            }

            if (moraDouble > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = CP.Orange, modifier = Modifier.size(14.dp))
                    Text("Mora diaria: ${formatearLempiras(moraDouble)}", fontSize = 12.sp, color = CP.Orange)
                }
            }
            if (fotosCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Photo, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                    Text("$fotosCount foto(s) adjunta(s)", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun ResumenItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.45f), fontWeight = FontWeight.Medium)
        Text(value, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

// ─────────────────────────────────────────────
//  DROPDOWN CLIENTE (sin cambios en lógica)
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuscarClienteDropdownMejorado(
    clientes: List<ClienteModel>,
    selectedCliente: ClienteModel?,
    onClienteSeleccionado: (ClienteModel) -> Unit
) {
    var expanded            by remember { mutableStateOf(false) }
    var textoBusqueda       by remember { mutableStateOf(selectedCliente?.nombre ?: "") }
    var textoBusquedaInterna by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(textoBusquedaInterna) {
        kotlinx.coroutines.delay(300)
        textoBusqueda = textoBusquedaInterna
    }
    LaunchedEffect(selectedCliente) {
        if (selectedCliente != null && selectedCliente.nombre.isNotEmpty()) {
            textoBusquedaInterna = selectedCliente.nombre
            textoBusqueda        = selectedCliente.nombre
        }
    }

    val clientesFiltrados = if (textoBusqueda.length < 2) emptyList()
    else clientes.filter {
        it.nombre.contains(textoBusqueda, ignoreCase = true) ||
                it.identidad.contains(textoBusqueda, ignoreCase = true)
    }.take(10)

    Column {
        OutlinedTextField(
            value         = textoBusquedaInterna,
            onValueChange = { new ->
                textoBusquedaInterna = new
                expanded = new.length >= 2
            },
            label         = { Text("Buscar cliente", fontSize = 13.sp) },
            placeholder   = { Text("Nombre o identidad...", fontSize = 13.sp) },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            shape         = RoundedCornerShape(12.dp),
            leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = CP.TextSec) },
            trailingIcon  = {
                if (textoBusquedaInterna.isNotEmpty()) {
                    IconButton(onClick = {
                        textoBusquedaInterna = ""; textoBusqueda = ""; expanded = false
                        onClienteSeleccionado(ClienteModel())
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpiar", modifier = Modifier.size(16.dp))
                    }
                }
            },
            isError        = textoBusqueda.length >= 2 && clientesFiltrados.isEmpty(),
            supportingText = {
                when {
                    textoBusqueda.isEmpty()                             -> Text("Escriba al menos 2 caracteres", fontSize = 11.sp, color = CP.TextMuted)
                    textoBusqueda.length >= 2 && clientesFiltrados.isEmpty() -> Text("No se encontraron clientes", fontSize = 11.sp, color = CP.Red)
                    clientesFiltrados.isNotEmpty()                      -> Text("${clientesFiltrados.size} cliente(s) encontrado(s)", fontSize = 11.sp, color = CP.Green)
                    else                                                -> Text("Continúe escribiendo...", fontSize = 11.sp, color = CP.TextMuted)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = CP.Blue,
                unfocusedBorderColor = CP.Border,
                focusedLabelColor    = CP.Blue,
                errorBorderColor     = CP.Red
            )
        )

        DropdownMenu(
            expanded          = expanded,
            onDismissRequest  = { expanded = false },
            modifier          = Modifier.fillMaxWidth().heightIn(max = 280.dp)
        ) {
            if (clientesFiltrados.isEmpty()) {
                DropdownMenuItem(
                    text    = { Text("No se encontraron clientes", color = CP.TextMuted) },
                    onClick = {},
                    enabled = false
                )
            } else {
                clientesFiltrados.forEach { cliente ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(cliente.nombre, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                if (cliente.identidad.isNotEmpty()) {
                                    Text("ID: ${cliente.identidad}", fontSize = 11.sp, color = CP.TextMuted)
                                }
                            }
                        },
                        onClick = {
                            textoBusquedaInterna = cliente.nombre
                            textoBusqueda        = cliente.nombre
                            expanded             = false
                            focusManager.clearFocus()
                            onClienteSeleccionado(cliente)
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(CP.BlueLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    cliente.nombre.firstOrNull()?.uppercase() ?: "?",
                                    fontSize   = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color      = CP.Blue
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}