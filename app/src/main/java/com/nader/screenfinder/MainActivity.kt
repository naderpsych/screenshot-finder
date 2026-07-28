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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.nader.screenfinder.scan.Categorizer
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
                colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)
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
                "האפליקציה צריכה הרשאת גישה לכל התמונות.\nהגדרות > אפליקציות > Screenote > הרשאות",
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
        var fast by remember { mutableStateOf(0 to 0) }
        var deep by remember { mutableStateOf(0) }
        var speed by remember { mutableStateOf("") }
        val prefs = remember { getSharedPreferences("sf", MODE_PRIVATE) }
        var showRule by remember { mutableStateOf(false) }
        var assign by remember { mutableStateOf<Shot?>(null) }
        var viewer by remember { mutableStateOf<Int?>(null) }
        var tick by remember { mutableStateOf(0) }
        var brainReady by remember { mutableStateOf(Brain.available(this)) }
        var brainProgress by remember { mutableStateOf<Int?>(null) }

        LaunchedEffect(Unit) {
            while (true) {
                fast = dao.countScanned() to dao.countAll()
                deep = dao.countDeep()
                speed = prefs.getString("speed", "") ?: ""
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
                    "Screenote",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
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
                // always visible status - tap to restart scanning
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(Card, RoundedCornerShape(10.dp))
                        .clickable {
                            ScanWorker.enqueue(this@MainActivity)
                            Toast.makeText(this@MainActivity, "הסריקה הופעלה", Toast.LENGTH_SHORT).show()
                        }
                        .padding(8.dp)
                ) {
                    Text(
                        when {
                            fast.second == 0 -> "גרסה ${BuildConfig.VERSION_NAME} · מחפש סקרינשוטים..."
                            fast.first < fast.second ->
                                "גרסה ${BuildConfig.VERSION_NAME} · שלב 1 מהיר: ${fast.first} מתוך ${fast.second}"
                            deep < fast.second ->
                                "גרסה ${BuildConfig.VERSION_NAME} · שלב 2 מעמיק: $deep מתוך ${fast.second}"
                            else -> "גרסה ${BuildConfig.VERSION_NAME} · הכל נסרק (${fast.second})"
                        },
                        fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Bold
                    )
                    Text(
                        speed.ifBlank { "מודד מהירות... (הקש כאן כדי להפעיל סריקה)" },
                        fontSize = 11.sp, color = Color(0xFF8A94A6)
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
                                label = { Text("🧠 הורד מוח AI (~520MB, על WiFi)") })
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
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    listOfNotNull(s.category, s.source).joinToString(" · ").ifBlank { "לא מסווג" },
                                    color = Color.White, fontSize = 13.sp
                                )
                                TextButton(onClick = { assign = s }) { Text("שנה קטגוריה") }
                            }
                        }
                    }
                    Text(
                        "✕",
                        color = Color.White, fontSize = 26.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                            .clickable { viewer = null }
                    )
                }
            }
        }

        // assign a shot to a category - and teach the app from it
        val target = assign
        if (target != null) {
            var custom by remember { mutableStateOf("") }
            fun apply(name: String) {
                if (name.isBlank()) return
                scope.launch {
                    dao.setUserCat(target.id, name.trim())
                    Toast.makeText(
                        this@MainActivity,
                        "נשמר. תמונות דומות יסווגו כך גם הן",
                        Toast.LENGTH_SHORT
                    ).show()
                    ScanWorker.enqueue(this@MainActivity)
                    tick++
                }
                assign = null
            }
            AlertDialog(
                onDismissRequest = { assign = null },
                title = { Text("לאיזו קטגוריה זה שייך?") },
                text = {
                    Column {
                        LazyColumn(Modifier.heightIn(max = 260.dp)) {
                            rowItems(cats.mapNotNull { it.category }.filter { it != "לא מסווג" }) { name ->
                                Text(
                                    name, color = Color.White, fontSize = 16.sp,
                                    modifier = Modifier.fillMaxWidth().clickable { apply(name) }
                                        .padding(vertical = 10.dp)
                                )
                            }
                        }
                        OutlinedTextField(
                            custom, { custom = it },
                            placeholder = { Text("או קטגוריה חדשה") }, singleLine = true
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { apply(custom) }) { Text("שמור") } },
                dismissButton = { TextButton(onClick = { assign = null }) { Text("ביטול") } }
            )
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
                                for (k in kw.split(",").map { Ocr.norm(it.trim()) }.filter { it.isNotBlank() }) {
                                    for (s in dao.candidates(k)) {
                                        if (Categorizer.wordMatch(s.norm ?: "", k) && s.category != name.trim()) {
                                            dao.update(s.copy(category = name.trim()))
                                            moved++
                                        }
                                    }
                                }
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
                dismissButton = { TextButton(onClick = { showRule = false }) { Text("ביטול") } }
            )
        }
    }
}
