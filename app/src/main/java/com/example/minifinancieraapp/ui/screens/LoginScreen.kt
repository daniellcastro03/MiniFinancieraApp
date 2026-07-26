package com.example.capitalexpressapp.ui.screens

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
import androidx.compose.ui.graphics.vector.ImageVector
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

private val FondoLoginTop = Color(0xFF10151F)
private val FondoLoginBottom = Color(0xFF060709)
private val AcentoLogin = Color(0xFF2F6FED)

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

    // Animación de pulso suave (liviana: solo interpola un Float, sin nada pesado)
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
        delay(250)
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
                        containerColor = Color(0xFF232326),
                        contentColor = Color.White
                    )
                }
            )
        },
        containerColor = FondoLoginTop
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(colors = listOf(FondoLoginTop, FondoLoginBottom))
                )
                .padding(padding)
        ) {
            // Resplandores decorativos para que el fondo no se vea plano.
            Box(
                modifier = Modifier
                    .size(320.dp)
                    .align(Alignment.TopStart)
                    .offset((-120).dp, (-120).dp)
                    .blur(110.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(AcentoLogin.copy(alpha = 0.35f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .align(Alignment.BottomEnd)
                    .offset(90.dp, 90.dp)
                    .blur(100.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF7C3AED).copy(alpha = 0.22f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo animado
                AnimatedVisibility(
                    visible = logoVisible.value,
                    enter = fadeIn(animationSpec = tween(700)) +
                            scaleIn(animationSpec = tween(700, easing = FastOutSlowInEasing))
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            // Resplandor sutil detrás del logo
                            Box(
                                modifier = Modifier
                                    .size(180.dp)
                                    .scale(pulseAnimation * 0.1f + 0.9f)
                                    .blur(55.dp)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = 0.18f),
                                                Color.Transparent
                                            )
                                        ),
                                        shape = CircleShape
                                    )
                            )

                            // Fondo redondo claro para que el logo se destaque sobre
                            // el fondo oscuro de la pantalla (si no, se pierde).
                            Box(
                                modifier = Modifier
                                    .size(132.dp)
                                    .shadow(elevation = 16.dp, shape = CircleShape)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.logo_capital),
                                    contentDescription = "Logo Capital Express",
                                    modifier = Modifier
                                        .size(96.dp)
                                        .scale(pulseAnimation * 0.04f + 0.96f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        Text(
                            text = "CAPITAL EXPRESS",
                            fontSize = 24.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Tu socio financiero de confianza",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Formulario de login minimalista
                AnimatedVisibility(
                    visible = contentVisible.value,
                    enter = slideInVertically(
                        initialOffsetY = { it / 3 },
                        animationSpec = tween(500, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(500))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        CampoLogin(
                            value = codigo.value,
                            onValueChange = { codigo.value = it },
                            placeholder = "Código de usuario",
                            icono = Icons.Default.Person,
                            keyboardType = KeyboardType.Number
                        )

                        CampoLogin(
                            value = password.value,
                            onValueChange = { password.value = it },
                            placeholder = "Contraseña",
                            icono = Icons.Default.Lock,
                            esPassword = true,
                            passwordVisible = passwordVisible.value,
                            onTogglePasswordVisible = { passwordVisible.value = !passwordVisible.value }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

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
                                .height(54.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AcentoLogin,
                                contentColor = Color.White,
                                disabledContainerColor = AcentoLogin.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(28.dp),
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
                                    Text("Iniciando...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            } else {
                                Text("Iniciar Sesión", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CampoLogin(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icono: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    esPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisible: (() -> Unit)? = null
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Color.White.copy(alpha = 0.35f)) },
        leadingIcon = { Icon(icono, contentDescription = null, tint = Color.White.copy(alpha = 0.6f)) },
        trailingIcon = if (esPassword) {
            {
                IconButton(onClick = { onTogglePasswordVisible?.invoke() }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (passwordVisible) "Ocultar contraseña" else "Mostrar contraseña",
                        tint = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        } else null,
        visualTransformation = if (esPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedIndicatorColor = AcentoLogin,
            unfocusedIndicatorColor = Color.White.copy(alpha = 0.35f),
            cursorColor = AcentoLogin,
            focusedLeadingIconColor = AcentoLogin,
            unfocusedLeadingIconColor = Color.White.copy(alpha = 0.6f)
        )
    )
}
