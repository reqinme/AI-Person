package qinme.aiperson

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 主题

private fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** 每次回到前台执行一次，用来复查通知权限是否被系统关掉。 */
@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(block)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) current()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF07C160),
    onPrimary = Color.White,
    background = Color(0xFFEDEDED),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191919),
    onSurfaceVariant = Color(0xFF888888)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3EB575),
    onPrimary = Color(0xFF0B1F12),
    background = Color(0xFF111111),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF8C8C8C)
)

@Composable
fun AIPersonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

/** 微信骨架 + 水玻璃质感用到的颜色。 */
private data class WeChatColors(
    val background: Color,
    val bar: Color,
    val bubbleOther: Color,
    val bubbleSelf: Color,
    val selfText: Color,
    val text: Color,
    val muted: Color,
    val divider: Color,
    val inputBar: Color,
    val highlight: Color,
    val accent: Color
)

private val WeChatLight = WeChatColors(
    background = Color(0xFFEDEDED),
    bar = Color(0xFFF7F7F7),
    bubbleOther = Color(0xFFFFFFFF),
    bubbleSelf = Color(0xFF95EC69),
    selfText = Color(0xFF1A1A1A),
    text = Color(0xFF191919),
    muted = Color(0xFF888888),
    divider = Color(0xFFE3E3E3),
    inputBar = Color(0xFFFFFFFF),
    highlight = Color.White.copy(alpha = 0.65f),
    accent = Color(0xFF07C160)
)

private val WeChatDark = WeChatColors(
    background = Color(0xFF111111),
    bar = Color(0xFF1A1A1A),
    bubbleOther = Color(0xFF2C2C2C),
    bubbleSelf = Color(0xFF3EB575),
    selfText = Color(0xFF0B1F12),
    text = Color(0xFFE0E0E0),
    muted = Color(0xFF8C8C8C),
    divider = Color(0xFF2A2A2A),
    inputBar = Color(0xFF2A2A2A),
    highlight = Color.White.copy(alpha = 0.08f),
    accent = Color(0xFF3EB575)
)

@Composable
private fun weChatColors(): WeChatColors = if (isSystemInDarkTheme()) WeChatDark else WeChatLight

// ---------------------------------------------------------------- 背景与卡片

/** 雾面渐变 + 两团柔光，保留毛玻璃开关的观感。 */
@Composable
fun WeChatScreenBackground(accent: Color, content: @Composable BoxScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) {
        Brush.verticalGradient(listOf(Color(0xFF0E0E10), Color(0xFF17181B), Color(0xFF101114)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFF4F5F7), Color(0xFFE9ECEF), Color(0xFFF3F4F6)))
    }
    Box(Modifier.fillMaxSize().background(base)) {
        val blob = if (UiPrefs.glassEnabled) Modifier.blur(64.dp) else Modifier
        Box(
            Modifier
                .size(260.dp)
                .offset(x = (-80).dp, y = 80.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (dark) 0.20f else 0.14f))
                .then(blob)
        )
        Box(
            Modifier
                .size(220.dp)
                .offset(x = 220.dp, y = 460.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (dark) 0.16f else 0.11f))
                .then(blob)
        )
        content()
    }
}

/** 高透明磨砂卡片：大圆角 + 1px 白色高光边。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = weChatColors()
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(22.dp)
    val fill = c.bar.copy(alpha = if (UiPrefs.glassEnabled) 0.66f else 0.95f)
    val stroke = if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.7f)
    val clickable = if (onClick != null) Modifier.clickable { onClick() } else Modifier

    Column(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .then(clickable)
            .padding(18.dp)
    ) {
        content()
    }
}

// ---------------------------------------------------------------- 通用组件

/** 两步确认：所有会改变她记忆/人格的操作都走这里。 */
data class ConfirmRequest(
    val title: String,
    val body: String,
    val secondBody: String = "这一步会真正改变她的状态，确认继续？",
    val onConfirm: () -> Unit
)

@Composable
private fun TwoStepConfirm(request: ConfirmRequest?, onDismiss: () -> Unit) {
    if (request == null) return
    var step by remember { mutableStateOf(0) }
    LaunchedEffect(request) { step = 0 }

    if (step == 0) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(request.title) },
            text = { Text(request.body) },
            confirmButton = { TextButton(onClick = { step = 1 }) { Text("继续") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("再确认一次") },
            text = { Text(request.secondBody) },
            confirmButton = {
                TextButton(onClick = {
                    request.onConfirm()
                    onDismiss()
                }) { Text("确定修改") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("算了") } }
        )
    }
}

@Composable
fun CharacterAvatar(character: CharacterEntity?, size: Dp = 44.dp) {
    val context = LocalContext.current
    val c = weChatColors()
    val bitmap = remember(character?.avatarPath) {
        character?.avatarPath?.takeIf { it.isNotBlank() }?.let { ImageStore.bitmap(context, it) }
    }
    val accent = Color(character?.accentColor ?: 0xFF07C160.toInt())
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(accent)
            .border(1.dp, c.highlight, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                character?.name?.take(1) ?: "?",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.34f).sp
            )
        }
    }
}

/** 用户自己的头像占位，统一圆形。 */
@Composable
private fun UserAvatar(size: Dp = 40.dp) {
    val c = weChatColors()
    val fill = if (isSystemInDarkTheme()) Color(0xFF3A3A3A) else Color(0xFFD9D9D9)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, c.highlight, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text("我", color = c.text, fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun rememberMediaPicker(kind: ImageStore.Kind, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                ImageStore.save(context, uri, kind)?.let { onPicked(it) }
            }
        }
    }
    return remember {
        {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}

@Composable
private fun rememberCameraCapture(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val captured = uri
        if (ok && captured != null) {
            scope.launch {
                ImageStore.save(context, captured, ImageStore.Kind.CHAT)?.let { onPicked(it) }
            }
        }
    }
    return remember {
        {
            val file = File(context.cacheDir, "camera/${UUID.randomUUID()}.jpg").apply {
                parentFile?.mkdirs()
                createNewFile()
            }
            val target = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            uri = target
            launcher.launch(target)
        }
    }
}

private fun timeText(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

private fun listTime(ts: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = ts }
    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> timeText(ts)
        sameYear && dayDiff == 1 -> "昨天"
        sameYear -> "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
        else -> "${target.get(Calendar.YEAR)}/${target.get(Calendar.MONTH) + 1}/${target.get(Calendar.DAY_OF_MONTH)}"
    }
}

private fun dayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = ts }
    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "今天"
        sameYear && dayDiff == 1 -> "昨天"
        sameYear -> "${target.get(Calendar.MONTH) + 1} 月 ${target.get(Calendar.DAY_OF_MONTH)} 日"
        else -> "${target.get(Calendar.YEAR)} 年 ${target.get(Calendar.MONTH) + 1} 月 ${target.get(Calendar.DAY_OF_MONTH)} 日"
    }
}

private fun remainingText(until: Long): String {
    val left = until - System.currentTimeMillis()
    if (left <= 0) return "已结束"
    val hours = left / 3_600_000L
    val minutes = (left % 3_600_000L) / 60_000L
    return if (hours > 0) "剩余 $hours 小时 $minutes 分" else "剩余 $minutes 分钟"
}

private fun dayKey(ts: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(ts))

// ---------------------------------------------------------------- 顶栏与底栏

@Composable
private fun WeChatTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val c = weChatColors()
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bar.copy(alpha = 0.94f))
            .statusBarsPadding()
    ) {
        Box(Modifier.fillMaxWidth().height(50.dp)) {
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    Box(
                        Modifier.size(50.dp).clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = c.text,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.weight(1f))
                actions()
                Spacer(Modifier.width(4.dp))
            }
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(subtitle, fontSize = 10.sp, color = c.muted, maxLines = 1)
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = c.divider)
    }
}

@Composable
private fun BarAction(icon: ImageVector, onClick: () -> Unit) {
    val c = weChatColors()
    Box(
        Modifier.size(46.dp).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun BarTextAction(label: String, onClick: () -> Unit) {
    val c = weChatColors()
    Box(
        Modifier.height(46.dp).clickable { onClick() }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp, color = c.accent)
    }
}

@Composable
private fun WeChatBottomBar(selected: String, onChat: () -> Unit, onMe: () -> Unit) {
    val c = weChatColors()
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bar.copy(alpha = 0.94f))
            .navigationBarsPadding()
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = c.divider)
        Row(Modifier.fillMaxWidth().height(54.dp)) {
            BottomTab("聊天", Icons.Filled.ChatBubble, selected == "chat", Modifier.weight(1f), onChat)
            BottomTab("我的", Icons.Filled.Person, selected == "me", Modifier.weight(1f), onMe)
        }
    }
}

@Composable
private fun BottomTab(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val c = weChatColors()
    val tint = if (active) c.accent else c.muted
    Column(
        modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = tint)
    }
}

// ---------------------------------------------------------------- 导航

/** 会话内搜索跳转用：目标消息 id，聊天页读到后滚动并高亮。 */
private object ChatNav {
    var pendingJump by mutableStateOf<Long?>(null)
}

@Composable
fun AppRoot(openCharacterId: String?, onOpened: () -> Unit) {
    val nav = rememberNavController()

    LaunchedEffect(openCharacterId) {
        val id = openCharacterId
        if (!id.isNullOrBlank()) {
            nav.navigate("chat/$id")
            onOpened()
        }
    }

    NavHost(navController = nav, startDestination = "characters") {
        composable("characters") { CharacterListScreen(nav) }
        composable("me") { MeScreen(nav) }
        composable("search") { GlobalSearchScreen(nav) }
        composable("chat/{id}") { entry ->
            ChatScreen(nav, entry.arguments?.getString("id").orEmpty())
        }
        composable("chat/{id}/search") { entry ->
            InChatSearchScreen(nav, entry.arguments?.getString("id").orEmpty())
        }
        composable("chat/{id}/menu") { entry ->
            ChatMenuScreen(nav, entry.arguments?.getString("id").orEmpty())
        }
        composable("character/{id}/settings") { entry ->
            CharacterSettingsScreen(nav, entry.arguments?.getString("id").orEmpty())
        }
        composable("memory/{id}") { entry ->
            MemoryScreen(nav, entry.arguments?.getString("id").orEmpty())
        }
    }
}

private val ACCENTS = listOf(
    0xFF0FDC78.toInt(),
    0xFF80C1FF.toInt(),
    0xFFF87454.toInt(),
    0xFFB79BFF.toInt()
)

private suspend fun createCharacter(
    name: String,
    gender: String,
    persona: String,
    dislikes: String,
    accent: Int,
    avatarPath: String,
    temperament: Temperament,
    identity: String,
    background: String,
    catchphrase: String,
    memeLevel: Int,
    routine: String,
    routineDetail: String
) {
    val id = UUID.randomUUID().toString()
    AppGraph.db.characterDao().upsert(
        CharacterEntity(
            id = id,
            name = name,
            gender = gender,
            persona = persona,
            accentColor = accent,
            createdAt = System.currentTimeMillis(),
            dislikes = dislikes.ifBlank { DEFAULT_DISLIKES },
            avatarPath = avatarPath,
            identity = identity,
            background = background,
            catchphrase = catchphrase,
            memeLevel = memeLevel,
            routine = routine,
            routineDetail = routineDetail
        )
    )
    AppGraph.db.relationshipDao().upsert(RelationshipEntity(characterId = id))
    // 当前值和底色起点完全一致：还没发生任何事，就不该已经有"成长"
    AppGraph.db.personalityDao().upsert(
        PersonalityEntity(
            characterId = id,
            warmth = temperament.warmth,
            liveliness = temperament.liveliness,
            initiative = temperament.initiative,
            independence = temperament.independence,
            clinginess = temperament.clinginess,
            prefWarmth = temperament.warmth,
            prefLiveliness = temperament.liveliness,
            prefInitiative = temperament.initiative,
            prefIndependence = temperament.independence,
            prefClinginess = temperament.clinginess,
            temperament = temperament.name,
            baseWarmth = temperament.warmth,
            baseLiveliness = temperament.liveliness,
            baseInitiative = temperament.initiative,
            baseIndependence = temperament.independence,
            baseClinginess = temperament.clinginess
        )
    )
    AppGraph.db.moodDao().upsert(MoodEntity(characterId = id))
    BehaviorSettings.setIntimacyRaw(id, 0)
}

// ---------------------------------------------------------------- 聊天列表

@Composable
fun CharacterListScreen(nav: NavHostController) {
    val characters by AppGraph.db.characterDao().observeOrdered()
        .collectAsState(initial = emptyList<CharacterEntity>())
    var showCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val c = weChatColors()

    // 延迟消息到点后，列表预览需要自己刷新，不能只依赖数据库变更
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowTick = System.currentTimeMillis()
        }
    }

    WeChatScreenBackground(accent = c.accent) {
        Column(Modifier.fillMaxSize()) {
            WeChatTopBar(
                title = "AI Person",
                actions = {
                    BarAction(Icons.Filled.Search) { nav.navigate("search") }
                    BarAction(Icons.Filled.Add) { showCreate = true }
                }
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (characters.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("还没有角色", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "先给她一个名字和性格。之后的每一次聊天，都会成为她记住你的依据。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { showCreate = true }) { Text("创建第一个角色") }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(characters, key = { it.id }) { character ->
                            ConversationRow(character, nav, nowTick)
                        }
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(onClick = { showCreate = true }) { Text("＋ 新建角色") }
                            }
                        }
                    }
                }
            }
            WeChatBottomBar(
                selected = "chat",
                onChat = {},
                onMe = { nav.navigate("me") { launchSingleTop = true } }
            )
        }
    }

    if (showCreate) {
        CreateCharacterDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, gender, persona, dislikes, accent, avatar, temperament,
                         identity, background, catchphrase, memeLevel, routine, routineDetail ->
                showCreate = false
                scope.launch {
                    createCharacter(
                        name, gender, persona, dislikes, accent, avatar, temperament,
                        identity, background, catchphrase, memeLevel, routine, routineDetail
                    )
                }
            }
        )
    }
}

@Composable
private fun ConversationRow(character: CharacterEntity, nav: NavHostController, now: Long) {
    val c = weChatColors()
    val relationship by AppGraph.db.relationshipDao()
        .observe(character.id)
        .collectAsState(initial = null)
    val recent by remember(character.id) {
        AppGraph.db.messageDao().observeRecent(character.id, 20)
    }.collectAsState(initial = emptyList())
    val last = remember(recent, now) {
        recent.firstOrNull {
            (it.visibleAt == 0L || it.visibleAt <= now) &&
                !(it.role == "note" && it.content.isBlank())
        }
    }
    val shape = RoundedCornerShape(18.dp)
    val preview = last?.let { msg ->
        if (msg.imagePath.isNotBlank()) "[图片]" else msg.content.ifBlank { "…" }
    } ?: "还没开始聊天"

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.bar.copy(alpha = if (UiPrefs.glassEnabled) 0.72f else 0.95f))
            .border(1.dp, c.highlight, shape)
            .clickable { nav.navigate("chat/${character.id}") }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CharacterAvatar(character, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (character.pinned) {
                    Text(
                        "置顶",
                        fontSize = 9.sp,
                        color = c.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(c.accent.copy(alpha = 0.12f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    character.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                preview,
                fontSize = 12.sp,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                last?.let { listTime(it.visibleAt.takeIf { v -> v > 0 } ?: it.createdAt) } ?: "",
                fontSize = 11.sp,
                color = c.muted
            )
            Spacer(Modifier.height(4.dp))
            Text(
                relationship?.let {
                    if (UiPrefs.showGrowth) "${intimacyStage(it.intimacy)} ${it.intimacy}" else intimacyStage(it.intimacy)
                } ?: "陌生",
                fontSize = 10.sp,
                color = c.muted
            )
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label ${value.roundToInt()}",
            fontSize = 12.sp,
            modifier = Modifier.width(72.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CreateCharacterDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, Int, String, Temperament,
               String, String, String, Int, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("女") }
    var temperament by remember { mutableStateOf(Temperaments.ALL.first()) }
    var persona by remember { mutableStateOf(Temperaments.ALL.first().hint) }
    var dislikes by remember { mutableStateOf(Temperaments.ALL.first().dislikes) }
    var personaEdited by remember { mutableStateOf(false) }
    var dislikesEdited by remember { mutableStateOf(false) }
    var accent by remember { mutableStateOf(ACCENTS.first()) }
    var avatar by remember { mutableStateOf("") }
    var identity by remember { mutableStateOf("") }
    var background by remember { mutableStateOf("") }
    var catchphrase by remember { mutableStateOf("") }
    var routine by remember { mutableStateOf("") }
    var routineDetail by remember { mutableStateOf("") }
    var memeLevel by remember { mutableStateOf(50f) }
    var customBase by remember { mutableStateOf(false) }
    var warmth by remember { mutableStateOf(55f) }
    var liveliness by remember { mutableStateOf(55f) }
    var initiative by remember { mutableStateOf(50f) }
    var independence by remember { mutableStateOf(50f) }
    var clinginess by remember { mutableStateOf(45f) }
    val pickAvatar = rememberMediaPicker(ImageStore.Kind.AVATAR) { avatar = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建角色") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(CircleShape).clickable { pickAvatar() }) {
                        CharacterAvatar(
                            CharacterEntity("", name, gender, persona, accent, 0, avatarPath = avatar),
                            size = 56.dp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { pickAvatar() }) { Text("选头像") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(12) },
                    label = { Text("名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("女", "男").forEach { option ->
                        TextButton(
                            onClick = { gender = option },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (gender == option) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        ) { Text(option) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "她的底色",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Temperaments.ALL.forEach { option ->
                        val selected = !customBase && temperament.name == option.name
                        TextButton(
                            onClick = {
                                customBase = false
                                temperament = option
                                if (!personaEdited) persona = option.hint
                                if (!dislikesEdited) dislikes = option.dislikes
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                        ) { Text(option.name) }
                    }
                    TextButton(
                        onClick = { customBase = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (customBase) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (customBase) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    Color.Transparent
                                }
                            )
                    ) { Text("自定义") }
                }
                if (customBase) {
                    SliderRow("温暖", warmth, { warmth = it })
                    SliderRow("活泼", liveliness, { liveliness = it })
                    SliderRow("主动", initiative, { initiative = it })
                    SliderRow("独立", independence, { independence = it })
                    SliderRow("粘人", clinginess, { clinginess = it })
                } else {
                    Text(
                        temperament.style,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = persona,
                    onValueChange = {
                        persona = it.take(80)
                        personaEdited = true
                    },
                    label = { Text("性格") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = dislikes,
                    onValueChange = {
                        dislikes = it.take(120)
                        dislikesEdited = true
                    },
                    label = { Text("底线（可留空）") },
                    placeholder = { Text("留空默认：被欺骗、被敷衍 / 被当工具使唤 / 被无视") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = identity,
                    onValueChange = { identity = it.take(60) },
                    label = { Text("她是谁、在做什么（可留空）") },
                    placeholder = { Text("比如：插画师，一个人住，养了只猫") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = background,
                    onValueChange = { background = it.take(120) },
                    label = { Text("她的来历（可留空）") },
                    placeholder = { Text("比如：小城市长大，大学才来这边，换过两次工作") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "作息（可留空）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Routine.templates.forEach { (id, label) ->
                        FilterChip(
                            selected = routine == id,
                            onClick = { routine = if (routine == id) "" else id },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = routineDetail,
                    onValueChange = { routineDetail = it.take(160) },
                    label = { Text("她一天怎么过（可留空）") },
                    placeholder = { Text("比如：周三到周五上午有课，下午在图书馆，晚上回宿舍才看手机") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = catchphrase,
                    onValueChange = { catchphrase = it.take(60) },
                    label = { Text("说话习惯 / 口头禅（可留空）") },
                    placeholder = { Text("比如：爱说「行吧」；生气时不叫人全名") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "玩梗程度",
                        fontSize = 12.sp,
                        modifier = Modifier.width(72.dp)
                    )
                    Slider(
                        value = memeLevel,
                        onValueChange = { memeLevel = it },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(memeLevel.roundToInt().toString(), fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ACCENTS.forEach { color ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                                .border(
                                    width = if (accent == color) 3.dp else 0.dp,
                                    color = if (accent == color) Color.White else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { accent = color }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chosen = if (customBase) {
                        Temperament(
                            name = "自定义",
                            warmth = warmth.roundToInt(),
                            liveliness = liveliness.roundToInt(),
                            initiative = initiative.roundToInt(),
                            independence = independence.roundToInt(),
                            clinginess = clinginess.roundToInt(),
                            style = "你的性格是你自己长成的，不套任何模板；说话方式按人设和口头禅来。",
                            hint = persona,
                            dislikes = dislikes
                        )
                    } else {
                        temperament
                    }
                    onCreate(
                        name.trim(), gender, persona.trim(), dislikes.trim(), accent, avatar, chosen,
                        identity.trim(), background.trim(), catchphrase.trim(), memeLevel.roundToInt(),
                        routine, routineDetail.trim()
                    )
                },
                enabled = name.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------------------------------------------------------- 全局搜索

@Composable
fun GlobalSearchScreen(nav: NavHostController) {
    var query by remember { mutableStateOf("") }
    var characters by remember { mutableStateOf(emptyList<CharacterEntity>()) }
    var messages by remember { mutableStateOf(emptyList<MessageHit>()) }
    val c = weChatColors()

    LaunchedEffect(query) {
        delay(250)
        val q = query.trim()
        if (q.isBlank()) {
            characters = emptyList()
            messages = emptyList()
        } else {
            characters = AppGraph.db.characterDao().search(q)
            messages = AppGraph.db.messageDao().searchAll(q)
        }
    }

    WeChatScreenBackground(accent = c.accent) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
        ) {
            WeChatTopBar(title = "搜索", onBack = { nav.popBackStack() })
            Column(Modifier.weight(1f).fillMaxWidth().padding(14.dp)) {
                SearchField(value = query, onChange = { query = it }, placeholder = "搜联系人、聊天记录")
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (characters.isNotEmpty()) {
                        item {
                            Text("联系人", fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(start = 4.dp))
                        }
                        items(characters, key = { "c_" + it.id }) { character ->
                            GlassCard(
                                Modifier.fillMaxWidth(),
                                accent = Color(character.accentColor),
                                onClick = { nav.navigate("chat/${character.id}") }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CharacterAvatar(character, 38.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(character.name, style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                    if (messages.isNotEmpty()) {
                        item {
                            Text(
                                "聊天记录（${messages.size}）",
                                fontSize = 12.sp,
                                color = c.muted,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        items(messages, key = { "m_" + it.id }) { hit ->
                            GlassCard(
                                Modifier.fillMaxWidth(),
                                onClick = {
                                    ChatNav.pendingJump = hit.id
                                    nav.navigate("chat/${hit.characterId}")
                                }
                            ) {
                                Text(
                                    "${hit.characterName} · ${dayLabel(hit.createdAt)} ${timeText(hit.createdAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(hit.content, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                            }
                        }
                    }
                    if (query.isNotBlank() && characters.isEmpty() && messages.isEmpty()) {
                        item {
                            Text(
                                "没有找到相关内容",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.muted,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = weChatColors()
    Box(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(c.bar.copy(alpha = 0.92f))
            .border(1.dp, c.highlight, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = c.muted, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, color = c.muted)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                    singleLine = true,
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 聊天

@Composable
fun ChatScreen(nav: NavHostController, characterId: String) {
    val context = LocalContext.current
    val c = weChatColors()
    val character by AppGraph.db.characterDao()
        .observe(characterId)
        .collectAsState(initial = null)
    val relationship by AppGraph.db.relationshipDao()
        .observe(characterId)
        .collectAsState(initial = null)
    val messages by AppGraph.db.messageDao()
        .observeAll(characterId)
        .collectAsState(initial = emptyList<MessageEntity>())

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var selectedImage by remember { mutableStateOf("") }
    var showPlus by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<String?>(null) }
    var flashId by remember { mutableStateOf<Long?>(null) }
    var actionMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var quoteFrom by remember { mutableStateOf("") }
    var quoteText by remember { mutableStateOf("") }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var announced by remember { mutableStateOf(setOf<Long>()) }
    var announcedReady by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val accent = character?.accentColor?.let { Color(it) } ?: c.accent

    val pickImage = rememberMediaPicker(ImageStore.Kind.CHAT) { selectedImage = it }
    val takePhoto = rememberCameraCapture { selectedImage = it }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowTick = System.currentTimeMillis()
        }
    }

    // 到时间可见的消息。已读推断要用这一份，silent 的空 note 不能被过滤掉。
    val onTimeMessages = remember(messages, nowTick) {
        messages.filter { it.visibleAt == 0L || it.visibleAt <= nowTick }
    }
    val visible = remember(onTimeMessages) {
        onTimeMessages.filterNot { it.role == "note" && it.content.isBlank() }
    }

    // 已读推断：这条用户消息之后出现了她的回复或隐藏占位，就算已读
    val readIds = remember(onTimeMessages) {
        val read = mutableSetOf<Long>()
        onTimeMessages.forEachIndexed { index, message ->
            if (message.role == "user") {
                val later = onTimeMessages.subList(index + 1, onTimeMessages.size)
                if (later.any { it.role == "assistant" || it.role == "note" }) read.add(message.id)
            }
        }
        read
    }

    LaunchedEffect(visible.size) {
        if (!announcedReady) {
            if (visible.isNotEmpty()) {
                announcedReady = true
                announced = visible.map { it.id }.toSet()
            }
            return@LaunchedEffect
        }
        val newly = visible.filter {
            it.visibleAt > 0 && it.role == "assistant" && it.id !in announced
        }
        if (newly.isNotEmpty()) {
            announced = announced + newly.map { it.id }
            val current = character
            if (current != null && Notifier.strongAlertActive(current)) {
                Notifier.playShortTone(context)
            }
        }
    }

    LaunchedEffect(visible.size, sending) {
        if (visible.isNotEmpty() && ChatNav.pendingJump == null) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }

    LaunchedEffect(ChatNav.pendingJump, visible.size) {
        val target = ChatNav.pendingJump ?: return@LaunchedEffect
        val index = visible.indexOfFirst { it.id == target }
        if (index >= 0) {
            listState.scrollToItem(index)
            flashId = target
            ChatNav.pendingJump = null
            delay(1800)
            if (flashId == target) flashId = null
        }
    }

    fun deliver(text: String, image: String, from: String, quoted: String) {
        scope.launch {
            sending = true
            try {
                ChatEngine.send(characterId, text, image, from, quoted)
            } finally {
                sending = false
            }
        }
    }

    fun send() {
        if (sending) return
        val text = input.trim()
        val image = selectedImage
        if (text.isEmpty() && image.isEmpty()) return
        val from = quoteFrom
        val quoted = quoteText
        input = ""
        selectedImage = ""
        quoteFrom = ""
        quoteText = ""
        showPlus = false
        deliver(text, image, from, quoted)
    }

    Box(Modifier.fillMaxSize()) {
        ChatBackdrop(character, accent, c)

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
        ) {
            WeChatTopBar(
                title = character?.name ?: "…",
                subtitle = if (sending) "正在输入…" else null,
                onBack = { nav.popBackStack() },
                actions = {
                    BarAction(Icons.Filled.MoreHoriz) {
                        nav.navigate("chat/$characterId/menu")
                    }
                }
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(visible, key = { _, message -> message.id }) { index, message ->
                    val previous = visible.getOrNull(index - 1)
                    Column {
                        if (previous == null || dayKey(previous.createdAt) != dayKey(message.createdAt)) {
                            DayHeader(dayLabel(message.createdAt))
                        }
                        MessageRow(
                            message = message,
                            character = character,
                            isRead = message.id in readIds,
                            highlighted = flashId == message.id,
                            accent = accent,
                            onImageClick = { previewImage = it },
                            onLongPress = { actionMessage = it }
                        )
                    }
                }
            }

            if (showPlus) {
                PlusPanel(
                    onAlbum = {
                        showPlus = false
                        pickImage()
                    },
                    onCamera = {
                        showPlus = false
                        takePhoto()
                    }
                )
            }

            if (quoteText.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.bar.copy(alpha = 0.94f))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "引用 $quoteFrom：$quoteText",
                        fontSize = 12.sp,
                        color = c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { quoteFrom = ""; quoteText = "" }) { Text("取消") }
                }
            }

            if (selectedImage.isNotBlank()) {
                val thumb = remember(selectedImage) { ImageStore.bitmap(context, selectedImage) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.bar.copy(alpha = 0.94f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (thumb != null) {
                        Image(
                            thumb.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("图片待发送", fontSize = 12.sp, color = c.muted)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { selectedImage = "" }) { Text("移除") }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.bar.copy(alpha = 0.94f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(c.inputBar)
                        .border(1.dp, c.highlight, CircleShape)
                        .clickable { showPlus = !showPlus },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = c.text, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.inputBar)
                        .border(1.dp, c.highlight, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    if (input.isEmpty()) {
                        Text("发消息…", fontSize = 15.sp, color = c.muted)
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = TextStyle(color = c.text, fontSize = 15.sp),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (input.isNotBlank() || selectedImage.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.accent)
                            .clickable(enabled = !sending) { send() }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (sending) "…" else "发送",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    val shown = previewImage
    if (shown != null) {
        val bitmap = remember(shown) { ImageStore.bitmap(context, shown) }
        AlertDialog(
            onDismissRequest = { previewImage = null },
            confirmButton = { TextButton(onClick = { previewImage = null }) { Text("关闭") } },
            title = { Text("图片") },
            text = {
                if (bitmap != null) {
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        )
    }

    val target = actionMessage
    if (target != null) {
        val from = if (target.role == "user") "我" else character?.name ?: "她"
        val body = target.content.ifBlank { "（图片）" }
        MessageActions(
            excerpt = body,
            onDismiss = { actionMessage = null },
            onCopy = {
                copyMessage(context, body)
                actionMessage = null
            },
            onShare = {
                shareMessage(context, body)
                actionMessage = null
            },
            onQuote = {
                quoteFrom = from
                quoteText = body.take(200)
                actionMessage = null
            }
        )
    }
}

/** 长按消息后的操作：复制、转发到别的应用、引用回复。 */
@Composable
private fun MessageActions(
    excerpt: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onQuote: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息") },
        text = {
            Column {
                Text(excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCopy) { Text("复制") }
                    TextButton(onClick = onShare) { Text("转发") }
                    TextButton(onClick = onQuote) { Text("引用") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun copyMessage(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("AI Person", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

private fun shareMessage(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "转发到"))
}

/** 聊天背景：默认雾面渐变；有角色背景图则用图 + 轻微模糊。 */
@Composable
private fun ChatBackdrop(character: CharacterEntity?, accent: Color, c: WeChatColors) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (dark) {
                        listOf(Color(0xFF101114), Color(0xFF191A1E), Color(0xFF111214))
                    } else {
                        listOf(Color(0xFFF2F3F5), Color(0xFFE7EAED), Color(0xFFF1F2F4))
                    }
                )
            )
    ) {
        val bitmap = remember(character?.backgroundPath) {
            character?.backgroundPath?.takeIf { it.isNotBlank() }
                ?.let { ImageStore.bitmap(context, it) }
        }
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (UiPrefs.glassSupported) Modifier.blur(6.dp) else Modifier),
                contentScale = ContentScale.Crop
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (dark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f))
            )
        } else {
            Box(
                Modifier
                    .size(240.dp)
                    .offset(x = (-70).dp, y = 60.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (dark) 0.10f else 0.08f))
            )
            Box(
                Modifier
                    .size(190.dp)
                    .offset(x = 230.dp, y = 420.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (dark) 0.08f else 0.06f))
            )
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    val c = weChatColors()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label,
            fontSize = 10.sp,
            color = c.muted,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(c.text.copy(alpha = 0.05f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun MessageRow(
    message: MessageEntity,
    character: CharacterEntity?,
    isRead: Boolean,
    highlighted: Boolean,
    accent: Color,
    onImageClick: (String) -> Unit,
    onLongPress: (MessageEntity) -> Unit
) {
    val c = weChatColors()
    val context = LocalContext.current

    when (message.role) {
        "system" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message.content,
                fontSize = 11.sp,
                color = if (message.content.contains("连接失败")) Color(0xFFE64340) else c.muted,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        "note" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message.content,
                fontSize = 10.sp,
                color = c.muted.copy(alpha = 0.8f),
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        "user" -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            Column(horizontalAlignment = Alignment.End) {
                val image = remember(message.imagePath) {
                    message.imagePath.takeIf { it.isNotBlank() }?.let {
                        ImageStore.bitmap(context, it)
                    }
                }
                if (image != null) {
                    Image(
                        image.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(170.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageClick(message.imagePath) },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (message.content.isNotBlank()) {
                    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                    Column(
                        Modifier
                            .widthIn(max = 270.dp)
                            .clip(shape)
                            .background(c.bubbleSelf.copy(alpha = 0.78f))
                            .then(if (highlighted) Modifier.border(2.dp, accent, shape) else Modifier)
                            .pointerInput(message.id) {
                                detectTapGestures(onLongPress = { onLongPress(message) })
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        if (message.quoteText.isNotBlank()) {
                            QuotedLine(message.quoteFrom, message.quoteText, c)
                        }
                        Text(
                            message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.selfText
                        )
                    }
                }
                Row(
                    Modifier.padding(top = 3.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        timeText(message.visibleAt.takeIf { it > 0 } ?: message.createdAt),
                        fontSize = 9.sp,
                        color = c.muted
                    )
                    if (isRead) {
                        Spacer(Modifier.width(4.dp))
                        Text("已读", fontSize = 9.sp, color = c.muted)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            UserAvatar(40.dp)
        }

        else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            CharacterAvatar(character, 40.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.widthIn(max = 276.dp)) {
                Text(
                    character?.name ?: "她",
                    fontSize = 10.sp,
                    color = c.muted,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                )
                if (message.proactive) {
                    Text(
                        "她主动发来的",
                        fontSize = 9.sp,
                        color = c.muted,
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                    )
                }
                val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                Column(
                    Modifier
                        .clip(shape)
                        .background(c.bubbleOther.copy(alpha = 0.78f))
                        .then(if (highlighted) Modifier.border(2.dp, accent, shape) else Modifier)
                        .pointerInput(message.id) {
                            detectTapGestures(onLongPress = { onLongPress(message) })
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    if (message.quoteText.isNotBlank()) {
                        QuotedLine(message.quoteFrom, message.quoteText, c)
                    }
                    Text(
                        message.content.ifBlank { "……" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text
                    )
                }
                Row(Modifier.padding(top = 3.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeText(message.visibleAt.takeIf { it > 0 } ?: message.createdAt),
                        fontSize = 9.sp,
                        color = c.muted
                    )
                }
            }
        }
    }
}

@Composable
private fun QuotedLine(from: String, text: String, c: WeChatColors) {
    val who = if (from.isBlank()) "" else "$from："
    Text(
        who + text,
        fontSize = 11.sp,
        color = c.muted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(c.text.copy(alpha = 0.07f))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun PlusPanel(onAlbum: () -> Unit, onCamera: () -> Unit) {
    val c = weChatColors()
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.bar.copy(alpha = 0.97f))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        PlusTile("相片", onAlbum)
        PlusTile("拍照", onCamera)
    }
}

@Composable
private fun PlusTile(label: String, onClick: () -> Unit) {
    val c = weChatColors()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.inputBar)
                .border(1.dp, c.highlight, RoundedCornerShape(14.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(label.take(1), fontSize = 20.sp, color = c.text)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = c.muted)
    }
}

// ---------------------------------------------------------------- 会话内搜索

@Composable
fun InChatSearchScreen(nav: NavHostController, characterId: String) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<MessageEntity>()) }
    val c = weChatColors()

    LaunchedEffect(query) {
        delay(200)
        val q = query.trim()
        results = if (q.isBlank()) {
            emptyList()
        } else {
            AppGraph.db.messageDao().search(characterId, q)
                .filter { it.role == "user" || it.role == "assistant" }
        }
    }

    fun jump(message: MessageEntity) {
        ChatNav.pendingJump = message.id
        val popped = nav.popBackStack("chat/{id}", inclusive = false)
        if (!popped) nav.navigate("chat/$characterId") { launchSingleTop = true }
    }

    WeChatScreenBackground(accent = c.accent) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
        ) {
            WeChatTopBar(title = "搜索聊天记录", onBack = { nav.popBackStack() })
            Column(Modifier.weight(1f).fillMaxWidth().padding(14.dp)) {
                SearchField(value = query, onChange = { query = it }, placeholder = "输入关键词")
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { it.id }) { message ->
                        GlassCard(
                            Modifier.fillMaxWidth(),
                            onClick = { jump(message) }
                        ) {
                            Text(
                                "${dayLabel(message.createdAt)} ${timeText(message.createdAt)}",
                                fontSize = 10.sp,
                                color = c.muted
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(message.content, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                        }
                    }
                    if (query.isNotBlank() && results.isEmpty()) {
                        item {
                            Text(
                                "没有找到",
                                fontSize = 13.sp,
                                color = c.muted,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 聊天信息（⋯ 菜单）

@Composable
fun ChatMenuScreen(nav: NavHostController, characterId: String) {
    val context = LocalContext.current
    val c = weChatColors()
    val character by AppGraph.db.characterDao()
        .observe(characterId)
        .collectAsState(initial = null)
    val relationship by AppGraph.db.relationshipDao()
        .observe(characterId)
        .collectAsState(initial = null)
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var dislikeCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val setStrongAlert: (Boolean) -> Unit = { on ->
        scope.launch {
            AppGraph.db.characterDao().get(characterId)?.let {
                val at = if (on) System.currentTimeMillis() + 24 * 3_600_000L else 0L
                AppGraph.db.characterDao().upsert(it.copy(strongAlertAt = at))
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && notificationsEnabled(context)) {
            setStrongAlert(true)
        } else {
            setStrongAlert(false)
            Toast.makeText(context, "通知权限未开启，强提醒保持关闭", Toast.LENGTH_SHORT).show()
        }
    }
    // 在系统设置里关掉通知后，回到 App 时自动把强提醒关掉
    OnResume {
        if ((character?.strongAlertAt ?: 0L) > System.currentTimeMillis() &&
            !notificationsEnabled(context)
        ) {
            setStrongAlert(false)
        }
    }

    val pickBackground = rememberMediaPicker(ImageStore.Kind.BACKGROUND) { name ->
        scope.launch {
            AppGraph.db.characterDao().get(characterId)?.let {
                AppGraph.db.characterDao().upsert(it.copy(backgroundPath = name))
            }
        }
    }

    LaunchedEffect(characterId) {
        dislikeCount = AppGraph.db.memoryDao().countTag(characterId, "dislike")
    }

    WeChatScreenBackground(accent = character?.accentColor?.let { Color(it) } ?: c.accent) {
        Column(Modifier.fillMaxSize()) {
            WeChatTopBar(title = "聊天信息", onBack = { nav.popBackStack() })
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(14.dp)
            ) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CharacterAvatar(character, 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                character?.name ?: "…",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.text
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                relationship?.let {
                                    if (UiPrefs.showGrowth) {
                                        "${intimacyStage(it.intimacy)} · 亲密度 ${it.intimacy}/100"
                                    } else {
                                        intimacyStage(it.intimacy)
                                    }
                                } ?: "陌生",
                                fontSize = 12.sp,
                                color = c.muted
                            )
                            Spacer(Modifier.height(8.dp))
                            IntimacyBar(relationship?.intimacy ?: 0, c.accent)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "底线 ${character?.dislikes?.lines()?.count { it.isNotBlank() } ?: 0} 条 · 讨厌的记忆 $dislikeCount 条",
                        fontSize = 11.sp,
                        color = c.muted
                    )
                }

                Spacer(Modifier.height(14.dp))
                MenuGroup {
                    MenuRow("搜索聊天记录") { nav.navigate("chat/$characterId/search") }
                    MenuDivider()
                    MenuRow("她的设定") { nav.navigate("character/$characterId/settings") }
                    MenuDivider()
                    MenuRow("记忆库") { nav.navigate("memory/$characterId") }
                }

                Spacer(Modifier.height(14.dp))
                MenuGroup {
                    MenuSwitchRow(
                        title = "置顶聊天",
                        subtitle = "排在聊天列表最前面",
                        checked = character?.pinned == true
                    ) { on ->
                        scope.launch {
                            AppGraph.db.characterDao().get(characterId)?.let {
                                AppGraph.db.characterDao().upsert(it.copy(pinned = on))
                            }
                        }
                    }
                    MenuDivider()
                    MenuSwitchRow(
                        title = "强提醒",
                        subtitle = if ((character?.strongAlertAt ?: 0L) > System.currentTimeMillis()) {
                            "她发消息会响一声短铃 · ${remainingText(character?.strongAlertAt ?: 0L)}"
                        } else {
                            "开启后维持 24 小时"
                        },
                        checked = (character?.strongAlertAt ?: 0L) > System.currentTimeMillis()
                    ) { on ->
                        if (!on) {
                            setStrongAlert(false)
                        } else if (notificationsEnabled(context)) {
                            setStrongAlert(true)
                        } else {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    MenuDivider()
                    MenuRow(
                        title = "聊天背景",
                        value = if (character?.backgroundPath.isNullOrBlank()) "默认" else "已设置"
                    ) { pickBackground() }
                    if (!character?.backgroundPath.isNullOrBlank()) {
                        MenuDivider()
                        MenuRow(title = "清除背景", value = "", danger = false) {
                            scope.launch {
                                AppGraph.db.characterDao().get(characterId)?.let {
                                    AppGraph.db.characterDao().upsert(it.copy(backgroundPath = ""))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                MenuGroup {
                    MenuRow(title = "清空聊天记录", danger = true) {
                        confirm = ConfirmRequest(
                            title = "清空聊天记录",
                            body = "会删除这段聊天的全部消息、聊天图片，以及还没到达的延迟回复；记忆、亲密度和人格都会保留。",
                            secondBody = "聊天记录和图片无法恢复，确定清空吗？",
                            onConfirm = {
                                scope.launch {
                                    val images = AppGraph.db.messageDao().all(characterId)
                                        .map { it.imagePath }
                                        .filter { it.isNotBlank() }
                                        .distinct()
                                    AppGraph.db.messageDao().deleteAll(characterId)
                                    withContext(Dispatchers.IO) {
                                        images.forEach { ImageStore.delete(context, it) }
                                    }
                                    WorkManager.getInstance(context)
                                        .cancelAllWorkByTag("deliver_$characterId")
                                    Notifier.cancel(context, characterId)
                                    BehaviorSettings.setLastEvalId(characterId, 0L)
                                    ChatNav.pendingJump = null
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "清空只影响这段聊天。她记得的事、对你的好感都还在。",
                    fontSize = 11.sp,
                    color = c.muted,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    TwoStepConfirm(confirm, onDismiss = { confirm = null })
}

@Composable
private fun IntimacyBar(value: Int, accent: Color) {
    val c = weChatColors()
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(c.text.copy(alpha = 0.08f))
    ) {
        Box(
            Modifier
                .fillMaxWidth((value.coerceIn(0, 100) / 100f))
                .height(6.dp)
                .clip(shape)
                .background(accent)
        )
    }
}

@Composable
private fun MenuGroup(content: @Composable ColumnScope.() -> Unit) {
    val c = weChatColors()
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.bar.copy(alpha = if (UiPrefs.glassEnabled) 0.78f else 0.96f))
            .border(1.dp, c.highlight, shape)
    ) {
        content()
    }
}

@Composable
private fun MenuDivider() {
    val c = weChatColors()
    HorizontalDivider(
        thickness = 0.5.dp,
        color = c.divider,
        modifier = Modifier.padding(start = 16.dp)
    )
}

@Composable
private fun MenuRow(
    title: String,
    value: String = "",
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val c = weChatColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 15.sp,
            color = if (danger) Color(0xFFE64340) else c.text
        )
        Spacer(Modifier.weight(1f))
        if (value.isNotBlank()) {
            Text(value, fontSize = 12.sp, color = c.muted)
            Spacer(Modifier.width(6.dp))
        }
        Text("›", fontSize = 16.sp, color = c.muted)
    }
}

@Composable
private fun MenuSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val c = weChatColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = c.text)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 11.sp, color = c.muted)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------------------------------------------------------------- 角色设定

@Composable
fun CharacterSettingsScreen(nav: NavHostController, characterId: String) {
    val context = LocalContext.current
    val c = weChatColors()
    val character by AppGraph.db.characterDao()
        .observe(characterId)
        .collectAsState(initial = null)
    val relationship by AppGraph.db.relationshipDao()
        .observe(characterId)
        .collectAsState(initial = null)
    var personality by remember { mutableStateOf<PersonalityEntity?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var dislikesDraft by remember { mutableStateOf("") }
    var identityDraft by remember { mutableStateOf("") }
    var backgroundDraft by remember { mutableStateOf("") }
    var catchphraseDraft by remember { mutableStateOf("") }
    var routineDraft by remember { mutableStateOf("") }
    var routineDetailDraft by remember { mutableStateOf("") }
    var fillingLife by remember { mutableStateOf(false) }
    var basicsLoaded by remember { mutableStateOf(false) }
    var memeDraft by remember { mutableFloatStateOf(50f) }
    var intimacyDraft by remember { mutableFloatStateOf(0f) }
    var prefWarmth by remember { mutableFloatStateOf(50f) }
    var prefLiveliness by remember { mutableFloatStateOf(50f) }
    var prefInitiative by remember { mutableFloatStateOf(40f) }
    var prefIndependence by remember { mutableFloatStateOf(50f) }
    var prefClinginess by remember { mutableFloatStateOf(30f) }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var vision by remember { mutableStateOf(true) }
    var dislikeMemories by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(characterId) {
        personality = AppGraph.db.personalityDao().get(characterId)
        personality?.let {
            prefWarmth = it.prefWarmth.toFloat()
            prefLiveliness = it.prefLiveliness.toFloat()
            prefInitiative = it.prefInitiative.toFloat()
            prefIndependence = it.prefIndependence.toFloat()
            prefClinginess = it.prefClinginess.toFloat()
        }
        intimacyDraft = (relationship?.intimacy ?: 0).toFloat()
        dislikeMemories = AppGraph.db.memoryDao().countTag(characterId, "dislike")
        AppGraph.db.characterDao().get(characterId)?.let { current ->
            dislikesDraft = current.dislikes
            identityDraft = current.identity
            backgroundDraft = current.background
            catchphraseDraft = current.catchphrase
            routineDraft = current.routine
            routineDetailDraft = current.routineDetail
            memeDraft = current.memeLevel.toFloat()
            baseUrl = current.apiBaseUrl
            model = current.apiModel
            vision = current.vision
            apiKey = AppGraph.api.keyFor(characterId)
        }
        basicsLoaded = true
    }
    LaunchedEffect(relationship?.intimacy) {
        intimacyDraft = (relationship?.intimacy ?: 0).toFloat()
    }
    // 基础设定改完就自动保存：不再依赖那个按钮，离开页面也不会丢
    LaunchedEffect(
        basicsLoaded,
        identityDraft,
        backgroundDraft,
        catchphraseDraft,
        routineDraft,
        routineDetailDraft,
        memeDraft
    ) {
        if (!basicsLoaded) return@LaunchedEffect
        delay(700)
        AppGraph.db.characterDao().get(characterId)?.let { saved ->
            AppGraph.db.characterDao().upsert(
                saved.copy(
                    identity = identityDraft.trim(),
                    background = backgroundDraft.trim(),
                    catchphrase = catchphraseDraft.trim(),
                    memeLevel = memeDraft.roundToInt(),
                    routine = routineDraft,
                    routineDetail = routineDetailDraft.trim()
                )
            )
        }
    }

    // 当前实际人格（会随成长变化），用来展示“底色 + 成长 + 关系”
    val livePersonality by AppGraph.db.personalityDao()
        .observe(characterId)
        .collectAsState(initial = null)
    val personaHistory by AppGraph.db.personaHistoryDao()
        .observe(characterId)
        .collectAsState(initial = emptyList<PersonaChangeEntity>())
    var retemperOpen by remember { mutableStateOf(false) }
    var retemperPick by remember { mutableStateOf(Temperaments.ALL.first()) }

    val accent = character?.accentColor?.let { Color(it) } ?: c.accent
    val pickAvatar = rememberMediaPicker(ImageStore.Kind.AVATAR) { name ->
        scope.launch {
            AppGraph.db.characterDao().get(characterId)?.let {
                AppGraph.db.characterDao().upsert(it.copy(avatarPath = name))
            }
        }
    }
    val pickBackground = rememberMediaPicker(ImageStore.Kind.BACKGROUND) { name ->
        scope.launch {
            AppGraph.db.characterDao().get(characterId)?.let {
                AppGraph.db.characterDao().upsert(it.copy(backgroundPath = name))
            }
        }
    }
    val setStrongAlert: (Boolean) -> Unit = { on ->
        scope.launch {
            AppGraph.db.characterDao().get(characterId)?.let {
                val at = if (on) System.currentTimeMillis() + 24 * 3_600_000L else 0L
                AppGraph.db.characterDao().upsert(it.copy(strongAlertAt = at))
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && notificationsEnabled(context)) {
            setStrongAlert(true)
        } else {
            setStrongAlert(false)
            Toast.makeText(context, "通知权限未开启，强提醒保持关闭", Toast.LENGTH_SHORT).show()
        }
    }
    OnResume {
        if ((character?.strongAlertAt ?: 0L) > System.currentTimeMillis() &&
            !notificationsEnabled(context)
        ) {
            setStrongAlert(false)
        }
    }

    WeChatScreenBackground(accent = accent) {
        Column(Modifier.fillMaxSize()) {
            WeChatTopBar(
                title = "${character?.name ?: ""} 的设定",
                onBack = { nav.popBackStack() }
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(14.dp)
            ) {
                GlassCard(Modifier.fillMaxWidth(), accent = accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CharacterAvatar(character, 64.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("头像", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "列表、聊天头部和通知都会用这张图",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { pickAvatar() }) { Text("更换") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("聊天背景", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (character?.backgroundPath.isNullOrBlank()) "默认雾面背景"
                                else "已设置自定义背景",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { pickBackground() }) { Text("选择") }
                        if (!character?.backgroundPath.isNullOrBlank()) {
                            TextButton(onClick = {
                                scope.launch {
                                    AppGraph.db.characterDao().get(characterId)?.let {
                                        AppGraph.db.characterDao().upsert(it.copy(backgroundPath = ""))
                                    }
                                }
                            }) { Text("清除") }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth(), accent = accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("置顶聊天", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "排在角色列表最前面",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = character?.pinned == true,
                            onCheckedChange = { on ->
                                scope.launch {
                                    AppGraph.db.characterDao().get(characterId)?.let {
                                        AppGraph.db.characterDao().upsert(it.copy(pinned = on))
                                    }
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("强提醒", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if ((character?.strongAlertAt ?: 0L) > System.currentTimeMillis()) {
                                    "她发消息会响一声短铃 · ${remainingText(character?.strongAlertAt ?: 0L)}"
                                } else {
                                    "开启后维持 24 小时，到期自动关闭"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = (character?.strongAlertAt ?: 0L) > System.currentTimeMillis(),
                            onCheckedChange = { on ->
                                when {
                                    !on -> setStrongAlert(false)
                                    notificationsEnabled(context) -> setStrongAlert(true)
                                    else -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth(), accent = accent) {
                    Text("她的基础设定", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    val fillLifeNow: () -> Unit = {
                        val current = character
                        if (!fillingLife && current != null) {
                            fillingLife = true
                            scope.launch {
                                try {
                                    val raw = withContext(Dispatchers.IO) {
                                        Llm.complete(
                                            current,
                                            PromptBuilder.fillLife(
                                                current.name,
                                                current.persona,
                                                identityDraft,
                                                backgroundDraft,
                                                routineDetailDraft
                                            ),
                                            emptyList()
                                        )
                                    }
                                    val start = raw.indexOf('{')
                                    val end = raw.lastIndexOf('}')
                                    if (start < 0 || end <= start) {
                                        throw IllegalStateException("返回格式不对")
                                    }
                                    val json = JSONObject(raw.substring(start, end + 1))
                                    // 用户写了什么就以用户为准，模型只补没写的和细节
                                    val identity = identityDraft.trim()
                                        .ifBlank { json.optString("identity").trim() }.take(120)
                                    val background = backgroundDraft.trim()
                                        .ifBlank { json.optString("background").trim() }.take(200)
                                    val detail = routineDetailDraft.trim()
                                        .ifBlank { json.optString("routineDetail").trim() }.take(160)
                                    val r = json.optString("routine")
                                    val routine = routineDraft.ifBlank {
                                        if (Routine.templates.any { it.first == r }) r else ""
                                    }
                                    identityDraft = identity
                                    backgroundDraft = background
                                    routineDetailDraft = detail
                                    routineDraft = routine
                                    AppGraph.db.characterDao().get(characterId)?.let { saved ->
                                        AppGraph.db.characterDao().upsert(
                                            saved.copy(
                                                identity = identity.trim(),
                                                background = background.trim(),
                                                routine = routine,
                                                routineDetail = detail.trim()
                                            )
                                        )
                                    }
                                    Toast.makeText(
                                        context, "补好了，已保存；不满意可以重新生成", Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context, e.message ?: "补全失败", Toast.LENGTH_SHORT
                                    ).show()
                                } finally {
                                    fillingLife = false
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = !fillingLife && character != null,
                            onClick = fillLifeNow
                        ) {
                            Text(if (fillingLife) "正在补全…" else "帮她补全生活")
                        }
                        if (identityDraft.isNotBlank() || backgroundDraft.isNotBlank() ||
                            routineDetailDraft.isNotBlank()
                        ) {
                            Spacer(Modifier.width(6.dp))
                            TextButton(
                                enabled = !fillingLife && character != null,
                                onClick = fillLifeNow
                            ) { Text("重新生成") }
                        }
                    }
                    Text(
                        "会读你上面已经写的内容，只在那个方向上补；补完会自动保存。",
                        fontSize = 11.sp,
                        color = c.muted
                    )
                    Spacer(Modifier.height(2.dp))
                    OutlinedTextField(
                        value = identityDraft,
                        onValueChange = { identityDraft = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("她是谁、在做什么") },
                        placeholder = { Text("比如：插画师，一个人住，养了只猫") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = backgroundDraft,
                        onValueChange = { backgroundDraft = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("她的来历") },
                        placeholder = { Text("比如：小城市长大，大学才来这边，换过两次工作") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = catchphraseDraft,
                        onValueChange = { catchphraseDraft = it.take(60) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("说话习惯 / 口头禅") },
                        placeholder = { Text("比如：爱说「行吧」；生气时不叫人全名") }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("生活作息", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Routine.templates.forEach { (id, label) ->
                            FilterChip(
                                selected = routineDraft == id,
                                onClick = {
                                    routineDraft = if (routineDraft == id) "" else id
                                },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "作息会真的影响她：上班、上课、午休、睡觉，回得快慢和句子长短都不一样；" +
                            "熬夜后的第二天会困。",
                        fontSize = 11.sp,
                        color = c.muted
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = routineDetailDraft,
                        onValueChange = { routineDetailDraft = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("她一天怎么过（可自己写）") },
                        placeholder = { Text("比如：周三到周五上午有课，下午在图书馆，晚上回宿舍才看手机") }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "模板只决定大概的作息，这里写她的真实安排，她聊起一天时会照这个说。",
                        fontSize = 11.sp,
                        color = c.muted
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("玩梗程度", fontSize = 12.sp, modifier = Modifier.width(72.dp))
                        Slider(
                            value = memeDraft,
                            onValueChange = { memeDraft = it },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f)
                        )
                        Text(memeDraft.roundToInt().toString(), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("她讨厌 / 不能接受的事", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dislikesDraft,
                        onValueChange = { dislikesDraft = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("每行一条，比如：讨厌被命令、讨厌敷衍、讨厌说谎") }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "底线硬性生效：踩到会掉亲密度，可以冷淡、回怼或不回。",
                        fontSize = 11.sp,
                        color = c.muted
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "底线 ${dislikesDraft.lines().count { it.isNotBlank() }} 条 / 讨厌的记忆 $dislikeMemories 条",
                        fontSize = 12.sp,
                        color = c.text
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "基础设定会自动保存；底线改动需要确认。",
                            fontSize = 11.sp,
                            color = c.muted,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                confirm = ConfirmRequest(
                                    title = "修改她的底线",
                                    body = "这会影响她以后怎么反应，包括什么时候会不高兴、不回你。",
                                    secondBody = "确定把这些写进她的性格里？",
                                    onConfirm = {
                                        scope.launch {
                                            AppGraph.db.characterDao().get(characterId)?.let {
                                                AppGraph.db.characterDao().upsert(
                                                    it.copy(dislikes = dislikesDraft.trim())
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        ) { Text("保存底线") }
                    }
                }

                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth(), accent = accent) {
                    Text("她的接口", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "这个角色独立使用这套接口；留空则用设置里的默认接口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("这个接口支持识图", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "关掉后只发文字，避免不支持图片的接口报错",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = vision, onCheckedChange = { vision = it })
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                AppGraph.db.characterDao().get(characterId)?.let {
                                    AppGraph.db.characterDao().upsert(
                                        it.copy(
                                            apiBaseUrl = baseUrl.trim(),
                                            apiModel = model.trim(),
                                            vision = vision
                                        )
                                    )
                                }
                                AppGraph.api.setKeyFor(characterId, apiKey)
                                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("保存接口") }
                }

                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth(), accent = accent) {
                    val live = livePersonality
                    val intimacyNow = relationship?.intimacy ?: 0
                    val gains = live?.let { relationGains(it, intimacyNow) }
                    Text(
                        "她的底色：${live?.temperament ?: "自定义"}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "底色是她最初的样子。之后的每一句话、每一次被认真回应，都会在这个底色上留一点痕迹。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (character?.nickname?.isNotBlank() == true) {
                        Spacer(Modifier.height(6.dp))
                        Text("她现在叫他「${character?.nickname}」", fontSize = 12.sp, color = c.text)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("现在她实际的样子", fontSize = 12.sp, color = c.text)
                    TraitEcho(
                        "温柔",
                        live?.baseWarmth ?: prefWarmth.roundToInt(),
                        live?.warmth ?: prefWarmth.roundToInt(),
                        gains?.warmth ?: 0
                    )
                    TraitEcho(
                        "活泼",
                        live?.baseLiveliness ?: prefLiveliness.roundToInt(),
                        live?.liveliness ?: prefLiveliness.roundToInt(),
                        gains = 0
                    )
                    TraitEcho(
                        "主动",
                        live?.baseInitiative ?: prefInitiative.roundToInt(),
                        live?.initiative ?: prefInitiative.roundToInt(),
                        gains?.initiative ?: 0
                    )
                    TraitEcho(
                        "独立",
                        live?.baseIndependence ?: prefIndependence.roundToInt(),
                        live?.independence ?: prefIndependence.roundToInt(),
                        gains = 0
                    )
                    TraitEcho(
                        "粘人",
                        live?.baseClinginess ?: prefClinginess.roundToInt(),
                        live?.clinginess ?: prefClinginess.roundToInt(),
                        gains?.clinginess ?: 0
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("这些年", style = MaterialTheme.typography.titleSmall)
                    val narrative = (live?.personaChanges ?: "")
                        .split('\n').map { it.trim() }.filter { it.isNotBlank() }
                    // 数值动了就说明她确实有点不一样了，文案不能再说"没有变化"
                    val drifted = live?.let { p ->
                        p.warmth != p.baseWarmth || p.liveliness != p.baseLiveliness ||
                            p.initiative != p.baseInitiative ||
                            p.independence != p.baseIndependence ||
                            p.clinginess != p.baseClinginess
                    } == true
                    if (narrative.isEmpty() && personaHistory.isEmpty()) {
                        Text(
                            if (drifted) "她比最初有一点点不一样了，只是还没到能说出口的程度。"
                            else "还没有明显的变化。变化很慢，通常要一起过一段时间才看得出来。",
                            fontSize = 12.sp,
                            color = c.muted
                        )
                    } else {
                        narrative.forEach { line ->
                            Text("· $line", fontSize = 12.sp, color = c.text)
                        }
                        personaHistory.take(6).forEach { item ->
                            Text(
                                "· ${dayLabel(item.createdAt)} ${item.change}" +
                                    if (item.reason.isNotBlank()) "（${item.reason}）" else "",
                                fontSize = 12.sp,
                                color = c.muted
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            retemperPick = live?.let { Temperaments.byName(it.temperament) }
                                ?: Temperaments.ALL.first()
                            retemperOpen = true
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("重新选择底色") }
                }

                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth(), accent = accent) {
                    Text("你希望她往哪边变（软引导）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "滑杆只影响她未来的变化方向，不会直接改她现在的样子；她也不会完全照着走。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    TraitSlider("温柔", prefWarmth) { prefWarmth = it }
                    TraitSlider("活泼", prefLiveliness) { prefLiveliness = it }
                    TraitSlider("主动", prefInitiative) { prefInitiative = it }
                    TraitSlider("独立", prefIndependence) { prefIndependence = it }
                    TraitSlider("粘人", prefClinginess) { prefClinginess = it }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            confirm = ConfirmRequest(
                                title = "修改人格偏好",
                                body = "这会影响她未来的演化方向。",
                                secondBody = "确定要改变她的成长方向吗？",
                                onConfirm = {
                                    scope.launch {
                                        val current = AppGraph.db.personalityDao().get(characterId)
                                            ?: PersonalityEntity(characterId = characterId)
                                        AppGraph.db.personalityDao().upsert(
                                            current.copy(
                                                prefWarmth = prefWarmth.toInt(),
                                                prefLiveliness = prefLiveliness.toInt(),
                                                prefInitiative = prefInitiative.toInt(),
                                                prefIndependence = prefIndependence.toInt(),
                                                prefClinginess = prefClinginess.toInt()
                                            )
                                        )
                                    }
                                }
                            )
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("保存偏好") }
                }

                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth(), accent = accent) {
                    Text("亲密度", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "当前 ${intimacyDraft.roundToInt()}/100 · ${intimacyStage(intimacyDraft.roundToInt())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = intimacyDraft,
                        onValueChange = { intimacyDraft = it },
                        valueRange = 0f..100f,
                        steps = 99
                    )
                    Button(
                        onClick = {
                            confirm = ConfirmRequest(
                                title = "直接修改亲密度",
                                body = "这是绕过她自己的成长，直接改关系状态。",
                                secondBody = "确定把亲密度改成 ${intimacyDraft.roundToInt()} 吗？",
                                onConfirm = {
                                    scope.launch {
                                        val current = AppGraph.db.relationshipDao().get(characterId)
                                            ?: RelationshipEntity(characterId = characterId)
                                        val value = intimacyDraft.roundToInt()
                                        AppGraph.db.relationshipDao().upsert(
                                            current.copy(intimacy = value)
                                        )
                                        BehaviorSettings.setIntimacyRaw(characterId, value)
                                    }
                                }
                            )
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("修改亲密度") }
                }

                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth(), accent = accent) {
                    Text("删除这个角色", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "会一并删除她的聊天、记忆、人格、图片和接口配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            confirm = ConfirmRequest(
                                title = "删除角色",
                                body = "她的所有记录都会消失，而且无法恢复。",
                                secondBody = "最后一次确认：要删除「${character?.name ?: ""}」吗？",
                                onConfirm = {
                                    scope.launch {
                                        val images = AppGraph.db.messageDao().all(characterId)
                                            .map { it.imagePath }
                                        character?.let { ImageStore.deleteForCharacter(context, it, images) }
                                        AppGraph.db.messageDao().deleteAll(characterId)
                                        AppGraph.db.memoryDao().deleteAll(characterId)
                                        AppGraph.db.relationshipDao().delete(characterId)
                                        AppGraph.db.personalityDao().delete(characterId)
                                        AppGraph.db.personaHistoryDao().deleteAll(characterId)
                                        AppGraph.db.moodDao().delete(characterId)
                                        AppGraph.db.characterDao().delete(characterId)
                                        AppGraph.api.clearKeyFor(characterId)
                                        BehaviorSettings.resetIntimacyRaw(characterId)
                                        BehaviorSettings.resetIdlePenalty(characterId)
                                        BehaviorSettings.resetMoodHold(characterId)
                                        // 直接回到会话列表，避免停在已删除角色的聊天信息页
                                        if (!nav.popBackStack("characters", false)) {
                                            nav.navigate("characters")
                                        }
                                    }
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("删除") }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    if (retemperOpen) {
        AlertDialog(
            onDismissRequest = { retemperOpen = false },
            title = { Text("重新选择底色") },
            text = {
                Column {
                    Text(
                        "这会把她的底色换回原型值，现在的成长也一起归零。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Temperaments.ALL.forEach { option ->
                        val selected = retemperPick.name == option.name
                        TextButton(
                            onClick = { retemperPick = option },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                        ) {
                            Text(
                                if (selected) "✓ ${option.name}" else option.name,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    retemperOpen = false
                    val picked = retemperPick
                    confirm = ConfirmRequest(
                        title = "确定换回「${picked.name}」？",
                        body = "她现在的人格会被重置成这个底色，之后的成长重新开始。",
                        secondBody = "这些年变化过的痕迹会清空，确定吗？",
                        onConfirm = {
                            scope.launch {
                                val current = AppGraph.db.personalityDao().get(characterId)
                                    ?: PersonalityEntity(characterId = characterId)
                                AppGraph.db.personalityDao().upsert(
                                    current.copy(
                                        temperament = picked.name,
                                        baseWarmth = picked.warmth,
                                        baseLiveliness = picked.liveliness,
                                        baseInitiative = picked.initiative,
                                        baseIndependence = picked.independence,
                                        baseClinginess = picked.clinginess,
                                        warmth = picked.warmth,
                                        liveliness = picked.liveliness,
                                        initiative = picked.initiative,
                                        independence = picked.independence,
                                        clinginess = picked.clinginess,
                                        prefWarmth = picked.warmth,
                                        prefLiveliness = picked.liveliness,
                                        prefInitiative = picked.initiative,
                                        prefIndependence = picked.independence,
                                        prefClinginess = picked.clinginess,
                                        personaChanges = "",
                                        lastPersonaChangeAt = 0L
                                    )
                                )
                                AppGraph.db.personaHistoryDao().deleteAll(characterId)
                                BehaviorSettings.setDriftState(characterId, "")
                                prefWarmth = picked.warmth.toFloat()
                                prefLiveliness = picked.liveliness.toFloat()
                                prefInitiative = picked.initiative.toFloat()
                                prefIndependence = picked.independence.toFloat()
                                prefClinginess = picked.clinginess.toFloat()
                            }
                        }
                    )
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { retemperOpen = false }) { Text("取消") } }
        )
    }

    TwoStepConfirm(confirm, onDismiss = { confirm = null })
}

@Composable
private fun TraitSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..100f,
            steps = 9,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${value.roundToInt()}",
            modifier = Modifier.width(32.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TraitEcho(label: String, base: Int, grown: Int, gains: Int) {
    val c = weChatColors()
    val current = (grown + gains).coerceIn(0, 100)
    val growth = grown - base
    val detail = buildString {
        append("底色 $base")
        if (growth != 0) append(" · 成长 ${if (growth > 0) "+" else ""}$growth")
        if (gains > 0) append(" · 关系 +$gains")
    }
    Text(
        "$label $current（$detail）",
        fontSize = 12.sp,
        color = c.muted,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}

// ---------------------------------------------------------------- 我的

@Composable
fun MeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val c = weChatColors()
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(AppGraph.api.baseUrl) }
    var apiKey by remember { mutableStateOf(AppGraph.api.apiKey) }
    var model by remember { mutableStateOf(AppGraph.api.model) }
    var searchUrl by remember { mutableStateOf(AppGraph.api.searchUrl) }
    var searchKey by remember { mutableStateOf(AppGraph.api.searchKey) }
    var status by remember { mutableStateOf("") }

    // 主动消息依赖通知，权限被拒时开关必须保持关闭
    var proactiveOn by remember { mutableStateOf(BehaviorSettings.proactiveEnabled) }
    val setProactive: (Boolean) -> Unit = { on ->
        BehaviorSettings.updateProactiveEnabled(on)
        proactiveOn = on
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && notificationsEnabled(context)) {
            setProactive(true)
        } else {
            setProactive(false)
            Toast.makeText(context, "通知权限未开启，主动消息保持关闭", Toast.LENGTH_SHORT).show()
        }
    }
    // 系统设置里关掉通知后，回到 App 时把主动消息一起关掉
    OnResume {
        if (proactiveOn && !notificationsEnabled(context)) {
            setProactive(false)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = Backup.export(context, uri)
                status = if (result.isSuccess) "已导出备份" else "导出失败：${result.exceptionOrNull()?.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = Backup.import(context, uri)
                status = if (result.isSuccess) {
                    "已导入 ${result.getOrNull() ?: 0} 个角色，API Key 需要重新填写"
                } else {
                    "导入失败：${result.exceptionOrNull()?.message}"
                }
            }
        }
    }

    WeChatScreenBackground(accent = c.accent) {
        Column(Modifier.fillMaxSize()) {
            WeChatTopBar(title = "我的")
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(58.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("我", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "这台手机上的私人关系，都只属于你。",
                                fontSize = 12.sp,
                                color = c.muted
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("接口")
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("默认接口", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "新建角色时使用；每个角色可以在自己的设定页里覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key（本地加密保存）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("联网搜索（可选）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "她只在自己觉得需要查的时候才搜，日常聊天不会联网。留空则不启用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchUrl,
                        onValueChange = { searchUrl = it },
                        label = { Text("搜索接口（Tavily 格式）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchKey,
                        onValueChange = { searchKey = it },
                        label = { Text("搜索 Key（可留空）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            AppGraph.api.baseUrl = baseUrl
                            AppGraph.api.model = model
                            AppGraph.api.apiKey = apiKey
                            AppGraph.api.searchUrl = searchUrl
                            AppGraph.api.searchKey = searchKey
                            status = "默认接口已保存"
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("保存") }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("相处")
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("记忆压缩", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "每 ${BehaviorSettings.compressionInterval} 轮对话整理一次长期记忆",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = BehaviorSettings.compressionInterval.toFloat(),
                        onValueChange = { BehaviorSettings.updateCompressionInterval(it.roundToInt()) },
                        valueRange = 4f..20f,
                        steps = 15
                    )
                    Text(
                        "4-8 轮适合话多的关系；12 以上更省调用，但记忆会更粗。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("主动消息", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "她会主动来找你：想念、分享，或者突然求助",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = proactiveOn,
                            onCheckedChange = { on ->
                                when {
                                    !on -> setProactive(false)
                                    notificationsEnabled(context) -> setProactive(true)
                                    else -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                    if (proactiveOn) {
                        Text(
                            "空闲 ${BehaviorSettings.proactiveIdleHours} 小时后",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = BehaviorSettings.proactiveIdleHours.toFloat(),
                            onValueChange = { BehaviorSettings.updateProactiveIdleHours(it.roundToInt()) },
                            valueRange = 6f..24f,
                            steps = 17
                        )
                        Text(
                            "每天最多 ${BehaviorSettings.proactiveDailyCap} 条（按她的作息，睡着时不发，求助类每天最多 1 次）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = BehaviorSettings.proactiveDailyCap.toFloat(),
                            onValueChange = { BehaviorSettings.updateProactiveDailyCap(it.roundToInt()) },
                            valueRange = 1f..3f,
                            steps = 1
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("外观")
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("显示成长数值", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "亲密度、心情等数字默认隐藏",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = UiPrefs.showGrowth,
                            onCheckedChange = { UiPrefs.setShowGrowthEnabled(it) }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("毛玻璃", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (UiPrefs.glassSupported) {
                                    "Android 12 以上使用真实背景模糊"
                                } else {
                                    "当前系统版本不支持真实模糊，只能用磨砂效果"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = UiPrefs.glassEnabled,
                            enabled = UiPrefs.glassSupported,
                            onCheckedChange = { UiPrefs.setGlass(it) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("数据")
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("备份", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "导出 zip（含聊天、记忆、人格、图片）。不包含 API Key，只记模型名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { exportLauncher.launch("ai-person-backup.zip") }) { Text("导出") }
                        OutlinedButton(onClick = {
                            importLauncher.launch(arrayOf("application/zip", "*/*"))
                        }) { Text("导入（覆盖）") }
                    }
                    if (status.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = c.accent)
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("关于")
                GlassCard(Modifier.fillMaxWidth()) {
                    AboutLine("会成长", "亲密度、人格和记忆都会随对话慢慢变化。")
                    AboutLine("会遗忘", "记忆先变模糊，再被删除；重要的会被反复想起。")
                    AboutLine("有边界", "她有自己的底线和情绪，可以不回你、可以生气。")
                    AboutLine("真实节奏", "不是每句都秒回，延迟和沉默都是她的一部分。")
                    AboutLine("会查", "遇到新闻和实时信息才自己查一下，平时不联网。")
                    AboutLine("会玩梗", "网感按角色设定，克制使用，不硬凑。")
                    AboutLine("记忆闭合", "记忆默认锁住，查看或修改需要两次确认。")
                    AboutLine("数据在本地", "聊天、记忆、图片都存在这台手机上。")
                    AboutLine("Key 加密", "接口密钥由系统 Keystore 加密，按角色分开存。")
                    AboutLine("作者", "reqinme")
                    AboutLine("许可", "AI Person 非商业源码开放协议 1.0")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "AI Person " + runCatching {
                            context.packageManager
                                .getPackageInfo(context.packageName, 0)
                                .versionName
                        }.getOrNull().orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            WeChatBottomBar(
                selected = "me",
                onChat = {
                    if (!nav.popBackStack("characters", false)) {
                        nav.navigate("characters") { launchSingleTop = true }
                    }
                },
                onMe = {}
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val c = weChatColors()
    Text(
        text,
        fontSize = 12.sp,
        color = c.muted,
        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
    )
}

@Composable
private fun AboutLine(title: String, body: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            title,
            modifier = Modifier.width(78.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------- 记忆库

@Composable
fun MemoryScreen(nav: NavHostController, characterId: String) {
    val c = weChatColors()
    val memories by AppGraph.db.memoryDao().observeAll(characterId)
        .collectAsState(initial = emptyList<MemoryEntity>())
    val character by AppGraph.db.characterDao().observe(characterId)
        .collectAsState(initial = null)
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val accent = character?.accentColor?.let { Color(it) } ?: c.accent

    WeChatScreenBackground(accent = accent) {
        Column(Modifier.fillMaxSize()) {
            WeChatTopBar(
                title = "记忆库",
                onBack = { nav.popBackStack() },
                actions = {
                    if (unlocked) {
                        BarTextAction("＋") { adding = true }
                        BarTextAction("上锁") { unlocked = false }
                    }
                }
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(14.dp)
            ) {
                if (!unlocked) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        GlassCard(Modifier.fillMaxWidth(), accent = accent) {
                            Text("这里锁着她的记忆", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "默认闭合。想看或想改，需要连续确认两次。关掉 App 后会自动重新上锁。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { showUnlock = true }) { Text("解锁") }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "共 ${memories.size} 条记忆",
                                fontSize = 12.sp,
                                color = c.muted
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(memories, key = { it.id }) { memory ->
                            GlassCard(Modifier.fillMaxWidth(), accent = accent) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (memory.tags.contains("dislike")) {
                                        Text(
                                            "讨厌",
                                            fontSize = 10.sp,
                                            color = Color(0xFFE64340),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFE64340).copy(alpha = 0.1f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        if (memory.tier == "FADED") "模糊" else "清晰",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (memory.tier == "FADED") c.muted else c.accent
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "重要度 ${memory.importance}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (memory.pinned) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "钉住",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.accent
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        confirm = ConfirmRequest(
                                            title = if (memory.pinned) "取消钉住这条记忆" else "钉住这条记忆",
                                            body = "「${memory.content}」",
                                            secondBody = if (memory.pinned) {
                                                "取消钉住后，它会重新参与遗忘。"
                                            } else {
                                                "钉住后她会一直记得这件事，不再变模糊。"
                                            },
                                            onConfirm = {
                                                scope.launch {
                                                    AppGraph.db.memoryDao().setPinned(memory.id, !memory.pinned)
                                                }
                                            }
                                        )
                                    }) { Text(if (memory.pinned) "取消钉住" else "钉住") }
                                    TextButton(onClick = { editing = memory }) { Text("编辑") }
                                    TextButton(onClick = {
                                        confirm = ConfirmRequest(
                                            title = "删除这条记忆",
                                            body = "「${memory.content}」",
                                            secondBody = "删除后她就再也不会记得这件事了。",
                                            onConfirm = {
                                                scope.launch {
                                                    AppGraph.db.memoryDao().delete(memory.id)
                                                }
                                            }
                                        )
                                    }) { Text("删除") }
                                }
                            }
                        }
                        if (memories.isEmpty()) {
                            item {
                                Text(
                                    "她还没形成长期记忆。多聊几轮，或者自己添加一条。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.muted
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUnlock) {
        var step by remember { mutableStateOf(0) }
        LaunchedEffect(showUnlock) { step = 0 }
        AlertDialog(
            onDismissRequest = { showUnlock = false },
            title = { Text(if (step == 0) "查看她的记忆" else "再确认一次") },
            text = {
                Text(
                    if (step == 0) {
                        "记忆是属于她的私密内容。你确定现在要看吗？"
                    } else {
                        "确认后，这次查看会在你关闭 App 前一直有效。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (step == 0) step = 1 else {
                        unlocked = true
                        showUnlock = false
                    }
                }) { Text(if (step == 0) "继续" else "确定查看") }
            },
            dismissButton = {
                TextButton(onClick = { showUnlock = false }) { Text("取消") }
            }
        )
    }

    if (adding) {
        MemoryEditDialog(
            existing = null,
            onDismiss = { adding = false },
            onSubmit = { content, importance ->
                adding = false
                confirm = ConfirmRequest(
                    title = "添加一条记忆",
                    body = "「$content」",
                    secondBody = "这会被她当成真实发生过的事，确认写入？",
                    onConfirm = {
                        scope.launch {
                            val now = System.currentTimeMillis()
                            AppGraph.db.memoryDao().insert(
                                MemoryEntity(
                                    characterId = characterId,
                                    content = content,
                                    importance = importance,
                                    weight = importance.toFloat(),
                                    createdAt = now,
                                    lastRecalledAt = now
                                )
                            )
                        }
                    }
                )
            }
        )
    }

    val editingMemory = editing
    if (editingMemory != null) {
        MemoryEditDialog(
            existing = editingMemory,
            onDismiss = { editing = null },
            onSubmit = { content, importance ->
                editing = null
                confirm = ConfirmRequest(
                    title = "修改这条记忆",
                    body = "「$content」",
                    secondBody = "修改会覆盖她原本记得的内容。",
                    onConfirm = {
                        scope.launch {
                            AppGraph.db.memoryDao().edit(
                                editingMemory.id,
                                content,
                                importance,
                                importance.toFloat()
                            )
                        }
                    }
                )
            }
        )
    }

    TwoStepConfirm(confirm, onDismiss = { confirm = null })
}

@Composable
private fun MemoryEditDialog(
    existing: MemoryEntity?,
    onDismiss: () -> Unit,
    onSubmit: (String, Int) -> Unit
) {
    var content by remember { mutableStateOf(existing?.content.orEmpty()) }
    var importance by remember { mutableFloatStateOf((existing?.importance ?: 3).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加记忆" else "修改记忆") },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(80) },
                    label = { Text("她记得的事") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "重要度 ${importance.roundToInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = importance,
                    onValueChange = { importance = it },
                    valueRange = 1f..5f,
                    steps = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(content.trim(), importance.roundToInt()) },
                enabled = content.trim().length >= 2
            ) { Text("下一步") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
