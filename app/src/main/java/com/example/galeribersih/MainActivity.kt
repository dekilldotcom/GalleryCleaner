package com.example.galeribersih

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns as F
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Media(val id: Long, val uri: Uri, val name: String, val size: Long, val date: Long, val video: Boolean)
data class Header(val label: String, val ids: List<Long>, val size: Long)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

fun fmt(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "${maxOf(1, b / 1024)} KB"
}

fun loadMedia(cr: ContentResolver): List<Media> {
    val out = ArrayList<Media>()
    val proj = arrayOf(F._ID, F.DISPLAY_NAME, F.SIZE, F.DATE_TAKEN, F.DATE_ADDED, F.MEDIA_TYPE)
    val where = "${F.MEDIA_TYPE}=? OR ${F.MEDIA_TYPE}=?"
    val args = arrayOf(F.MEDIA_TYPE_IMAGE.toString(), F.MEDIA_TYPE_VIDEO.toString())
    cr.query(MediaStore.Files.getContentUri("external"), proj, where, args, null)?.use { c ->
        while (c.moveToNext()) {
            val video = c.getInt(5) == F.MEDIA_TYPE_VIDEO
            val taken = c.getLong(3)
            val date = if (taken > 0) taken else c.getLong(4) * 1000
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val id = c.getLong(0)
            out += Media(id, ContentUris.withAppendedId(base, id), c.getString(1) ?: "?", c.getLong(2), date, video)
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<Media>>(emptyList()) }
    var granted by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(setOf<Long>()) }
    var sort by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(0) }

    fun reload() { items = loadMedia(ctx.contentResolver) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }
        if (granted) reload()
    }
    val delLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { sel = emptySet(); reload() }
    }
    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permLauncher.launch(perms)
    }

    val shown = items.filter { filter == 0 || (filter == 1 && !it.video) || (filter == 2 && it.video) }
    val sorted = when (sort) {
        0 -> shown.sortedByDescending { it.date }
        1 -> shown.sortedBy { it.date }
        else -> shown.sortedByDescending { it.size }
    }
    val monthFmt = remember { SimpleDateFormat("MMMM yyyy", Locale("id")) }
    val dayFmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id")) }
    val rows: List<Any> = if (sort == 2) sorted else
        sorted.groupBy { monthFmt.format(Date(it.date)) }.flatMap { (k, v) ->
            listOf<Any>(Header(k, v.map { it.id }, v.sumOf { it.size })) + v
        }
    val selSize = items.filter { it.id in sel }.sumOf { it.size }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.padding(12.dp)) {
            Text("${shown.size} file • ${fmt(shown.sumOf { it.size })}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Terbaru", "Terlama", "Terbesar").forEachIndexed { i, t ->
                    FilterChip(sort == i, { sort = i }, { Text(t) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Semua", "Foto", "Video").forEachIndexed { i, t ->
                    FilterChip(filter == i, { filter = i }, { Text(t) })
                }
            }
        }
        if (!granted) Text("Izinkan akses foto & video agar daftar muncul.", Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { if (it is Media) it.id else (it as Header).label }) { r ->
                if (r is Header) {
                    val all = r.ids.all { it in sel }
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable { sel = if (all) sel - r.ids.toSet() else sel + r.ids }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.label, style = MaterialTheme.typography.titleSmall)
                            Text("${r.ids.size} file • ${fmt(r.size)} • ketuk untuk pilih semua", style = MaterialTheme.typography.bodySmall)
                        }
                        Checkbox(all, null)
                    }
                } else if (r is Media) {
                    Row(
                        Modifier.fillMaxWidth().clickable { sel = if (r.id in sel) sel - r.id else sel + r.id }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Thumb(r)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(r.name, maxLines = 1)
                            Text("${dayFmt.format(Date(r.date))} • ${fmt(r.size)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Checkbox(r.id in sel, null)
                    }
                }
            }
        }
        if (sel.isNotEmpty()) {
            Button(
                onClick = {
                    val uris = items.filter { it.id in sel }.map { it.uri }
                    val pi = MediaStore.createDeleteRequest(ctx.contentResolver, uris)
                    delLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) { Text("Hapus ${sel.size} file (${fmt(selSize)})") }
        }
    }
}

@Composable
fun Thumb(m: Media) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, m.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(m.uri, Size(160, 160), null) }.getOrNull()
        }
    }
    Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(Color.LightGray)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (m.video) Text("▶", Modifier.align(Alignment.Center), color = Color.White)
    }
}
