package com.example.capitalexpressapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.capitalexpressapp.ui.screens.BackupScreen
import com.example.capitalexpressapp.ui.screens.CobrosAdminScreen
import com.example.capitalexpressapp.ui.screens.CrearPrestamoScreen
import com.example.capitalexpressapp.ui.screens.CrearUsuarioScreen
import com.example.capitalexpressapp.ui.screens.DashboardScreen
import com.example.capitalexpressapp.ui.screens.DetalleCobrosScreen
import com.example.capitalexpressapp.ui.screens.EditarUsuarioScreen
import com.example.capitalexpressapp.ui.screens.HistorialPagosPrestamoScreen
import com.example.capitalexpressapp.ui.screens.HistorialPagosScreen
import com.example.capitalexpressapp.ui.screens.HistorialPrestamosScreen
import com.example.capitalexpressapp.ui.screens.LoginScreen
import com.example.capitalexpressapp.ui.screens.NotificacionesScreen
import com.example.capitalexpressapp.ui.screens.PerfilClienteScreen
import com.example.capitalexpressapp.ui.screens.PrestamoAdminScreen
import com.example.capitalexpressapp.ui.screens.VerPrestamoScreen
import com.example.capitalexpressapp.ui.theme.CapitalExpressAppTheme
import com.example.minifinancieraapp.ui.screens.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CapitalExpressAppTheme {
                val navController = rememberNavController()

                Surface(modifier = Modifier.fillMaxSize()) {
                    NavHost(navController = navController, startDestination = "login") {

                        composable("login") {
                            LoginScreen(navController)
                        }

                        composable(
                            route = "AdminScreen/{uid}/{rol}",
                            arguments = listOf(
                                navArgument("uid") { type = NavType.StringType },
                                navArgument("rol") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val uid = backStackEntry.arguments?.getString("uid") ?: ""
                            val rol = backStackEntry.arguments?.getString("rol") ?: ""
                            AdminScreen(navController, uid, rol)
                        }

                        composable(
                            route = "crearprestamo?clienteId={clienteId}",
                            arguments = listOf(
                                navArgument("clienteId") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                }
                            )
                        ) { backStackEntry ->
                            val clienteId = backStackEntry.arguments?.getString("clienteId") ?: ""
                            CrearPrestamoScreen(navController, clienteId)
                        }

                        composable(
                            route = "CobradorPantalla?uid={uid}",
                            arguments = listOf(navArgument("uid") { type = NavType.StringType })
                        ) {
                            val uid = it.arguments?.getString("uid") ?: ""
                            CobradorPantallaScreen(navController, uid)
                        }

                        composable("crearUsuario") { CrearUsuarioScreen(navController) }
                        composable("crearCliente") { CrearClienteScreen(navController) }

                        composable(
                            route = "ClientesVista/{uid}/{rol}",
                            arguments = listOf(
                                navArgument("uid") { type = NavType.StringType },
                                navArgument("rol") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val uid = backStackEntry.arguments?.getString("uid") ?: ""
                            val rol = backStackEntry.arguments?.getString("rol") ?: ""
                            ClientesVista(navController, uid, rol)
                        }

                        composable("PrestamoAdminScreen/{uid}/{rol}") { backStackEntry ->
                            val uid = backStackEntry.arguments?.getString("uid") ?: "unknown"
                            val rol = backStackEntry.arguments?.getString("rol") ?: "user"
                            PrestamoAdminScreen(navController = navController, uid = uid, rol = rol)
                        }

                        composable(route = "UsuariosVista") {
                            UsuariosVista(navController, rol = "admin")
                        }

                        composable("editarUsuario/{userId}") { backStack ->
                            val userId = backStack.arguments?.getString("userId") ?: ""
                            EditarUsuarioScreen(navController, userId)
                        }

                        composable("EditarClienteScreen/{clienteId}") { backStackEntry ->
                            val clienteId = backStackEntry.arguments?.getString("clienteId") ?: return@composable
                            EditarClienteScreen(navController, clienteId)
                        }

                        composable(
                            route = "EditarPrestamoScreen/{idPrestamo}/{uid}/{rol}",
                            arguments = listOf(
                                navArgument("idPrestamo") { type = NavType.StringType },
                                navArgument("uid") { type = NavType.StringType },
                                navArgument("rol") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val idPrestamo = backStackEntry.arguments?.getString("idPrestamo") ?: ""
                            val uid = backStackEntry.arguments?.getString("uid") ?: ""
                            val rol = backStackEntry.arguments?.getString("rol") ?: ""
                            EditarPrestamoScreen(navController, idPrestamo)
                        }

                        composable("AsignarCobradorScreen/{clienteId}") { backStackEntry ->
                            val clienteId = backStackEntry.arguments?.getString("clienteId") ?: return@composable
                            AsignarCobradorScreen(navController, clienteId)
                        }

                        composable("HistorialPagosScreen/{rol}") { backStack ->
                            val rol = backStack.arguments?.getString("rol") ?: "admin"
                            HistorialPagosScreen(navController, rol)
                        }

                        composable(
                            route = "HistorialPrestamosScreen/{uid}/{rol}",
                            arguments = listOf(
                                navArgument("uid") { type = NavType.StringType },
                                navArgument("rol") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val uid = backStackEntry.arguments?.getString("uid") ?: ""
                            val rol = backStackEntry.arguments?.getString("rol") ?: ""
                            HistorialPrestamosScreen(navController = navController, uid = uid, rol = rol)
                        }

                        composable("HistorialGlobalScreen") { HistorialGlobalScreen(navController) }

                        composable("HistorialCuotasPrestamoScreen/{prestamoId}") { backStack ->
                            val prestamoId = backStack.arguments?.getString("prestamoId") ?: ""
                            HistorialCuotasPrestamoScreen(navController, prestamoId)
                        }

                        composable("CuotasPrestamoScreen/{prestamoId}/{uid}/{rol}",
                            arguments = listOf(
                                navArgument("prestamoId") { type = NavType.StringType },
                                navArgument("uid") { type = NavType.StringType },
                                navArgument("rol") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val prestamoId = backStackEntry.arguments?.getString("prestamoId") ?: ""
                            val uid = backStackEntry.arguments?.getString("uid") ?: ""
                            val rol = backStackEntry.arguments?.getString("rol") ?: ""
                            CuotasPrestamoScreen(prestamoId, navController, uid, rol)
                        }

                        composable("DashboardScreen") { DashboardScreen(navController) }

                        composable("NotificacionesScreen/{uid}/{rol}") { backStackEntry ->
                            val uid = backStackEntry.arguments?.getString("uid") ?: ""
                            val rol = backStackEntry.arguments?.getString("rol") ?: "admin"
                            NotificacionesScreen(navController, uid, rol)
                        }

                        composable("DetalleCobrosScreen/{cobrador}") { backStack ->
                            val cobrador = backStack.arguments?.getString("cobrador") ?: ""
                            DetalleCobrosScreen(navController, cobrador)
                        }

                        composable(
                            route = "RegistrarPagoScreen/{clienteId}/{prestamoId}/{saldoActual}/{cobrador}",
                            arguments = listOf(
                                navArgument("clienteId") { type = NavType.StringType },
                                navArgument("prestamoId") { type = NavType.StringType },
                                navArgument("saldoActual") { type = NavType.StringType },
                                navArgument("cobrador") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val clienteId = backStackEntry.arguments?.getString("clienteId") ?: ""
                            val prestamoId = backStackEntry.arguments?.getString("prestamoId") ?: ""
                            val saldoActual = backStackEntry.arguments?.getString("saldoActual")?.toDoubleOrNull() ?: 0.0
                            val cobrador = backStackEntry.arguments?.getString("cobrador") ?: ""
                            RegistrarPagoScreen(navController, clienteId, prestamoId, saldoActual, cobrador)
                        }

                        composable(
                            route = "PerfilClienteScreen/{clienteId}/{rol}",
                            arguments = listOf(
                                navArgument("clienteId") { type = NavType.StringType },
                                navArgument("rol") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val clienteId = backStackEntry.arguments?.getString("clienteId") ?: ""
                            val rol = backStackEntry.arguments?.getString("rol") ?: ""
                            PerfilClienteScreen(
                                clienteId = clienteId,
                                navController = navController,
                                rol = rol
                            )
                        }

                        composable("CobrosAdminScreen/{adminUid}") { backStack ->
                            val adminUid = backStack.arguments?.getString("adminUid") ?: ""
                            CobrosAdminScreen(navController, adminUid)
                        }

                        composable("CobradorPantalla/{uid}") { backStack ->
                            val uid = backStack.arguments?.getString("uid") ?: ""
                            CobradorPantallaScreen(navController, uid)
                        }

                        composable("EditarPagoScreen/{prestamoId}/{cuota}/{clienteNombre}") { backStackEntry ->
                            EditarPagoScreen(
                                navController = navController,
                                prestamoId = backStackEntry.arguments?.getString("prestamoId") ?: "",
                                cuota = backStackEntry.arguments?.getString("cuota") ?: "",
                                clienteNombre = backStackEntry.arguments?.getString("clienteNombre") ?: ""
                            )
                        }

                        composable("solicitudPrestamo/{cobradorUid}") { backStackEntry ->
                            val cobradorUid = backStackEntry.arguments?.getString("cobradorUid") ?: ""
                            SolicitudPrestamoScreen(navController)
                        }

                        composable(
                            route = "SolicitudesAdminScreen/{uid}/{rol}",
                            arguments = listOf(
                                navArgument("uid") { type = NavType.StringType },
                                navArgument("rol") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val uid = backStackEntry.arguments?.getString("uid") ?: ""
                            val rol = backStackEntry.arguments?.getString("rol") ?: ""

                            SolicitudesAdminScreen(
                                navController = navController,
                                uid = uid,
                                rol = rol
                            )
                        }

                        composable("BackupScreen") {
                            BackupScreen(navController)
                        }

                        composable("SincronizarAbonosPendientesScreen") {
                            SincronizarAbonosPendientesScreen(navController)
                        }

                        composable("SincronizarSolicitudesPendientes") {
                            SincronizarSolicitudesPendientesScreen(navController)
                        }

                        composable(
                            route = "VerPrestamoScreen/{prestamoId}/{uid}/{rol}",
                            arguments = listOf(
                                navArgument("prestamoId") { type = NavType.StringType },
                                navArgument("uid") { type = NavType.StringType },
                                navArgument("rol") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val prestamoId = backStackEntry.arguments?.getString("prestamoId") ?: ""
                            val uid = backStackEntry.arguments?.getString("uid") ?: ""
                            val rol = backStackEntry.arguments?.getString("rol") ?: ""

                            VerPrestamoScreen(
                                navController = navController,
                                prestamoId = prestamoId,
                                uid = uid,
                                rol = rol
                            )
                        }

                        composable(
                            route = "detalleSolicitud/{solicitudId}",
                            arguments = listOf(
                                navArgument("solicitudId") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val solicitudId = backStackEntry.arguments?.getString("solicitudId") ?: ""

                            DetalleSolicitudScreen(
                                navController = navController,
                                solicitudId = solicitudId
                            )
                        }

                        composable("solicitudesAdmin") {
                            SolicitudesAdminScreen(navController)
                        }

                        composable("DetalleClienteScreen/{clienteId}") { backStackEntry ->
                            val clienteId = backStackEntry.arguments?.getString("clienteId") ?: return@composable
                            DetalleClienteScreen(navController, clienteId)
                        }

                        composable(
                            route = "HistorialPagosScreen/{rol}",
                            arguments = listOf(
                                navArgument("rol") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val rol = backStackEntry.arguments?.getString("rol") ?: "user"
                            HistorialPagosScreen(navController = navController, rol = rol)
                        }

                        composable("InformeClienteScreen/{clienteId}") { backStackEntry ->
                            val clienteId = backStackEntry.arguments?.getString("clienteId") ?: ""
                            InformeClienteScreen(navController, clienteId)
                        }

                        composable("PagosAsignadosCobrador") {
                            PagosAsignadosCobradorScreen(navController)
                        }

                        composable(
                            route = "HistorialPagosPrestamoScreen/{prestamoId}/{clienteId}/{userRole}",
                            arguments = listOf(
                                navArgument("prestamoId") { type = NavType.StringType },
                                navArgument("clienteId") { type = NavType.StringType },
                                navArgument("userRole") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val prestamoId = backStackEntry.arguments?.getString("prestamoId") ?: ""
                            val clienteId = backStackEntry.arguments?.getString("clienteId") ?: ""
                            val rol = backStackEntry.arguments?.getString("userRole") ?: ""

                            HistorialPagosPrestamoScreen(
                                navController = navController,
                                prestamoId = prestamoId,
                                clienteId = clienteId,
                                rol = rol
                            )
                        }
                    }
                }
            }
        }
    }
}
