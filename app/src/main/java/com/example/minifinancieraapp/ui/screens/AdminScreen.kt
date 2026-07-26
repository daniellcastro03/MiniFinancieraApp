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
import com.example.capitalexpressapp.core.migrarNombresClientesAMayusculas
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Capital Express - Admin",
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
                                        Icons.Default.AdminPanelSettings,
                                        contentDescription = "Perfil Admin",
                                        tint = Color(0xFF0061A7),
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "¡Bienvenido Administrador!",
                                fontSize = 16.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                fontWeight = FontWeight.Normal
                            )

                            Text(
                                text = nombreAdmin.ifBlank { "Admin" },
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // SECCIÓN DE CREACIÓN
            item {
                Text(
                    text = "Crear",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // FILA 1: CREAR PRÉSTAMO Y CREAR CLIENTE
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AdminOpcionCardCompacta(
                        icon = Icons.Default.Add,
                        label = "Crear Préstamo",
                        color = Color(0xFF27AE60),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("crearprestamo")
                    }

                    AdminOpcionCardCompacta(
                        icon = Icons.Default.PersonAdd,
                        label = "Crear Cliente",
                        color = Color(0xFF3498DB),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("crearCliente")
                    }
                }
            }

            // CREAR USUARIO
            item {
                AdminOpcionCard(
                    icon = Icons.Default.AccountBox,
                    label = "Crear Usuario",
                    subtitle = "Agregar nuevo usuario al sistema",
                    color = Color(0xFF9B59B6)
                ) {
                    navController.navigate("crearUsuario")
                }
            }

            // SECCIÓN DE VISUALIZACIÓN
            item {
                Text(
                    text = "Visualizar",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // FILA 2: CLIENTES Y PRÉSTAMOS
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AdminOpcionCardCompacta(
                        icon = Icons.Default.People,
                        label = "Ver Clientes",
                        color = Color(0xFF3498DB),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("ClientesVista/$uid/$rol")
                    }

                    AdminOpcionCardCompacta(
                        icon = Icons.Default.FolderOpen,
                        label = "Ver Préstamos",
                        color = Color(0xFF9B59B6),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("PrestamoAdminScreen/$uid/$rol")
                    }
                }
            }

            // FILA 3: USUARIOS Y SOLICITUDES
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AdminOpcionCardCompacta(
                        icon = Icons.Default.AccountCircle,
                        label = "Ver Usuarios",
                        color = Color(0xFFE67E22),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("UsuariosVista")
                    }

                    AdminOpcionCardCompacta(
                        icon = Icons.Default.MarkUnreadChatAlt,
                        label = "Solicitudes",
                        color = Color(0xFFE74C3C),
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("solicitudesAdmin")
                    }
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

            // HISTORIAL PAGOS
            item {
                AdminOpcionCard(
                    icon = Icons.Default.DateRange,
                    label = "Historial de Pagos",
                    subtitle = "Consultar pagos realizados",
                    color = Color(0xFF27AE60)
                ) {
                    navController.navigate("HistorialPagosScreen/$rol")
                }
            }

            // HISTORIAL PRÉSTAMOS
            item {
                AdminOpcionCard(
                    icon = Icons.Default.Folder,
                    label = "Historial de Préstamos",
                    subtitle = "Revisar préstamos otorgados",
                    color = Color(0xFF3498DB)
                ) {
                    navController.navigate("HistorialPrestamosScreen/$uid/$rol")
                }
            }

            // HISTORIAL GLOBAL
            item {
                AdminOpcionCard(
                    icon = Icons.Default.Assessment,
                    label = "Historial Global",
                    subtitle = "Vista completa del sistema",
                    color = Color(0xFF9B59B6)
                ) {
                    navController.navigate("HistorialGlobalScreen")
                }
            }

            // SECCIÓN DE ESTADÍSTICAS Y HERRAMIENTAS
            item {
                Text(
                    text = "Estadísticas y Herramientas",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // DASHBOARD
            item {
                AdminOpcionCard(
                    icon = Icons.Default.Insights,
                    label = "Dashboard",
                    subtitle = "Estadísticas y métricas del sistema",
                    color = Color(0xFF8E44AD)
                ) {
                    navController.navigate("DashboardScreen")
                }
            }

            // NOTIFICACIONES
            item {
                AdminOpcionCard(
                    icon = Icons.Default.Notifications,
                    label = "Ver Notificaciones",
                    subtitle = "Revisar mensajes y alertas",
                    color = Color(0xFFE74C3C)
                ) {
                    navController.navigate("NotificacionesScreen/$uid/$rol")
                }
            }

            // BUSCAR ACTUALIZACIONES
            item {
                AdminOpcionCard(
                    icon = Icons.Default.SystemUpdate,
                    label = "Buscar Actualizaciones",
                    subtitle = "Revisar si hay una nueva versión de la app",
                    color = Color(0xFF16A085)
                ) {
                    chequeoManualActualizacion++
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
                        popUpTo(0) { inclusive = true }
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
fun AdminOpcionCard(
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
fun AdminOpcionCardCompacta(
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