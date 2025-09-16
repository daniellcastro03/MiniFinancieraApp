package com.example.capitalexpressapp.ui.screens

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
    val backgroundAnimation = remember { mutableStateOf(0f) }

    // Animaciones infinitas
    val infiniteTransition = rememberInfiniteTransition(label = "background")
    val animatedOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "rotate"
    )

    val pulseAnimation by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse"
    )

    LaunchedEffect(Unit) {
        // Animación secuencial de entrada
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
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0D47A1),
                            Color(0xFF1565C0),
                            Color(0xFF1976D2),
                            Color(0xFF0D47A1)
                        ),
                        radius = 1000f
                    )
                )
                .padding(padding)
        ) {
            // Elementos de fondo animados
            AnimatedBackgroundElements(animatedOffset, pulseAnimation)

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
                        modifier = Modifier.padding(bottom = 32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .shadow(
                                    elevation = 20.dp,
                                    shape = CircleShape,
                                    spotColor = Color.White.copy(alpha = 0.3f)
                                )
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.2f),
                                            Color.White.copy(alpha = 0.1f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.logo_capital),
                                contentDescription = "Logo Capital Express",
                                modifier = Modifier
                                    .size(100.dp)
                                    .scale(pulseAnimation * 0.1f + 0.9f)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "CAPITAL EXPRESS",
                            fontSize = 32.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.shadow(
                                elevation = 8.dp,
                                spotColor = Color.Black.copy(alpha = 0.5f)
                            )
                        )

                        Text(
                            text = "Tu socio financiero de confianza",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                // Formulario de login
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
                                elevation = 25.dp,
                                shape = RoundedCornerShape(24.dp),
                                spotColor = Color.Black.copy(alpha = 0.3f)
                            ),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.95f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Text(
                                text = "Iniciar Sesión",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0D47A1),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Campo de código
                            OutlinedTextField(
                                value = codigo.value,
                                onValueChange = { codigo.value = it },
                                label = { Text("Código único") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = Color(0xFF0D47A1)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = customTextFieldColors(),
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
                                        tint = Color(0xFF0D47A1)
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
                                            tint = Color(0xFF0D47A1)
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible.value)
                                    VisualTransformation.None
                                else
                                    PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = customTextFieldColors(),
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Botón de login
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
                                    containerColor = Color(0xFF0D47A1),
                                    disabledContainerColor = Color(0xFF0D47A1).copy(alpha = 0.6f)
                                ),
                                shape = RoundedCornerShape(16.dp),
                                enabled = !isLoading.value
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
private fun AnimatedBackgroundElements(rotation: Float, pulse: Float) {
    // Círculos de fondo animados
    Box(
        modifier = Modifier
            .fillMaxSize()
            .blur(50.dp)
    ) {
        // Círculo grande giratorio
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(x = (-100).dp, y = (-150).dp)
                .rotate(rotation)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Círculo mediano pulsante
        Box(
            modifier = Modifier
                .size(200.dp)
                .offset(x = 150.dp, y = 100.dp)
                .scale(pulse * 0.2f + 0.8f)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Círculo pequeño
        Box(
            modifier = Modifier
                .size(150.dp)
                .offset(x = 250.dp, y = (-50).dp)
                .rotate(-rotation * 0.5f)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.06f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun customTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF0D47A1),
    unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
    cursorColor = Color(0xFF0D47A1),
    focusedLabelColor = Color(0xFF0D47A1),
    unfocusedLabelColor = Color.Gray,
    focusedTextColor = Color(0xFF0D47A1),
    unfocusedTextColor = Color.Black
)