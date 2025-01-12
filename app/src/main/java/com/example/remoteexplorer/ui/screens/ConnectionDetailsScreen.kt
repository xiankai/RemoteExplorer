package com.example.remoteexplorer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.remoteexplorer.data.db.Connection
import java.util.Date

@Composable
fun ConnectionDetailsScreen(
    connection: Connection?,
    onSave: (Connection) -> Unit,
    onCancel: () -> Unit,
    onTest: (Connection) -> Unit,
    onConnect: (Connection) -> Unit,
) {
    var name by remember { mutableStateOf(connection?.name ?: "") }
    var hostname by remember { mutableStateOf(connection?.hostname ?: "") }
    var username by remember { mutableStateOf(connection?.username ?: "") }
    var password by remember { mutableStateOf(connection?.password ?: "") }
    var domain by remember { mutableStateOf(connection?.domain ?: "") }
    var share by remember { mutableStateOf(connection?.share ?: "") }

    val getCurrentConnection = {
        Connection(
            name = name,
            hostname = hostname,
            username = username,
            password = password,
            domain = domain,
            share = share,
            lastModified = Date()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = if (connection != null) { "Edit Connection" } else { "Add Connection"}, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = hostname,
            onValueChange = { hostname = it },
            label = { Text("Hostname") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = domain,
            onValueChange = { domain = it },
            label = { Text("Domain") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = share,
            onValueChange = { share = it },
            label = { Text("Share") },
            modifier = Modifier.fillMaxWidth()
        )
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
            Button(onClick = { onConnect(getCurrentConnection()) }) {
                Text("Connect")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { onSave(getCurrentConnection()) }) {
                Text("Save")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewConnectionDetailsScreen() {
    ConnectionDetailsScreen(
        connection = Connection(
            name = "test",
            hostname = "192.168.1.100",
            username = "testuser",
            password = "testpassword",
            domain = "testdomain",
            share = "testshare",
            lastModified = Date()
        ),
        onSave = { connection ->
            println("Save: host=${connection.hostname}, port=${connection.username}")
        },
        onTest = { connection ->
            println("Test: host=${connection.hostname}, port=${connection.username}")
        },
        onConnect = { connection ->
            println("Connect: host=${connection.hostname}, port=${connection.username}")
        },
        onCancel = { println("Cancel") }
    )
}