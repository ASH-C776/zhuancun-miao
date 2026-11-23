package com.ashchan.cloudsaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 底栏四个主栏目。名称统一 2 字：任务 / 添加 / 日志 / 设置。
 * 图标引用的是 material-icons-core 里已有的四个，不额外引扩展库。
 */
enum class MainTab(val label: String, val icon: ImageVector) {
    TASKS("任务", Icons.Filled.Home),
    ADD("添加", Icons.Filled.Add),
    RUNLOG("日志", Icons.Filled.List),
    SETTINGS("设置", Icons.Filled.Settings),
}

/**
 * 传统 App 式底栏（参考微信）：四个等宽栏目，选中项用主色图标 + 主色文字 +
 * 图标底下的一小块主色浅底，未选中为弱色。高度 64dp（另加系统手势条避让）。
 */
@Composable
fun AppTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MainTab.entries.forEach { tab ->
                    val active = tab == selected
                    val iconColor = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    val labelColor = iconColor
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clickable { if (!active) onSelect(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // 选中态的最小指示块：图标下的浅色胶囊底
                            Box(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(28.dp)
                                    .background(
                                        color = if (active) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainer,
                                        shape = RoundedCornerShape(12.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = iconColor,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = labelColor,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
