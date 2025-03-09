import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

@Composable
fun ExpandableSearchBar(
    currentContext: String,
    onSearch: (search: String) -> Unit,
) {
    var isSearching by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Crossfade(
        modifier = Modifier.animateContentSize(),
        targetState = isSearching,
        label = "Search"
    ) { target ->
        if (!target) {
            IconButton(
                onClick = { isSearching = !isSearching },
                content = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "Search",
                    )
                }
            )
        } else {
            TextField(
                value = value,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search within $currentContext") },
                onValueChange = { value = it },
                keyboardOptions = KeyboardOptions.Default.copy(
                    imeAction = ImeAction.Done // Shows Done button on soft keyboard
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        isSearching = !isSearching
                        onSearch(value)
                        keyboardController?.hide() // Hide keyboard after submission
                    }
                ),
                leadingIcon = {
                    IconButton(
                        content = {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = ""
                            )
                        },
                        onClick = {
                            isSearching = !isSearching
                            onSearch(value)
                        },
                    )
                },
                trailingIcon = if (value.isNotBlank()) {
                    {

                        IconButton(
                            content = {
                                Icon(Icons.Filled.Close, contentDescription = "")
                            },
                            onClick = {
                                value = ""
                            },
                        )
                    }
                } else {
                    null
                }
            )
        }
    }
}
