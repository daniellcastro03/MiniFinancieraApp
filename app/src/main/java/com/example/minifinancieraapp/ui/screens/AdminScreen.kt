package com.example.minifinancieraapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.TrendingUp
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
import com.example.capitalexpressapp.core.migrarNombresClientesAMayusculas
import com.example.capitalexpressapp.ui.theme.CEColors
import com.example.capitalexpressapp.ui.theme.IconoCaja
import com.example.capitalexpressapp.ui.theme.PremiumCard
import com.example.capitalexpressapp.ui.theme.SeccionTitulo
import com.example.capitalexpressapp.ui.theme.dashedBorder
import com.example.minifinancieraapp.util.SessionManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(navController: NavController, uid: String, rol: String) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()
    val session = remember(context) { SessionManager(context) }

    var nombreAdmin by remember { mutableStateOf("") }
    var fotoUrl by remember { mutableStateOf("") }
    var mostrarDialogoCerrarSesion by remember { mutableStateOf(false) }
    var chequeoManualActualizacion by remember { mutableStateOf(0) }

    ActualizacionOverlay(
        chequeoManualId = chequeoManualActualizacion,
        onSinActualizaciones = {
            Toast.makeText(context, "No hay actualizaciones disponibles", Toast.LENGTH_SHORT).show()
        }
    )

    LaunchedEffect(Unit) {
        migrarNombresClientesAMayusculas(context)
    }

    LaunchedEffect(uid) {
        try {
            val doc = db.collection("usuarios").document(uid).get().await()
            nombreAdmin = doc.getString("nombre") ?: ""
            fotoUrl = doc.getString("fotoUrl") ?: ""
        } catch (e: Exception) {
            nombreAdmin = ""
            fotoUrl = ""
        }
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
                            text = "ADMIN PANEL",
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
                    IconButton(onClick = { navController.navigate("NotificacionesScreen/$uid/$rol") }) {
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
                        Box {
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
                                        contentDescription = "Perfil Admin",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .align(Alignment.BottomEnd)
                                    .clip(CircleShape)
                                    .background(CEColors.Surface)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(CEColors.Secondary)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = "Hola, ${nombreAdmin.ifBlank { "Administrador" }}",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = CEColors.Primary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(CEColors.Secondary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Sesión activa • Admin Central",
                                    fontSize = 12.sp,
                                    color = CEColors.OnSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = CEColors.OutlineVariant)
                }
            }

            // VER NOTIFICACIONES (acceso rápido)
            item {
                FilaOpcion(
                    icon = Icons.Default.Notifications,
                    label = "Ver Notificaciones",
                    subtitle = "Centro de alertas",
                    iconoFondo = CEColors.Primary.copy(alpha = 0.06f),
                    iconoColor = CEColors.Primary
                ) {
                    navController.navigate("NotificacionesScreen/$uid/$rol")
                }
            }

            // SECCIÓN DE CREACIÓN
            item { SeccionTitulo("Crear") }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TarjetaGrid(
                        icon = Icons.Default.Add,
                        label = "Crear Préstamo",
                        subtitle = "Nueva solicitud",
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("crearprestamo")
                    }
                    TarjetaGrid(
                        icon = Icons.Default.PersonAdd,
                        label = "Crear Cliente",
                        subtitle = "Alta de usuario",
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("crearCliente")
                    }
                }
            }

            item {
                FilaOpcion(
                    icon = Icons.Default.Badge,
                    label = "Crear Usuario",
                    subtitle = "Gestión interna",
                    iconoFondo = CEColors.SurfaceContainer,
                    iconoColor = CEColors.Primary
                ) {
                    navController.navigate("crearUsuario")
                }
            }

            // SECCIÓN DE VISUALIZACIÓN
            item { SeccionTitulo("Visualizar") }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TarjetaGridOscura(
                        icon = Icons.Default.MarkUnreadChatAlt,
                        label = "Solicitudes",
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("solicitudesAdmin")
                    }
                    TarjetaGridClara(
                        icon = Icons.Default.Group,
                        label = "Ver Clientes",
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("ClientesVista/$uid/$rol")
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
                        navController.navigate("PrestamoAdminScreen/$uid/$rol")
                    }
                    TarjetaGridClara(
                        icon = Icons.Default.ManageAccounts,
                        label = "Ver Usuarios",
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("UsuariosVista")
                    }
                }
            }

            // SECCIÓN DE HISTORIAL
            item { SeccionTitulo("Historial") }

            item {
                FilaOpcion(
                    icon = Icons.Default.Payments,
                    label = "Historial de Pagos",
                    subtitle = "Arqueo de caja y transacciones",
                    iconoFondo = CEColors.SurfaceContainer,
                    iconoColor = CEColors.Primary
                ) {
                    navController.navigate("HistorialPagosScreen/$rol")
                }
            }
            item {
                FilaOpcion(
                    icon = Icons.Default.Folder,
                    label = "Historial de Préstamos",
                    subtitle = "Revisar préstamos otorgados",
                    iconoFondo = CEColors.SurfaceContainer,
                    iconoColor = CEColors.Primary
                ) {
                    navController.navigate("HistorialPrestamosScreen/$uid/$rol")
                }
            }
            item {
                FilaOpcion(
                    icon = Icons.Default.Analytics,
                    label = "Historial Global",
                    subtitle = "Auditoría completa del sistema",
                    iconoFondo = CEColors.SurfaceContainer,
                    iconoColor = CEColors.Primary
                ) {
                    navController.navigate("HistorialGlobalScreen")
                }
            }

            // SECCIÓN DE ESTADÍSTICAS Y HERRAMIENTAS
            item { SeccionTitulo("Estadísticas y Herramientas") }

            item {
                PremiumCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { navController.navigate("DashboardScreen") }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconoCaja(contenedorColor = CEColors.Primary.copy(alpha = 0.06f)) {
                            Icon(Icons.Default.Dashboard, contentDescription = null, tint = CEColors.Primary)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dashboard", fontWeight = FontWeight.Bold, color = CEColors.Primary, fontSize = 16.sp)
                            Text("Métricas en tiempo real", fontSize = 13.sp, color = CEColors.OnSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = CEColors.Secondary)
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dashedBorder(CEColors.OutlineVariant)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { chequeoManualActualizacion++ }
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconoCaja(contenedorColor = CEColors.SurfaceContainerLow) {
                            Icon(Icons.Default.Update, contentDescription = null, tint = CEColors.Primary)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Buscar Actualizaciones", fontWeight = FontWeight.Bold, color = CEColors.Primary, fontSize = 16.sp)
                            Text(
                                "Revisar si hay una nueva versión de la app",
                                fontSize = 13.sp,
                                color = CEColors.OnSurfaceVariant
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(CEColors.Primary)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("v$VERSION_APP", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
                        popUpTo(0) { inclusive = true }
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

/** Fila de opción de ancho completo: icono + título + subtítulo + flecha. */
@Composable
fun FilaOpcion(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    iconoFondo: Color,
    iconoColor: Color,
    onClick: () -> Unit
) {
    PremiumCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconoCaja(contenedorColor = iconoFondo) {
                Icon(icon, contentDescription = null, tint = iconoColor)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, color = CEColors.Primary, fontSize = 16.sp)
                Text(subtitle, fontSize = 13.sp, color = CEColors.OnSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "Ir",
                tint = CEColors.Outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** Tarjeta de grilla clara (2 columnas): icono navy sólido, texto + subtítulo. */
@Composable
fun TarjetaGrid(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    PremiumCard(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            IconoCaja(contenedorColor = CEColors.Primary) {
                Icon(icon, contentDescription = null, tint = Color.White)
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(label, fontWeight = FontWeight.Bold, color = CEColors.Primary, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.sp, color = CEColors.OnSurfaceVariant)
        }
    }
}

/** Tarjeta de grilla clara simple (icono gris claro + label centrado), usada en "Visualizar". */
@Composable
fun TarjetaGridClara(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    PremiumCard(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            IconoCaja(contenedorColor = CEColors.SurfaceContainerLow) {
                Icon(icon, contentDescription = null, tint = CEColors.Primary)
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(label, fontWeight = FontWeight.Bold, color = CEColors.Primary, fontSize = 15.sp)
        }
    }
}

/** Tarjeta de grilla oscura destacada, usada para "Solicitudes". */
@Composable
fun TarjetaGridOscura(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CEColors.Primary)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            IconoCaja(contenedorColor = Color.White.copy(alpha = 0.1f)) {
                Icon(icon, contentDescription = null, tint = CEColors.Secondary)
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(label, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
        }
    }
}
