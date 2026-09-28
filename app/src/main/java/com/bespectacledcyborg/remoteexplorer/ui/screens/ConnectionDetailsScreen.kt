package com.bespectacledcyborg.remoteexplorer.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import com.bespectacledcyborg.remoteexplorer.data.db.Pairing
import com.bespectacledcyborg.remoteexplorer.data.db.DEFAULT_SMB_PORT
import com.bespectacledcyborg.remoteexplorer.ui.components.LocalRootPickerDialog
import com.bespectacledcyborg.remoteexplorer.ui.components.PairingSection
import com.bespectacledcyborg.remoteexplorer.ui.screens.header.ConnectionDetailsButtons
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionDetailsScreen(
    connection: Connection?,
    onSave: (Connection) -> Unit,
    onCancel: () -> Unit,
    onTest: (Connection) -> Unit,
    onDelete: (Connection) -> Unit,
    connections: List<Connection> = emptyList(),
    pairings: List<Pairing> = emptyList(),
    onSavePairing: (Pairing) -> Unit = {},
    onDeletePairing: (Pairing) -> Unit = {},
) {
    val id by remember { mutableIntStateOf(connection?.id ?: 0) }
    var name by remember { mutableStateOf(connection?.name ?: "") }
    var hostname by remember { mutableStateOf(connection?.hostname ?: "") }
    var port by remember { mutableStateOf((connection?.port ?: DEFAULT_SMB_PORT).toString()) }
    var username by remember { mutableStateOf(connection?.username ?: "") }
    var password by remember { mutableStateOf(connection?.password?.value ?: "") }
    var domain by remember { mutableStateOf(connection?.domain ?: "") }
    var share by remember { mutableStateOf(connection?.share ?: "") }
    var type by remember { mutableStateOf(connection?.type ?: ConnectionType.SMB) }
    var localRoot by remember { mutableStateOf(connection?.localRoot) }
    var pickingRoot by remember { mutableStateOf(false) }

    // Asked again whenever the system settings screen hands control back, since that is
    // where the user grants it — and can revoke it, at any time, from outside the app.
    var hasAllFilesAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val allFilesAccessSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { hasAllFilesAccess = Environment.isExternalStorageManager() }
    val context = LocalContext.current
    val requestAllFilesAccess = {
        // Straight to this app's own toggle where the device offers it, and to the list of
        // every app otherwise — some builds leave the per-app screen out.
        try {
            allFilesAccessSettings.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    "package:${context.packageName}".toUri(),
                )
            )
        } catch (e: ActivityNotFoundException) {
            allFilesAccessSettings.launch(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            )
        }
    }

    val getCurrentConnection = {
        when (type) {
            ConnectionType.SMB -> Connection(
                id = id,
                name = name,
                type = ConnectionType.SMB,
                hostname = hostname,
                // A blank or non-numeric entry falls back to the standard SMB port.
                port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_SMB_PORT,
                username = username,
                password = SecretText(password),
                domain = domain,
                share = share,
                lastModified = Date()
            )

            ConnectionType.LOCAL -> Connection(
                id = id,
                name = name,
                type = ConnectionType.LOCAL,
                hostname = null,
                username = null,
                password = null,
                domain = null,
                share = null,
                localRoot = localRoot,
                lastModified = Date()
            )
        }
    }
    // A local root is nothing without its folder, and a row saved without one would fail
    // every time it was opened.
    val canSave = type == ConnectionType.SMB || localRoot != null

    Scaffold(
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        // on-container, not primary: primary on primaryContainer is two tints of one
                    // hue, and in the dark scheme the title all but disappeared into the bar.
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                title = { Text("Connections") },
                actions = {
                    if (connection != null) {
                        ConnectionDetailsButtons { onDelete(connection) }
                    }
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
            )
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text =
                        if (connection != null) {
                            "Edit Connection"
                        } else {
                            "Add Connection"
                        },
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(32.dp))

                // Only while adding. An existing connection keeps its kind: its id already
                // keys a cached listing, tags and thumbnails, all of which describe what it
                // pointed at, and none of which would be true of the other kind.
                if (connection == null) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = type == ConnectionType.SMB,
                            onClick = { type = ConnectionType.SMB },
                            label = { Text("SMB share") },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = type == ConnectionType.LOCAL,
                            onClick = { type = ConnectionType.LOCAL },
                            label = { Text("Folder on this device") },
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                val fields: List<Triple<String, (String) -> Unit, String>> =
                    when (type) {
                        ConnectionType.SMB -> listOf(
                            Triple(name, { name = it.trim() }, "Name"),
                            Triple(hostname, { hostname = it.trim() }, "Hostname"),
                            Triple(port, { port = it.trim() }, "Port"),
                            Triple(username, { username = it.trim() }, "Username"),
                            Triple(password, { password = it.trim() }, "Password"),
                            Triple(domain, { domain = it.trim() }, "Domain"),
                            Triple(share, { share = it.trim() }, "Share"),
                        )

                        ConnectionType.LOCAL -> listOf(
                            Triple(name, { name = it.trim() }, "Name"),
                        )
                    }
                val localFocusManager = LocalFocusManager.current
                val keyboardController = LocalSoftwareKeyboardController.current

                // Allowed to shrink, and scroll, so what sits below it — the buttons and the
                // pairings — is not pushed off a short screen by the seven SMB fields.
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    itemsIndexed(fields) { index, field ->
                        val imeAction =
                            if (index == fields.lastIndex) {
                                ImeAction.Done
                            } else ImeAction.Next

                        val keyboardActions =
                            KeyboardActions(
                                onNext = {
                                    localFocusManager.moveFocus(FocusDirection.Down)
                                },
                                onDone = {
                                    localFocusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            )

                        val (value, onValueChange, label) = field
                        val visualTransformation =
                            if (label == "Password") {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            }

                        val keyboardType =
                            if (label == "Port") {
                                KeyboardType.Number
                            } else {
                                KeyboardType.Text
                            }

                        OutlinedTextField(
                            value = value,
                            onValueChange = onValueChange,
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                imeAction = imeAction,
                                keyboardType = keyboardType
                            ),
                            keyboardActions = keyboardActions,
                            visualTransformation = visualTransformation
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (type == ConnectionType.LOCAL) {
                    Text(
                        text = localRoot ?: "No folder chosen",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (hasAllFilesAccess) {
                        Button(onClick = { pickingRoot = true }) { Text("Choose folder") }
                    } else {
                        // Plain file access to shared storage — the card included — needs
                        // this one permission, and it is granted on a system screen rather
                        // than by a dialog.
                        Text(
                            text = "Browsing a folder on this device needs all files access.",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = requestAllFilesAccess) {
                            Text("Allow all files access")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onCancel) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onTest(getCurrentConnection()) }, enabled = canSave) {
                        Text("Test Connection")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onSave(getCurrentConnection()) }, enabled = canSave) {
                        Text("Save")
                    }
                }

                // Only for a saved connection: a pairing names both sides by id, and a
                // connection being added has none yet.
                if (connection != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    PairingSection(
                        connection = connection,
                        connections = connections,
                        pairings = pairings,
                        onSave = onSavePairing,
                        onDelete = onDeletePairing,
                    )
                } else if (type == ConnectionType.LOCAL) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Once saved, edit it again to pair it with a share.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (pickingRoot) {
                    LocalRootPickerDialog(
                        initial = localRoot,
                        onPick = {
                            localRoot = it
                            pickingRoot = false
                        },
                        onDismiss = { pickingRoot = false },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    )
}

@Preview(showBackground = true)
@Composable
fun PreviewConnectionDetailsScreen() {
    ConnectionDetailsScreen(
        connection =
            Connection(
                id = 1,
                name = "test",
                hostname = "192.168.1.100",
                username = "testuser",
                password = SecretText("testpassword"),
                domain = "testdomain",
                share = "testshare",
                lastModified = Date()
            ),
        onSave = { connection ->
            Log.d(
                "ConnectionDetailsScreen",
                "Save: host=${connection.hostname}, port=${connection.port}"
            )
        },
        onTest = { connection ->
            Log.d(
                "ConnectionDetailsScreen",
                "Test: host=${connection.hostname}, port=${connection.port}"
            )
        },
        onCancel = { Log.d("ConnectionDetailsScreen", "Cancel") },
        onDelete = { connection ->
            Log.d(
                "ConnectionDetailsScreen",
                "Delete: host=${connection.hostname}, port=${connection.port}"
            )
        }
    )
}
