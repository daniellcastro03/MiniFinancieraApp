package com.example.minifinancieraapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.capitalexpressapp.core.coincideAproximado
import com.example.capitalexpressapp.ui.theme.CEColors
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class UsuarioModel(
    val id: String,
    val nombre: String,
    val codigo: String,
    val rol: String,
    val prestamosAsignados: Int,
    val fotoUrl: String,
    val telefono: String,
    val estado: String = "activo"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsuariosVista(navController: NavController, rol: String) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var usuarios by remember { mutableStateOf(listOf<UsuarioModel>()) }
    var filtroRol by remember { mutableStateOf("todos") }
    var filtroEstado by remember { mutableStateOf("todos") }
    var busqueda by remember { mutableStateOf("") }

    var usuarioAEliminar by remember { mutableStateOf<UsuarioModel?>(null) }
    var usuarioAEditar by remember { mutableStateOf<UsuarioModel?>(null) }
    var usuarioAReactivar by remember { mutableStateOf<UsuarioModel?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val opcionesRol = listOf("todos", "admin", "cobrador")
    val opcionesEstado = listOf("todos", "activo", "inactivo")

    // Colores personalizados
    val primaryColor = CEColors.Primary
    val successColor = Color(0xFF4CAF50)
    val warningColor = Color(0xFFFF9800)
    val errorColor = Color(0xFFE53935)
    val surfaceColor = CEColors.Surface

    LaunchedEffect(Unit) {
        val usuariosSnapshot = db.collection("usuarios").get().await()
        val prestamosSnapshot = db.collection("prestamos").get().await()

        usuarios = usuariosSnapshot.documents.map { doc ->
            val id = doc.id
            val nombre = doc.getString("nombre") ?: ""
            val codigo = doc.getString("codigo") ?: ""
            val rolU = doc.getString("rol") ?: ""
            val foto = doc.getString("fotoUrl") ?: ""
            val telefono = doc.getString("telefono") ?: "No registrado"
            val estado = doc.getString("estado") ?: "activo"
            val prestamosAsignados = prestamosSnapshot.documents.count {
                it.getString("cobradorAsignado") == nombre && rolU == "cobrador"
            }
            UsuarioModel(id, nombre, codigo, rolU, prestamosAsignados, foto, telefono, estado)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Gestión de Usuarios",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = primaryColor
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .background(surfaceColor)
                .fillMaxSize()
        ) {
            // Header con estadísticas
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatsCard(
                        title = "Total",
                        value = usuarios.size.toString(),
                        color = primaryColor,
                        modifier = Modifier.weight(1f)
                    )
                    StatsCard(
                        title = "Activos",
                        value = usuarios.count { it.estado == "activo" }.toString(),
                        color = successColor,
                        modifier = Modifier.weight(1f)
                    )
                    StatsCard(
                        title = "Inactivos",
                        value = usuarios.count { it.estado == "inactivo" }.toString(),
                        color = errorColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Barra de búsqueda mejorada
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = busqueda,
                        onValueChange = { busqueda = it },
                        label = { Text("Buscar usuario") },
                        placeholder = { Text("Nombre o código...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Buscar")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Filtros en chips
                    Text(
                        "Filtrar por rol:",
                        fontWeight = FontWeight.Medium,
                        color = primaryColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(opcionesRol) { r ->
                            FilterChip(
                                onClick = { filtroRol = r },
                                label = { Text(r.replaceFirstChar { it.uppercase() }) },
                                selected = filtroRol == r,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = primaryColor,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Filtrar por estado:",
                        fontWeight = FontWeight.Medium,
                        color = primaryColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(opcionesEstado) { est ->
                            FilterChip(
                                onClick = { filtroEstado = est },
                                label = { Text(est.replaceFirstChar { it.uppercase() }) },
                                selected = filtroEstado == est,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = when (est) {
                                        "activo" -> successColor
                                        "inactivo" -> errorColor
                                        else -> primaryColor
                                    },
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            // remember(keys) evita repetir la búsqueda difusa (costosa) en cada
            // recomposición que no tenga que ver con la lista/filtros (p. ej. al scrollear).
            val listaFiltrada = remember(usuarios, filtroRol, filtroEstado, busqueda) {
                usuarios.filter {
                    (filtroRol == "todos" || it.rol == filtroRol) &&
                            (filtroEstado == "todos" || it.estado == filtroEstado) &&
                            (busqueda.isBlank() || coincideAproximado(busqueda, it.nombre) || it.codigo.contains(busqueda, true))
                }
            }

            // Lista de usuarios mejorada
            LazyColumn(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(listaFiltrada, key = { it.id }) { usuario ->
                    ModernUserCard(
                        usuario = usuario,
                        onEdit = { usuarioAEditar = usuario },
                        onDelete = { usuarioAEliminar = usuario },
                        onReactivate = { usuarioAReactivar = usuario },
                        userRole = rol
                    )
                }
            }
        }
    }

    // Diálogos con mejor diseño
    usuarioAEliminar?.let { usuario ->
        ModernAlertDialog(
            onDismissRequest = { usuarioAEliminar = null },
            title = "¿Inactivar usuario?",
            text = "¿Deseas marcar a ${usuario.nombre} como inactivo? Esta acción se puede revertir.",
            confirmText = "Inactivar",
            confirmColor = errorColor,
            onConfirm = {
                scope.launch {
                    db.collection("usuarios").document(usuario.id).update("estado", "inactivo").await()
                    usuarios = usuarios.map {
                        if (it.id == usuario.id) it.copy(estado = "inactivo") else it
                    }
                    usuarioAEliminar = null
                    snackbarHostState.showSnackbar("Usuario inactivado correctamente")
                }
            },
            onDismiss = { usuarioAEliminar = null }
        )
    }

    usuarioAReactivar?.let { usuario ->
        ModernAlertDialog(
            onDismissRequest = { usuarioAReactivar = null },
            title = "¿Reactivar usuario?",
            text = "¿Deseas reactivar a ${usuario.nombre}? El usuario volverá a estar activo en el sistema.",
            confirmText = "Reactivar",
            confirmColor = successColor,
            onConfirm = {
                scope.launch {
                    db.collection("usuarios").document(usuario.id).update("estado", "activo").await()
                    usuarios = usuarios.map {
                        if (it.id == usuario.id) it.copy(estado = "activo") else it
                    }
                    usuarioAReactivar = null
                    snackbarHostState.showSnackbar("Usuario reactivado correctamente")
                }
            },
            onDismiss = { usuarioAReactivar = null }
        )
    }

    usuarioAEditar?.let { usuario ->
        ModernAlertDialog(
            onDismissRequest = { usuarioAEditar = null },
            title = "Editar usuario",
            text = "¿Deseas editar la información de ${usuario.nombre}?",
            confirmText = "Editar",
            confirmColor = warningColor,
            onConfirm = {
                usuarioAEditar = null
                navController.navigate("editarUsuario/${usuario.id}")
            },
            onDismiss = { usuarioAEditar = null }
        )
    }
}

@Composable
fun StatsCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = title,
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

@Composable
fun ModernUserCard(
    usuario: UsuarioModel,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReactivate: () -> Unit,
    userRole: String
) {
    val isActive = usuario.estado == "activo"
    val cardColor = if (isActive) Color.White else Color(0xFFF5F5F5)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isActive) 4.dp else 2.dp,
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar mejorado
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    CEColors.Primary.copy(alpha = 0.1f),
                                    CEColors.Primary.copy(alpha = 0.3f)
                                )
                            )
                        )
                        .border(2.dp, CEColors.Primary.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (usuario.fotoUrl.isNotBlank()) {
                        val context = LocalContext.current
                        Image(
                            painter = rememberAsyncImagePainter(
                                model = coil.request.ImageRequest.Builder(context)
                                    .data(usuario.fotoUrl)
                                    .size(200, 200)
                                    .build()
                            ),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = CEColors.Primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Información del usuario
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = usuario.nombre,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = if (isActive) Color.Black else Color.Gray
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Badge,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color.Gray
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${usuario.rol.uppercase()} • ${usuario.codigo}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    if (usuario.telefono != "No registrado") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color.Gray
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = usuario.telefono,
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }

                // Estado visual
                Surface(
                    color = if (isActive) Color(0xFF4CAF50) else Color(0xFFE53935),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isActive) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = usuario.estado.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }

            // Información adicional si es cobrador
            if (usuario.rol == "cobrador") {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = CEColors.ActionBlue.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Assignment,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = CEColors.ActionBlue
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Préstamos asignados: ${usuario.prestamosAsignados}",
                            fontSize = 13.sp,
                            color = CEColors.ActionBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Botones de acción
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botón Editar
                FilledTonalButton(
                    onClick = onEdit,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CEColors.ActionBlue.copy(alpha = 0.1f),
                        contentColor = CEColors.ActionBlue
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Editar", fontSize = 12.sp)
                }

                // Botón Inactivar/Reactivar
                if (isActive) {
                    FilledTonalButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFE53935).copy(alpha = 0.1f),
                            contentColor = Color(0xFFE53935)
                        )
                    ) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Inactivar", fontSize = 12.sp)
                    }
                } else if (userRole == "admin") {
                    FilledTonalButton(
                        onClick = onReactivate,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f),
                            contentColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reactivar", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ModernAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmText: String,
    confirmColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Text(
                text = text,
                fontSize = 14.sp,
                color = Color.Gray
            )
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onConfirm,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = confirmColor,
                    contentColor = Color.White
                )
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}