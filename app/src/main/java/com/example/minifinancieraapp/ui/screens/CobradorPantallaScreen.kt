package com.example.minifinancieraapp.ui.screens

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.example.capitalexpressapp.core.ActualizacionOverlay
import com.example.capitalexpressapp.core.VERSION_APP
import com.example.capitalexpressapp.ui.theme.CEColors
import com.example.capitalexpressapp.ui.theme.SeccionTitulo
import com.example.minifinancieraapp.util.SessionManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CobradorPantallaScreen(navController: NavController, uid: String) {
    val context = LocalContext.current
    val session = remember(context) { SessionManager(context) }

    val nombreCobrador = session.getNombre()
    val fotoUrl = session.getFotoUrl()
    var mostrarDialogoCerrarSesion by remember { mutableStateOf(false) }
    var hayAbonosPendientes by remember { mutableStateOf(false) }
    var chequeoManualActualizacion by remember { mutableStateOf(0) }

    ActualizacionOverlay(
        chequeoManualId = chequeoManualActualizacion,
        onSinActualizaciones = {
            Toast.makeText(context, "No hay actualizaciones disponibles", Toast.LENGTH_SHORT).show()
        }
    )

    // Verifica si hay abonos pendientes en SharedPreferences
    LaunchedEffect(Unit) {
        val prefs: SharedPreferences = context.getSharedPreferences("offline_data", Context.MODE_PRIVATE)
        val json = prefs.getString("abonos_pendientes", "[]")
        val tipo = object : TypeToken<List<Map<String, Any>>>() {}.type
        val lista = Gson().fromJson<List<Map<String, Any>>>(json, tipo)
        hayAbonosPendientes = lista.isNotEmpty()
    }

    val drawerController = com.example.capitalexpressapp.core.LocalDrawerController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Capital Express",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "PANEL DE COBRANZA",
                            color = CEColors.Secondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { drawerController.abrir() }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menú", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("NotificacionesScreen/$uid/cobrador") }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notificaciones", tint = Color.White)
                    }
                    IconButton(onClick = { mostrarDialogoCerrarSesion = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Cerrar sesión",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CEColors.Primary
                )
            )
        },
        containerColor = CEColors.Surface
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(CEColors.Surface),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            // ENCABEZADO DE PERFIL
            item {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(CEColors.Primary),
                            contentAlignment = Alignment.Center
                        ) {
                            if (fotoUrl.isNotBlank()) {
                                Image(
                                    painter = rememberAsyncImagePainter(fotoUrl),
                                    contentDescription = "Foto de perfil",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(18.dp))
                                )
                            } else {
                                Icon(
                                    Icons.Default.AccountCircle,
                                    contentDescription = "Perfil",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = "¡Bienvenido!",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = CEColors.Primary
                            )
                            Text(
                                text = nombreCobrador.ifBlank { "Cobrador" },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = CEColors.OnSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = CEColors.OutlineVariant)
                }
            }

            // SECCIÓN DE OPCIONES PRINCIPALES
            item { SeccionTitulo("Opciones Principales") }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TarjetaGrid(
                        icon = Icons.Default.PersonAdd,
                        label = "Registrar Cliente",
                        subtitle = "Alta de usuario",
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("crearCliente")
                    }
                    TarjetaGridClara(
                        icon = Icons.Default.Group,
                        label = "Ver Clientes",
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("ClientesVista/$uid/cobrador")
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TarjetaGridClara(
                        icon = Icons.Default.AccountBalance,
                        label = "Ver Préstamos",
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("PrestamoAdminScreen/$uid/cobrador")
                    }
                    TarjetaGridClara(
                        icon = Icons.AutoMirrored.Filled.ReceiptLong,
                        label = "Mis Pagos",
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("PagosAsignadosCobrador")
                    }
                }
            }

            // SECCIÓN DE GESTIÓN
            item { SeccionTitulo("Gestión y Servicios") }

            item {
                FilaOpcion(
                    icon = Icons.Default.AddCard,
                    label = "Solicitar Préstamo",
                    subtitle = "Crear nueva solicitud",
                    iconoFondo = CEColors.SurfaceContainer,
                    iconoColor = CEColors.Primary
                ) {
                    navController.navigate("solicitudPrestamo/${nombreCobrador}")
                }
            }
            item {
                FilaOpcion(
                    icon = Icons.Default.Notifications,
                    label = "Ver Notificaciones",
                    subtitle = "Revisar mensajes y alertas",
                    iconoFondo = CEColors.Primary.copy(alpha = 0.06f),
                    iconoColor = CEColors.Primary
                ) {
                    navController.navigate("NotificacionesScreen/$uid/cobrador")
                }
            }
            item {
                FilaOpcion(
                    icon = Icons.Default.Update,
                    label = "Buscar Actualizaciones",
                    subtitle = "Versión actual: v$VERSION_APP",
                    iconoFondo = CEColors.SurfaceContainer,
                    iconoColor = CEColors.Primary
                ) {
                    chequeoManualActualizacion++
                }
            }

            // SECCIÓN DE HISTORIAL
            item { SeccionTitulo("Historial") }

            item {
                FilaOpcion(
                    icon = Icons.Default.Folder,
                    label = "Historial Préstamos",
                    subtitle = "Listado completo de movimientos",
                    iconoFondo = CEColors.SurfaceContainer,
                    iconoColor = CEColors.Primary
                ) {
                    navController.navigate("HistorialPrestamosScreen/$uid/cobrador")
                }
            }

            // SINCRONIZACIÓN
            item { SeccionTitulo("Sincronización") }

            item {
                BotonSincronizacion(
                    icon = Icons.Default.Sync,
                    text = "Sincronizar Solicitudes",
                    onClick = { navController.navigate("SincronizarSolicitudesPendientes") }
                )
            }

            if (hayAbonosPendientes) {
                item {
                    BotonSincronizacion(
                        icon = Icons.Default.Sync,
                        text = "Sincronizar Abonos",
                        onClick = { navController.navigate("SincronizarAbonosPendientes") },
                        destacado = true
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (mostrarDialogoCerrarSesion) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoCerrarSesion = false },
            title = {
                Text(
                    "¿Cerrar sesión?",
                    fontWeight = FontWeight.Bold,
                    color = CEColors.Primary
                )
            },
            text = {
                Text(
                    "¿Estás seguro que deseas cerrar sesión?",
                    color = CEColors.OnSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    session.clearSession()
                    navController.navigate("login") {
                        popUpTo("CobradorPantalla/$uid") { inclusive = true }
                    }
                }) {
                    Text("Cerrar sesión", color = CEColors.Error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogoCerrarSesion = false }) {
                    Text("Cancelar", color = CEColors.ActionBlue)
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun BotonSincronizacion(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    destacado: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destacado) Color(0xFFE67E22) else CEColors.Primary
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
