package com.example.capitalexpressapp.ui.screens

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.capitalexpressapp.R
import com.example.capitalexpressapp.util.NetworkUtils
import com.example.minifinancieraapp.util.SessionManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(navController: NavController) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    val db = FirebaseFirestore.getInstance()

    val codigo = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    val passwordVisible = remember { mutableStateOf(false) }
    val isLoading = remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Estados de animación
    val logoVisible = remember { mutableStateOf(false) }
    val contentVisible = remember { mutableStateOf(false) }

    // Animación de pulso suave
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAnimation by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse"
    )

    LaunchedEffect(Unit) {
        logoVisible.value = true
        delay(300)
        contentVisible.value = true

        if (!NetworkUtils.isInternetAvailable(context)) {
            sessionManager.getSession()?.let { (uid, _, rol) ->
                when (rol) {
                    "admin" -> navController.navigate("AdminScreen/$uid/admin")
                    "cobrador" -> navController.navigate("CobradorPantalla/$uid")
                }
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                snackbar = { snackbarData ->
                    Snackbar(
                        snackbarData = snackbarData,
                        shape = RoundedCornerShape(12.dp),
                        containerColor = Color(0xFF1565C0),
                        contentColor = Color.White
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1565C0), // Azul primario
                            Color(0xFF0D47A1), // Azul más oscuro
                            Color(0xFF01579B)  // Azul profundo
                        )
                    )
                )
                .padding(padding)
        ) {
            // Elementos decorativos de fondo
            Box(modifier = Modifier.fillMaxSize()) {
                // Círculos decorativos con blur
                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .offset(x = (-100).dp, y = (-50).dp)
                        .blur(80.dp)
                        .background(
                            Color(0xFF42A5F5).copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                )

                Box(
                    modifier = Modifier
                        .size(250.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 100.dp, y = 100.dp)
                        .blur(70.dp)
                        .background(
                            Color(0xFF1E88E5).copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                )

                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .align(Alignment.CenterEnd)
                        .offset(x = 50.dp)
                        .blur(60.dp)
                        .background(
                            Color(0xFF2196F3).copy(alpha = 0.2f),
                            shape = CircleShape
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo animado
                AnimatedVisibility(
                    visible = logoVisible.value,
                    enter = fadeIn(animationSpec = tween(800)) +
                            scaleIn(animationSpec = tween(800, easing = FastOutSlowInEasing))
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 40.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            // Resplandor detrás del logo
                            Box(
                                modifier = Modifier
                                    .size(180.dp)
                                    .scale(pulseAnimation * 0.15f + 0.85f)
                                    .blur(40.dp)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                Color(0xFF64B5F6).copy(alpha = 0.6f),
                                                Color.Transparent
                                            )
                                        ),
                                        shape = CircleShape
                                    )
                            )

                            // Logo con sombra elegante
                            Box(
                                modifier = Modifier
                                    .size(140.dp)
                                    .shadow(
                                        elevation = 30.dp,
                                        shape = CircleShape,
                                        spotColor = Color(0xFF90CAF9).copy(alpha = 0.8f)
                                    )
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.logo_capital),
                                    contentDescription = "Logo Capital Express",
                                    modifier = Modifier
                                        .size(100.dp)
                                        .scale(pulseAnimation * 0.05f + 0.95f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        // Título elegante
                        Text(
                            text = "CAPITAL EXPRESS",
                            fontSize = 32.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Tu socio financiero de confianza",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Formulario de login moderno
                AnimatedVisibility(
                    visible = contentVisible.value,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(600, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(600))
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(
                                elevation = 40.dp,
                                shape = RoundedCornerShape(32.dp),
                                spotColor = Color.Black.copy(alpha = 0.25f)
                            ),
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Título del formulario
                            Text(
                                text = "Iniciar Sesión",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1565C0),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Campo de código
                            OutlinedTextField(
                                value = codigo.value,
                                onValueChange = { codigo.value = it },
                                label = { Text("Código único") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = Color(0xFF1565C0)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = modernTextFieldColors(),
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true
                            )

                            // Campo de contraseña
                            OutlinedTextField(
                                value = password.value,
                                onValueChange = { password.value = it },
                                label = { Text("Contraseña") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = Color(0xFF1565C0)
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { passwordVisible.value = !passwordVisible.value }
                                    ) {
                                        Icon(
                                            imageVector = if (passwordVisible.value)
                                                Icons.Default.Visibility
                                            else
                                                Icons.Default.VisibilityOff,
                                            contentDescription = if (passwordVisible.value)
                                                "Ocultar contraseña"
                                            else
                                                "Mostrar contraseña",
                                            tint = Color(0xFF1565C0)
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible.value)
                                    VisualTransformation.None
                                else
                                    PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = modernTextFieldColors(),
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Botón de login moderno
                            Button(
                                onClick = {
                                    val code = codigo.value.trim()
                                    val pass = password.value.trim()

                                    if (code.isEmpty() || pass.isEmpty()) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Por favor, completa todos los campos")
                                        }
                                        return@Button
                                    }

                                    isLoading.value = true

                                    db.collection("usuarios")
                                        .whereEqualTo("codigo", code)
                                        .whereEqualTo("password", pass)
                                        .get()
                                        .addOnSuccessListener { result ->
                                            isLoading.value = false
                                            if (result.isEmpty) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar("Credenciales incorrectas")
                                                }
                                            } else {
                                                val userDoc = result.documents[0]
                                                val uid = userDoc.id
                                                val rol = userDoc.getString("rol") ?: ""
                                                val nombre = userDoc.getString("nombre") ?: ""
                                                val estado = userDoc.getString("estado") ?: "activo"

                                                if (estado != "activo") {
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar("Tu cuenta está inactiva. Contacta al administrador.")
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        sessionManager.saveSession(uid, nombre, rol)
                                                    }
                                                    when (rol) {
                                                        "admin" -> navController.navigate("AdminScreen/$uid/admin")
                                                        "cobrador" -> navController.navigate("CobradorPantalla/$uid")
                                                        else -> coroutineScope.launch {
                                                            snackbarHostState.showSnackbar("Rol no reconocido: $rol")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .addOnFailureListener {
                                            isLoading.value = false
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("Error de conexión. Verifica tu internet.")
                                            }
                                        }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1565C0),
                                    disabledContainerColor = Color(0xFF1565C0).copy(alpha = 0.6f)
                                ),
                                shape = RoundedCornerShape(16.dp),
                                enabled = !isLoading.value,
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 4.dp,
                                    pressedElevation = 8.dp
                                )
                            ) {
                                if (isLoading.value) {
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            "Iniciando...",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                    }
                                } else {
                                    Text(
                                        "Iniciar Sesión",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
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
private fun modernTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF1565C0),
    unfocusedBorderColor = Color(0xFFBDBDBD),
    cursorColor = Color(0xFF1565C0),
    focusedLabelColor = Color(0xFF1565C0),
    unfocusedLabelColor = Color(0xFF757575),
    focusedTextColor = Color(0xFF212121),
    unfocusedTextColor = Color(0xFF212121)
)