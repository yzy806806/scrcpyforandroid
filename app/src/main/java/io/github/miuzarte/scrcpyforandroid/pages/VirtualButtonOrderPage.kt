package io.github.miuzarte.scrcpyforandroid.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import io.github.miuzarte.scrcpyforandroid.constants.UiSpacing
import io.github.miuzarte.scrcpyforandroid.haptics.rememberAppHaptics
import io.github.miuzarte.scrcpyforandroid.scaffolds.AppPageLazyColumn
import io.github.miuzarte.scrcpyforandroid.widgets.SortDropPayload
import io.github.miuzarte.scrcpyforandroid.widgets.SortTransferDirection
import io.github.miuzarte.scrcpyforandroid.widgets.SortableCardItem
import io.github.miuzarte.scrcpyforandroid.widgets.SortableCardList
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonAction
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonActions
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt

private const val LIST_REORDER_STEP_PX = 54f
private const val LIST_TRANSFER_STEP_PX = 72f

@Composable
internal fun VirtualButtonOrderPage(
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    outsideIds: List<String>,
    moreIds: List<String>,
    onLayoutChange: (outside: List<String>, more: List<String>) -> Unit,
) {
    val haptics = rememberAppHaptics()
    val normalized = remember(outsideIds, moreIds) {
        VirtualButtonActions.resolveLayout(outsideIds, moreIds)
    }
    val outsideState = remember { normalized.first.map { it.id }.toMutableStateList() }
    val moreState = remember { normalized.second.map { it.id }.toMutableStateList() }

    LaunchedEffect(outsideIds, moreIds) {
        val resolved = VirtualButtonActions.resolveLayout(outsideIds, moreIds)
        outsideState.clear()
        outsideState.addAll(resolved.first.map { it.id })
        moreState.clear()
        moreState.addAll(resolved.second.map { it.id })
    }

    fun emit() {
        onLayoutChange(outsideState.toList(), moreState.toList())
    }

    fun reorderInside(list: androidx.compose.runtime.snapshots.SnapshotStateList<String>, itemId: String, deltaY: Float) {
        val fromIndex = list.indexOf(itemId)
        if (fromIndex < 0) return

        val steps = (deltaY / LIST_REORDER_STEP_PX).roundToInt()
        if (steps == 0) return

        val toIndex = (fromIndex + steps).coerceIn(0, list.lastIndex)
        if (toIndex == fromIndex) return

        val moved = list.removeAt(fromIndex)
        list.add(toIndex, moved)
        emit()
    }

    fun transferToOther(
        from: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
        to: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
        itemId: String,
        deltaY: Float,
    ) {
        val fromIndex = from.indexOf(itemId)
        if (fromIndex < 0) return

        val steps = (deltaY / LIST_REORDER_STEP_PX).roundToInt()
        val baseIndex = fromIndex + steps
        val insertIndex = baseIndex.coerceIn(0, to.size)

        from.removeAt(fromIndex)
        to.add(insertIndex, itemId)
        emit()
    }

    fun handleOutsideDrop(payload: SortDropPayload) {
        val transfer = payload.transferDirection == SortTransferDirection.TO_RIGHT && payload.deltaX >= LIST_TRANSFER_STEP_PX
        if (transfer) {
            if (payload.itemId == VirtualButtonAction.MORE.id) return
            transferToOther(outsideState, moreState, payload.itemId, payload.deltaY)
        } else {
            reorderInside(outsideState, payload.itemId, payload.deltaY)
        }
    }

    fun handleMoreDrop(payload: SortDropPayload) {
        val transfer = payload.transferDirection == SortTransferDirection.TO_LEFT && payload.deltaX <= -LIST_TRANSFER_STEP_PX
        if (transfer) {
            transferToOther(moreState, outsideState, payload.itemId, payload.deltaY)
        } else {
            reorderInside(moreState, payload.itemId, payload.deltaY)
        }
    }

    AppPageLazyColumn(
        contentPadding = contentPadding,
        scrollBehavior = scrollBehavior,
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "长按可在两列间拖动排序\n“更多”不可移入右侧菜单",
                    modifier = Modifier.padding(UiSpacing.CardContent),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SortableCardList(
                    title = "外部按钮",
                    items = outsideState.map { id ->
                        val action = VirtualButtonAction.entries.first { it.id == id }
                        SortableCardItem(
                            id = action.id,
                            title = action.title,
                            subtitle = "显示在预览与全屏底部",
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = UiSpacing.Small),
                    transferDirection = SortTransferDirection.TO_RIGHT,
                    onLongPressHaptic = haptics.press,
                    onDrop = ::handleOutsideDrop,
                )

                SortableCardList(
                    title = "更多菜单",
                    items = moreState.map { id ->
                        val action = VirtualButtonAction.entries.first { it.id == id }
                        SortableCardItem(
                            id = action.id,
                            title = action.title,
                            subtitle = "显示在“更多”弹窗中",
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = UiSpacing.Small),
                    transferDirection = SortTransferDirection.TO_LEFT,
                    onLongPressHaptic = haptics.press,
                    onDrop = ::handleMoreDrop,
                )
            }
        }
    }
}
