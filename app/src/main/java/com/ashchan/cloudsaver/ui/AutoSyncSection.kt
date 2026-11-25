package com.ashchan.cloudsaver.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ashchan.cloudsaver.sync.AutoSyncConfig
import com.ashchan.cloudsaver.notify.AlertPublisher
import com.ashchan.cloudsaver.notify.ShowAlert
import com.ashchan.cloudsaver.sync.AutoSyncPrefs
import com.ashchan.cloudsaver.sync.AutoSyncScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置页「自动化」段。
 *
 * 全部状态都读独立 `auto_sync_prefs`，不碰数据库、不碰其他设置项；
 * 这一段的开关只管「后台自动刷新」，主页圆钮的手动检测不受影响。
 */
@Composable
fun AutoSyncSection() {
    val context = LocalContext.current
    val prefs = remember { AutoSyncPrefs(context) }

    var enabled by remember { mutableStateOf(prefs.enabled) }
    var slots by remember { mutableStateOf(prefs.slots) }
    var chargeEnabled by remember { mutableStateOf(prefs.chargeEnabled) }
    var notifyEnabled by remember { mutableStateOf(prefs.notifyEnabled) }
    var showAddDialog by remember { mutableStateOf(false) }
    // 状态卡里「距上次多久 / 下次几点」是随时间变的，用一个计数触发重算
    var rev by remember { mutableIntStateOf(0) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun refresh() {
        slots = prefs.slots
        chargeEnabled = prefs.chargeEnabled
        notifyEnabled = prefs.notifyEnabled
        rev++
    }

    Text("自动化", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))

    Spacer(Modifier.height(8.dp))

    SwitchRow(
        title = "自动刷新",
        subtitle = "按下面的时间点在后台检查并转存新集",
        checked = enabled,
        onCheckedChange = { v ->
            enabled = v
            prefs.enabled = v
            if (v) {
                AutoSyncScheduler.rescheduleAll(context)
                // Android 13+ 首次开启时顺手要通知权限；不给也能用，只是不提醒
                if (!AlertPublisher.canNotify(context)) {
                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                AutoSyncScheduler.cancelAll(context)
                prefs.clearState()
            }
            rev++
        },
    )

    if (!enabled) return

    Spacer(Modifier.height(16.dp))
    Text("每天刷新", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Text(
        "到点必跑，最多 ${AutoSyncConfig.MAX_SLOTS} 个时间点",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        slots.forEach { slot ->
            SlotChip(slot = slot) {
                prefs.slots = slots - slot
                AutoSyncScheduler.rescheduleAll(context)
                refresh()
            }
        }
        if (slots.size < AutoSyncConfig.MAX_SLOTS) {
            AssistChip(onClick = { showAddDialog = true }, label = { Text("+ 添加") })
        }
    }

    Spacer(Modifier.height(12.dp))
    SwitchRow(
        title = "充电时刷新",
        subtitle = "插电后立即刷新一次；若 3 小时内刚同步过则跳过",
        checked = chargeEnabled,
        onCheckedChange = { v ->
            chargeEnabled = v
            prefs.chargeEnabled = v
            rev++
        },
    )

    Spacer(Modifier.height(4.dp))
    SwitchRow(
        title = "跑完发通知",
        subtitle = "只在有新集时提醒，一部剧一条",
        checked = notifyEnabled,
        onCheckedChange = { v ->
            notifyEnabled = v
            prefs.notifyEnabled = v
            if (v && !AlertPublisher.canNotify(context)) {
                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            rev++
        },
    )

    Spacer(Modifier.height(12.dp))
    // 仅 debug 包：一次发 4 条样例，覆盖「单集 / 连更范围 / 多部剧 / 无集号兜底」
    if (com.ashchan.cloudsaver.BuildConfig.DEBUG) {
        SwitchRow(
            title = "通知实验室",
            subtitle = "debug 专用：一次发 4 条样例，验证一剧一条、集号范围与角标",
            checked = false,
            onCheckedChange = {
                AlertPublisher.publish(
                    context,
                    listOf(
                        ShowAlert(1, "斗破苍穹", 5, 207, null, 1),
                        ShowAlert(2, "剑来", 1, 52, 53, 2),
                        ShowAlert(3, "仙逆", 1, 168, null, 1),
                        ShowAlert(4, "玫瑰战争", null, null, null, 2),
                    ),
                )
            },
        )
    }

    Spacer(Modifier.height(12.dp))
    StatusCard(prefs = prefs, slots = slots, chargeEnabled = chargeEnabled, rev = rev)

    if (showAddDialog) {
        AddSlotDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { value ->
                val merged = AutoSyncConfig.normalize((slots + value).joinToString(","))
                prefs.slots = merged
                AutoSyncScheduler.rescheduleAll(context)
                showAddDialog = false
                refresh()
            },
        )
    }
}

/** 时段 chip：点文字不做事，× 才删除，避免手滑改时间 */
@Composable
private fun SlotChip(slot: String, onDelete: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(slot, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Close,
            contentDescription = "删除 $slot",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onDelete),
        )
    }
}

@Composable
private fun SwitchRow(    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 状态卡：上次 / 最近 24h 次数 / 下次，外加一条随冷却变化的口径说明。
 * 数字全部来自 prefs，界面不自己维护一份状态，避免和后台对不上。
 */
@Composable
private fun StatusCard(
    prefs: AutoSyncPrefs,
    slots: List<String>,
    chargeEnabled: Boolean,
    rev: Int,
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(rev) { System.currentTimeMillis() }
    val lastStart = prefs.lastRunStartAt
    val lastNew = prefs.lastRunNew
    val lastOk = prefs.lastRunOk
    val total = prefs.lastRunTotal
    val count24h = prefs.runCount24h(now)
    val next = AutoSyncConfig.nextTriggerAt(slots, now, zone)
    val cooldownLeft = AutoSyncConfig.chargeCooldownLeft(lastStart, now)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val lastText = if (lastStart <= 0L) {
                if (total == 0) "还没跑过" else "上次没跑成"
            } else {
                val okText = when {
                    total == 0 -> "没有启用的任务"
                    !lastOk -> "有失败 · $total 个任务"
                    lastNew > 0 -> "新增 $lastNew 集"
                    else -> "没有新集 · $total 个任务"
                }
                "上次 ${timeText(lastStart, now, zone)} · $okText"
            }
            Text(lastText, style = MaterialTheme.typography.bodySmall)
            Text(
                "最近 24 小时 $count24h 次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                next?.let { "下次 ${timeText(it, now, zone)}" } ?: "没有设定时间点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!chargeEnabled) {
                Text(
                    "充电时刷新已关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (cooldownLeft > 0L) {
                Text(
                    "冷却中：距上次 ${durationText(cooldownLeft)}，插电将跳过",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "插电即会刷新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 超过 3 天没跑：多数是系统省电压住了，给一句可读解释
            if (lastStart > 0L && (now - lastStart) > 3L * 24 * 60 * 60 * 1000) {
                Text(
                    "已 ${(now - lastStart) / (24L * 60 * 60 * 1000)} 天未运行 · 可能被系统限制",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddSlotDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val valid = AutoSyncConfig.isValidSlot(input)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加刷新时间") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim().take(5) },
                    singleLine = true,
                    label = { Text("时间（HH:mm）") },
                    placeholder = { Text("例如 12:30") },
                    isError = input.isNotEmpty() && !valid,
                )
                if (input.isNotEmpty() && !valid) {
                    Text(
                        "请用 24 小时制，两位小时，例如 09:00",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input.trim()) }, enabled = valid) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 今天 / 明天 / 日期，避免状态卡只给一个光秃秃的 HH:mm */
private fun timeText(targetAt: Long, nowAt: Long, zone: ZoneId): String {
    val target = Instant.ofEpochMilli(targetAt).atZone(zone)
    val nowDate = Instant.ofEpochMilli(nowAt).atZone(zone).toLocalDate()
    val day = when {
        target.toLocalDate() == nowDate -> "今天"
        target.toLocalDate() == nowDate.plusDays(1) -> "明天"
        else -> target.format(DateTimeFormatter.ofPattern("MM-dd"))
    }
    return "$day ${target.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

private fun durationText(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(1L)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "$h 小时 $m 分"
        h > 0 -> "$h 小时"
        else -> "$m 分"
    }
}
