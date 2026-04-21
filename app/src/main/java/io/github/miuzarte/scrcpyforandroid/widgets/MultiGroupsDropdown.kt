package io.github.miuzarte.scrcpyforandroid.widgets

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider

data class MultiGroupsDropdownGroup(
    val options: List<String>,
    val selectedIndex: Int,
    val onSelectedIndexChange: (Int) -> Unit,
)

@Composable
fun MultiGroupsDropdown(
    groups: List<MultiGroupsDropdownGroup>,
    modifier: Modifier = Modifier,
) {
    groups.forEachIndexed { groupIndex, group ->
        group.options.forEachIndexed { optionIndex, option ->
            DropdownImpl(
                text = option,
                optionSize = group.options.size,
                isSelected = optionIndex == group.selectedIndex,
                index = optionIndex,
                onSelectedIndexChange = group.onSelectedIndexChange,
            )
        }
        if (groupIndex != groups.lastIndex) {
            HorizontalDivider(modifier = modifier.padding(horizontal = 20.dp))
        }
    }
}
