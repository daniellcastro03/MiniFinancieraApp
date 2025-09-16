package com.example.capitalexpressapp.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(navController: NavController) {
    val context = LocalContext.current
    val backupsDir = File(context.filesDir, "backups")
    val backups = remember { mutableStateListOf<File>() }
    var showConfirmDelete by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        if (backupsDir.exists()) {
            backups.clear()
            backups.addAll(backupsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList())
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Lista de Backups") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            if (backups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay backups disponibles.", fontSize = 16.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(backups) { file ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(text = file.name, fontSize = 16.sp)
                                Text(text = "Última modificación: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(file.lastModified())}", fontSize = 12.sp)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            context.packageName + ".fileprovider",
                                            file
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Compartir Backup"))
                                    }) {
                                        Icon(Icons.Default.Share, contentDescription = "Compartir")
                                    }

                                    IconButton(onClick = { showConfirmDelete = file }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Eliminar")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showConfirmDelete?.let { fileToDelete ->
        AlertDialog(
            onDismissRequest = { showConfirmDelete = null },
            title = { Text("¿Eliminar Backup?") },
            text = { Text("¿Deseas eliminar el archivo ${fileToDelete.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    fileToDelete.delete()
                    backups.remove(fileToDelete)
                    showConfirmDelete = null
                }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
