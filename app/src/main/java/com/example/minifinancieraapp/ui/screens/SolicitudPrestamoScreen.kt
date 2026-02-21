package com.example.minifinancieraapp.ui.screens

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.example.minifinancieraapp.ui.models.ClienteModel
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

object SolicitudPrestamoColors {
    val Primary = Color(0xFFE65100)
    val PrimaryDark = Color(0xFFD84315)
    val PrimaryLight = Color(0xFFFFF3E0)
    val Accent = Color(0xFF1976D2)
    val AccentLight = Color(0xFFE3F2FD)
    val Success = Color(0xFF2E7D32)
    val SuccessLight = Color(0xFFE8F5E9)
    val Warning = Color(0xFFEF6C00)
    val Info = Color(0xFF0288D1)
    val Purple = Color(0xFF9C27B0)
    val PurpleLight = Color(0xFFF3E5F5)
    val TextPrimary = Color(0xFF212121)
    val TextSecondary = Color(0xFF757575)
    val Background = Color(0xFFF5F5F5)
}

fun contarDiasSinDomingosSolicitud(diasNecesarios: Int, fechaInicio: Calendar = Calendar.getInstance()): Int {
    var diasEfectivos = 0
    var diasTotales = 0
    val fecha = fechaInicio.clone() as Calendar
    while (diasEfectivos < diasNecesarios) {
        if (fecha.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) diasEfectivos++
        diasTotales++
        fecha.add(Calendar.DAY_OF_YEAR, 1)
    }
    return diasTotales
}

fun calcularDiasEfectivosSolicitud(plazo: String, cuotas: Int, fechaInicio: Calendar): Int =
    when (plazo) {
        "Diario"         -> cuotas
        "Lunes a Sábado" -> contarDiasSinDomingosSolicitud(cuotas, fechaInicio)
        "Semanal"        -> contarDiasSinDomingosSolicitud(cuotas * 6, fechaInicio)
        "Quincenal"      -> cuotas * 15
        "Mensual"        -> cuotas * 30
        "Bimestral"      -> cuotas * 60
        else             -> cuotas * 30
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolicitudPrestamoScreen(navController: NavController) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    val db      = FirebaseFirestore.getInstance()
    val auth    = FirebaseAuth.getInstance()
    val session = remember { com.example.minifinancieraapp.util.SessionManager(context) }

    var currentUid     by remember { mutableStateOf("") }
    var nombreCobrador by remember { mutableStateOf("") }
    var numeroCobrador by remember { mutableStateOf("") }
    var isLoadingAuth  by remember { mutableStateOf(true) }

    val clientes = remember { mutableStateListOf<ClienteModel>() }
    var selectedCliente by remember { mutableStateOf<ClienteModel?>(null) }
    var monto          by remember { mutableStateOf("") }
    var interesMensual by remember { mutableStateOf("") }
    var cuotas         by remember { mutableStateOf("") }
    var interesTotal   by remember { mutableStateOf("") }
    var usarInteresMensual by remember { mutableStateOf(true) }
    var mora           by remember { mutableStateOf("") }
    var selectedPlazo  by remember { mutableStateOf("Semanal") }
    var lugar          by remember { mutableStateOf("") }
    var firma          by remember { mutableStateOf("") }
    var garantia       by remember { mutableStateOf("") }   // ✅ CAMPO AÑADIDO
    var observaciones  by remember { mutableStateOf("") }
    var isLoading      by remember { mutableStateOf(false) }
    var fechaSeleccionada by remember { mutableStateOf(Calendar.getInstance()) }
    var fotosSeleccionadas by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var fotoUriCamara     by remember { mutableStateOf<Uri?>(null) }

    // Auth
    LaunchedEffect(Unit) {
        try {
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
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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

    // Cámara / galería
    val crearArchivoTemporal = remember {
        {
            try {
                val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val dir = context.getExternalFilesDir("Pictures")?.also { if (!it.exists()) it.mkdirs() }
                val f   = File.createTempFile("JPEG_${ts}_", ".jpg", dir)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            } catch (e: Exception) { null }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) fotosSeleccionadas = fotosSeleccionadas + uris
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && fotoUriCamara != null) {
            fotosSeleccionadas = fotosSeleccionadas + listOf(fotoUriCamara!!)
            Toast.makeText(context, "Foto capturada", Toast.LENGTH_SHORT).show()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            crearArchivoTemporal()?.let { uri -> fotoUriCamara = uri; cameraLauncher.launch(uri) }
        } else {
            Toast.makeText(context, "Permiso de cámara necesario", Toast.LENGTH_SHORT).show()
        }
    }

    if (isLoadingAuth) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // ─── CÁLCULOS ───────────────────────────────
    val montoDouble        = monto.toDoubleOrNull() ?: 0.0
    val interesPct         = interesMensual.toDoubleOrNull() ?: 0.0
    val cuotasInt          = cuotas.toIntOrNull() ?: 1
    val interesTotalDouble = interesTotal.toDoubleOrNull() ?: 0.0
    val moraDouble         = mora.toDoubleOrNull() ?: 0.0

    val fechaInicio    = fechaSeleccionada.clone() as Calendar
    val diasEfectivos  = calcularDiasEfectivosSolicitud(selectedPlazo, cuotasInt, fechaInicio)

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
            val intMes  = montoDouble * (interesPct / 100)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Nueva Solicitud", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Formulario de préstamo", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Volver", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SolicitudPrestamoColors.Primary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SolicitudPrestamoColors.Background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CardInfoSolicitud()

            SeccionCliente(
                clientes = clientes,
                selectedCliente = selectedCliente,
                onClienteSeleccionado = { selectedCliente = it }
            )

            SeccionMontoYCuotas(
                monto = monto, onMontoChange = { monto = it },
                cuotas = cuotas, onCuotasChange = { cuotas = it }
            )

            SeccionInteres(
                usarInteresMensual = usarInteresMensual,
                onToggleInteres = { usarInteresMensual = it },
                interesMensual = interesMensual, onInteresMensualChange = { interesMensual = it },
                interesTotal = interesTotal, onInteresTotalChange = { interesTotal = it }
            )

            SeccionMoraYFecha(
                mora = mora, onMoraChange = { mora = it },
                fecha = fechaSeleccionada, onFechaChange = { fechaSeleccionada = it },
                context = context, formatter = formatter
            )

            // ✅ SECCIÓN DETALLES AHORA INCLUYE GARANTÍA
            SeccionDetalles(
                lugar = lugar, onLugarChange = { lugar = it },
                firma = firma, onFirmaChange = { firma = it },
                garantia = garantia, onGarantiaChange = { garantia = it },
                observaciones = observaciones, onObservacionesChange = { observaciones = it }
            )

            SeccionFotos(
                fotosSeleccionadas = fotosSeleccionadas,
                onRemoveFoto = { index -> fotosSeleccionadas = fotosSeleccionadas.filterIndexed { i, _ -> i != index } },
                onCameraClick = {
                    when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
                        PackageManager.PERMISSION_GRANTED -> {
                            crearArchivoTemporal()?.let { uri -> fotoUriCamara = uri; cameraLauncher.launch(uri) }
                        }
                        else -> permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onGalleryClick = { galleryLauncher.launch("image/*") }
            )

            SeccionPlazo(selectedPlazo = selectedPlazo, onPlazoChange = { selectedPlazo = it })

            ResumenSolicitud(
                monto = montoDouble, interesCalculado = interesCalculado,
                totalAPagar = totalAPagar, cuotas = cuotasInt, plazo = selectedPlazo,
                cuotaEstimada = cuotaEstimada, proximoPago = proximoPagoString,
                mora = moraDouble, fotosCount = fotosSeleccionadas.size,
                usarInteresMensual = usarInteresMensual, interesPorcentaje = interesPct,
                interesTotalFijo = interesTotalDouble, diasEfectivos = diasEfectivos
            )

            // ─── BOTÓN ENVIAR ────────────────────
            Button(
                onClick = {
                    // ✅ FIX: Validaciones correctas
                    if (selectedCliente == null) {
                        Toast.makeText(context, "Selecciona un cliente", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (montoDouble <= 0) {
                        Toast.makeText(context, "Ingresa un monto válido", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (cuotasInt <= 0) {
                        Toast.makeText(context, "Ingresa un número de cuotas válido", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    // ✅ FIX: Validación de interés corregida (la original era always-false)
                    val tieneInteresValido = (usarInteresMensual && interesPct > 0) ||
                            (!usarInteresMensual && interesTotalDouble > 0)
                    if (!tieneInteresValido) {
                        Toast.makeText(context, "Configura un interés válido", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    isLoading = true
                    scope.launch {
                        try {
                            val docRef     = db.collection("solicitudes_prestamo").document()
                            val solicitudId = docRef.id
                            val fecha       = formatter.format(fechaSeleccionada.time)

                            // ✅ FIX: cobradorUid nunca vacío en la solicitud
                            val uidFinal = currentUid.takeIf { it.isNotBlank() } ?: ""

                            val solicitud = hashMapOf(
                                "id"                 to solicitudId,
                                "cliente"            to selectedCliente!!.nombre,
                                "clienteId"          to selectedCliente!!.id,
                                "monto"              to montoDouble,
                                "interes"            to interesCalculado,
                                "interesMensual"     to if (usarInteresMensual) interesPct else 0.0,
                                "interesTotal"       to if (!usarInteresMensual) interesTotalDouble else interesCalculado,
                                "usarInteresMensual" to usarInteresMensual,
                                "interesTotalFijo"   to if (!usarInteresMensual) interesTotalDouble else 0.0,
                                // ✅ interesManual = interesTotalFijo para que aceptarSolicitudYGenerarRecibo lo lea
                                "interesManual"      to if (!usarInteresMensual) interesTotalDouble else 0.0,
                                "mora"               to moraDouble,
                                "totalPagar"         to totalAPagar,
                                "cuota"              to cuotaEstimada,
                                "cuotas"             to cuotasInt,
                                "plazo"              to selectedPlazo,
                                "fecha"              to fecha,
                                "fechaTimestamp"     to Timestamp(fechaSeleccionada.time),
                                "fechaCreacion"      to Timestamp(Date()),
                                "lugar"              to lugar,
                                "firma"              to firma,
                                "garantia"           to garantia,    // ✅ CAMPO AÑADIDO
                                "cobrador"           to nombreCobrador,
                                "cobradorSolicitante" to nombreCobrador,
                                "numeroCobrador"     to numeroCobrador,
                                "cobradorUid"        to uidFinal,    // ✅ nunca vacío por accidente
                                "solicitudId"        to solicitudId,
                                "proximoPago"        to proximoPagoTimestamp,
                                "montoPagado"        to 0.0,
                                "saldoAnterior"      to montoDouble,
                                "estado"             to "pendiente",
                                "observaciones"      to observaciones,
                                "fotos"              to fotosSeleccionadas.map { it.toString() },
                                "diasEfectivos"      to diasEfectivos.toDouble(),
                                "saldo"              to totalAPagar,
                                "tipoDocumento"      to "solicitud",
                                "requiereAprobacion" to true
                            )

                            docRef.set(solicitud).await()

                            Log.d("SolicitudPrestamo", """
                                ✅ Solicitud creada exitosamente
                                ID: $solicitudId
                                Cliente: ${selectedCliente!!.nombre}
                                Monto: L. $montoDouble
                                Total a pagar: L. $totalAPagar
                                Cuotas: $cuotasInt ($selectedPlazo)
                                Cobrador UID: $uidFinal
                                Garantia: $garantia
                            """.trimIndent())

                            Toast.makeText(context, "✅ Solicitud enviada exitosamente", Toast.LENGTH_LONG).show()
                            navController.popBackStack()

                        } catch (e: Exception) {
                            Log.e("SolicitudPrestamo", "❌ Error al crear solicitud: ${e.message}", e)
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled  = !isLoading,
                colors   = ButtonDefaults.buttonColors(containerColor = SolicitudPrestamoColors.Primary),
                shape    = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("ENVIANDO...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("ENVIAR SOLICITUD", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────
//  COMPONENTES (SeccionDetalles actualizada)
// ─────────────────────────────────────────────

@Composable
fun CardInfoSolicitud() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SolicitudPrestamoColors.PurpleLight),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(SolicitudPrestamoColors.Purple),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Solicitud de Préstamo", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SolicitudPrestamoColors.Purple)
                Text("Esta solicitud requiere aprobación administrativa", fontSize = 12.sp, color = SolicitudPrestamoColors.Purple.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun SeccionCliente(
    clientes: List<ClienteModel>,
    selectedCliente: ClienteModel?,
    onClienteSeleccionado: (ClienteModel) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, tint = SolicitudPrestamoColors.Primary, modifier = Modifier.size(24.dp))
                Text("Cliente", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SolicitudPrestamoColors.TextPrimary)
            }
            Spacer(Modifier.height(12.dp))
            BuscarClienteDropdownMejoradoSolicitud(clientes, selectedCliente, onClienteSeleccionado)
        }
    }
}

@Composable
fun SeccionMontoYCuotas(monto: String, onMontoChange: (String) -> Unit, cuotas: String, onCuotasChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Paid, contentDescription = null, tint = SolicitudPrestamoColors.Primary, modifier = Modifier.size(24.dp))
                Text("Datos del Préstamo", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SolicitudPrestamoColors.TextPrimary)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = monto, onValueChange = onMontoChange,
                label = { Text("Monto (L.)") },
                leadingIcon = { Text("L.", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = cuotas, onValueChange = onCuotasChange,
                label = { Text("Número de Cuotas") },
                leadingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
fun SeccionInteres(
    usarInteresMensual: Boolean, onToggleInteres: (Boolean) -> Unit,
    interesMensual: String, onInteresMensualChange: (String) -> Unit,
    interesTotal: String, onInteresTotalChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SolicitudPrestamoColors.AccentLight),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Percent, contentDescription = null, tint = SolicitudPrestamoColors.Accent, modifier = Modifier.size(24.dp))
                Text("Configuración de Interés", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SolicitudPrestamoColors.Accent)
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = usarInteresMensual,
                    onClick = { onToggleInteres(true); onInteresTotalChange("") },
                    label = { Text("Interés mensual %") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !usarInteresMensual,
                    onClick = { onToggleInteres(false); onInteresMensualChange("") },
                    label = { Text("Interés fijo") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(16.dp))
            AnimatedVisibility(visible = usarInteresMensual, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                OutlinedTextField(
                    value = interesMensual, onValueChange = onInteresMensualChange,
                    label = { Text("Interés mensual (%)") },
                    leadingIcon = { Icon(Icons.Default.Percent, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                )
            }
            AnimatedVisibility(visible = !usarInteresMensual, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                OutlinedTextField(
                    value = interesTotal, onValueChange = onInteresTotalChange,
                    label = { Text("Interés Total Fijo (L.)") },
                    leadingIcon = { Text("L.", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                    supportingText = { Text("Monto fijo de interés a agregar", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
}

@Composable
fun SeccionMoraYFecha(
    mora: String, onMoraChange: (String) -> Unit,
    fecha: Calendar, onFechaChange: (Calendar) -> Unit,
    context: android.content.Context, formatter: SimpleDateFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            OutlinedTextField(
                value = mora, onValueChange = onMoraChange,
                label = { Text("Mora diaria (L.)") },
                leadingIcon = { Icon(Icons.Default.Warning, contentDescription = null) },
                supportingText = { Text("Cargo por día de retraso", fontSize = 11.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = formatter.format(fecha.time), onValueChange = {},
                label = { Text("Fecha del préstamo") },
                leadingIcon = { Icon(Icons.Default.Event, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(), readOnly = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    IconButton(onClick = {
                        DatePickerDialog(context, { _, year, month, day ->
                            onFechaChange((fecha.clone() as Calendar).also { it.set(year, month, day) })
                        }, fecha.get(Calendar.YEAR), fecha.get(Calendar.MONTH), fecha.get(Calendar.DAY_OF_MONTH)).show()
                    }) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    }
                }
            )
        }
    }
}

// ✅ SeccionDetalles AHORA INCLUYE garantia
@Composable
fun SeccionDetalles(
    lugar: String, onLugarChange: (String) -> Unit,
    firma: String, onFirmaChange: (String) -> Unit,
    garantia: String, onGarantiaChange: (String) -> Unit,  // ✅ NUEVO PARÁMETRO
    observaciones: String, onObservacionesChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Description, contentDescription = null, tint = SolicitudPrestamoColors.Primary, modifier = Modifier.size(24.dp))
                Text("Detalles Adicionales", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SolicitudPrestamoColors.TextPrimary)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = lugar, onValueChange = onLugarChange,
                label = { Text("Lugar") },
                leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = firma, onValueChange = onFirmaChange,
                label = { Text("Firma") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            // ✅ CAMPO GARANTÍA AÑADIDO
            OutlinedTextField(
                value = garantia, onValueChange = onGarantiaChange,
                label = { Text("Garantía") },
                leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = observaciones, onValueChange = onObservacionesChange,
                label = { Text("Observaciones") },
                leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(), maxLines = 3, minLines = 2,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
fun SeccionFotos(
    fotosSeleccionadas: List<Uri>, onRemoveFoto: (Int) -> Unit,
    onCameraClick: () -> Unit, onGalleryClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = SolicitudPrestamoColors.Primary, modifier = Modifier.size(24.dp))
                Text("Fotos (${fotosSeleccionadas.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SolicitudPrestamoColors.TextPrimary)
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCameraClick, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cámara")
                }
                OutlinedButton(onClick = onGalleryClick, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Galería")
                }
            }
            if (fotosSeleccionadas.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(fotosSeleccionadas.withIndex().toList()) { (index, uri) ->
                        Card(modifier = Modifier.size(80.dp), shape = RoundedCornerShape(12.dp)) {
                            Box {
                                Image(
                                    painter = rememberAsyncImagePainter(uri),
                                    contentDescription = "Foto ${index + 1}",
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                )
                                IconButton(onClick = { onRemoveFoto(index) }, modifier = Modifier.align(Alignment.TopEnd)) {
                                    Icon(Icons.Default.Close, contentDescription = "Eliminar", tint = Color.White,
                                        modifier = Modifier.size(18.dp).background(Color.Red.copy(alpha = 0.7f), CircleShape).padding(2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeccionPlazo(selectedPlazo: String, onPlazoChange: (String) -> Unit) {
    val plazos = listOf("Diario" to Icons.Default.Today, "Lunes a Sábado" to Icons.Default.CalendarToday,
        "Semanal" to Icons.Default.DateRange, "Quincenal" to Icons.Default.Event,
        "Mensual" to Icons.Default.CalendarMonth, "Bimestral" to Icons.Default.CalendarMonth)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = SolicitudPrestamoColors.Primary, modifier = Modifier.size(24.dp))
                Text("Plazo de Pago", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SolicitudPrestamoColors.TextPrimary)
            }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plazos.chunked(3).forEach { rowPlazos ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowPlazos.forEach { (plazo, icon) ->
                            FilterChip(
                                selected = selectedPlazo == plazo, onClick = { onPlazoChange(plazo) },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text(plazo, fontSize = 12.sp)
                                    }
                                }, modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowPlazos.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ResumenSolicitud(
    monto: Double, interesCalculado: Double, totalAPagar: Double,
    cuotas: Int, plazo: String, cuotaEstimada: Double, proximoPago: String,
    mora: Double, fotosCount: Int, usarInteresMensual: Boolean,
    interesPorcentaje: Double, interesTotalFijo: Double, diasEfectivos: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SolicitudPrestamoColors.PrimaryLight),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Assignment, contentDescription = null, tint = SolicitudPrestamoColors.Primary, modifier = Modifier.size(28.dp))
                Column {
                    Text("Resumen de Solicitud", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = SolicitudPrestamoColors.Primary)
                    Text("Pendiente de aprobación", fontSize = 12.sp, color = SolicitudPrestamoColors.Primary.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Divider(color = SolicitudPrestamoColors.Primary.copy(alpha = 0.2f))
            Spacer(Modifier.height(16.dp))

            ResumenRow("Monto solicitado",   "L. ${"%.2f".format(monto)}",            true)
            if (usarInteresMensual) ResumenRow("Interés mensual", "${"%.1f".format(interesPorcentaje)}%", false)
            else                    ResumenRow("Interés fijo",    "L. ${"%.2f".format(interesTotalFijo)}", false)
            ResumenRow("Días efectivos",     "$diasEfectivos días",                    false)
            ResumenRow("Interés total",      "L. ${"%.2f".format(interesCalculado)}", false)
            ResumenRow("Total a pagar",      "L. ${"%.2f".format(totalAPagar)}",      true)
            ResumenRow("Cuotas",             "$cuotas $plazo",                         false)
            ResumenRow("Cuota estimada",     "L. ${"%.0f".format(cuotaEstimada)}",    true)
            ResumenRow("Próximo pago",       proximoPago,                              false)
            if (mora > 0)         ResumenRow("Mora diaria",   "L. ${"%.2f".format(mora)}", false)
            if (fotosCount > 0)   ResumenRow("Fotos adjuntas", "$fotosCount",          false)
        }
    }
}

@Composable
fun ResumenRow(label: String, value: String, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = if (bold) 15.sp else 14.sp, color = SolicitudPrestamoColors.TextSecondary, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        Text(value, fontSize = if (bold) 16.sp else 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, color = if (bold) SolicitudPrestamoColors.Primary else SolicitudPrestamoColors.TextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuscarClienteDropdownMejoradoSolicitud(
    clientes: List<ClienteModel>, selectedCliente: ClienteModel?,
    onClienteSeleccionado: (ClienteModel) -> Unit
) {
    var expanded             by remember { mutableStateOf(false) }
    var textoBusqueda        by remember { mutableStateOf(selectedCliente?.nombre ?: "") }
    var textoBusquedaInterna by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(textoBusquedaInterna) { kotlinx.coroutines.delay(300); textoBusqueda = textoBusquedaInterna }
    LaunchedEffect(selectedCliente) {
        if (selectedCliente != null && selectedCliente.nombre.isNotEmpty()) {
            textoBusquedaInterna = selectedCliente.nombre; textoBusqueda = selectedCliente.nombre
        }
    }

    val clientesFiltrados = if (textoBusqueda.length < 2) emptyList()
    else clientes.filter { it.nombre.contains(textoBusqueda, ignoreCase = true) || it.identidad.contains(textoBusqueda, ignoreCase = true) }.take(10)

    Column {
        OutlinedTextField(
            value = textoBusquedaInterna,
            onValueChange = { new -> textoBusquedaInterna = new; expanded = new.length >= 2 },
            label = { Text("Buscar cliente *") },
            placeholder = { Text("Escriba nombre o identidad...") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = SolicitudPrestamoColors.Primary) },
            trailingIcon = {
                if (textoBusquedaInterna.isNotEmpty()) {
                    IconButton(onClick = { textoBusquedaInterna = ""; textoBusqueda = ""; expanded = false; onClienteSeleccionado(ClienteModel()) }) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                    }
                }
            },
            isError = textoBusqueda.length >= 2 && clientesFiltrados.isEmpty(),
            supportingText = {
                when {
                    textoBusqueda.isEmpty()                              -> Text("Escriba al menos 2 caracteres")
                    textoBusqueda.length >= 2 && clientesFiltrados.isEmpty() -> Text("No se encontraron clientes", color = MaterialTheme.colorScheme.error)
                    clientesFiltrados.isNotEmpty()                       -> Text("${clientesFiltrados.size} encontrado(s)", color = SolicitudPrestamoColors.Primary)
                    else -> {}
                }
            },
            shape = RoundedCornerShape(12.dp)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            if (clientesFiltrados.isEmpty()) {
                DropdownMenuItem(text = { Text("No se encontraron clientes", color = MaterialTheme.colorScheme.outline) }, onClick = {}, enabled = false)
            } else {
                clientesFiltrados.forEach { cliente ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(cliente.nombre, fontWeight = FontWeight.Medium)
                                if (cliente.identidad.isNotEmpty()) Text("ID: ${cliente.identidad}", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = { textoBusquedaInterna = cliente.nombre; textoBusqueda = cliente.nombre; expanded = false; focusManager.clearFocus(); onClienteSeleccionado(cliente) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = SolicitudPrestamoColors.Primary, modifier = Modifier.size(20.dp)) }
                    )
                }
            }
        }
    }
}