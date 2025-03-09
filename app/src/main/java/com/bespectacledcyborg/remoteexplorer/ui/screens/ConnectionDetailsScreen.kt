package com.bespectacledcyborg.remoteexplorer.ui.screens

import android.util.Log
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
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
) {
    val id by remember { mutableIntStateOf(connection?.id ?: 0) }
    var name by remember { mutableStateOf(connection?.name ?: "") }
    var hostname by remember { mutableStateOf(connection?.hostname ?: "") }
    var username by remember { mutableStateOf(connection?.username ?: "") }
    var password by remember { mutableStateOf(connection?.password ?: "") }
    var domain by remember { mutableStateOf(connection?.domain ?: "") }
    var share by remember { mutableStateOf(connection?.share ?: "") }

    val getCurrentConnection = {
        Connection(
            id = id,
            name = name,
            hostname = hostname,
            username = username,
            password = password,
            domain = domain,
            share = share,
            lastModified = Date()
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text("Connections")
                },
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
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (connection != null) {
                        "Edit Connection"
                    } else {
                        "Add Connection"
                    }, style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                val fields: List<Triple<String, (String) -> Unit, String>> = listOf(
                    Triple(name, { name = it.trim() }, "Name"),
                    Triple(hostname, { hostname = it.trim() }, "Hostname"),
                    Triple(username, { username = it.trim() }, "Username"),
                    Triple(password, { password = it.trim() }, "Password"),
                    Triple(domain, { domain = it.trim() }, "Domain"),
                    Triple(share, { share = it.trim() }, "Share"),
                )
                val localFocusManager = LocalFocusManager.current
                val keyboardController = LocalSoftwareKeyboardController.current

                LazyColumn {
                    itemsIndexed(fields) { index, field ->
                        val imeAction = if (index == fields.lastIndex) {
                            ImeAction.Done
                        } else ImeAction.Next

                        val keyboardActions = KeyboardActions(
                            onNext = { localFocusManager.moveFocus(FocusDirection.Down) },
                            onDone = {
                                localFocusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        )

                        val (value, onValueChange, label) = field
                        val visualTransformation = if (label == "Password") {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        }

                        OutlinedTextField(
                            value = value,
                            onValueChange = onValueChange,
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                imeAction = imeAction
                            ),
                            keyboardActions = keyboardActions,
                            visualTransformation = visualTransformation
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onCancel) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onTest(getCurrentConnection()) }) {
                        Text("Test Connection")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onSave(getCurrentConnection()) }) {
                        Text("Save")
                    }
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
        connection = Connection(
            id = 1,
            name = "test",
            hostname = "192.168.1.100",
            username = "testuser",
            password = "testpassword",
            domain = "testdomain",
            share = "testshare",
            lastModified = Date()
        ),
        onSave = { connection ->
            Log.d(
                "ConnectionDetailsScreen",
                "Save: host=${connection.hostname}, port=${connection.username}"
            )
        },
        onTest = { connection ->
            Log.d(
                "ConnectionDetailsScreen",
                "Test: host=${connection.hostname}, port=${connection.username}"
            )
        },
        onCancel = { Log.d("ConnectionDetailsScreen", "Cancel") },
        onDelete = { connection ->
            Log.d(
                "ConnectionDetailsScreen",
                "Delete: host=${connection.hostname}, port=${connection.username}"
            )
        }
    )
}