package app.parallaxwall

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6FE7FF),
                    onPrimary = Color(0xFF00222A),
                    secondary = Color(0xFFB69CFF),
                    background = Color(0xFF0B0F1A),
                    surface = Color(0xFF141A2B),
                    onBackground = Color(0xFFEAF0FF),
                    onSurface = Color(0xFFEAF0FF),
                )
            ) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Home()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    companion object {
        val resumeTick = mutableIntStateOf(0)
    }
}

private val Accent = Color(0xFF6FE7FF)
private const val ASPECT = 9f / 20.4f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var library by remember { mutableStateOf(store.items()) }
    // 目前正在編輯哪個畫面的素材（主畫面 / 鎖定畫面）
    var slot by remember { mutableStateOf(Store.HOME) }
    var currentId by remember { mutableStateOf(store.currentId(Store.HOME)) }
    var st by remember { mutableStateOf(store.settings()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val tick = MainActivity.resumeTick.intValue
    val homeActive = remember(tick) {
        try {
            WallpaperManager.getInstance(ctx).wallpaperInfo?.packageName == ctx.packageName
        } catch (e: Exception) {
            false
        }
    }
    // 鎖定畫面是否已套用本 App：Android 14 以上才能查；查不到就回傳 null（不顯示狀態）
    val lockActive: Boolean? = remember(tick) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                WallpaperManager.getInstance(ctx).getWallpaperInfo(WallpaperManager.FLAG_LOCK)
                    ?.packageName == ctx.packageName
            } catch (e: Throwable) {
                null
            }
        } else null
    }
    val active = if (slot == Store.LOCK) lockActive == true else homeActive

    val current = library.firstOrNull { it.id == currentId } ?: library.firstOrNull()
    val screenName = if (slot == Store.LOCK) "鎖定畫面" else "主畫面"

    fun refresh() {
        library = store.items()
        currentId = store.currentId(slot)
    }

    fun update(n: Settings) {
        st = n
        store.save(n)
    }

    fun importAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            busy = true
            var ok = 0
            val errs = mutableListOf<String>()
            for (u in uris) {
                try {
                    withContext(Dispatchers.IO) { Importer.ingest(ctx.applicationContext, u, slot) }
                    ok++
                } catch (e: Exception) {
                    errs.add(e.message ?: "匯入失敗")
                }
            }
            refresh()
            busy = false
            msg = "已匯入 $ok 個" + if (errs.isNotEmpty()) "，失敗 ${errs.size}：${errs.first()}" else ""
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        importAll(it)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        importAll(it)
    }

    fun applyWallpaper() {
        // 主畫面與鎖定畫面是兩個獨立的桌布項目，系統才允許分別套用
        val service = if (slot == Store.LOCK) LockWallpaperService::class.java
        else ParallaxWallpaperService::class.java
        val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(ctx, service)
        )
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                msg = "無法開啟桌布設定，請從系統「桌布與樣式」選擇本 App"
            }
        }
    }

    val dm = ctx.resources.displayMetrics

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("立體動態桌布", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("要編輯哪個畫面的素材？", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = slot == Store.HOME,
                onClick = { slot = Store.HOME; currentId = store.currentId(Store.HOME) },
                label = { Text(if (homeActive) "主畫面 ●" else "主畫面") }
            )
            FilterChip(
                selected = slot == Store.LOCK,
                onClick = { slot = Store.LOCK; currentId = store.currentId(Store.LOCK) },
                label = { Text(if (lockActive == true) "鎖定畫面 ●" else "鎖定畫面") }
            )
        }
        Text(
            if (active) "● $screenName 已套用本 App"
            else "○ $screenName 尚未套用：選好素材後按下方「設為${screenName}桌布」",
            color = if (active) Accent else Color(0xFFB0B8C8),
            fontSize = 13.sp
        )
        if (slot == Store.LOCK) {
            val own = store.ownId(Store.LOCK) != null
            Text(
                if (own) "鎖定畫面使用自己的素材。" else "鎖定畫面目前跟隨主畫面的素材；在素材庫點選或匯入新素材，就會改用獨立素材。",
                fontSize = 12.sp,
                color = Color(0xFFB0B8C8)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FocusPreview(
                store = store,
                item = current,
                modifier = Modifier
                    .width(150.dp)
                    .aspectRatio(ASPECT),
                onCommit = { fx, fy ->
                    current?.let {
                        store.updateFocus(it.id, fx, fy)
                        refresh()
                    }
                }
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("從相簿選擇") }
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("image/*", "video/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("從檔案選擇") }
                Button(
                    onClick = { applyWallpaper() },
                    enabled = library.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (active) "重新套用到$screenName" else "設為${screenName}桌布") }
                Text(
                    "系統跳出確認畫面時，請選「$screenName」。",
                    fontSize = 11.sp,
                    color = Color(0xFFB0B8C8)
                )
                if (slot == Store.LOCK && store.ownId(Store.LOCK) != null) {
                    OutlinedButton(
                        onClick = {
                            store.clearOwn(Store.LOCK)
                            refresh()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("改回跟隨主畫面") }
                }
                OutlinedButton(
                    onClick = {
                        current?.let {
                            store.remove(it.id)
                            refresh()
                        }
                    },
                    enabled = current != null && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("刪除目前素材") }
                if (current != null) {
                    val kindText = when {
                        current.kind == "VIDEO" -> "影片"
                        current.ext == "gif" -> "GIF 動圖"
                        else -> "圖片（${current.ext.uppercase()}）"
                    }
                    Text(
                        "${current.name}\n$kindText　${current.w}×${current.h}",
                        fontSize = 12.sp,
                        color = Color(0xFFB0B8C8)
                    )
                }
            }
        }

        if (busy) Text("匯入中…", color = Accent)
        msg?.let { Text(it, color = Color(0xFFFFD27A), fontSize = 13.sp) }

        if (library.isNotEmpty()) {
            Text("素材庫", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(library, key = { it.id }) { it2 ->
                    Thumb(store, it2, it2.id == current?.id) {
                        store.setCurrent(it2.id, slot)
                        refresh()
                    }
                }
            }
        }

        Card {
            Text("立體與光暈", fontWeight = FontWeight.SemiBold)
            LabeledSlider("視差強度（整體晃動幅度）", st.parallax) { update(st.copy(parallax = it)) }
            LabeledSlider("立體深度（前後景位移差）", st.depth) { update(st.copy(depth = it)) }
            LabeledSlider("光暈強度", st.glow) { update(st.copy(glow = it)) }
            LabeledSlider("光影掃過", st.light) { update(st.copy(light = it)) }
            LabeledSlider("畫面暗化（提升桌面圖示可讀性）", st.dim) { update(st.copy(dim = it)) }
            SwitchRow("光暈呼吸", st.pulse) { update(st.copy(pulse = it)) }
            SwitchRow("反轉深度（若覺得前後景顛倒）", st.invertDepth) { update(st.copy(invertDepth = it)) }
            SwitchRow("反向視差（若覺得晃動方向反了）", st.reverseDir) { update(st.copy(reverseDir = it)) }
        }

        Card {
            Text("鎖定畫面時鐘", fontWeight = FontWeight.SemiBold)
            SwitchRow("顯示鐘點戰風格時鐘（年 • 月 • 日 • 星期 • 時 • 分 • 秒）", st.clockOn) { update(st.copy(clockOn = it)) }
            LabeledSlider("垂直位置（預設在「指紋或上滑解鎖」上方）", st.clockY, 0.5f..0.95f) {
                update(st.copy(clockY = it))
            }
            LabeledSlider("字體大小", st.clockSize, 0.6f..1.4f) { update(st.copy(clockSize = it)) }
            Text(
                "只在鎖定畫面顯示，解鎖回桌面就會隱藏。系統自己的時鐘無法由 App 移除，" +
                    "請到手機系統設定把鎖定畫面時鐘改成小時鐘（路徑依 ROG UI 版本而異）。",
                fontSize = 12.sp,
                color = Color(0xFFB0B8C8)
            )
        }

        Card {
            Text("影片與輪播", fontWeight = FontWeight.SemiBold)
            SwitchRow("影片靜音", st.muteVideo) { update(st.copy(muteVideo = it)) }
            Text("素材輪播（亮屏時，超過間隔就換下一個）", fontSize = 13.sp, color = Color(0xFFB0B8C8))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "關閉", 5 to "5 分", 15 to "15 分", 30 to "30 分", 60 to "1 小時", 180 to "3 小時").forEach { (m, label) ->
                    FilterChip(
                        selected = st.rotateMin == m,
                        onClick = { update(st.copy(rotateMin = m)) },
                        label = { Text(label) }
                    )
                }
            }
        }

        Card {
            Text("小提醒", fontWeight = FontWeight.SemiBold)
            Text(
                "你的螢幕是 ${dm.widthPixels}×${dm.heightPixels}（約 9:20.4）。素材用同樣比例最完整；" +
                    "其他比例會自動置中填滿裁切，可在左側預覽上拖曳調整構圖焦點。\n\n" +
                    "立體效果會依畫面亮度與中心位置自動推算前後景，不需要另外提供深度圖。" +
                    "影片/GIF 以整體位移呈現視差；影片桌布為了省電，離開桌面時會自動暫停。",
                fontSize = 13.sp,
                color = Color(0xFFB0B8C8)
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { content() }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    onChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp)
            Text("${(value * 100).roundToInt()}%", fontSize = 13.sp, color = Accent)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Thumb(store: Store, item: Item, selected: Boolean, onClick: () -> Unit) {
    val bmp = remember(item.id) {
        BitmapFactory.decodeFile(store.thumbOf(item).absolutePath)?.asImageBitmap()
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .width(56.dp)
            .aspectRatio(ASPECT)
            .clip(shape)
            .background(Color(0xFF1B2236))
            .then(if (selected) Modifier.border(2.dp, Accent, shape) else Modifier)
            .clickable(onClick = onClick)
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(item.fx * 2f - 1f, item.fy * 2f - 1f)
            )
        }
        val badge = when {
            item.kind == "VIDEO" -> "▶"
            item.ext == "gif" -> "GIF"
            else -> ""
        }
        if (badge.isNotEmpty()) {
            Text(
                badge,
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

/** 預覽目前素材在 9:20.4 畫面中的裁切；拖曳可調整構圖焦點。 */
@Composable
private fun FocusPreview(store: Store, item: Item?, modifier: Modifier, onCommit: (Float, Float) -> Unit) {
    val bmp = remember(item?.id) {
        item?.let { BitmapFactory.decodeFile(store.thumbOf(it).absolutePath)?.asImageBitmap() }
    }
    var fx by remember(item?.id, item?.fx) { mutableFloatStateOf(item?.fx ?: 0.5f) }
    var fy by remember(item?.id, item?.fy) { mutableFloatStateOf(item?.fy ?: 0.5f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier
            .clip(shape)
            .background(Color(0xFF111827))
            .border(1.dp, Color(0xFF2A3350), shape)
            .onSizeChanged { size = it }
            .pointerInput(item?.id) {
                detectDragGestures(
                    onDragEnd = { onCommit(fx, fy) }
                ) { change, drag ->
                    change.consume()
                    if (size.width > 0 && size.height > 0) {
                        fx = (fx - drag.x / size.width * 1.6f).coerceIn(0f, 1f)
                        fy = (fy - drag.y / size.height * 1.6f).coerceIn(0f, 1f)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(fx * 2f - 1f, fy * 2f - 1f)
            )
            Text(
                "拖曳調整構圖",
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        } else {
            Text("尚無素材", fontSize = 13.sp, color = Color(0xFF7C86A0))
        }
    }
}
