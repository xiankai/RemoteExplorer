package com.bespectacledcyborg.remoteexplorer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable
fun <T> TextFieldDialog(
    title: String,
    value: T,
    onValueChange: (String) -> T,
    supportingText: String = "",
    display: (T) -> String = { it.toString() },
) {
    var showDialog by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isError by rememberSaveable { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    TextField(
        label = { Text(title) },
        value = display(value),
        onValueChange = {},
        enabled = false,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                showDialog = true
            },
        interactionSource = interactionSource,
    )

    // Dialog for input
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Edit $title") },
            text = {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Row {
                            Text(
                                text = if (isError) errorMessage else "",
                                Modifier.clearAndSetSemantics {},
                                color = Color.Red,
                            )
                            if (supportingText.isNotEmpty()) {
                                Spacer(Modifier.weight(1f))
                                Text(supportingText)
                            }
                        }
                    },
                    isError = isError
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            onValueChange(inputText)
                            showDialog = false
                        } catch (e: Exception) {
                            isError = true
                            errorMessage = e.message ?: "Unknown error"
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
