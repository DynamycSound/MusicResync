package pl.lambada.songsync.ui.screens.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pl.lambada.songsync.R
import pl.lambada.songsync.util.folders.FolderFilter
import pl.lambada.songsync.util.folders.FolderFilterMode
import pl.lambada.songsync.util.folders.FolderNode
import pl.lambada.songsync.util.folders.buildFolderTree
import pl.lambada.songsync.util.folders.flattenFolderTree

private const val INTERNAL_STORAGE = "/storage/emulated/0"

/** Path as shown to the user: relative to internal storage, like the old folder list. */
private fun relativePath(path: String): String = path.removePrefix("$INTERNAL_STORAGE/")

private class FolderRow(val node: FolderNode, val depth: Int, val showFullPath: Boolean)

/**
 * Full-screen folder picker (issue #21): a collapsible tree with tri-state checkboxes (checking a parent checks
 * everything below it), a search field, song counts, "Select all / none" and the blacklist/whitelist mode switch.
 * A checked folder is shown on the home list. Every change is applied immediately, like the old picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerDialog(
    folderSongCounts: Map<String, Int>,
    folderFilter: FolderFilter,
    onSetFolderVisible: (String, Boolean) -> Unit,
    onSetAllFoldersVisible: (Boolean) -> Unit,
    onModeChange: (FolderFilterMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val roots = remember(folderSongCounts) { buildFolderTree(folderSongCounts) }
    val allNodes = remember(roots) { flattenFolderTree(roots) }
    var query by rememberSaveable { mutableStateOf("") }
    // A lone root is just a wrapper, so start with it open.
    var expanded by remember(roots) {
        mutableStateOf(if (roots.size == 1) setOf(roots.single().path) else emptySet())
    }
    val rows = remember(roots, allNodes, expanded, query) {
        val q = query.trim()
        if (q.isNotEmpty()) {
            allNodes
                .filter { relativePath(it.path).contains(q, ignoreCase = true) }
                .map { FolderRow(it, depth = 0, showFullPath = true) }
        } else buildList {
            fun visit(node: FolderNode, depth: Int) {
                add(FolderRow(node, depth, showFullPath = depth == 0))
                if (node.path in expanded) node.children.forEach { visit(it, depth + 1) }
            }
            roots.forEach { visit(it, 0) }
        }
    }
    val shownCount = remember(folderSongCounts, folderFilter) {
        folderSongCounts.keys.count { folderFilter.isVisible(it) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.folders)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                )

                Column(modifier = Modifier.selectableGroup()) {
                    ModeOption(
                        title = stringResource(R.string.folder_mode_all_except_unchecked),
                        description = stringResource(R.string.folder_mode_all_except_unchecked_desc),
                        selected = folderFilter.mode == FolderFilterMode.SHOW_ALL_EXCEPT_UNCHECKED,
                        onClick = { onModeChange(FolderFilterMode.SHOW_ALL_EXCEPT_UNCHECKED) },
                    )
                    ModeOption(
                        title = stringResource(R.string.folder_mode_only_checked),
                        description = stringResource(R.string.folder_mode_only_checked_desc),
                        selected = folderFilter.mode == FolderFilterMode.ONLY_CHECKED,
                        onClick = { onModeChange(FolderFilterMode.ONLY_CHECKED) },
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.search_folders)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.folders_shown_summary, shownCount, folderSongCounts.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSetAllFoldersVisible(true) }) {
                        Text(stringResource(R.string.select_all))
                    }
                    TextButton(onClick = { onSetAllFoldersVisible(false) }) {
                        Text(stringResource(R.string.select_none))
                    }
                }
                HorizontalDivider()

                if (rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_matching_folders),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(rows, key = { it.node.path }, contentType = { "folder" }) { row ->
                        val node = row.node
                        val state = remember(node, folderFilter) { checkboxState(node, folderFilter) }
                        val isExpanded = node.path in expanded
                        val toggleExpanded = {
                            expanded = if (isExpanded) expanded - node.path else expanded + node.path
                        }
                        FolderRowItem(
                            label = if (row.showFullPath) displayPath(node.path) else node.label,
                            songCount = node.totalSongCount,
                            depth = row.depth,
                            // In search results the tree structure is gone, so rows can't be expanded.
                            expandable = query.isBlank(),
                            hasChildren = node.children.isNotEmpty(),
                            isExpanded = isExpanded,
                            state = state,
                            onToggleExpanded = toggleExpanded,
                            onToggleChecked = { onSetFolderVisible(node.path, state != ToggleableState.On) },
                        )
                    }
                }
            }
        }
    }
}

private fun checkboxState(node: FolderNode, filter: FolderFilter): ToggleableState {
    val folders = node.songFolders
    val visible = folders.count { filter.isVisible(it) }
    return when (visible) {
        0 -> ToggleableState.Off
        folders.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

@Composable
private fun displayPath(path: String): String =
    if (path == INTERNAL_STORAGE) stringResource(R.string.internal_storage) else relativePath(path)

@Composable
private fun ModeOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FolderRowItem(
    label: String,
    songCount: Int,
    depth: Int,
    expandable: Boolean,
    hasChildren: Boolean,
    isExpanded: Boolean,
    state: ToggleableState,
    onToggleExpanded: () -> Unit,
    onToggleChecked: () -> Unit,
) {
    val canExpand = expandable && hasChildren
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (canExpand) onToggleExpanded() else onToggleChecked() }
            // Cap the indent so deeply nested folders still leave room for their names.
            .padding(start = 4.dp + 16.dp * depth.coerceAtMost(6), end = 8.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canExpand) {
            IconButton(onClick = onToggleExpanded) {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowDown
                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(if (isExpanded) R.string.collapse else R.string.expand),
                )
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            Text(text = label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = pluralStringResource(R.plurals.songs_count, songCount, songCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TriStateCheckbox(state = state, onClick = onToggleChecked)
    }
}
