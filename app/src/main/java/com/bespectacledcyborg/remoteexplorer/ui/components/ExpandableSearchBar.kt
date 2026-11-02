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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

@Composable
fun ExpandableSearchBar(
    currentContext: String,
    onSearch: (search: String) -> Unit,
    searchAsYouType: Boolean = false,
    query: String = "",
) {
    var isSearching by remember { mutableStateOf(query.isNotEmpty()) }
    var openedByTap by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(query) }
    var sent by remember { mutableStateOf(query) }
    val send = { text: String ->
        sent = text
        onSearch(text)
    }
    LaunchedEffect(query) {
        if (query != sent) {
            sent = query
            value = query
            if (query.isEmpty()) isSearching = false
        }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    Crossfade(
        modifier = Modifier.animateContentSize(),
        targetState = isSearching,
        label = "Search"
    ) { target ->
        if (!target) {
            IconButton(
                onClick = {
                    openedByTap = true
                    isSearching = true
                },
                content = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "Search",
                    )
                }
            )
        } else {
            LaunchedEffect(openedByTap) {
                if (openedByTap) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                    openedByTap = false
                }
            }
            TextField(
                value = value,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("Search within $currentContext") },
                onValueChange = {
                    value = it
                    if (searchAsYouType) send(it)
                },
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        isSearching = !isSearching
                        send(value)
                        keyboardController?.hide()
                    }
                ),
                leadingIcon = {
                    IconButton(
                        content = {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close search"
                            )
                        },
                        onClick = {
                            isSearching = !isSearching
                            send(value)
                        },
                    )
                },
                trailingIcon = if (value.isNotBlank()) {
                    {

                        IconButton(
                            content = {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            },
                            onClick = {
                                value = ""
                                if (searchAsYouType) send("")
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
