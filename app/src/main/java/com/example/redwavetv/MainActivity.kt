package com.example.redwavetv

import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import jcifs.CIFSContext
import jcifs.SmbResource
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import java.util.Properties
import kotlin.concurrent.thread
import java.util.concurrent.Executors


private const val BG = 0xFF0B0C10.toInt()
private const val SURFACE = 0xFF171A22.toInt()
private const val SURFACE_HI = 0xFF242A38.toInt()
private const val RED = 0xFFE50914.toInt()
private const val WHITE = 0xFFF5F6FA.toInt()
private const val MUTED = 0xFF9BA3B4.toInt()
private const val GREEN = 0xFF55D6A0.toInt()
private const val ORANGE = 0xFFB85C00.toInt()
private const val ORANGE_HI = 0xFFD97706.toInt()

data class Track(val title: String, val path: String, val folder: String, val artist: String = "Unknown Artist", val album: String = "", val artworkPath: String = "")
data class LyricLine(val timeMs: Long, val text: String)

class MainActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private var miniPlayPause: TextView? = null
    private var playerPlayPause: TextView? = null
    private lateinit var bottomPlayer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var player: ExoPlayer
    private val tracks = mutableListOf<Track>()
    private val navButtons = linkedMapOf<String, TextView>()
    private var smbContext: CIFSContext? = null
    private var currentShare = ""
    private var currentTrack = -1
    private var playbackQueue: List<Track> = emptyList()
    private var queuePosition = -1
    private var queueIsFolder = false
    private var shuffleEnabled = false
    private var repeatMode = 0
    private var playingTrack: Track? = null
    private var isScanning = false
    private var scanStatus = "Not connected"
    private var currentScreen = "HOME"
    private var currentLyrics: List<LyricLine> = emptyList()
    private var lyricsStatus = "Choose a track to look for an .lrc file"
    private var lyricsLoadingPath = ""
    private var playerLyricLine: TextView? = null
    private var playerLyricViews: List<TextView> = emptyList()
    private var recordAnimator: ObjectAnimator? = null
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchGeneration = 0
    private val lyricsHandler = Handler(Looper.getMainLooper())
    private val artCache = mutableMapOf<String, android.graphics.Bitmap?>()
    private lateinit var libraryStore: LibraryStore
    private val securePrefs by lazy { androidx.security.crypto.EncryptedSharedPreferences.create(this, "redwave_secure", androidx.security.crypto.MasterKey.Builder(this).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(), androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)).build()
        player.addListener(object : com.google.android.exoplayer2.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlaybackButtons()
                if (playbackState == com.google.android.exoplayer2.Player.STATE_ENDED && repeatMode != 1) playNext()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlaybackButtons() }
            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                val trackName = playingTrack?.title ?: "Track"
                android.util.Log.e("RedWavePlayer", "Playback failed for $trackName: ${error.errorCodeName}", error)
                val detail = when (error.errorCode) {
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_DECODING_FAILED,
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    com.google.android.exoplayer2.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                        "Audio format not supported: $trackName"
                    else -> "Can't play $trackName: ${error.errorCodeName}"
                }
                runOnUiThread { toast(detail) }
            }
        })
        libraryStore = LibraryStore(this)
        buildShell()
        try { tracks.addAll(libraryStore.loadTracks(limit = 100)) } catch (_: Exception) { scanStatus = "Library database unavailable" }
        try { playingTrack = libraryStore.loadRecent(1).firstOrNull() } catch (_: Exception) { }
        showHome()
        try { restoreSavedShare() } catch (_: Exception) { scanStatus = "Saved connection needs attention"; updateStatus() }
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 10, 22, 8)
            setBackgroundColor(0xFF10131A.toInt())
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("REDWAVE", 23, RED, true))
        brand.addView(label("MUSIC", 9, MUTED, true))
        header.addView(brand, LinearLayout.LayoutParams(145, 54))
        val tabs = listOf(
            Triple("⌂  HOME", "HOME") { showHome() },
            Triple("♫  LIBRARY", "QUEUE") { showQueue() },
            Triple("⌕  SEARCH", "SEARCH") { showSearch() },
            Triple("FOLDERS", "FOLDERS") { showFolders() },
            Triple("⚙  SETTINGS", "SETTINGS") { showSettings() }
        )
        tabs.forEach { tab ->
            val nav = button(tab.first, currentScreen == tab.second) { tab.third() }
            if (tab.second == "FOLDERS") {
                nav.setCompoundDrawablesWithIntrinsicBounds(FolderGlyph(), null, null, null)
                nav.compoundDrawablePadding = 5
            }
            navButtons[tab.second] = nav
            nav.setOnFocusChangeListener { view, focused ->
                val selected = if (tab.second == "QUEUE") currentScreen == "QUEUE" || currentScreen == "ALBUMS" else currentScreen == tab.second
                view.background = rounded(if (focused || selected) RED else SURFACE, 10)
            }
            header.addView(nav, LinearLayout.LayoutParams(0, 48, 1f).apply { leftMargin = 4; rightMargin = 4 })
        }
        root.addView(header, LinearLayout.LayoutParams(-1, 72))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 0, 24, 0)
        }
        top.addView(label("YOUR LIBRARY", 11, MUTED, true), LinearLayout.LayoutParams(0, 34, 1f))
        statusText = label(scanStatus, 11, GREEN, true).apply {
            gravity = Gravity.CENTER
            setPadding(12, 0, 12, 0)
            background = rounded(SURFACE, 18)
        }
        top.addView(statusText, LinearLayout.LayoutParams(-2, 30))
        root.addView(top)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 14) }
        contentScroll = ScrollView(this).apply {
            id = View.generateViewId()
            isFillViewport = true
            isSmoothScrollingEnabled = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(content)
        }
        root.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        contentScroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            if (focused != null && isViewInside(contentScroll, focused)) {
                contentScroll.post {
                    val rect = android.graphics.Rect()
                    focused.getDrawingRect(rect)
                    contentScroll.offsetDescendantRectToMyCoords(focused, rect)
                    val topEdge = contentScroll.scrollY
                    val bottomEdge = topEdge + contentScroll.height
                    when {
                        rect.bottom > bottomEdge -> contentScroll.smoothScrollBy(0, rect.bottom - bottomEdge + 20)
                        rect.top < topEdge -> contentScroll.smoothScrollBy(0, rect.top - topEdge - 20)
                    }
                }
            }
        }
        bottomPlayer = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL; setPadding(14, 6, 14, 6); setBackgroundColor(0xFF171A22.toInt()); descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        root.addView(bottomPlayer, LinearLayout.LayoutParams(-1, 66))
        setContentView(root)
        addNowPlaying()
    }

    private fun showSettings() {
        currentScreen = "SETTINGS"
        content.removeAllViews()
        updateNavigation()
        content.addView(label("Settings", 30, WHITE, true), LinearLayout.LayoutParams(-1, 52))
        content.addView(label("Manage your network library and playback.", 14, MUTED), LinearLayout.LayoutParams(-1, 34))
        val connection = panel()
        connection.addView(label("MUSIC SERVER", 12, RED, true))
        connection.addView(label(if (currentShare.isBlank()) "No SMB share configured" else currentShare, 14, WHITE))
        connection.addView(button(if (smbContext == null) "CONNECT TO MUSIC" else "EDIT CONNECTION", true) { showSmbDialog() }, LinearLayout.LayoutParams(-1, 44))
        content.addView(connection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        val scan = panel()
        scan.addView(label("LIBRARY SCAN", 12, ORANGE, true))
        scan.addView(label(libraryStore.scanSummary() ?: "No scan has been run yet", 14, WHITE))
        val scanButton = button(if (isScanning) "SCAN IN PROGRESS" else "SCAN / RESCAN LIBRARY", false) { if (!isScanning) scanShare() }
        scanButton.background = rounded(ORANGE, 10)
        scanButton.setTextColor(0xFF17120C.toInt())
        scanButton.setOnFocusChangeListener { view, focused ->
            view.background = rounded(if (focused) ORANGE_HI else ORANGE, 10)
            (view as TextView).setTextColor(0xFF17120C.toInt())
        }
        scan.addView(scanButton, LinearLayout.LayoutParams(-1, 42).apply { topMargin = 8 })
        content.addView(scan, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        content.addView(button("BROWSE FOLDERS", false) { showFolders() }, LinearLayout.LayoutParams(-1, 48))
        addNowPlaying()
        focusContent()
    }

    private fun isViewInside(parent: ViewGroup, child: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    private fun focusContent() {
        content.post {
            fun firstFocusable(view: View): View? {
                if (view.isShown && view.isFocusable) return view
                if (view is android.view.ViewGroup) {
                    for (index in 0 until view.childCount) {
                        val found = firstFocusable(view.getChildAt(index))
                        if (found != null) return found
                    }
                }
                return null
            }
            firstFocusable(content)?.requestFocus()
        }
    }

    private fun updateNavigation() {
        navButtons.forEach { (screen, view) ->
            val selected = if (screen == "QUEUE") currentScreen == "QUEUE" || currentScreen == "ALBUMS" else currentScreen == screen
            view.background = rounded(if (selected || view.hasFocus()) RED else SURFACE, 10)
        }
    }
    private fun addNav(parent: LinearLayout, title: String, id: String, action: () -> Unit) {
        parent.addView(button(title, id == currentScreen, action), LinearLayout.LayoutParams(-1, 54).apply { bottomMargin = 7 })
    }

    // Startup-safe database access: a damaged/old library must never prevent the TV UI opening.
    private fun safeTrackCount(): Long = try { libraryStore.countTracks() } catch (_: Exception) { 0L }
    private fun safeLoadTracks(limit: Int = 100, offset: Int = 0): List<Track> =
        try { libraryStore.loadTracks(limit, offset) } catch (_: Exception) { emptyList() }
    private fun safeLoadAlbumGroups(limit: Int): List<Pair<String, Long>> =
        try { libraryStore.loadAlbumGroups(limit) } catch (_: Exception) { emptyList() }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val indexedCount = if (::libraryStore.isInitialized) safeTrackCount() else tracks.size
        statusText.text = when {
            isScanning -> "SCANNING • " + indexedCount + " TRACKS"
            smbContext != null -> "CONNECTED • " + indexedCount + " TRACKS"
            else -> scanStatus.uppercase()
        }
        statusText.setTextColor(if (smbContext != null || isScanning) GREEN else MUTED)
    }

    private fun showHome() {
        currentScreen = "HOME"
        content.removeAllViews()
        content.setPadding(24, 8, 24, 18)
        updateStatus()
        updateNavigation()
        content.addView(label("Listen Now", 32, WHITE, true), LinearLayout.LayoutParams(-1, 48))
        content.addView(label("Your music, picked by you.", 14, MUTED), LinearLayout.LayoutParams(-1, 30))
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 18, 24, 18)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF54171D.toInt(), 0xFF24191B.toInt(), SURFACE)).apply { cornerRadius = 18f }
        }
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroCopy.addView(label("ALL YOUR MUSIC.\nONE BEAUTIFUL LIBRARY.", 25, WHITE, true), LinearLayout.LayoutParams(-1, 68))
        heroCopy.addView(label(safeTrackCount().toString() + " songs indexed  ·  browse folders anytime", 13, 0xFFD8D8DE.toInt()))
        val openLibrary = button(if (smbContext == null) "OPEN SETTINGS" else "OPEN LIBRARY", false) {
            if (smbContext != null) showQueue() else showSettings()
        }.apply {
            textSize = 14f
            background = rounded(ORANGE, 10)
            setTextColor(0xFFFFF4E8.toInt())
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(if (focused) 0xFFF08A24.toInt() else 0xFFD97706.toInt(), 12)
                (view as TextView).setTextColor(0xFFFFF4E8.toInt())
            }
        }
        openLibrary.background = rounded(0xFFD97706.toInt(), 12)
        heroCopy.addView(openLibrary, LinearLayout.LayoutParams(196, 50).apply { topMargin = 10 })
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(label("♫", 58, 0xFFFF454F.toInt(), true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(112, 112))
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10; bottomMargin = 22 })
        if (isScanning) content.addView(label("Scanning network library…  " + tracks.size + " found", 13, GREEN, true), LinearLayout.LayoutParams(-1, 36))
        val recent = try { libraryStore.loadRecent(8) } catch (_: Exception) { emptyList() }
        val favourites = try { libraryStore.loadFavorites(8) } catch (_: Exception) { emptyList() }
        val randomPicks = try { libraryStore.loadRandomTracks(12) } catch (_: Exception) { emptyList() }
        val latest = safeLoadTracks(8)
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addHomeSection(left, "RECENTLY PLAYED", "SEE ALL", recent, "Play a song and it will appear here.") { showRecents() }
        addHomeSection(left, "FAVOURITES", "SEE ALL", favourites, "Save songs with ☆ to build your favourites.") { showFavourites() }
        addHomeSection(right, "RANDOM FROM YOUR LIBRARY", "SEE ALL SONGS", randomPicks, "Connect and scan your SMB library to get random picks.") { showQueue() }
        if (latest.isNotEmpty()) addHomeSection(right, "YOUR LIBRARY", "SEE ALL SONGS", latest, "Your scanned tracks will appear here.") { showQueue() }
        columns.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 18 })
        columns.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 18 })
        content.addView(columns, LinearLayout.LayoutParams(-1, -2))
        addNowPlaying()
        focusContent()
    }

    private fun addHomeSection(parent: LinearLayout, title: String, action: String, items: List<Track>, emptyText: String, onClick: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 12)
            background = rounded(SURFACE, 14)
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(title, 13, WHITE, true), LinearLayout.LayoutParams(0, 42, 1f))
        heading.addView(button(action, false, onClick), LinearLayout.LayoutParams(-2, 34))
        card.addView(heading)
        if (items.isEmpty()) {
            card.addView(label(emptyText, 12, MUTED), LinearLayout.LayoutParams(-1, 58))
        } else {
            items.take(5).forEach { addTrackRow(it, items, card) }
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 })
    }

    private fun sectionTitle(title: String, action: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 15, WHITE, true), LinearLayout.LayoutParams(0, 42, 1f))
        row.addView(button(action, false, onClick), LinearLayout.LayoutParams(112, 36))
        content.addView(row)
    }

    private fun addArtworkGrid(groups: List<Pair<String, List<Track>>>) {
        var index = 0
        while (index < groups.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            for (column in 0..2) {
                if (index >= groups.size) break
                val group = groups[index++]
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8, 8, 8, 8)
                    background = rounded(SURFACE, 12)
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { v, focus -> v.background = rounded(if (focus) SURFACE_HI else SURFACE, 12) }
                    setOnClickListener { showTrackList(group.first.trimEnd('/').substringAfterLast('/'), group.second) }
                }
                val art = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF65151D.toInt(), 0xFF242B48.toInt())).apply { cornerRadius = 9f }
                    setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF54171D.toInt(), 0xFF242B48.toInt())).apply { cornerRadius = 9f })
                }
                card.addView(art, LinearLayout.LayoutParams(-1, 116))
                card.addView(label(group.first.trimEnd('/').substringAfterLast('/').ifBlank { "Music" }, 13, WHITE, true), LinearLayout.LayoutParams(-1, 28))
                card.addView(label(group.second.size.toString() + " songs", 11, MUTED), LinearLayout.LayoutParams(-1, 20))
                row.addView(card, LinearLayout.LayoutParams(0, 174, 1f).apply { if (column < 2) rightMargin = 10; bottomMargin = 10 })
                loadFolderArt(group.second.firstOrNull()?.folder ?: group.first, group.second.firstOrNull(), art)
            }
            while (row.childCount < 3) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            content.addView(row, LinearLayout.LayoutParams(-1, 180))
        }
    }

    private fun loadFolderArt(folder: String, sample: Track?, target: ImageView) {
        synchronized(artCache) {
            if (artCache.containsKey(folder)) {
                artCache[folder]?.let { target.setImageBitmap(it) }
                return
            }
        }
        thread(name = "redwave-art") {
            var bitmap: android.graphics.Bitmap? = null
            try {
                if (sample != null && sample.artworkPath.isNotBlank()) {
                    val embedded = File(sample.artworkPath)
                    if (embedded.exists()) bitmap = BitmapFactory.decodeFile(embedded.absolutePath)
                }
                val ctx = smbContext
                if (bitmap == null && ctx != null) for (name in listOf("cover.jpg", "folder.jpg", "album.jpg", "front.jpg", "cover.png", "folder.png")) {
                    try {
                        val image = SmbFile(folder.trimEnd('/') + "/" + name, ctx)
                        if (image.exists() && image.isFile) {
                            bitmap = SmbFileInputStream(image).use { BitmapFactory.decodeStream(it) }
                            if (bitmap != null) break
                        }
                    } catch (_: Exception) {}
                }
                if (bitmap == null && sample != null && ctx != null) {
                    val temp = File(cacheDir, "art_" + sample.path.hashCode() + ".audio")
                    try {
                        SmbFileInputStream(SmbFile(sample.path, ctx)).use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(temp.absolutePath)
                            val bytes = retriever.embeddedPicture
                            if (bytes != null) bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } finally { retriever.release(); temp.delete() }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            synchronized(artCache) { artCache[folder] = bitmap }
            runOnUiThread { if (bitmap != null && target.isAttachedToWindow) target.setImageBitmap(bitmap) }
        }
    }

    private fun showAlbums() {
        currentScreen = "ALBUMS"
        updateNavigation()
        content.removeAllViews()
        content.addView(label("Albums & folders", 30, WHITE, true), LinearLayout.LayoutParams(-1, 48))
        content.addView(label("Your network music, arranged as visual collections.", 13, MUTED), LinearLayout.LayoutParams(-1, 32))
        val groups = safeLoadAlbumGroups(120).mapNotNull { group -> try { group.first to libraryStore.tracksInGroup(group.first, 1) } catch (_: Exception) { null } }
        if (groups.isEmpty()) showEmpty() else addArtworkGrid(groups)
        addNowPlaying()
        focusContent()
    }

    private fun showFolders() {
        currentScreen = "FOLDERS"
        updateNavigation()
        if (smbContext != null && currentShare.isNotBlank()) { showRemoteFolder(currentShare); return }
        currentScreen = "FOLDERS"
        content.removeAllViews()
        content.addView(label("Browse folders", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        content.addView(label("Connect to an SMB share to browse folders before scanning.", 14, MUTED))
        content.addView(button("＋  CONNECT TO SMB", true) { showSmbDialog() }, LinearLayout.LayoutParams(240, 50))
        addNowPlaying()
        focusContent()
    }

    private fun showRemoteFolder(path: String, offset: Int = 0) {
        currentScreen = "FOLDERS"
        content.removeAllViews()
        val title = path.trimEnd('/').substringAfterLast('/').ifBlank { "SMB share" }
        content.addView(label("FOLDERS  /  $title", 25, WHITE, true), LinearLayout.LayoutParams(-1, 48))
        content.addView(label("Browse every item in this folder • page ${offset / 100 + 1}", 13, MUTED), LinearLayout.LayoutParams(-1, 30))
        thread(name = "redwave-folder-browse") {
            try {
                val ctx = smbContext ?: return@thread
                val directory = SmbFile(path, ctx)
                val iterator = directory.children()
                val allVisible = ArrayList<SmbResource>()
                try {
                    while (iterator.hasNext()) {
                        val file = iterator.next()
                        val name = file.getName().trimEnd('/')
                        val isAudio = !file.isDirectory && name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
                        if (file.isDirectory || isAudio) allVisible.add(file)
                    }
                } finally {
                    try { iterator.close() } catch (_: Exception) { }
                }
                // Sort the complete directory before paging so subfolders stay first on every page.
                val sortedAll = allVisible.sortedWith(compareBy<SmbResource>({ !it.isDirectory }, { it.getName().trimEnd('/').lowercase() }))
                val page = sortedAll.drop(offset).take(100)
                val hasMore = offset + page.size < sortedAll.size
                runOnUiThread {
                    if (currentScreen != "FOLDERS") return@runOnUiThread
                    if (path.trimEnd('/') != currentShare.trimEnd('/')) {
                        content.addView(button("↑  PARENT FOLDER", false) {
                            val parent = try { SmbFile(path, smbContext!!).getParent() } catch (_: Exception) { null }
                            showRemoteFolder(parent ?: currentShare)
                        }, LinearLayout.LayoutParams(-1, 48).apply { bottomMargin = 6 })
                    }
                    if (page.isEmpty() && offset == 0) content.addView(label("This folder is empty.", 15, MUTED))
                    val sortedPage = page
                    val folderQueue = sortedAll.filter { !it.isDirectory }.map { file ->
                        val name = file.getName().trimEnd('/')
                        Track(name.substringBeforeLast('.'), file.getLocator().getCanonicalURL(), file.getLocator().getParent() ?: path)
                    }
                    if (folderQueue.isNotEmpty()) content.addView(button("▶ PLAY FOLDER", true) {
                        playTrack(folderQueue.first(), folderQueue, true)
                    }, LinearLayout.LayoutParams(-1, 48).apply { bottomMargin = 8 })
                    sortedPage.forEach { file ->
                        val name = file.getName().trimEnd('/')
                        if (file.isDirectory) {
                            content.addView(button("▤   $name   ›", false) { showRemoteFolder(file.getLocator().getCanonicalURL()) }, LinearLayout.LayoutParams(-1, 54).apply { bottomMargin = 6 })
                        } else {
                            content.addView(button("♫   " + name.substringBeforeLast('.'), false) {
                                playTrack(Track(name.substringBeforeLast('.'), file.getLocator().getCanonicalURL(), file.getLocator().getParent() ?: path), folderQueue, true)
                            }, LinearLayout.LayoutParams(-1, 54).apply { bottomMargin = 6 })
                        }
                    }
                    val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 12, 0, 12) }
                    if (offset > 0) nav.addView(button("PREVIOUS 100", false) { showRemoteFolder(path, (offset - 100).coerceAtLeast(0)) }, LinearLayout.LayoutParams(180, 48).apply { rightMargin = 8 })
                    if (hasMore) nav.addView(button("NEXT 100", false) { showRemoteFolder(path, offset + 100) }, LinearLayout.LayoutParams(180, 48))
                    if (nav.childCount > 0) content.addView(nav, LinearLayout.LayoutParams(-1, 64))
                    addNowPlaying()
                }
            } catch (e: Exception) {
                runOnUiThread { if (currentScreen == "FOLDERS") content.addView(label("Could not read folder: " + (e.message ?: "SMB error"), 14, RED)) }
            }
        }
    }

    private fun showQueue() {
        currentScreen = "QUEUE"
        updateNavigation()
        content.removeAllViews()
        content.addView(label("All tracks", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        content.addView(label(safeTrackCount().toString() + " tracks indexed • showing first " + tracks.size, 13, MUTED), LinearLayout.LayoutParams(-1, 34))
        val page = safeLoadTracks(limit = 100)
        if (page.isEmpty()) {
            showEmpty()
            val summary = try { libraryStore.scanSummary() } catch (_: Exception) { null }
            content.addView(label("Database: ${safeTrackCount()} indexed tracks" + (summary?.let { " • $it" } ?: " • no completed scan recorded"), 12, ORANGE), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
            if (smbContext != null) content.addView(button("RESCAN LIBRARY", true) { scanShare() }, LinearLayout.LayoutParams(220, 48))
        }
        page.forEach { addTrackRow(it, page) }
        if (safeTrackCount() > page.size) content.addView(button("LOAD NEXT 100", false) { showQueuePage(100) }, LinearLayout.LayoutParams(220, 48))
        addNowPlaying()
        focusContent()
    }

    private fun showQueuePage(offset: Int) {
        currentScreen = "QUEUE"
        content.removeAllViews()
        content.addView(label("All tracks", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        val total = safeTrackCount()
        content.addView(label("$total tracks indexed • page ${offset / 100 + 1}", 13, MUTED), LinearLayout.LayoutParams(-1, 34))
        val page = safeLoadTracks(limit = 100, offset = offset)
        if (page.isEmpty()) showEmpty()
        page.forEach { addTrackRow(it) }
        if (offset > 0) content.addView(button("PREVIOUS 100", false) { showQueuePage((offset - 100).coerceAtLeast(0)) }, LinearLayout.LayoutParams(220, 48))
        if (offset + page.size < total) content.addView(button("NEXT 100", false) { showQueuePage(offset + 100) }, LinearLayout.LayoutParams(220, 48))
        addNowPlaying()
    }

    private fun showTrackList(title: String, songs: List<Track>) {
        currentScreen = "QUEUE"
        updateNavigation()
        content.removeAllViews()
        content.addView(label(title, 27, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        content.addView(label(songs.size.toString() + " tracks", 13, MUTED), LinearLayout.LayoutParams(-1, 34))
        if (songs.isNotEmpty()) content.addView(button("▶  PLAY ALBUM / FOLDER", true) { playTrack(songs.first(), songs) }, LinearLayout.LayoutParams(-1, 48).apply { bottomMargin = 8 })
        songs.take(100).forEach { addTrackRow(it, songs) }
        if (songs.size > 100) content.addView(label("Showing first 100 tracks in this folder.", 13, MUTED))
        addNowPlaying()
    }

    private fun showEmpty() {
        val box = panel()
        box.addView(label("No tracks to show yet", 20, WHITE, true))
        box.addView(label("Connect an SMB share and scan your music folders.", 14, MUTED))
        content.addView(box, LinearLayout.LayoutParams(-1, 120).apply { topMargin = 12 })
    }

    private fun addTrackRow(track: Track, queue: List<Track>? = null, parent: LinearLayout = content) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 3, 8, 3)
            background = rounded(SURFACE_HI, 8)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, focus -> v.background = rounded(if (focus) RED else SURFACE, 10) }
            setOnClickListener { playTrack(track, queue) }
        }
        val play = button("▶", false) { playTrack(track, queue) }.apply {
            textSize = 15f
            setTextColor(WHITE)
            background = rounded(0xFF343B4A.toInt(), 18)
            setOnFocusChangeListener { view, focused -> view.background = rounded(if (focused) RED else 0xFF343B4A.toInt(), 18) }
        }
        row.addView(play, LinearLayout.LayoutParams(38, 38).apply { rightMargin = 10 })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        names.addView(label(track.title, 13, WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        names.addView(label(track.artist + if (track.album.isNotBlank()) " · " + track.album else " · " + track.folder.trimEnd('/').substringAfterLast('/'), 10, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        row.addView(names, LinearLayout.LayoutParams(0, 54, 1f))
        val favorite = try { libraryStore.isFavorite(track.path) } catch (_: Exception) { false }
        val favoriteButton = TextView(this).apply {
            text = if (favorite) "★" else "☆"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(if (favorite) 0xFFFFB547.toInt() else MUTED)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = rounded(SURFACE, 8)
            setOnFocusChangeListener { view, focused -> view.background = rounded(if (focused) SURFACE_HI else SURFACE, 8) }
            setOnClickListener {
                try {
                    val saved = libraryStore.isFavorite(track.path)
                    libraryStore.saveFavorite(track, !saved)
                    toast(if (saved) "Removed from favourites" else "Added to favourites")
                    text = if (saved) "☆" else "★"
                    setTextColor(if (saved) MUTED else 0xFFFFB547.toInt())
                } catch (e: Exception) { toast("Could not save favourite: " + (e.message ?: "database error")) }
                if (currentScreen == "FAVOURITES") showFavourites()
            }
        }
        row.addView(favoriteButton, LinearLayout.LayoutParams(42, 40).apply { leftMargin = 6 })
        parent.addView(row, LinearLayout.LayoutParams(-1, 54).apply { bottomMargin = 6 })
    }

    private fun showSearch() {
        currentScreen = "SEARCH"
        updateNavigation()
        content.removeAllViews()
        content.addView(label("Search your music", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        val query = EditText(this).apply { hint = "Search title, artist, album or folder"; setSingleLine(true); textSize = 16f; setTextColor(WHITE); setHintTextColor(MUTED); setPadding(14, 8, 14, 8); background = rounded(SURFACE_HI, 10) }
        content.addView(query, LinearLayout.LayoutParams(-1, 54).apply { bottomMargin = 12 })
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results, LinearLayout.LayoutParams(-1, -2))
        fun runSearch(value: String, offset: Int = 0) {
            searchGeneration++
            val generation = searchGeneration
            searchHandler.removeCallbacksAndMessages("search")
            results.removeAllViews()
            if (value.isBlank()) { results.addView(label("Type to search your indexed library.", 14, MUTED)); return }
            if (value.trim().length < 2) { results.addView(label("Type at least 2 characters to search.", 14, MUTED)); return }
            results.addView(label("Searching…", 14, MUTED))
            val task = Runnable {
                searchExecutor.execute {
                    val matches = try { libraryStore.searchTracks(value, 61, offset) } catch (e: Exception) {
                        android.util.Log.e("RedWaveSearch", "Search failed", e); emptyList()
                    }
                    runOnUiThread {
                        if (generation != searchGeneration || currentScreen != "SEARCH") return@runOnUiThread
                        results.removeAllViews()
                        results.addView(label("Results ${offset + 1}–${offset + minOf(matches.size, 60)}", 12, MUTED))
                        val visible = matches.take(60)
                        if (visible.isEmpty()) results.addView(label("No matching tracks.", 14, MUTED)) else visible.forEach { track ->
                            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                            row.addView(button("▶  ${track.title}  ·  ${track.artist}", false) { playTrack(track, visible) }, LinearLayout.LayoutParams(0, 54, 1f))
                            val isFav = try { libraryStore.isFavorite(track.path) } catch (_: Exception) { false }
                            row.addView(button(if (isFav) "★" else "☆", false) {
                                try {
                                    libraryStore.saveFavorite(track, !isFav)
                                    toast(if (isFav) "Removed from favourites" else "Added to favourites")
                                    runSearch(value, offset)
                                } catch (e: Exception) { toast("Could not save favourite: " + (e.message ?: "database error")) }
                            }, LinearLayout.LayoutParams(54, 48))
                            results.addView(row)
                        }
                        val paging = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 12, 0, 12) }
                        if (offset > 0) paging.addView(button("PREVIOUS 60", false) { runSearch(value, (offset - 60).coerceAtLeast(0)) }, LinearLayout.LayoutParams(180, 48).apply { rightMargin = 10 })
                        if (matches.size > 60) paging.addView(button("NEXT 60", true) { runSearch(value, offset + 60) }, LinearLayout.LayoutParams(180, 48))
                        if (paging.childCount > 0) results.addView(paging, LinearLayout.LayoutParams(-1, 64))
                    }
                }
            }
            searchHandler.postAtTime(task, "search", android.os.SystemClock.uptimeMillis() + 350L)
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { runSearch(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        addNowPlaying()
    }

    private fun showRecents() {
        currentScreen = "RECENTS"
        updateNavigation()
        content.removeAllViews()
        content.addView(label("Recently Played", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        val recent = try { libraryStore.loadRecent(100) } catch (_: Exception) { emptyList() }
        content.addView(label("Saved play history • ${recent.size} shown", 13, MUTED), LinearLayout.LayoutParams(-1, 30))
        if (recent.isEmpty()) content.addView(label("Your played songs will appear here. History is saved on this device.", 14, MUTED)) else recent.forEach { addTrackRow(it) }
        addNowPlaying()
        focusContent()
    }

    private fun showFavourites() {
        currentScreen = "FAVOURITES"
        updateNavigation()
        content.removeAllViews()
        content.addView(label("Favourites", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        val favourites = try { libraryStore.loadFavorites(100) } catch (_: Exception) { emptyList() }
        if (favourites.isEmpty()) content.addView(label("Press ☆ beside a song to save it here.", 14, MUTED)) else favourites.forEach { addTrackRow(it) }
        addNowPlaying()
        focusContent()
    }

    private fun addNowPlaying() {
        if (!::bottomPlayer.isInitialized) return
        bottomPlayer.visibility = if (currentScreen == "PLAYER" || currentScreen == "LYRICS") View.GONE else View.VISIBLE
        if (bottomPlayer.visibility == View.GONE) return
        bottomPlayer.removeAllViews()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8, 2, 8, 2); background = rounded(SURFACE_HI, 10); isFocusable = false; isClickable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        val title = playingTrack?.title ?: tracks.getOrNull(currentTrack)?.title ?: "Nothing playing"
        // The Now Playing target is its own large focusable control; the parent row
        // must not intercept clicks intended for transport buttons.
        val openPlayer = button("NOW PLAYING  ·  " + title, false) { showPlayer() }.apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            nextFocusRightId = View.NO_ID
        }
        val previous = button("|◀", false) { playPrevious(); addNowPlaying() }.apply { id = View.generateViewId() }
        val playPause = button(if (player.playWhenReady) "Ⅱ" else "▶", false) { togglePlayback() }.apply { id = View.generateViewId(); miniPlayPause = this }
        val next = button("▶|", false) { playNext(); addNowPlaying() }.apply { id = View.generateViewId() }
        openPlayer.nextFocusRightId = previous.id
        previous.nextFocusLeftId = openPlayer.id
        previous.nextFocusRightId = playPause.id
        playPause.nextFocusLeftId = previous.id
        playPause.nextFocusRightId = next.id
        next.nextFocusLeftId = playPause.id
        next.nextFocusRightId = View.NO_ID
        bar.addView(openPlayer, LinearLayout.LayoutParams(0, 48, 1f))
        bar.addView(previous, LinearLayout.LayoutParams(58, 46))
        bar.addView(playPause, LinearLayout.LayoutParams(58, 46))
        bar.addView(next, LinearLayout.LayoutParams(58, 46))
        bottomPlayer.addView(bar, LinearLayout.LayoutParams(-1, 54))
    }

    private fun togglePlayback() {
        if (player.playWhenReady) player.pause() else player.play()
        updatePlaybackButtons()
    }

    private fun updatePlaybackButtons() {
        val playing = player.playWhenReady && player.playbackState != com.google.android.exoplayer2.Player.STATE_ENDED
        playerPlayPause?.text = if (playing) "Ⅱ  PAUSE" else "▶  PLAY"
        miniPlayPause?.text = if (playing) "Ⅱ" else "▶"
    }

    private fun refreshScreen() {
        when (currentScreen) {
            "PLAYER" -> showPlayer()
            "LYRICS" -> showLyrics()
            "ALBUMS" -> showAlbums()
            "RECENTS" -> showRecents()
            "FAVOURITES" -> showFavourites()
            "FOLDERS" -> showFolders()
            "QUEUE" -> showQueue()
            "SEARCH" -> showSearch()
            else -> showHome()
        }
    }

    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressSeekBar: SeekBar? = null
    private val progressUpdater = object : Runnable {
        override fun run() {
            val bar = progressSeekBar
            if (currentScreen == "PLAYER") {
                if (bar != null && player.duration > 0 && !bar.isPressed) {
                    bar.progress = ((player.currentPosition.toDouble() / player.duration.toDouble()) * 1000).toInt().coerceIn(0, 1000)
                }
                updatePlayerLyricPreview()
                progressHandler.postDelayed(this, 500)
            }
        }
    }

    private fun showPlayer() {
        currentScreen = "PLAYER"
        updateNavigation()
        bottomPlayer.visibility = View.GONE
        content.removeAllViews()
        content.setPadding(24, 8, 24, 14)
        val track = playingTrack ?: tracks.getOrNull(currentTrack)
        if (track != null && currentLyrics.isEmpty() && lyricsLoadingPath != track.path) loadLyrics(track, false)
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        modes.addView(button(if (shuffleEnabled) "SHUFFLE ON" else "SHUFFLE OFF", shuffleEnabled) { shuffleEnabled = !shuffleEnabled; player.shuffleModeEnabled = shuffleEnabled; showPlayer() }, LinearLayout.LayoutParams(150, 46))
        modes.addView(button(when (repeatMode) { 1 -> "REPEAT ONE"; 2 -> "REPEAT ALL"; else -> "REPEAT OFF" }, repeatMode != 0) {
            repeatMode = (repeatMode + 1) % 3
            // Manage repeat-all ourselves so the end callback advances through the folder queue.
            player.repeatMode = if (repeatMode == 1) com.google.android.exoplayer2.Player.REPEAT_MODE_ONE else com.google.android.exoplayer2.Player.REPEAT_MODE_OFF
            showPlayer()
        }, LinearLayout.LayoutParams(150, 46).apply { leftMargin = 10 })
        content.addView(modes, LinearLayout.LayoutParams(-1, 52))
        content.addView(label("NOW PLAYING", 12, RED, true), LinearLayout.LayoutParams(-1, 34))
        val art = VinylRecordView(this, track?.artworkPath)
        art.contentDescription = "Rotating vinyl record"
        val density = resources.displayMetrics.density
        val availableWidthDp = (resources.displayMetrics.widthPixels / density).toInt() - 72
        val availableHeightDp = (resources.displayMetrics.heightPixels / density).toInt() - 150
        val coverSize = minOf(availableWidthDp * 0.42f, availableHeightDp * 0.43f).toInt().coerceIn(150, 300)
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
        }
        val lyricPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 12, 14, 12)
            background = rounded(0xCC171A22.toInt(), 12)
        }
        playerLyricViews = (0..2).map { index ->
            label("", if (index == 1) 17 else 13, if (index == 1) WHITE else MUTED, index == 1).apply {
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(4, 5, 4, 5)
            }.also { lyricPanel.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        }
        playerLyricLine = playerLyricViews.getOrNull(1)
        updatePlayerLyricPreview()
        stage.addView(lyricPanel, LinearLayout.LayoutParams(0, coverSize, 1f).apply { rightMargin = 14 })
        stage.addView(art, LinearLayout.LayoutParams(coverSize, coverSize))
        content.addView(stage, LinearLayout.LayoutParams(-1, coverSize + 12).apply { topMargin = 8; bottomMargin = 10 })
        content.addView(label(track?.title ?: "Nothing playing", 22, WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6 })
        content.addView(label(track?.artist ?: "Choose a song from your library", 15, MUTED).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, 26))
        content.addView(label(track?.album ?: track?.folder?.trimEnd('/')?.substringAfterLast('/') ?: "", 13, MUTED).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, 24))
        val seek = SeekBar(this).apply { isFocusable = true; isFocusableInTouchMode = true }
        seek.max = 1000
        progressSeekBar = seek
        progressHandler.removeCallbacks(progressUpdater)
        if (player.duration > 0) seek.progress = ((player.currentPosition.toDouble() / player.duration.toDouble()) * 1000).toInt().coerceIn(0, 1000)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val duration = player.duration
                if (duration > 0 && bar != null) player.seekTo((duration * bar.progress / 1000).toLong())
            }
        })
        content.addView(seek, LinearLayout.LayoutParams(-1, 42))
        progressHandler.post(progressUpdater)
        content.addView(button(if (try { libraryStore.isFavorite(track?.path ?: "") } catch (_: Exception) { false }) "★  REMOVE FROM FAVOURITES" else "☆  ADD TO FAVOURITES", false) {
            if (track != null) {
                val saved = try { libraryStore.isFavorite(track.path) } catch (_: Exception) { false }
                try {
                    libraryStore.saveFavorite(track, !saved)
                    toast(if (saved) "Removed from favourites" else "Added to favourites")
                    showPlayer()
                } catch (e: Exception) {
                    toast("Favourite save failed: " + (e.message ?: e.javaClass.simpleName))
                }
            } else toast("Play a song before adding it to favourites")
        }, LinearLayout.LayoutParams(-1, 46))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("PREVIOUS", false) { playPrevious() }, LinearLayout.LayoutParams(120, 54))
        val playerToggle = button(if (player.playWhenReady) "Ⅱ  PAUSE" else "▶  PLAY", true) { togglePlayback() }.apply { playerPlayPause = this }
        controls.addView(playerToggle, LinearLayout.LayoutParams(150, 54).apply { leftMargin = 12; rightMargin = 12 })
        controls.addView(button("NEXT", false) { playNext() }, LinearLayout.LayoutParams(120, 54))
        content.addView(controls, LinearLayout.LayoutParams(-1, 62))
        content.addView(button("OPEN LYRICS", false) { showLyrics() }, LinearLayout.LayoutParams(210, 40).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 4 })
    }

    private fun updatePlayerLyricPreview() {
        if (playerLyricViews.isEmpty()) return
        if (currentLyrics.isEmpty()) {
            playerLyricViews.forEachIndexed { index, view ->
                view.text = if (index == 1) lyricsStatus else ""
                view.setTextColor(if (index == 1) WHITE else MUTED)
            }
            return
        }
        val active = currentLyrics.indexOfLast { it.timeMs <= player.currentPosition }.coerceAtLeast(0)
        playerLyricViews.forEachIndexed { offset, view ->
            val lyric = currentLyrics.getOrNull(active + offset - 1)
            view.text = lyric?.text ?: ""
            view.setTextColor(if (offset == 1) ORANGE else MUTED)
            view.textSize = if (offset == 1) 17f else 13f
            view.setTypeface(Typeface.DEFAULT, if (offset == 1) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun showLyrics() {
        currentScreen = "LYRICS"
        updateNavigation()
        bottomPlayer.visibility = View.GONE
        content.removeAllViews()
        content.setPadding(12, 4, 12, 6)
        val track = playingTrack ?: tracks.getOrNull(currentTrack)
        content.addView(button("←  BACK TO PLAYER", false) { showPlayer() }, LinearLayout.LayoutParams(220, 42))
        content.addView(label("LYRICS", 12, RED, true), LinearLayout.LayoutParams(-1, 34))
        content.addView(label(track?.title ?: "No song selected", 26, WHITE, true), LinearLayout.LayoutParams(-1, 46))
        content.addView(label(track?.artist ?: track?.folder?.trimEnd('/')?.substringAfterLast('/') ?: "Play a track to find its lyrics", 13, MUTED), LinearLayout.LayoutParams(-1, 34))
        if (track == null) {
            val empty = panel()
            empty.addView(label("Your lyrics will appear here", 20, WHITE, true))
            empty.addView(label("RedWave looks for a matching .lrc file beside the audio file on your SMB share.", 14, MUTED))
            content.addView(empty, LinearLayout.LayoutParams(-1, 140).apply { topMargin = 18 })
            return
        }
        val lyricColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(18, 12, 18, 28) }
        if (currentLyrics.isEmpty()) {
            lyricColumn.addView(label(lyricsStatus, 15, MUTED), LinearLayout.LayoutParams(-1, 58))
            lyricColumn.addView(button("RELOAD LYRICS", false) { loadLyrics(track, true) }, LinearLayout.LayoutParams(-1, 52))
        } else {
            currentLyrics.forEach { line ->
                val row = label(line.text, 20, MUTED, true)
                row.gravity = Gravity.CENTER
                row.setPadding(12, 12, 12, 12)
                row.isFocusable = true
                row.isFocusableInTouchMode = true
                row.setOnFocusChangeListener { view, focused -> view.setBackgroundColor(if (focused) SURFACE_HI else android.graphics.Color.TRANSPARENT) }
                lyricColumn.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        content.addView(lyricColumn, LinearLayout.LayoutParams(-1, -2))
        lyricsHandler.removeCallbacksAndMessages(null)
        val updater = object : Runnable {
            override fun run() {
                if (currentScreen != "LYRICS" || currentLyrics.isEmpty()) return
                val active = currentLyrics.indexOfLast { it.timeMs <= player.currentPosition }
                for (i in 0 until lyricColumn.childCount) {
                    val view = lyricColumn.getChildAt(i)
                    if (view is TextView) view.setTextColor(if (i == active) RED else MUTED)
                }
                lyricsHandler.postDelayed(this, 500)
            }
        }
        lyricsHandler.post(updater)
        if (lyricsStatus == "Choose a track to look for an .lrc file") loadLyrics(track, false)
    }

    private fun loadLyrics(track: Track, force: Boolean) {
        if (!force && (currentLyrics.isNotEmpty() || lyricsLoadingPath == track.path)) return
        lyricsLoadingPath = track.path
        lyricsStatus = "Searching online lyrics…"
        thread(name = "redwave-online-lyrics") {
            var parsed = emptyList<LyricLine>()
            var message = ""
            try {
                val address = "https://lrclib.net/api/get?artist_name=" +
                    java.net.URLEncoder.encode(track.artist, "UTF-8") + "&track_name=" +
                    java.net.URLEncoder.encode(track.title, "UTF-8")
                val connection = java.net.URL(address).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 9000
                connection.readTimeout = 9000
                connection.setRequestProperty("User-Agent", "RedWaveTV Android music player")
                try {
                    if (connection.responseCode == 200) {
                        val json = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        val synced = json.optString("syncedLyrics", "")
                        val plain = json.optString("plainLyrics", "")
                        parsed = if (synced.isNotBlank()) parseLrcText(synced) else plain.lines()
                            .filter { it.isNotBlank() }.mapIndexed { index, line -> LyricLine(index * 4500L, line) }
                    } else message = "No matching online lyrics"
                } finally { connection.disconnect() }
            } catch (e: Exception) { message = "Online lyrics unavailable" }
            if (parsed.isEmpty() && smbContext != null) {
                try {
                    val path = track.path.substringBeforeLast('.', track.path) + ".lrc"
                    val file = SmbFile(path, smbContext)
                    if (file.exists() && file.isFile) {
                        parsed = SmbFileInputStream(file).bufferedReader(Charsets.UTF_8).use { parseLrcText(it.readText()) }
                        if (parsed.isNotEmpty()) message = "Using local LRC file"
                    }
                } catch (_: Exception) {}
            }
            currentLyrics = parsed
            lyricsStatus = if (parsed.isEmpty()) message.ifBlank { "Lyrics not found" } else message.ifBlank { "Lyrics loaded" }
            runOnUiThread {
                if (playingTrack?.path == track.path) {
                    if (currentScreen == "LYRICS") showLyrics() else if (currentScreen == "PLAYER") showPlayer()
                }
            }
        }
    }

    private fun parseLrcText(text: String): List<LyricLine> {
        val pattern = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)")
        return text.lineSequence().flatMap { line ->
            pattern.findAll(line).map { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val millis = match.groupValues[3].padEnd(3, '0').take(3).ifBlank { "000" }.toLong()
                LyricLine((minutes * 60 + seconds) * 1000 + millis, match.groupValues[4].trim())
            }
        }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.toList()
    }


    private fun restoreSavedShare() {
        currentShare = securePrefs.getString("share", null) ?: return
        scanStatus = "Connecting to saved share…"
        updateStatus()
        connectSavedShare()
    }

    private fun connectSavedShare() {
        try {
            currentShare = securePrefs.getString("share", null) ?: run { showSmbDialog(); return }
            val username = securePrefs.getString("username", "") ?: ""
            val password = securePrefs.getString("password", "") ?: ""
            val domain = securePrefs.getString("domain", "") ?: ""
            connectAndScan(username, password, domain, safeTrackCount() == 0L)
        } catch (_: Exception) {
            toast("Could not read saved SMB login. Please reconnect.")
            showSmbDialog()
        }
    }

    private fun showSmbDialog() {
        currentScreen = "SETTINGS"
        updateNavigation()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 6, 20, 6) }
        val host = field("Server IP or hostname, e.g. 192.168.1.20")
        val share = field("Share name, e.g. Music")
        val folder = field("Folder inside share (optional)")
        val user = field("Username (blank for guest)")
        val pass = field("Password (optional)").apply { inputType = 129 }
        val domain = field("Workgroup / domain (optional)")
        listOf(host, share, folder, user, pass, domain).forEach { box.addView(it, LinearLayout.LayoutParams(-1, 50)) }
        val dialog = AlertDialog.Builder(this).setTitle("Connect to SMB2 / SMB3")
            .setMessage("Enter the server and share. You can browse folders first or scan the library later.")
            .setView(box).setNegativeButton("CANCEL", null).setPositiveButton("CONNECT", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val server = host.text.toString().trim().removePrefix("smb://").trimEnd('/')
                val shareName = share.text.toString().trim().trim('/')
                if (server.isBlank() || shareName.isBlank() || server.contains("/") || shareName.contains("/")) {
                    toast("Enter a server and one share name")
                    return@setOnClickListener
                }
                val sub = folder.text.toString().trim().trim('/')
                currentShare = "smb://" + server + "/" + shareName + "/" + if (sub.isBlank()) "" else sub.trim('/') + "/"
                val username = user.text.toString().trim()
                val password = pass.text.toString()
                val workgroup = domain.text.toString().trim()
                securePrefs.edit().putString("share", currentShare).putString("username", username)
                    .putString("password", password).putString("domain", workgroup).apply()
                artCache.clear()
                dialog.dismiss()
                connectAndScan(username, password, workgroup, false)
            }
        }
        dialog.show()
    }

    private fun field(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        setTextColor(WHITE)
        setHintTextColor(MUTED)
        textSize = 14f
        setPadding(8, 0, 8, 0)
    }

    private fun connectAndScan(username: String, password: String, domain: String, startScan: Boolean = true) {
        isScanning = true
        scanStatus = "Connecting"
        showHome()
        val base = currentShare
        thread(name = "redwave-smb-connect") {
            try {
                val props = Properties().apply {
                    setProperty("jcifs.smb.client.minVersion", "SMB210")
                    setProperty("jcifs.smb.client.maxVersion", "SMB311")
                    setProperty("jcifs.smb.client.connTimeout", "15000")
                    setProperty("jcifs.smb.client.responseTimeout", "30000")
                    setProperty("jcifs.smb.client.soTimeout", "30000")
                }
                var ctx: CIFSContext = BaseContext(PropertyConfiguration(props))
                if (username.isNotBlank()) ctx = ctx.withCredentials(NtlmPasswordAuthenticator(domain, username, password))
                // A real listing validates credentials and share access; creating SmbFile alone does not.
                val directory = SmbFile(base, ctx)
                if (!directory.exists() || !directory.isDirectory) throw IllegalStateException("Share or folder not found: " + base)
                // Avoid loading a potentially huge share listing just to connect.
                smbContext = ctx
                if (startScan) performScan(base, ctx) else {
                    isScanning = false
                    scanStatus = "Connected • ready to browse"
                    runOnUiThread { updateStatus(); showHome() }
                }
            } catch (e: Exception) {
                isScanning = false
                scanStatus = "Connection failed"
                runOnUiThread { updateStatus(); showHome(); toast("SMB connection failed: " + (e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private fun scanShare() {
        val ctx = smbContext
        if (ctx == null || currentShare.isBlank()) { showSmbDialog(); return }
        if (isScanning) { toast("A scan is already running"); return }
        isScanning = true
        scanStatus = "Scanning"
        showHome()
        thread(name = "redwave-smb-rescan") { performScan(currentShare, ctx) }
    }

    private fun performScan(base: String, ctx: CIFSContext) {
        var visited = 0L
        var discovered = 0L
        val batch = ArrayList<Track>(100)
        try {
            // Guard database setup and resume reads too; these previously ran before try/catch.
            val resumed = libraryStore.startOrResumeScan(base)
            val savedProgress = libraryStore.scanProgress()
            visited = if (resumed) savedProgress.first else 0L
            discovered = if (resumed) savedProgress.second else 0L

            while (true) {
                val nextDirectory = libraryStore.dequeueDirectory() ?: break
                val (url, depth) = nextDirectory
                if (depth > 40) continue
                val directory = SmbFile(url, ctx)
                val iterator = directory.children()
                try {
                    while (iterator.hasNext()) {
                        val resource = iterator.next()
                        visited++
                        try {
                            // jcifs-ng returns SmbResource entries. Do not force-cast them.
                            val file = resource
                            if (file.isDirectory) {
                                if (depth < 40) libraryStore.enqueueDirectory(file.getLocator().getCanonicalURL(), depth + 1)
                            } else {
                                val name = file.getName().trimEnd('/')
                                val ext = name.substringAfterLast('.', "").lowercase()
                                if (ext in AUDIO_EXTENSIONS) {
                                    batch.add(Track(name.substringBeforeLast('.'), file.getLocator().getCanonicalURL(), file.getLocator().getParent() ?: base))
                                    discovered++
                                    if (batch.size >= 100) { libraryStore.upsertBatch(batch); batch.clear() }
                                }
                            }
                        } catch (entryError: Exception) {
                            android.util.Log.w("RedWaveScan", "Skipping unreadable SMB entry", entryError)
                        }
                        if (visited % 250L == 0L) {
                            libraryStore.updateScanProgress(visited, discovered)
                            val count = discovered
                            runOnUiThread { if (::statusText.isInitialized) statusText.text = "SCANNING • $count TRACKS" }
                        }
                    }
                } finally {
                    try { iterator.close() } catch (_: Exception) { }
                    try { directory.close() } catch (_: Exception) { }
                }
            }
            if (batch.isNotEmpty()) libraryStore.upsertBatch(batch)
            libraryStore.finishScan("COMPLETE", visited, discovered)
            tracks.clear()
            tracks.addAll(safeLoadTracks(limit = 100))
            currentTrack = -1
            isScanning = false
            scanStatus = "Scan complete • $discovered tracks"
            runOnUiThread { updateStatus(); refreshScreen(); toast("Indexed $discovered audio files. Browse folders for the complete library.") }
        } catch (e: Exception) {
            if (batch.isNotEmpty()) try { libraryStore.upsertBatch(batch) } catch (saveError: Exception) { android.util.Log.e("RedWaveScan", "Could not save final scan batch", saveError) }
            try { libraryStore.finishScan("INTERRUPTED", visited, discovered) } catch (stateError: Exception) { android.util.Log.e("RedWaveScan", "Could not persist interrupted scan state", stateError) }
            tracks.clear()
            tracks.addAll(safeLoadTracks(limit = 100))
            isScanning = false
            scanStatus = "Scan failed • $discovered saved"
            android.util.Log.e("RedWaveScan", "SMB scan failed after $visited entries", e)
            runOnUiThread { updateStatus(); refreshScreen(); toast("Scan failed after $visited entries: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }
    private fun enrichMetadata(track: Track, ctx: CIFSContext): Track {
        val temp = File(cacheDir, "meta_" + track.path.hashCode() + ".tmp")
        val retriever = MediaMetadataRetriever()
        try {
            SmbFileInputStream(SmbFile(track.path, ctx)).use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            retriever.setDataSource(temp.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: track.title
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val bytes = retriever.embeddedPicture
            var artPath = ""
            if (bytes != null) {
                val art = File(filesDir, "cover_" + track.path.hashCode() + ".jpg")
                art.writeBytes(bytes)
                artPath = art.absolutePath
            }
            return track.copy(title = title, artist = artist, album = album, artworkPath = artPath)
        } catch (_: Exception) { return track }
        finally { try { retriever.release() } catch (_: Exception) {}; temp.delete() }
    }

    private fun playPrevious() {
        if (playbackQueue.isEmpty() || queuePosition <= 0) return
        queuePosition--
        playTrack(playbackQueue[queuePosition], playbackQueue, queueIsFolder)
    }

    private fun playNext() {
        if (playbackQueue.isEmpty()) {
            val first = try { libraryStore.loadRandomTracks(1).firstOrNull() } catch (_: Exception) { null } ?: return
            playbackQueue = listOf(first)
            queuePosition = 0
            queueIsFolder = false
            playTrack(first, playbackQueue)
            return
        }
        val next = when {
            shuffleEnabled && playbackQueue.size > 1 -> playbackQueue.indices.filter { it != queuePosition }.randomOrNull() ?: queuePosition
            queuePosition + 1 < playbackQueue.size -> queuePosition + 1
            repeatMode == 2 -> 0
            queueIsFolder -> return
            else -> {
                val random = try { libraryStore.loadRandomTracks(300, playingTrack?.path ?: "") } catch (_: Exception) { emptyList() }
                if (random.isEmpty()) return
                playbackQueue = random
                queuePosition = 0
                queueIsFolder = false
                0
            }
        }
        queuePosition = next
        playTrack(playbackQueue[next], playbackQueue, queueIsFolder)
    }

    private fun audioMimeType(path: String): String? = when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "m4b", "alac" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "wma" -> "audio/x-ms-wma"
        "aiff", "aif" -> "audio/aiff"
        else -> null
    }

    private fun playTrack(track: Track, queue: List<Track>? = null, folderQueue: Boolean = false) {
        try {
            val ctx = smbContext ?: throw IllegalStateException("Connect to your SMB share first")
            if (queue != null && folderQueue) {
                playbackQueue = queue
                queueIsFolder = true
            } else if (queue !== playbackQueue) {
                val random = try { libraryStore.loadRandomTracks(300, track.path) } catch (_: Exception) { emptyList() }
                playbackQueue = listOf(track) + random
                queueIsFolder = false
            }
            queuePosition = playbackQueue.indexOfFirst { it.path == track.path }
            if (queuePosition < 0) {
                playbackQueue = listOf(track)
                queuePosition = 0
            }
            currentTrack = tracks.indexOfFirst { it.path == track.path }
            playingTrack = track
            try {
                // Tracks launched from the live SMB browser must also be indexed locally;
                // otherwise loadRecent() cannot resolve their history row.
                libraryStore.upsertBatch(listOf(track))
                libraryStore.recordPlay(track.path)
            } catch (_: Exception) { }
            currentLyrics = emptyList()
            lyricsLoadingPath = ""
            playerLyricLine = null
            lyricsStatus = "Choose a track to look for an .lrc file"
            val factory = DataSource.Factory { SmbStreamDataSource(ctx) }
            val mediaItem = MediaItem.Builder()
                .setUri(track.path)
                .setMimeType(audioMimeType(track.path))
                .build()
            val source = ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
            player.setMediaSource(source)
            player.prepare()
            player.play()
            refreshScreen()
            // Extract metadata and embedded cover only for the selected track.
            thread(name = "redwave-track-metadata") {
                val enriched = enrichMetadata(track, ctx)
                if (enriched != track) {
                    try { libraryStore.updateTrackMetadata(enriched) } catch (_: Exception) { }
                    runOnUiThread {
                        if (playingTrack?.path == enriched.path) {
                            playingTrack = enriched
                            if (currentScreen == "PLAYER") showPlayer()
                        }
                    }
                }
            }
        } catch (e: Exception) { toast("Playback error: " + (e.message ?: e.javaClass.simpleName)) }
    }

    private inner class VinylRecordView(context: android.content.Context, artworkPath: String?) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
        private var rotationDegrees = 0f
        private var spinAnimator: ValueAnimator? = null
        private val artwork = artworkPath?.takeIf { it.isNotBlank() }?.let {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it, options)
                options.inSampleSize = maxOf(1, minOf(options.outWidth, options.outHeight) / 256)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(it, options)
            } catch (_: Exception) { null }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            spinAnimator?.cancel()
            spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 9000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { rotationDegrees = it.animatedValue as Float; postInvalidateOnAnimation() }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            spinAnimator?.cancel()
            spinAnimator = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.47f
            fill.color = 0xFF090A0D.toInt()
            canvas.drawCircle(cx, cy, radius, fill)
            // Rotate only the vinyl grooves. The centre label/artwork stays perfectly still,
            // avoiding the logo wobble/fade caused by rotating the entire ImageView/View.
            val save = canvas.save()
            canvas.rotate(rotationDegrees, cx, cy)
            for (i in 1..10) {
                stroke.color = if (i % 3 == 0) 0xFF777B84.toInt() else 0xFF3A3E47.toInt()
                stroke.strokeWidth = if (i % 3 == 0) 2f else 1f
                val ringRadius = radius * (0.48f + i * 0.047f)
                val ring = android.graphics.RectF(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
                canvas.drawArc(ring, rotationDegrees + i * 11f, 250f, false, stroke)
            }
            stroke.color = 0xFF8A8E98.toInt()
            stroke.strokeWidth = 2f
            canvas.drawLine(cx + radius * 0.53f, cy, cx + radius * 0.91f, cy, stroke)
            canvas.restoreToCount(save)
            fill.color = 0xFFB00020.toInt()
            canvas.drawCircle(cx, cy, radius * 0.34f, fill)
            if (artwork != null && !artwork.isRecycled) {
                val artRadius = radius * 0.30f
                val clip = android.graphics.Path().apply { addCircle(cx, cy, artRadius, android.graphics.Path.Direction.CW) }
                val artSave = canvas.save()
                canvas.clipPath(clip)
                val side = minOf(artwork.width, artwork.height)
                val src = android.graphics.Rect((artwork.width-side)/2, (artwork.height-side)/2, (artwork.width+side)/2, (artwork.height+side)/2)
                val dst = android.graphics.RectF(cx-artRadius, cy-artRadius, cx+artRadius, cy+artRadius)
                canvas.drawBitmap(artwork, src, dst, fill)
                canvas.restoreToCount(artSave)
            }
            fill.color = 0xFFFFB05A.toInt()
            canvas.drawCircle(cx, cy, radius * 0.055f, fill)
        }
    }

    private class FolderGlyph : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; style = Paint.Style.FILL }
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            val path = android.graphics.Path().apply {
                moveTo(b.left + w * 0.08f, b.top + h * 0.22f)
                lineTo(b.left + w * 0.42f, b.top + h * 0.22f)
                lineTo(b.left + w * 0.54f, b.top + h * 0.36f)
                lineTo(b.left + w * 0.91f, b.top + h * 0.36f)
                lineTo(b.left + w * 0.84f, b.top + h * 0.82f)
                lineTo(b.left + w * 0.10f, b.top + h * 0.82f)
                close()
            }
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = 16
        override fun getIntrinsicHeight(): Int = 16
    }

    private fun button(title: String, selected: Boolean, action: () -> Unit): TextView = TextView(this).apply {
        text = title
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(WHITE)
        setPadding(9, 8, 9, 8)
        background = rounded(if (selected) RED else SURFACE, 10)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setOnFocusChangeListener { v, focus -> v.background = rounded(if (focus || selected) RED else SURFACE, 10) }
        setOnClickListener { action() }
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(22, 14, 22, 14)
        background = rounded(SURFACE, 14)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun toast(message: String) {
        // Avoid framework Toast layout inflation on older Android TV builds.
        scanStatus = message
        if (::statusText.isInitialized) statusText.text = message
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayback(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); updatePlaybackButtons(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); updatePlaybackButtons(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (currentScreen == "LYRICS") { contentScroll.smoothScrollBy(0, (resources.displayMetrics.density * 72).toInt()); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (currentScreen == "LYRICS") { contentScroll.smoothScrollBy(0, -(resources.displayMetrics.density * 72).toInt()); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playPrevious(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        progressHandler.removeCallbacksAndMessages(null)
        lyricsHandler.removeCallbacksAndMessages(null)
        try { libraryStore.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)") } catch (_: Exception) { }
        try { libraryStore.close() } catch (_: Exception) { }
        player.release()
        super.onDestroy()
    }

    private class SmbStreamDataSource(private val context: CIFSContext) : DataSource {
        private var stream: SmbFileInputStream? = null
        private var uri: Uri? = null
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            val file = SmbFile(dataSpec.uri.toString(), context)
            stream = SmbFileInputStream(file)
            var remaining = dataSpec.position
            while (remaining > 0) {
                val skipped = stream!!.skip(remaining)
                if (skipped > 0) remaining -= skipped
                else if (stream!!.read() == -1) throw java.io.EOFException("Could not seek to SMB offset")
                else remaining--
            }
            val length = (file.length() - dataSpec.position).coerceAtLeast(0L)
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(length, dataSpec.length) else length
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = stream?.read(buffer, offset, length) ?: -1
            return if (count == -1) C.RESULT_END_OF_INPUT else count
        }
        override fun getUri(): Uri? = uri
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun close() { try { stream?.close() } finally { stream = null; uri = null } }
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "opus", "wma", "aiff", "alac")
    }
}
