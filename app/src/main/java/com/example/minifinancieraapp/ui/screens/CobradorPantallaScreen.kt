package com.example.minifinancieraapp.ui.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.example.capitalexpressapp.core.ActualizacionOverlay
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

    ActualizacionOverlay()

    // Verifica si hay abonos pendientes en SharedPreferences
    LaunchedEffect(Unit) {
        val prefs: SharedPreferences = context.getSharedPreferences("offline_data", Context.MODE_PRIVATE)
        val json = prefs.getString("abonos_pendientes", "[]")
        val tipo = object : TypeToken<List<Map<String, Any>>>() {}.type
        val lista = Gson().fromJson<List<Map<String, Any>>>(json, tipo)
        hayAbonosPendientes = lista.isNotEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Capital Express - Cobrador",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    IconButton(onClick = { mostrarDialogoCerrarSesion = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Cerrar sesión",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0061A7)
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFFF5F7FA)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            // HEADER CON FOTO Y NOMBRE
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF0061A7),
                                        Color(0xFF0077CC)
                                    )
                                )
                            )
                            .padding(24.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // FOTO DE PERFIL
                            if (fotoUrl.isNotBlank()) {
                                Image(
                                    painter = rememberAsyncImagePainter(fotoUrl),
                                    contentDescription = "Foto de perfil",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = "Perfil",
                                        tint = Color(0xFF0061A7),
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "¡Bienvenido!",
                                fontSize = 16.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                fontWeight = FontWeight.Normal
                            )

                            Text(
                                text = nombreCobrador.ifBlank { "Cobrador" },
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // SECCIÓN DE OPCIONES PRINCIPALES
            item {
                Text(
                    text = "Opciones Principales",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // FILA 1: REGISTRAR CLIENTE Y VER CLIENTES
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CobradorOpcionCardCompacta(
                        icon = Icons.Default.PersonAdd,
                        label = "Registrar Cliente",
                        color = Color(0xFF27AE60),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("crearCliente")
                    }

                    CobradorOpcionCardCompacta(
                        icon = Icons.Default.People,
                        label = "Ver Clientes",
                        color = Color(0xFF3498DB),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("ClientesVista/$uid/cobrador")
                    }
                }
            }

            // FILA 2: VER PRÉSTAMOS Y MIS PAGOS
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CobradorOpcionCardCompacta(
                        icon = Icons.Default.AccountBalance,
                        label = "Ver Préstamos",
                        color = Color(0xFF9B59B6),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("PrestamoAdminScreen/$uid/cobrador")
                    }

                    CobradorOpcionCardCompacta(
                        icon = Icons.Default.Money,
                        label = "Mis Pagos",
                        color = Color(0xFFE67E22),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("PagosAsignadosCobrador")
                    }
                }
            }

            // SECCIÓN DE GESTIÓN
            item {
                Text(
                    text = "Gestión y Servicios",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // SOLICITAR PRÉSTAMO
            item {
                CobradorOpcionCard(
                    icon = Icons.Default.DateRange,
                    label = "Solicitar Préstamo",
                    subtitle = "Crear nueva solicitud",
                    color = Color(0xFF8E44AD)
                ) {
                    navController.navigate("solicitudPrestamo/${nombreCobrador}")
                }
            }

            // NOTIFICACIONES
            item {
                CobradorOpcionCard(
                    icon = Icons.Default.Notifications,
                    label = "Ver Notificaciones",
                    subtitle = "Revisar mensajes y alertas",
                    color = Color(0xFFE74C3C)
                ) {
                    navController.navigate("NotificacionesScreen/$uid/cobrador")
                }
            }

            // SECCIÓN DE HISTORIAL
            item {
                Text(
                    text = "Historial",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // FILA 3: HISTORIAL PAGOS Y HISTORIAL PRÉSTAMOS
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CobradorOpcionCardCompacta(
                        icon = Icons.Default.Folder,
                        label = "Historial Préstamos",
                        color = Color(0xFF3498DB),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("HistorialPrestamosScreen/$uid/cobrador")
                    }
                }
            }

            // BOTONES DE SINCRONIZACIÓN
            item {
                Text(
                    text = "Sincronización",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SincronizacionButton(
                    text = "🔄 Sincronizar Solicitudes",
                    onClick = { navController.navigate("SincronizarSolicitudesPendientes") }
                )
            }

            if (hayAbonosPendientes) {
                item {
                    SincronizacionButton(
                        text = "💰 Sincronizar Abonos",
                        onClick = { navController.navigate("SincronizarAbonosPendientes") },
                        isHighlighted = true
                    )
                }
            }

            // ESPACIO ADICIONAL AL FINAL
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (mostrarDialogoCerrarSesion) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoCerrarSesion = false },
            title = {
                Text(
                    "¿Cerrar sesión?",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50)
                )
            },
            text = {
                Text(
                    "¿Estás seguro que deseas cerrar sesión?",
                    color = Color(0xFF7F8C8D)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    session.clearSession()
                    navController.navigate("login") {
                        popUpTo("CobradorPantalla/$uid") { inclusive = true }
                    }
                }) {
                    Text("Cerrar sesión", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { mostrarDialogoCerrarSesion = false }) {
                    Text("Cancelar", color = Color(0xFF0061A7))
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun CobradorOpcionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .shadow(6.dp, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(color.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50)
                )
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = Color(0xFF7F8C8D)
                )
            }

            Icon(
                Icons.Default.ArrowForward,
                contentDescription = "Ir",
                tint = Color(0xFFBDC3C7),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun CobradorOpcionCardCompacta(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clickable { onClick() }
            .shadow(4.dp, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF2C3E50),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SincronizacionButton(
    text: String,
    onClick: () -> Unit,
    isHighlighted: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp)),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isHighlighted) Color(0xFFE67E22) else Color(0xFF0061A7)
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}