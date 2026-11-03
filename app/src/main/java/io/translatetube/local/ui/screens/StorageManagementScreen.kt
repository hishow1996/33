private data class StorageSnapshot(
    val total: Long, val availableBytes: Long, val capacityBytes: Long,
    val cache: Long, val subtitleCount: Int, val subtitleBytes: Long,
    val historyCount: Int, val favorites: Int, val models: Long, val tts: Long, val whisper: Long,
    val senseVoice: Long, val piperBytes: Long, val piperCount: Int, val piperModels: List<StoragePiperModel>, val ttsReady: Boolean, val whisperReady: Boolean,
    val senseReady: Boolean, val selectedAsr: String, val database: Long, val logs: Long,
    val logCount: Int, val timestamp: Long, val warnings: List<String>,
)

@Composable
fun StorageManagementScreen(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onPlay: (PlayRequest) -> Unit = {},
    historyOnly: Boolean = false,
) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(ctx) }
    val manager = remember { (ctx as TranslateTubeApp).modelDownload }
    val ttsState by manager.tts.collectAsState()
    val asrState by manager.asr.collectAsState()
    val player by PlaybackService.ui.collectAsState()
    val piperDownloads by PiperModels.activeDownloadCount.collectAsState()
    val activePiperModelIds by PiperModels.activeModelIds.collectAsState()
    var data by remember { mutableStateOf<StorageSnapshot?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val selectedTtsEngineId = remember(refresh) { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("tts_engine", "edge") ?: "edge" }
    val selectedPiperModelId = remember(refresh) { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("piper_model", "zh_cn_chaowen_medium") ?: "zh_cn_chaowen_medium" }
    var category by remember(historyOnly) { mutableStateOf(if (historyOnly) StorageCategory.HISTORY else null) }
    var pending by remember { mutableStateOf<StorageAction?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }
    var historyVideos by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    var historyLoaded by remember { mutableStateOf(false) }
    var selectedHistoryKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var historySelectionMode by remember { mutableStateOf(false) }
    var historyMenuExpanded by remember { mutableStateOf(false) }
    var historyFavoritesOnly by remember(historyOnly) { mutableStateOf(false) }