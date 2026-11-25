package com.ashchan.cloudsaver.ui.tasks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashchan.cloudsaver.data.db.TaskEntity
import com.ashchan.cloudsaver.storage.PosterStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 卡片左侧的海报位：默认 64×96、2:3、圆角 8；卡片内传 97×146 并只圆左侧两角，贴住卡片上/左/下。
 *
 * - 有海报：读 `filesDir/posters/<tmdbId>.jpg`，本地解码，不联网；
 * - 没海报（未绑 TMDB / 下载失败）：同尺寸占位块，淡主题色底 + 剧名首字，尺寸与圆角完全一致；
 * - [totalBadge] 不为空时，在海报底部压一枚半透明深灰胶囊（「共211集」），只有有海报才显示。
 */
@Composable
fun PosterArt(
    name: String,
    tmdbId: Long?,
    posterPath: String,
    totalBadge: String? = null,
    width: Dp = 64.dp,
    height: Dp = 96.dp,
    reloadKey: Int = 0,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, tmdbId, posterPath, reloadKey) {
        value = withContext(Dispatchers.IO) {
            val id = tmdbId ?: return@withContext null
            val file = PosterStore(context).fileFor(id)
            if (!file.isFile || file.length() <= 0) return@withContext null
            decodePoster(file)
        }
    }

    Box(
        modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Text(
                text = name.trim().take(1).ifBlank { "剧" },
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            )
        }

        if (bmp != null && totalBadge != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .widthIn(max = width - 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = totalBadge,
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    letterSpacing = 0.sp,
                )
            }
        }
    }
}

/** 只解到展示尺寸附近（56×84 @3x ≈ 168×252），避免整张 w342 原图常驻内存 */
private fun decodePoster(file: File): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 200) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
}.getOrNull()




