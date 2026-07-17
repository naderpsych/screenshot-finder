package com.nader.screenfinder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nader.screenfinder.data.CatCount
import com.nader.screenfinder.data.Db
import com.nader.screenfinder.data.Shot
import com.nader.screenfinder.data.UserRule
import com.nader.screenfinder.scan.Brain
import com.nader.screenfinder.scan.Ocr
import com.nader.screenfinder.scan.ScanWorker
import com.nader.screenfinder.scan.Scanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0E1116)
private val Card = Color(0xFF1A1F27)
private val Accent = Color(0xFF6CB4EE)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Bg,
                    surface = Card
                )
            ) { Main() }
        }
    }

    private fun hasPerm(): Boolean =
        checkSelfPermission(ScanWorker.perm) == PackageManager.PERMISSION_GRANTED

    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val bridge = mapOf(
        "אוכל" to "food", "עוגה" to "cake", "קינוח" to "dessert", "כלב" to "dog",
        "חתול" to "cat", "מפה" to "map", "רכב" to "car", "חוף" to "beach",
        "ים" to "beach", "פרח" to "flower", "בגדים" to "clothing", "צמח" to "plant"
    )

    private fun buildFts(q: String): String {
        val words = Ocr.norm(q).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "\"\""
        if (words.size == 1) {
            val w = words[0]
            val b = bridge[w]
            return if (b != null) "$w* OR $b*" else "$w*"
        }
        return words.joinToString(" ") { "$it*" }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun Main() {
        var granted by remember { mutableStateOf(hasPerm()) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            granted = hasPerm()
            if (granted) ScanWorker.enqueue(this)
        }
        LaunchedEffect(Unit) {
            if (!granted) launcher.launch(perms()) else ScanWorker.enqueue(this@MainActivity)
        }
        if (!granted) {
            Text(
                "האפליקציה צריכה הרשאת גישה לכל התמונות.\nהגדרות > אפליקציות > חיפוש סקרינשוטים > הרשאות",
                Modifier.padding(24.dp), color = Color.White
            )
            return
        }

        val dao = remember { Db.get(this).dao() }
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf("") }
        var cat by remember { mutableStateOf<String?>(null) }
        var shots by remember { mutableStateOf(listOf<Shot>()) }
        var cats by remember { mutableStateOf(listOf<CatCount>()) }
        var prog by remember { mutableStateOf(0 to 0) }
        var showRule by remember { mutableStateOf(false) }
        var viewer by remember { mutableStateOf<Int?>(null) }
        var tick by remember { mutableStateOf(0) }
        var brainReady by remember { mutableStateOf(Brain.available(this)) }
        var brainProgress by remember { mutableStateOf<Int?>(null) }

        LaunchedEffect(Unit) {
            while (true) {
                prog = dao.countScanned() to dao.countAll()
                cats = dao.cats()
                tick++
                delay(3000)
            }
        }
        LaunchedEffect(query, cat, tick) {
            shots = when {
                query.isNotBlank() -> try {
                    dao.search(buildFts(query))
                } catch (e: Exception) {
                    emptyList()
                }
                cat != null -> dao.byCategory(cat!!)
                else -> dao.recent()
            }
        }

        Box(Modifier.fillMaxSize().background(Bg)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                Text(
                    "חיפוש סקרינשוטים",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("חיפוש בכל הסקרינשוטים...") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                if (prog.second > 0 && prog.first < prog.second) {
                    Text(
                        "נסרקו ${prog.first} מתוך ${prog.second}",
                        fontSize = 12.sp, color = Accent,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    item {
                        FilterChip(
                            selected = cat == null && query.isBlank(),
                            onClick = { cat = null; query = "" },
                            label = { Text("הכל") })
                    }
                    rowItems(cats.filter { it.category != null }) { cc ->
                        FilterChip(
                            selected = cat == cc.category,
                            onClick = { cat = cc.category; query = "" },
                            label = { Text("${cc.category} (${cc.cnt})") })
                    }
                    item {
                        AssistChip(onClick = { showRule = true }, label = { Text("+ קטגוריה") })
                    }
                    if (!brainReady && brainProgress == null) {
                        item {
                            AssistChip(
                                onClick = {
                                    brainProgress = 0
                                    scope.launch {
                                        val ok = Brain.download(this@MainActivity) { p -> brainProgress = p }
                                        brainProgress = null
                                        brainReady = Brain.available(this@MainActivity)
                                        Toast.makeText(
                                            this@MainActivity,
                                            if (ok) "המוח הותקן! הסיווג החכם ירוץ ברקע" else "ההורדה נכשלה, נסה שוב על WiFi",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        if (ok) ScanWorker.enqueue(this@MainActivity)
                                    }
                                },
                                label = { Text("🧠 הורד מוח AI (~350MB)") })
                        }
                    }
                    if (brainProgress != null) {
                        item { AssistChip(onClick = {}, label = { Text("מוריד מוח... $brainProgress%") }) }
                    }
                }
                LazyVerticalGrid(columns = GridCells.Fixed(3)) {
                    itemsIndexed(shots, key = { _, s -> s.id }) { idx, s ->
                        AsyncImage(
                            model = Scanner.uri(s.id),
                            contentDescription = s.category,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(2.dp)
                                .aspectRatio(0.55f)
                                .clickable { viewer = idx }
                        )
                    }
                }
            }

            // in-app fullscreen viewer with swipe
            val vi = viewer
            if (vi != null && shots.isNotEmpty()) {
                BackHandler { viewer = null }
                val pager = rememberPagerState(initialPage = vi.coerceIn(0, shots.size - 1)) { shots.size }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                        val s = shots[page]
                        Column(Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = Scanner.uri(s.id),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                            Text(
                                listOfNotNull(s.category, s.source).joinToString(" · ")
                                    .ifBlank { " " },
                                color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp)
                            )
                        }
                    }
                    Text(
                        "✕",
                        color = Color.White, fontSize = 26.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .clickable { viewer = null }
                    )
                }
            }
        }

        if (showRule) {
            var name by remember { mutableStateOf("") }
            var kw by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showRule = false },
                title = { Text("קטגוריה חדשה") },
                text = {
                    Column {
                        OutlinedTextField(name, { name = it }, placeholder = { Text("שם הקטגוריה") })
                        OutlinedTextField(kw, { kw = it }, placeholder = { Text("מילות מפתח, מופרדות בפסיק") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (name.isNotBlank() && kw.isNotBlank()) {
                            scope.launch {
                                dao.addRule(UserRule(name = name.trim(), keywords = kw))
                                var moved = 0
                                kw.split(",").map { Ocr.norm(it.trim()) }
                                    .filter { it.isNotBlank() }
                                    .forEach { moved += dao.applyRule(name.trim(), it) }
                                Toast.makeText(
                                    this@MainActivity,
                                    "סווגו $moved תמונות לקטגוריה \"${name.trim()}\"",
                                    Toast.LENGTH_LONG
                                ).show()
                                tick++
                            }
                        }
                        showRule = false
                    }) { Text("שמור") }
                },
                dismissButton = {
                    TextButton(onClick = { showRule = false }) { Text("ביטול") }
                }
            )
        }
    }
}
