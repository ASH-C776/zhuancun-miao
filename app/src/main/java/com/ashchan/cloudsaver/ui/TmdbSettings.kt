package com.ashchan.cloudsaver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ashchan.cloudsaver.core.tmdb.TmdbClient
import com.ashchan.cloudsaver.storage.TmdbStore
import kotlinx.coroutines.launch

/**
 * 凭证脱敏：前 9 位可见，其余星号遮蔽（最多 12 个星），仅界面展示、存储保持原样。
 * 与设置页 Refresh Token / Cookie 用的是同一口径。
 */
private fun maskSecret(raw: String): String {
    if (raw.length <= 9) return raw
    return raw.take(9) + "*".repeat(minOf(raw.length - 9, 12))
}

/**
 * 设置页「TMDB」区块（可选功能）。
 *
 * 只做三件事：保存凭证、验证能不能用、清除。
 * 凭证类型不做选择框 —— v3 API Key 和 v4 Read Access Token 都由 [TmdbClient] 自动识别，
 * 用户从 TMDB 网站复制哪个都行，少一个需要理解的选项。
 */
@Composable
fun TmdbCredentialSection() {
    val context = LocalContext.current
    val store = remember { TmdbStore(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf(store.loadCredential().orEmpty()) }
    var baseUrl by remember { mutableStateOf(store.loadBaseUrl()) }
    var saved by remember { mutableStateOf(store.loadCredential() != null) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text("TMDB（可选）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            // 已保存的凭证只露前 9 位，其余用星号遮蔽（与 Refresh Token / Cookie 一致的脱敏口径）
            value = if (saved) maskSecret(input) else input,
            onValueChange = {
                input = it
                message = null
            },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = saved,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = {
                baseUrl = it
                message = null
            },
            label = { Text("接口地址") },
            placeholder = { Text(TmdbStore.DEFAULT_BASE_URL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val value = input.trim()
                    if (value.isBlank()) {
                        message = "请先填写 TMDB 凭证"
                        success = false
                        return@Button
                    }
                    testing = true
                    message = null
                    scope.launch {
                        val result = TmdbClient(value, baseUrl).ping()
                        testing = false
                        result.onSuccess {
                            store.saveCredential(value)
                            store.saveBaseUrl(baseUrl)
                            saved = true
                            success = true
                            message = "验证通过：$it"
                        }.onFailure {
                            success = false
                            message = it.message ?: "验证失败"
                        }
                    }
                },
                enabled = !testing,
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("验证中")
                } else {
                    Text(if (saved) "重新验证" else "保存并测试")
                }
            }
            if (saved) {
                TextButton(
                    onClick = {
                        store.clear()
                        input = ""
                        baseUrl = TmdbStore.DEFAULT_BASE_URL
                        saved = false
                        message = "已清除 TMDB 凭证"
                        success = false
                    }
                ) { Text("清除") }
            }
        }
        message?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (success) SuccessGreen else MaterialTheme.colorScheme.error,
            )
        }
    }
}
