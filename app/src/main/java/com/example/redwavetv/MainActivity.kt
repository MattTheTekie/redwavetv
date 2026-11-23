package com.example.redwavetv

import android.graphics.Typeface
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
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import java.util.Properties
import kotlin.concurrent.thread

private const val BG = 0xFF0B0C10.toInt()
private const val SURFACE = 0xFF171A22.toInt()
private const val SURFACE_HI = 0xFF242A38.toInt()
private const val RED = 0xFFFF4057.toInt()
private const val WHITE = 0xFFF5F6FA.toInt()
private const val MUTED = 0xFF9BA3B4.toInt()
private const val GREEN = 0xFF55D6A0.toInt()

data class Track(val title: String, val path: String, val folder: String, val artist: String = "Unknown Artist", val album: String = "", val artworkPath: String = "")
data class LyricLine(val timeMs: Long, val text: String)

class MainActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var bottomPlayer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var player: ExoPlayer
    private val tracks = mutableListOf<Track>()
    private var smbContext: CIFSContext? = null
    private var currentShare = ""
    private var currentTrack = -1
    private var playingTrack: Track? = null
    private var isScanning = false
    private var scanStatus = "Not connected"
    private var currentScreen = "HOME"
    private var currentLyrics: List<LyricLine> = emptyList()
    private var lyricsStatus = "Choose a track to look for an .lrc file"
    private val lyricsHandler = Handler(Looper.getMainLooper())
    private val artCache = mutableMapOf<String, android.graphics.Bitmap?>()
    private lateinit var libraryStore: LibraryStore
    private val securePrefs by lazy { androidx.security.crypto.EncryptedSharedPreferences.create(this, "redwave_secure", androidx.security.crypto.MasterKey.Builder(this).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(), androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        player = ExoPlayer.Builder(this).build()
        libraryStore = LibraryStore(this)
        buildShell()
        try { tracks.addAll(libraryStore.loadTracks(limit = 100)) } catch (_: Exception) { scanStatus = "Library database unavailable" }
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
            Triple("▦  ALBUMS", "ALBUMS") { showAlbums() },
            Triple("♫  TRACKS", "QUEUE") { showQueue() },
            Triple("▤  FOLDERS", "FOLDERS") { showFolders() },
            Triple("♪  LYRICS", "LYRICS") { showLyrics() },
            Triple("▶  PLAY", "PLAYER") { showPlayer() },
            Triple("＋  SMB", "SMB") { showSmbDialog() }
        )
        tabs.forEach { tab ->
            header.addView(button(tab.first, currentScreen == tab.second) { tab.third() },
                LinearLayout.LayoutParams(0, 48, 1f).apply { leftMargin = 4; rightMargin = 4 })
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
        val scroll = ScrollView(this).apply { id = View.generateViewId(); isFillViewport = true; isFocusable = false; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomPlayer = LinearLayout(this).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL; setPadding(14, 6, 14, 6); setBackgroundColor(0xFF171A22.toInt()); descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        root.addView(bottomPlayer, LinearLayout.LayoutParams(-1, 66))
        setContentView(root)
        addNowPlaying()
    }

    private fun addNav(parent: LinearLayout, title: String, id: String, action: () -> Unit) {
        parent.addView(button(title, id == currentScreen, action), LinearLayout.LayoutParams(-1, 54).apply { bottomMargin = 7 })
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val indexedCount = if (::libraryStore.isInitialized) libraryStore.countTracks() else tracks.size
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
        updateStatus()
        content.addView(label("Listen Now", 32, WHITE, true), LinearLayout.LayoutParams(-1, 48))
        content.addView(label("Your music, picked by you.", 14, MUTED), LinearLayout.LayoutParams(-1, 30))
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 16, 22, 16)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF522638.toInt(), 0xFF30243F.toInt(), SURFACE)).apply { cornerRadius = 18f }
        }
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroCopy.addView(label("ALL YOUR MUSIC.\nONE BEAUTIFUL LIBRARY.", 25, WHITE, true), LinearLayout.LayoutParams(-1, 78))
        heroCopy.addView(label(libraryStore.countTracks().toString() + " songs indexed  ·  browse folders anytime", 13, 0xFFE2D9E0.toInt()))
        heroCopy.addView(button(if (smbContext == null && currentShare.isNotBlank()) "CONNECT SAVED SHARE" else if (smbContext == null) "ADD MUSIC SHARE" else "SCAN LIBRARY", true) {
            if (smbContext != null) scanShare() else if (currentShare.isNotBlank()) connectSavedShare() else showSmbDialog()
        }, LinearLayout.LayoutParams(210, 44).apply { topMargin = 12 })
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(label("♫", 86, 0xFFFFA4B5.toInt(), true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(150, 130))
        content.addView(hero, LinearLayout.LayoutParams(-1, 172).apply { topMargin = 10; bottomMargin = 18 })
        if (isScanning) content.addView(label("Scanning network library…  " + tracks.size + " found", 13, GREEN, true), LinearLayout.LayoutParams(-1, 36))
        val folders = libraryStore.loadAlbumGroups(9).map { it.first to libraryStore.tracksInGroup(it.first, 1) }
        if (folders.isNotEmpty()) {
            sectionTitle("ALBUMS", "SEE ALL") { showAlbums() }
            addArtworkGrid(folders)
        } else if (!isScanning) {
            val empty = panel()
            empty.addView(label("Your library starts here", 20, WHITE, true))
            empty.addView(label("Connect an SMB share. RedWave scans nested folders and looks for cover artwork.", 14, MUTED))
            content.addView(empty, LinearLayout.LayoutParams(-1, 112).apply { bottomMargin = 16 })
        }
        if (tracks.isNotEmpty()) {
            sectionTitle("RECENTLY ADDED", "SEE SONGS") { showQueue() }
            tracks.take(6).forEach { addTrackRow(it) }
        }
        addNowPlaying()
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
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF713047.toInt(), 0xFF242B48.toInt())).apply { cornerRadius = 9f }
                    setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF713047.toInt(), 0xFF242B48.toInt())).apply { cornerRadius = 9f })
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
        content.removeAllViews()
        content.addView(label("Albums & folders", 30, WHITE, true), LinearLayout.LayoutParams(-1, 48))
        content.addView(label("Your network music, arranged as visual collections.", 13, MUTED), LinearLayout.LayoutParams(-1, 32))
        val groups = libraryStore.loadAlbumGroups(120).map { it.first to libraryStore.tracksInGroup(it.first, 1) }
        if (groups.isEmpty()) showEmpty() else addArtworkGrid(groups)
        addNowPlaying()
    }

    private fun showFolders() {
        if (smbContext != null && currentShare.isNotBlank()) { showRemoteFolder(currentShare); return }
        currentScreen = "FOLDERS"
        content.removeAllViews()
        content.addView(label("Browse folders", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        content.addView(label("Connect to an SMB share to browse folders before scanning.", 14, MUTED))
        content.addView(button("＋  CONNECT TO SMB", true) { showSmbDialog() }, LinearLayout.LayoutParams(240, 50))
        addNowPlaying()
    }

    private fun showRemoteFolder(path: String) {
        currentScreen = "FOLDERS"
        content.removeAllViews()
        val title = path.trimEnd('/').substringAfterLast('/').ifBlank { "SMB share" }
        content.addView(label("FOLDERS  /  $title", 25, WHITE, true), LinearLayout.LayoutParams(-1, 48))
        content.addView(label("Browse directly without scanning the entire share.", 13, MUTED), LinearLayout.LayoutParams(-1, 30))
        thread(name = "redwave-folder-browse") {
            try {
                val ctx = smbContext ?: return@thread
                val children = SmbFile(path, ctx).listFiles().sortedWith(compareBy<SmbFile> { !it.isDirectory }.thenBy { it.getName().lowercase() })
                runOnUiThread {
                    if (currentScreen != "FOLDERS") return@runOnUiThread
                    if (path.trimEnd('/') != currentShare.trimEnd('/')) {
                        content.addView(button("↑  PARENT FOLDER", false) {
                            val parent = try { SmbFile(path, smbContext!!).getParent() } catch (_: Exception) { null }
                            showRemoteFolder(parent ?: currentShare)
                        }, LinearLayout.LayoutParams(-1, 48).apply { bottomMargin = 6 })
                    }
                    if (children.isEmpty()) content.addView(label("This folder is empty.", 15, MUTED))
                    children.take(200).forEach { file ->
                        val name = file.getName().trimEnd('/')
                        if (file.isDirectory) {
                            content.addView(button("▤   $name   ›", false) { showRemoteFolder(file.getURL().toString()) }, LinearLayout.LayoutParams(-1, 54).apply { bottomMargin = 6 })
                        } else {
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (ext in AUDIO_EXTENSIONS) content.addView(button("♫   " + name.substringBeforeLast('.'), false) {
                                playTrack(Track(name.substringBeforeLast('.'), file.getURL().toString(), file.getParent() ?: path))
                            }, LinearLayout.LayoutParams(-1, 54).apply { bottomMargin = 6 })
                        }
                    }
                    if (children.size > 200) content.addView(label("Showing first 200 items in this folder. Open a narrower folder to browse more.", 13, MUTED))
                    addNowPlaying()
                }
            } catch (e: Exception) {
                runOnUiThread { if (currentScreen == "FOLDERS") content.addView(label("Could not read folder: " + (e.message ?: "SMB error"), 14, RED)) }
            }
        }
    }

    private fun showQueue() {
        currentScreen = "QUEUE"
        content.removeAllViews()
        content.addView(label("All tracks", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        content.addView(label(libraryStore.countTracks().toString() + " tracks indexed • showing first " + tracks.size, 13, MUTED), LinearLayout.LayoutParams(-1, 34))
        val page = libraryStore.loadTracks(limit = 100)
        if (page.isEmpty()) showEmpty()
        page.forEach { addTrackRow(it) }
        if (libraryStore.countTracks() > page.size) content.addView(button("LOAD NEXT 100", false) { showQueuePage(100) }, LinearLayout.LayoutParams(220, 48))
        addNowPlaying()
    }

    private fun showQueuePage(offset: Int) {
        currentScreen = "QUEUE"
        content.removeAllViews()
        content.addView(label("All tracks", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        val total = libraryStore.countTracks()
        content.addView(label("$total tracks indexed • page ${offset / 100 + 1}", 13, MUTED), LinearLayout.LayoutParams(-1, 34))
        val page = libraryStore.loadTracks(limit = 100, offset = offset)
        if (page.isEmpty()) showEmpty()
        page.forEach { addTrackRow(it) }
        if (offset > 0) content.addView(button("PREVIOUS 100", false) { showQueuePage((offset - 100).coerceAtLeast(0)) }, LinearLayout.LayoutParams(220, 48))
        if (offset + page.size < total) content.addView(button("NEXT 100", false) { showQueuePage(offset + 100) }, LinearLayout.LayoutParams(220, 48))
        addNowPlaying()
    }

    private fun showTrackList(title: String, songs: List<Track>) {
        content.removeAllViews()
        content.addView(label(title, 27, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        content.addView(label(songs.size.toString() + " tracks", 13, MUTED), LinearLayout.LayoutParams(-1, 34))
        songs.take(100).forEach { addTrackRow(it) }
        if (songs.size > 100) content.addView(label("Showing first 100 tracks in this folder.", 13, MUTED))
        addNowPlaying()
    }

    private fun showEmpty() {
        val box = panel()
        box.addView(label("No tracks to show yet", 20, WHITE, true))
        box.addView(label("Connect an SMB share and scan your music folders.", 14, MUTED))
        content.addView(box, LinearLayout.LayoutParams(-1, 120).apply { topMargin = 12 })
    }

    private fun addTrackRow(track: Track) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 4, 12, 4)
            background = rounded(SURFACE, 10)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, focus -> v.background = rounded(if (focus) RED else SURFACE, 10) }
            setOnClickListener { playTrack(track) }
        }
        row.addView(label("♫", 22, RED, true), LinearLayout.LayoutParams(44, 48))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        names.addView(label(track.title, 15, WHITE, true))
        names.addView(label(track.artist + if (track.album.isNotBlank()) " · " + track.album else " · " + track.folder.trimEnd('/').substringAfterLast('/'), 11, MUTED))
        row.addView(names, LinearLayout.LayoutParams(0, 54, 1f))
        row.addView(label("▶", 15, MUTED, true))
        content.addView(row, LinearLayout.LayoutParams(-1, 62).apply { bottomMargin = 6 })
    }

    private fun addNowPlaying() {
        if (!::bottomPlayer.isInitialized) return
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
        val previous = button("|◀", false) { player.seekToPrevious(); addNowPlaying() }.apply { id = View.generateViewId() }
        val playPause = button(if (player.isPlaying) "Ⅱ" else "▶", false) { if (player.isPlaying) player.pause() else player.play(); addNowPlaying() }.apply { id = View.generateViewId() }
        val next = button("▶|", false) { player.seekToNext(); addNowPlaying() }.apply { id = View.generateViewId() }
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

    private fun refreshScreen() {
        when (currentScreen) {
            "PLAYER" -> showPlayer()
            "LYRICS" -> showLyrics()
            "ALBUMS" -> showAlbums()
            "FOLDERS" -> showFolders()
            "QUEUE" -> showQueue()
            else -> showHome()
        }
    }

    private fun showPlayer() {
        currentScreen = "PLAYER"
        content.removeAllViews()
        val track = playingTrack ?: tracks.getOrNull(currentTrack)
        content.addView(label("NOW PLAYING", 12, RED, true), LinearLayout.LayoutParams(-1, 34))
        val art = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6A172B.toInt(), 0xFF231D3B.toInt(), 0xFF111722.toInt())).apply { cornerRadius = 22f }
        }
        if (track?.artworkPath?.isNotBlank() == true && File(track.artworkPath).exists()) {
            val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeFile(track.artworkPath)) }
            art.addView(cover, LinearLayout.LayoutParams(-1, -1))
        } else {
            art.addView(label("♫", 92, WHITE, true).apply { gravity = Gravity.CENTER })
        }
        val density = resources.displayMetrics.density
        val availableWidthDp = (resources.displayMetrics.widthPixels / density).toInt() - 72
        val coverSize = (availableWidthDp * 0.48f).toInt().coerceIn(176, 360)
        content.addView(art, LinearLayout.LayoutParams(coverSize, coverSize).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 8; bottomMargin = 14 })
        content.addView(label(track?.title ?: "Nothing playing", 22, WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6 })
        content.addView(label(track?.artist ?: "Choose a song from your library", 15, MUTED).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, 26))
        content.addView(label(track?.album ?: track?.folder?.trimEnd('/')?.substringAfterLast('/') ?: "", 13, MUTED).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, 24))
        val seek = SeekBar(this)
        seek.max = 1000
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
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("PREVIOUS", false) { if (currentTrack > 0) playTrack(tracks[currentTrack - 1]) }, LinearLayout.LayoutParams(120, 54))
        controls.addView(button(if (player.isPlaying) "Ⅱ  PAUSE" else "▶  PLAY", true) { if (player.isPlaying) player.pause() else player.play(); showPlayer() }, LinearLayout.LayoutParams(150, 54).apply { leftMargin = 12; rightMargin = 12 })
        controls.addView(button("NEXT", false) { if (currentTrack + 1 < tracks.size) playTrack(tracks[currentTrack + 1]) }, LinearLayout.LayoutParams(120, 54))
        content.addView(controls, LinearLayout.LayoutParams(-1, 62))
        content.addView(button("OPEN LYRICS", false) { showLyrics() }, LinearLayout.LayoutParams(-1, 54).apply { topMargin = 14 })
    }

    private fun showLyrics() {
        currentScreen = "LYRICS"
        content.removeAllViews()
        val track = playingTrack ?: tracks.getOrNull(currentTrack)
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
        if (currentLyrics.isEmpty()) {
            content.addView(label(lyricsStatus, 15, MUTED), LinearLayout.LayoutParams(-1, 58))
            content.addView(button("RELOAD LYRICS", false) { loadLyrics(track, true) }, LinearLayout.LayoutParams(-1, 52))
        } else {
            currentLyrics.forEach { line ->
                val row = label(line.text, 19, if (line.timeMs <= player.currentPosition) WHITE else MUTED, true)
                row.gravity = Gravity.CENTER
                row.setPadding(12, 12, 12, 12)
                content.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            content.addView(label("Timed highlighting follows playback when timestamps are present.", 11, MUTED), LinearLayout.LayoutParams(-1, 38))
        }
        lyricsHandler.removeCallbacksAndMessages(null)
        val updater = object : Runnable {
            override fun run() {
                if (currentScreen != "LYRICS" || currentLyrics.isEmpty()) return
                val active = currentLyrics.indexOfLast { it.timeMs <= player.currentPosition }
                for (i in 0 until content.childCount) {
                    val view = content.getChildAt(i)
                    if (view is TextView && i >= 3 && i < 3 + currentLyrics.size) {
                        view.setTextColor(if (i - 3 == active) RED else MUTED)
                    }
                }
                lyricsHandler.postDelayed(this, 500)
            }
        }
        lyricsHandler.post(updater)
        if (lyricsStatus == "Choose a track to look for an .lrc file") loadLyrics(track, false)
    }

    private fun loadLyrics(track: Track, force: Boolean) {
        if (!force && currentLyrics.isNotEmpty()) return
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
            runOnUiThread { if (currentScreen == "LYRICS") showLyrics() }
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
        scanStatus = "Saved share • not connected"
        updateStatus()
        showHome()
    }

    private fun connectSavedShare() {
        try {
            currentShare = securePrefs.getString("share", null) ?: run { showSmbDialog(); return }
            val username = securePrefs.getString("username", "") ?: ""
            val password = securePrefs.getString("password", "") ?: ""
            val domain = securePrefs.getString("domain", "") ?: ""
            connectAndScan(username, password, domain, false)
        } catch (_: Exception) {
            toast("Could not read saved SMB login. Please reconnect.")
            showSmbDialog()
        }
    }

    private fun showSmbDialog() {
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
                    runOnUiThread { updateStatus(); showFolders() }
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
        // Bounded-memory indexing: do not read whole tracks into RAM or extract tags during scan.
        var visited = 0L
        var discovered = 0L
        // Keep heap usage bounded: jcifs SmbFile.listFiles() materializes an entire
        // directory into an array. children() enumerates SMB entries incrementally.
        val batch = ArrayList<Track>(100)
        // SQLite is the scan index; do not keep a separate in-memory preview list.
        try {
            // Do not delete the previous index up front. If the network drops halfway
            // through a large scan, the last known library remains usable.
            libraryStore.clearScanQueue()
            libraryStore.enqueueDirectory(base, 0)
            while (true) {
                val nextDirectory = libraryStore.dequeueDirectory() ?: break
                val (url, depth) = nextDirectory
                if (depth > 40) continue
                try {
                    val directory = SmbFile(url, ctx)
                    val iterator = directory.children()
                    try {
                        while (iterator.hasNext()) {
                            val resource = iterator.next()
                            visited++
                            try {
                                val file = resource as SmbFile
                                if (file.isDirectory) {
                                    if (depth < 40) libraryStore.enqueueDirectory(file.getURL().toString(), depth + 1)
                                } else {
                                    val name = file.getName().trimEnd('/')
                                    val ext = name.substringAfterLast('.', "").lowercase()
                                    if (ext in AUDIO_EXTENSIONS) {
                                        val item = Track(name.substringBeforeLast('.'), file.getURL().toString(), file.getParent() ?: base)
                                        batch.add(item); discovered++
                                        if (batch.size >= 100) { libraryStore.upsertBatch(batch); batch.clear() }
                                    }
                                }
                            } catch (_: Exception) { }
                            if (visited % 2000L == 0L) {
                                val count = discovered
                                runOnUiThread { if (::statusText.isInitialized) statusText.text = "SCANNING • $count TRACKS" }
                            }
                        }
                    } finally {
                        try { iterator.close() } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }
            if (batch.isNotEmpty()) libraryStore.upsertBatch(batch)
            tracks.clear(); tracks.addAll(libraryStore.loadTracks(limit = 100))
            currentTrack = -1; isScanning = false
            scanStatus = "Scan complete • $discovered tracks"
            runOnUiThread { updateStatus(); refreshScreen(); toast("Indexed $discovered audio files. Browse folders for the complete library.") }
        } catch (e: Exception) {
            if (batch.isNotEmpty()) try { libraryStore.upsertBatch(batch) } catch (_: Exception) { }
            tracks.clear(); tracks.addAll(libraryStore.loadTracks(limit = 100))
            isScanning = false; scanStatus = "Scan interrupted • $discovered saved"
            runOnUiThread { updateStatus(); refreshScreen(); toast("Scan stopped after saving $discovered tracks: " + (e.message ?: e.javaClass.simpleName)) }
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

    private fun playTrack(track: Track) {
        try {
            val ctx = smbContext ?: throw IllegalStateException("Connect to your SMB share first")
            currentTrack = tracks.indexOfFirst { it.path == track.path }
            playingTrack = track
            currentLyrics = emptyList()
            lyricsStatus = "Choose a track to look for an .lrc file"
            val factory = DataSource.Factory { SmbStreamDataSource(ctx) }
            val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(track.path))
            player.setMediaSource(source)
            player.prepare()
            player.play()
            refreshScreen()
        } catch (e: Exception) { toast("Playback error: " + (e.message ?: e.javaClass.simpleName)) }
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
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); refreshScreen(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); refreshScreen(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); refreshScreen(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { player.seekToNext(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { player.seekToPrevious(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() { lyricsHandler.removeCallbacksAndMessages(null); player.release(); super.onDestroy() }

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
