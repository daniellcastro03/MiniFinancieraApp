package com.example.capitalexpressapp.core

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MarkUnreadChatAlt
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/** Controlador para abrir el menú lateral desde cualquier pantalla sin tener que
 *  pasarle el DrawerState a mano por cada composable. */
class DrawerController(val abrir: () -> Unit)

val LocalDrawerController = staticCompositionLocalOf { DrawerController {} }

private data class ItemMenu(val label: String, val icon: ImageVector, val ruta: String)

/**
 * Contenido del menú lateral: permite saltar directo a cualquier sección
 * principal de la app sin tener que volver antes a la pantalla de Inicio.
 */
@Composable
fun MenuLateralContenido(
    navController: NavController,
    uid: String,
    rol: String,
    onCerrar: () -> Unit
) {
    val esAdmin = rol == "admin"
    val rutaInicio = if (esAdmin) "AdminScreen/$uid/admin" else "CobradorPantalla/$uid"

    val itemsComunes = listOf(
        ItemMenu("Inicio", Icons.Default.Home, rutaInicio),
        ItemMenu("Clientes", Icons.Default.People, "ClientesVista/$uid/$rol"),
        ItemMenu("Préstamos", Icons.Default.Assignment, "PrestamoAdminScreen/$uid/$rol"),
        ItemMenu("Notificaciones", Icons.Default.Notifications, "NotificacionesScreen/$uid/$rol"),
        ItemMenu("Historial de Pagos", Icons.Default.Receipt, "HistorialPagosScreen/$rol"),
        ItemMenu("Historial de Préstamos", Icons.Default.History, "HistorialPrestamosScreen/$uid/$rol")
    )

    val itemsAdmin = listOf(
        ItemMenu("Cobros", Icons.Default.Payments, "CobrosAdminScreen/$uid"),
        ItemMenu("Solicitudes", Icons.Default.MarkUnreadChatAlt, "solicitudesAdmin"),
        ItemMenu("Historial Global", Icons.Default.Public, "HistorialGlobalScreen"),
        ItemMenu("Usuarios", Icons.Default.AccountCircle, "UsuariosVista"),
        ItemMenu("Dashboard", Icons.Default.Insights, "DashboardScreen")
    )

    val itemsCobrador = listOf(
        ItemMenu("Pagos Asignados", Icons.Default.AssignmentTurnedIn, "PagosAsignadosCobrador")
    )

    val items = itemsComunes + if (esAdmin) itemsAdmin else itemsCobrador

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .padding(vertical = 16.dp)
    ) {
        Text(
            "Capital Express",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        items.forEach { item ->
            NavigationDrawerItem(
                label = { Text(item.label) },
                icon = { Icon(item.icon, contentDescription = null) },
                selected = false,
                onClick = {
                    onCerrar()
                    // Al navegar desde el menú, la pila vuelve a quedar en
                    // Inicio -> destino: así "atrás" lleva a Inicio, no a la
                    // pantalla en la que estaba antes de abrir el menú.
                    navController.navigate(item.ruta) {
                        popUpTo(rutaInicio) { inclusive = item.ruta == rutaInicio }
                        launchSingleTop = true
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}
