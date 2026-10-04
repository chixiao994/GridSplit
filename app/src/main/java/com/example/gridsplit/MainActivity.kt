package com.example.gridsplit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GridSplitScreen()
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  图片仓库：扫描 / 预览 / 分割                                        */
/* ------------------------------------------------------------------ */

object ImageRepo {

    private val IMAGE_EXT = setOf(
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif"
    )

    fun listImages(context: Context, treeUri: Uri): List<DocumentFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = ArrayList<DocumentFile>()
        collect(root, out)
        out.sortBy { it.name?.lowercase() ?: "" }
        return out
    }

    private fun collect(dir: DocumentFile, out: MutableList<DocumentFile>) {
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            emptyArray()
        }
        for (f in children) {
            if (f.isDirectory) collect(f, out)
            else if (f.isFile && isImage(f.name.orEmpty())) out.add(f)
        }
    }

    private fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    fun loadPreview(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        } catch (_: Exception) {
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 按 rows × cols 切割图片并写入输出文件夹。
     * hOverlap / vOverlap 为相邻块在水平/垂直方向上的重叠像素数（左右各扩一半）。
     * @return 成功写出的块数
     */
    fun splitAndSave(
        context: Context,
        srcUri: Uri,
        srcName: String,
        outTreeUri: Uri,
        rows: Int,
        cols: Int,
        hOverlap: Int,
        vOverlap: Int
    ): Int {
        val outRoot = DocumentFile.fromTreeUri(context, outTreeUri)
            ?: throw IllegalStateException("无法访问输出文件夹")
        val src = loadFullBitmap(context, srcUri)
            ?: throw IllegalStateException("无法解码图片")

        val base = srcName.substringBeforeLast('.', srcName)
        var count = 0
        try {
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) throw IllegalStateException("图片尺寸无效")

            val hHalf = hOverlap / 2
            val vHalf = vOverlap / 2

            for (r in 0 until rows) {
                val baseY0 = r * h / rows
                val baseY1 = (r + 1) * h / rows
                val y0 = (baseY0 - vHalf).coerceAtLeast(0)
                val y1 = (baseY1 + vHalf).coerceAtMost(h)
                val th = y1 - y0
                if (th <= 0) continue

                for (c in 0 until cols) {
                    val baseX0 = c * w / cols
                    val baseX1 = (c + 1) * w / cols
                    val x0 = (baseX0 - hHalf).coerceAtLeast(0)
                    val x1 = (baseX1 + hHalf).coerceAtMost(w)
                    val tw = x1 - x0
                    if (tw <= 0) continue

                    val tile = Bitmap.createBitmap(src, x0, y0, tw, th)
                    try {
                        val name = "${base}_r${r + 1}c${c + 1}.png"
                        outRoot.findFile(name)?.delete()
                        val df = outRoot.createFile("image/png", name)
                            ?: throw IllegalStateException("无法创建文件 $name")

                        val ok = context.contentResolver.openOutputStream(df.uri)?.use { os ->
                            tile.compress(Bitmap.CompressFormat.PNG, 100, os)
                        } ?: false

                        if (!ok) throw IllegalStateException("写入失败 $name")
                        count++
                    } finally {
                        if (tile !== src) tile.recycle()
                    }
                }
            }
        } finally {
            src.recycle()
        }
        return count
    }

    private fun loadFullBitmap(context: Context, uri: Uri): Bitmap? {
        val bmp = try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it, null,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                )
            }
        } catch (e: Exception) {
            null
        } ?: return null

        val rotation = readRotation(context, uri)
        if (rotation == 0f) return bmp

        val m = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }

    private fun readRotation(context: Context, uri: Uri): Float {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
    }
}

/* ------------------------------------------------------------------ */
/*  界面                                                               */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridSplitScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }
    var rowsText by remember { mutableStateOf("20") }
    var colsText by remember { mutableStateOf("12") }
    var hOverlapText by remember { mutableStateOf("0") }
    var vOverlapText by remember { mutableStateOf("0") }
    var files by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("请先选择输入文件夹") }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }

    val rows = (rowsText.toIntOrNull() ?: 20).coerceIn(1, 50)
    val cols = (colsText.toIntOrNull() ?: 12).coerceIn(1, 50)
    val hOverlap = (hOverlapText.toIntOrNull() ?: 0).coerceAtLeast(0)
    val vOverlap = (vOverlapText.toIntOrNull() ?: 0).coerceAtLeast(0)

    val inputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            inputUri = uri
            index = 0
        }
    }

    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            outputUri = uri
            status = "输出文件夹已设置"
        }
    }

    LaunchedEffect(inputUri) {
        val uri = inputUri
        if (uri == null) {
            files = emptyList()
            return@LaunchedEffect
        }
        status = "正在读取图片列表…"
        val list = withContext(Dispatchers.IO) {
            try {
                ImageRepo.listImages(context, uri)
            } catch (e: Exception) {
                emptyList()
            }
        }
        files = list
        index = 0
        status = if (list.isEmpty()) "输入文件夹里没有找到图片" else "共 ${list.size} 张图片"
    }

    val currentFile = files.getOrNull(index)
    LaunchedEffect(currentFile?.uri) {
        preview = null
        val f = currentFile ?: return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            try {
                ImageRepo.loadPreview(context, f.uri, 1600)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 保存当前图片并返回是否成功 */
    suspend fun saveCurrent(): Boolean {
        val f = currentFile ?: run {
            status = "没有可处理的图片"
            return false
        }
        val out = outputUri ?: run {
            status = "请先选择输出文件夹"
            return false
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                ImageRepo.splitAndSave(
                    context, f.uri, f.name ?: "image", out,
                    rows, cols, hOverlap, vOverlap
                )
            }.fold(
                onSuccess = { n ->
                    withContext(Dispatchers.Main) {
                        status = "已保存 $n 块"
                    }
                    true
                },
                onFailure = { e ->
                    withContext(Dispatchers.Main) {
                        status = "处理失败：${e.message ?: e.javaClass.simpleName}"
                    }
                    false
                }
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("网格分割") }) }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // ========== 第一栏：输入 / 输出 / 保存 ==========
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = { inputPicker.launch(null) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text("输入", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = { outputPicker.launch(null) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text("输出", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Button(
                    onClick = {
                        if (files.isEmpty()) {
                            status = "没有可处理的图片"
                            return@Button
                        }
                        if (outputUri == null) {
                            status = "请先选择输出文件夹"
                            return@Button
                        }
                        scope.launch {
                            busy = true
                            var successCount = 0
                            var failCount = 0
                            var totalTiles = 0
                            for ((i, f) in files.withIndex()) {
                                status = "批量处理中 ${i + 1}/${files.size}：${f.name}"
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        ImageRepo.splitAndSave(
                                            context, f.uri, f.name ?: "image", outputUri!!,
                                            rows, cols, hOverlap, vOverlap
                                        )
                                    }
                                }
                                result.onSuccess { n ->
                                    successCount++
                                    totalTiles += n
                                }.onFailure {
                                    failCount++
                                }
                            }
                            busy = false
                            status = "批量完成：成功 $successCount 张，失败 $failCount 张，共保存 $totalTiles 块"
                        }
                    },
                    enabled = !busy && files.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (busy) "处理中" else "保存",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ========== 第二栏：行数 / 列数 / 左右重叠 / 上下重叠 ==========
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = rowsText,
                    onValueChange = { s -> rowsText = s.filter { it.isDigit() }.take(3) },
                    label = { Text("行数", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = colsText,
                    onValueChange = { s -> colsText = s.filter { it.isDigit() }.take(3) },
                    label = { Text("列数", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = hOverlapText,
                    onValueChange = { s -> hOverlapText = s.filter { it.isDigit() }.take(4) },
                    label = { Text("左右重叠", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = vOverlapText,
                    onValueChange = { s -> vOverlapText = s.filter { it.isDigit() }.take(4) },
                    label = { Text("上下重叠", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // ========== 中间：预览区 ==========
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFFEFEFEF))
            ) {
                PreviewCanvas(
                    bitmap = preview,
                    rows = rows,
                    cols = cols,
                    modifier = Modifier.fillMaxSize()
                )
                if (preview == null) {
                    Text(
                        text = if (files.isEmpty()) "没有可预览的图片" else "加载中…",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF757575)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ========== 状态信息 ==========
            Text(
                text = buildString {
                    append(if (files.isEmpty()) "0 / 0" else "${index + 1} / ${files.size}")
                    currentFile?.name?.let { append("  ·  $it") }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = status,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(8.dp))

            // ========== 底部：上一张 / 跳过 / 下一张 ==========
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = {
                        if (index > 0) index-- else status = "已经是第一张了"
                    },
                    enabled = !busy && files.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("上一张") }

                OutlinedButton(
                    onClick = {
                        if (index < files.size - 1) {
                            index++
                            status = "已跳过，未做处理"
                        } else {
                            status = "已经是最后一张了"
                        }
                    },
                    enabled = !busy && files.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("跳过") }

                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val ok = saveCurrent()
                            busy = false
                            if (ok) {
                                if (index < files.size - 1) {
                                    index++
                                    status = "已保存当前页，进入下一张"
                                } else {
                                    status = "已保存最后一张"
                                }
                            }
                        }
                    },
                    enabled = !busy && files.isNotEmpty(),
                    modifier = Modifier.weight(1.4f)
                ) { Text(if (busy) "处理中…" else "下一张") }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PreviewCanvas(
    bitmap: ImageBitmap?,
    rows: Int,
    cols: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val bmp = bitmap ?: return@Canvas
        if (bmp.width <= 0 || bmp.height <= 0) return@Canvas

        val scale = min(size.width / bmp.width, size.height / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f

        drawImage(
            image = bmp,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt())
        )

        val lineColor = Color(0xFFFF3D00)
        val stroke = 1.5.dp.toPx()

        for (i in 1 until cols) {
            val x = left + w * i / cols
            drawLine(lineColor, Offset(x, top), Offset(x, top + h), strokeWidth = stroke)
        }
        for (j in 1 until rows) {
            val y = top + h * j / rows
            drawLine(lineColor, Offset(left, y), Offset(left + w, y), strokeWidth = stroke)
        }
    }
}
