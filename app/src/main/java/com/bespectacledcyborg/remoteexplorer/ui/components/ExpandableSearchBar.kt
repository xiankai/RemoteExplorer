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
    // Narrow on every keystroke rather than on Done. For lists already in memory, where
    // filtering costs nothing and seeing it narrow is the point.
    searchAsYouType: Boolean = false,
    // A query held by the caller, for a screen that is left and returned to. Without it the
    // bar would come back empty over a list still filtered by the old text.
    initialValue: String = "",
) {
    var isSearching by remember { mutableStateOf(initialValue.isNotEmpty()) }
    var openedByTap by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(initialValue) }
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
                    if (searchAsYouType) onSearch(it)
                },
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        isSearching = !isSearching
                        onSearch(value)
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
                            onSearch(value)
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
                                if (searchAsYouType) onSearch("")
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
