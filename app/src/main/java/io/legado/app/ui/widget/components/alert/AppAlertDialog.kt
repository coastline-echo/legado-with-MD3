package io.legado.app.ui.widget.components.alert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LegadoTheme.composeEngine
import io.legado.app.ui.theme.ProvideAppDensity
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.button.PrimaryButton
import io.legado.app.ui.widget.components.button.SecondaryButton
import top.yukonga.miuix.kmp.window.WindowDialog

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppAlertDialog(
    modifier: Modifier = Modifier,
    contentMaxHeight: Dp? = null,
    fixedDialogHeight: Boolean = false,
    fixedContentHeight: Boolean = false,
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    text: String? = null,
    content: (@Composable () -> Unit)? = null,
    confirmText: String = "确定", // 默认文字
    onConfirm: (() -> Unit)? = null,
    dismissText: String = "取消",
    onDismiss: (() -> Unit)? = null,
) {
    if (ThemeResolver.isMiuixEngine(composeEngine)) {
        val maxHeight = with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height.toDp() * 0.82f
        }
        WindowDialog(
            show = show,
            // Miuix 对话框需要显式限制窗口高度，内容区再独立滚动，避免按钮被挤出屏幕。
            modifier = modifier.then(
                if (fixedDialogHeight) Modifier.height(maxHeight)
                else Modifier.heightIn(max = maxHeight)
            ),
            title = title,
            summary = text,
            onDismissRequest = onDismissRequest,
            content = {
                ProvideAppDensity {
                    // WindowDialog 的标题和 summary 也占用窗口高度，正文不能按剩余窗口近似值放大，
                    // 否则正文测量完成后会把底部操作行挤出窗口。正文最多占窗口约一半，超出部分滚动。
                    val bodyMaxHeight = (contentMaxHeight ?: (maxHeight * 0.52f))
                        .coerceAtLeast(160.dp)
                    if (content != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                // 正文独立滚动，给底部操作按钮预留固定空间，避免 Miuix 窗口裁剪按钮。
                                .then(
                                    if (fixedContentHeight) Modifier.height(bodyMaxHeight)
                                    else Modifier.heightIn(max = bodyMaxHeight)
                                )
                                .verticalScroll(rememberScrollState())
                        ) {
                            content()
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onDismiss != null) {
                            SecondaryButton(
                                text = dismissText,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onDismiss()
                                }
                            )
                        }

                        if (onConfirm != null) {
                            PrimaryButton(
                                text = confirmText,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onConfirm()
                                }
                            )
                        }
                    }
                }
            }
        )
    } else {
        if (show) {
            AlertDialog(
                onDismissRequest = onDismissRequest,
                modifier = modifier.then(
                    contentMaxHeight?.let {
                        if (fixedDialogHeight) Modifier.height(it) else Modifier.heightIn(max = it)
                    } ?: Modifier
                ),
                containerColor = LegadoTheme.colorScheme.surfaceContainer,
                iconContentColor = LegadoTheme.colorScheme.primary,
                titleContentColor = LegadoTheme.colorScheme.onSurface,
                textContentColor = LegadoTheme.colorScheme.onSurfaceVariant,
                tonalElevation = AlertDialogDefaults.TonalElevation,
                title = title?.let { { ProvideAppDensity { Text(text = it) } } },
                text = {
                    ProvideAppDensity {
                        // MD3 的 contentMaxHeight 限制的是整个弹窗，需要为标题和底部按钮预留空间；
                        // Miuix 的 contentMaxHeight 仅用于正文区域，不能共用同一个固定高度。
                        val bodyHeight = contentMaxHeight
                            ?.minus(168.dp)
                            ?.coerceAtLeast(120.dp)
                        Column(
                            modifier = (if (fixedContentHeight && contentMaxHeight != null) {
                                Modifier.height(bodyHeight ?: contentMaxHeight)
                            } else {
                                Modifier
                            }).fillMaxWidth().verticalScroll(rememberScrollState())
                        ) {
                            if (text != null) {
                                SelectionContainer {
                                    Text(
                                        text = text,
                                        modifier = Modifier.padding(bottom = if (content != null) 16.dp else 0.dp)
                                    )
                                }
                            }
                            if (content != null) {
                                content()
                            }
                        }
                    }
                },
                confirmButton = {
                    if (onConfirm != null) {
                        ProvideAppDensity {
                            PrimaryButton(
                                onClick = onConfirm,
                                text = confirmText
                            )
                        }
                    }
                },
                dismissButton = {
                    if (onDismiss != null) {
                        ProvideAppDensity {
                            SecondaryButton(
                                onClick = {
                                    onDismiss()
                                },
                                text = dismissText
                            )
                        }
                    }
                }
            )
        }
    }
}

/**
 * 专为 nullable 数据设计的 AppAlertDialog 重载。
 * 当 [data] 不为 null 时显示弹窗；当 [data] 变为 null 时，自动缓存最后一次数据并播放退出动画。
 */
@Composable
fun <T> AppAlertDialog(
    data: T?,
    onDismissRequest: () -> Unit,
    title: String? = null,
    text: String? = null,
    textProvider: @Composable (T.() -> String)? = null,
    confirmText: String = "确定",
    onConfirm: ((T) -> Unit)? = null,
    dismissText: String = "取消",
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentMaxHeight: Dp? = null,
    fixedDialogHeight: Boolean = false,
    fixedContentHeight: Boolean = false,
    content: (@Composable (T) -> Unit)? = null
) {
    var cachedData by remember { mutableStateOf(data) }

    if (data != null) {
        cachedData = data
    }

    val currentData = cachedData
    if (currentData != null) {
        val currentText = text ?: textProvider?.invoke(currentData)
        var lastValidText by remember { mutableStateOf(currentText) }
        
        if (currentText != null) {
            lastValidText = currentText
        }

        AppAlertDialog(
            show = data != null,
            onDismissRequest = onDismissRequest,
            title = title,
            text = currentText ?: lastValidText,
            modifier = modifier,
            contentMaxHeight = contentMaxHeight,
            fixedDialogHeight = fixedDialogHeight,
            fixedContentHeight = fixedContentHeight,
            confirmText = confirmText,
            onConfirm = onConfirm?.let { { it(currentData) } },
            dismissText = dismissText,
            onDismiss = onDismiss,
            content = content?.let { { it(currentData) } }
        )
    }
}
