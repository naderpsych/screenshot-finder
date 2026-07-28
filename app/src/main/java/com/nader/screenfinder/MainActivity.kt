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
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
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
import com.nader.screenfinder.data.IdEmb
import com.nader.screenfinder.data.ShotDao
import com.nader.screenfinder.scan.TextVec
import com.nader.screenfinder.scan.Vec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** keeps the fingerprints in memory so every search does not reload them */
private object EmbCache {
    private var data: List<IdEmb> = emptyList()
    private var loadedAt = 0L

    suspend fun get(dao: ShotDao): List<IdEmb> {
        val now = System.currentTimeMillis()
        if (data.isEmpty() || now - loadedAt > 60_000) {
            data = dao.allEmb()
            loadedAt = now
        }
        return data
    }
}

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
        var showAbout by remember { mutableStateOf(false) }
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
            if (query.isBlank()) {
                shots = if (cat != null) dao.byCategory(cat!!) else dao.recent()
                return@LaunchedEffect
            }
            delay(300)   // wait until typing stops
            val textHits = try {
                dao.search(buildFts(query))
            } catch (e: Exception) {
                emptyList()
            }
            val qv = withContext(Dispatchers.Default) { TextVec.embed(this@MainActivity, query) }
            if (qv == null) {
                shots = textHits
                return@LaunchedEffect
            }
            val packed = Vec.pack(qv)
            val all = EmbCache.get(dao)
            val sims = withContext(Dispatchers.Default) {
                all.mapNotNull { e -> e.emb?.let { b -> e.id to Vec.cos(packed, b) } }
                    .filter { it.second > 0.20f }
                    .sortedByDescending { it.second }
                    .take(200)
            }
            val score = sims.toMap()
            val textIds = textHits.map { it.id }.toSet()
            val visual = if (sims.isEmpty()) emptyList()
            else dao.byIds(sims.map { it.first }).filter { it.id !in textIds }
            shots = (textHits + visual)
                .sortedByDescending { (score[it.id] ?: 0f) + if (it.id in textIds) 0.15f else 0f }
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
                // progress card: what stage we are in and how far it got
                val total = fast.second
                val doneCount = fast.first
                val pct = if (total > 0) doneCount.toFloat() / total else 0f
                val finished = total > 0 && doneCount >= total
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(Card, RoundedCornerShape(12.dp))
                        .clickable { showAbout = true }
                        .padding(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (finished) "✓ שלב 2 · סריקה עמוקה הושלמה"
                            else if (total == 0) "מכין רשימת סקרינשוטים..."
                            else "שלב 2 · סריקה עמוקה — קורא טקסט ורואה תמונות",
                            fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Bold
                        )
                        Text("ⓘ", fontSize = 15.sp, color = Accent)
                    }
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Accent,
                            trackColor = Color(0xFF2A3140)
                        )
                        Text(
                            "${(pct * 100).toInt()}%  ·  $doneCount מתוך $total" +
                                if (finished) "  ·  שלב 3: מארגן קבוצות" else "",
                            fontSize = 12.sp, color = Color.White
                        )
                    }
                    Text(
                        "✓ שלב 1 · סיווג ראשוני לפי מקור הצילום — הושלם",
                        fontSize = 11.sp, color = Color(0xFF7BC67B),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        speed.ifBlank { "הקש לפרטים על האפליקציה" },
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
                        item {
                            AssistChip(
                                onClick = { Brain.cancel() },
                                label = { Text("מוריד מוח $brainProgress% · הקש לביטול") })
                        }
                    }
                    if (brainReady) {
                        item {
                            AssistChip(
                                onClick = {
                                    Brain.remove(this@MainActivity)
                                    brainReady = Brain.available(this@MainActivity)
                                    Toast.makeText(this@MainActivity, "המוח נמחק והשטח פונה", Toast.LENGTH_SHORT).show()
                                },
                                label = { Text("🧠 מחק מוח (${Brain.sizeMb(this@MainActivity)}MB)") })
                        }
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

        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("Screenote · גרסה ${BuildConfig.VERSION_NAME}") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        item {
                            Text(
                                "מוצא סקרינשוטים לפי מה שכתוב בהם ולפי מה שרואים בהם. " +
                                    "הכל קורה בטלפון - שום תמונה לא נשלחת לשום מקום, וגם לא נמחקת או משתנה: " +
                                    "לאפליקציה אין בכלל הרשאת כתיבה.\n\n" +

                                    "שלב 1 · סיווג ראשוני (שניות)\n" +
                                    "כל סקרינשוט נכנס לקטגוריה זמנית לפי האפליקציה שממנה צולם - פייסבוק, " +
                                    "וואטסאפ, דפדפן. זה מגיע משם הקובץ ולא דורש סריקה, אבל זה רק פיגום " +
                                    "שמתחלף בהמשך.\n\n" +

                                    "שלב 2 · סריקה עמוקה (שעות, ברקע)\n" +
                                    "לכל תמונה: קריאת הטקסט שבה בעברית, ערבית, אנגלית וצרפתית; זיהוי " +
                                    "מה רואים בתמונה עצמה; והחלטה על קטגוריה אמיתית שמחליפה את הזמנית. " +
                                    "אפשר לצאת מהאפליקציה - זה ממשיך, וממשיך מאיפה שהפסיק.\n\n" +

                                    "שלב 3 · ארגון עצמי (אוטומטי)\n" +
                                    "האפליקציה מזהה משפחות של סקרינשוטים דומים ויוצרת להן קטגוריה בשם " +
                                    "המילה שמשותפת לכולן. משפחה נחשבת כזו מ-25 תמונות ומעלה.\n\n" +

                                    "חיפוש\n" +
                                    "מחפש גם בטקסט וגם במשמעות הוויזואלית: \"french food\" יביא גם תמונות " +
                                    "שלא כתוב בהן כלום, כי האפליקציה מזהה מה מופיע בהן. עברית עובדת " +
                                    "היטב על טקסט, ובאנגלית גם החיפוש הוויזואלי מדויק יותר.\n\n" +

                                    "מוח AI (רשות)\n" +
                                    "קובץ בנפח כ-520MB שמוריד מודל שפה קטן לטלפון. הוא קורא טקסטים שהכללים " +
                                    "לא הצליחו לסווג ומחליט לפי הבנה. אפשר להוריד, לבטל באמצע, ולמחוק בכל רגע.\n\n" +

                                    "סקרינשוט חדש נסרק אוטומטית. סקרינשוט שנמחק מהטלפון נעלם גם מכאן.",
                                fontSize = 13.sp, color = Color.White
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        ScanWorker.enqueue(this@MainActivity)
                        Toast.makeText(this@MainActivity, "הסריקה הופעלה", Toast.LENGTH_SHORT).show()
                        showAbout = false
                    }) { Text("הפעל סריקה") }
                },
                dismissButton = { TextButton(onClick = { showAbout = false }) { Text("סגור") } }
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
                                    if (moved > 0) "סווגו $moved תמונות לקטגוריה \"${name.trim()}\""
                                    else "הקטגוריה נשמרה. עדיין אין התאמות - היא תתמלא ככל שהסריקה מתקדמת",
                                    Toast.LENGTH_LONG
                                ).show()
                                ScanWorker.enqueue(this@MainActivity)
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
