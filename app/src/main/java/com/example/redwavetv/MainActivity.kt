package com.example.redwavetv

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.EditText
import android.widget.LinearLayout
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

data class Track(val title: String, val path: String, val folder: String)
data class LyricLine(val timeMs: Long, val text: String)

class MainActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var player: ExoPlayer
    private val tracks = mutableListOf<Track>()
    private var smbContext: CIFSContext? = null
    private var currentShare = ""
    private var currentTrack = -1
    private var isScanning = false
    private var scanStatus = "Not connected"
    private var currentScreen = "HOME"
    private var currentLyrics: List<LyricLine> = emptyList()
    private var lyricsStatus = "Choose a track to look for an .lrc file"
    private val lyricsHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        player = ExoPlayer.Builder(this).build()
        buildShell()
        showHome()
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
            Triple("⌂  LISTEN", "HOME") { showHome() },
            Triple("▦  ALBUMS", "ALBUMS") { showAlbums() },
            Triple("♫  SONGS", "QUEUE") { showQueue() },
            Triple("▤  FOLDERS", "FOLDERS") { showFolders() },
            Triple("♪  LYRICS", "LYRICS") { showLyrics() },
            Triple("▶  PLAYER", "PLAYER") { showPlayer() },
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
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun addNav(parent: LinearLayout, title: String, id: String, action: () -> Unit) {
        parent.addView(button(title, id == currentScreen, action), LinearLayout.LayoutParams(-1, 54).apply { bottomMargin = 7 })
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        statusText.text = when {
            isScanning -> "SCANNING • " + tracks.size + " TRACKS"
            smbContext != null -> "CONNECTED • " + tracks.size + " TRACKS"
            else -> scanStatus.uppercase()
        }
        statusText.setTextColor(if (smbContext != null || isScanning) GREEN else MUTED)
    }

    private fun showHome() {
        currentScreen = "HOME"
        content.removeAllViews()
        updateStatus()
        content.addView(label("Your sound. Your space.", 31, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        content.addView(label(if (currentShare.isBlank()) "Connect your network music library to get started." else currentShare, 13, MUTED), LinearLayout.LayoutParams(-1, 34))
        val hero = panel()
        hero.addView(label("YOUR MUSIC LIBRARY", 11, RED, true))
        hero.addView(label(tracks.size.toString(), 42, WHITE, true), LinearLayout.LayoutParams(-1, 54))
        hero.addView(label("tracks discovered", 13, MUTED))
        content.addView(hero, LinearLayout.LayoutParams(-1, 142).apply { topMargin = 12; bottomMargin = 14 })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("＋  CONNECT SMB SHARE", false) { showSmbDialog() }, LinearLayout.LayoutParams(0, 58, 1f).apply { rightMargin = 8 })
        actions.addView(button("↻  SCAN AGAIN", false) { scanShare() }, LinearLayout.LayoutParams(0, 58, 1f).apply { leftMargin = 8 })
        content.addView(actions)
        when {
            isScanning -> {
                content.addView(label("Scanning your share recursively…", 17, GREEN, true), LinearLayout.LayoutParams(-1, 48))
                content.addView(label("This can take a little while on large network libraries.", 13, MUTED))
            }
            tracks.isEmpty() -> {
                val empty = panel()
                empty.addView(label("♫", 34, RED, true))
                empty.addView(label("Your library is waiting", 21, WHITE, true))
                empty.addView(label("Add an SMB share. RedWave will test access, then search nested folders for audio files.", 14, MUTED))
                content.addView(empty, LinearLayout.LayoutParams(-1, 178).apply { topMargin = 18 })
            }
            else -> {
                content.addView(label("RECENTLY DISCOVERED", 14, RED, true), LinearLayout.LayoutParams(-1, 48))
                tracks.take(8).forEach { addTrackRow(it) }
                if (tracks.size > 8) content.addView(button("VIEW ALL TRACKS", false) { showQueue() })
            }
        }
        addNowPlaying()
    }

    private fun showAlbums() {
        currentScreen = "ALBUMS"
        content.removeAllViews()
        content.addView(label("Albums & collections", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        content.addView(label("Grouped by folder name. Embedded music tags and artwork are not yet indexed.", 13, MUTED), LinearLayout.LayoutParams(-1, 38))
        val groups = tracks.groupBy { it.folder.trimEnd('/').substringAfterLast('/').ifBlank { "Music" } }.toList().sortedBy { it.first.lowercase() }
        if (groups.isEmpty()) showEmpty()
        groups.forEach { (name, songs) -> content.addView(button("♫   " + name + "    ·    " + songs.size + " tracks", false) { showTrackList(name, songs) }, LinearLayout.LayoutParams(-1, 60).apply { bottomMargin = 7 }) }
        addNowPlaying()
    }

    private fun showFolders() {
        currentScreen = "FOLDERS"
        content.removeAllViews()
        content.addView(label("Browse folders", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        val groups = tracks.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() }
        if (groups.isEmpty()) showEmpty()
        groups.forEach { (folder, songs) -> content.addView(button("▤   " + folder.trimEnd('/').substringAfterLast('/') + "    ·    " + songs.size, false) { showTrackList(folder, songs) }, LinearLayout.LayoutParams(-1, 58).apply { bottomMargin = 7 }) }
        addNowPlaying()
    }

    private fun showQueue() {
        currentScreen = "QUEUE"
        content.removeAllViews()
        content.addView(label("All tracks", 29, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        content.addView(label(tracks.size.toString() + " tracks in your library", 13, MUTED), LinearLayout.LayoutParams(-1, 34))
        if (tracks.isEmpty()) showEmpty()
        tracks.forEach { addTrackRow(it) }
        addNowPlaying()
    }

    private fun showTrackList(title: String, songs: List<Track>) {
        content.removeAllViews()
        content.addView(label(title, 27, WHITE, true), LinearLayout.LayoutParams(-1, 58))
        content.addView(label(songs.size.toString() + " tracks", 13, MUTED), LinearLayout.LayoutParams(-1, 34))
        songs.forEach { addTrackRow(it) }
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
        names.addView(label(track.folder.trimEnd('/').substringAfterLast('/'), 11, MUTED))
        row.addView(names, LinearLayout.LayoutParams(0, 54, 1f))
        row.addView(label("▶", 15, MUTED, true))
        content.addView(row, LinearLayout.LayoutParams(-1, 62).apply { bottomMargin = 6 })
    }

    private fun addNowPlaying() {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(10, 5, 10, 5); background = rounded(SURFACE_HI, 10) }
        val title = tracks.getOrNull(currentTrack)?.title ?: "Nothing playing"
        bar.addView(label("NOW PLAYING   " + title, 12, WHITE, true), LinearLayout.LayoutParams(0, 48, 1f))
        bar.addView(button("|◀", false) { player.seekToPrevious() }, LinearLayout.LayoutParams(58, 46))
        bar.addView(button(if (player.isPlaying) "Ⅱ" else "▶", false) { if (player.isPlaying) player.pause() else player.play(); refreshScreen() }, LinearLayout.LayoutParams(58, 46))
        bar.addView(button("▶|", false) { player.seekToNext() }, LinearLayout.LayoutParams(58, 46))
        content.addView(bar, LinearLayout.LayoutParams(-1, 60).apply { topMargin = 16; bottomMargin = 8 })
    }

    private fun refreshScreen() {
        when (currentScreen) {
            "ALBUMS" -> showAlbums()
            "FOLDERS" -> showFolders()
            "QUEUE" -> showQueue()
            else -> showHome()
        }
    }

    private fun showPlayer() {
        currentScreen = "PLAYER"
        content.removeAllViews()
        val track = tracks.getOrNull(currentTrack)
        content.addView(label("NOW PLAYING", 12, RED, true), LinearLayout.LayoutParams(-1, 40))
        val art = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6A172B.toInt(), 0xFF231D3B.toInt(), 0xFF111722.toInt())).apply { cornerRadius = 22f }
        }
        art.addView(label("♫", 92, WHITE, true).apply { gravity = Gravity.CENTER })
        content.addView(art, LinearLayout.LayoutParams(300, 250).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 12; bottomMargin = 18 })
        content.addView(label(track?.title ?: "Nothing playing", 25, WHITE, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 42))
        content.addView(label(track?.folder?.trimEnd('/')?.substringAfterLast('/') ?: "Choose a song from your library", 14, MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 30))
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
        val track = tracks.getOrNull(currentTrack)
        content.addView(label("LYRICS", 12, RED, true), LinearLayout.LayoutParams(-1, 34))
        content.addView(label(track?.title ?: "No song selected", 26, WHITE, true), LinearLayout.LayoutParams(-1, 46))
        content.addView(label(track?.folder?.trimEnd('/')?.substringAfterLast('/') ?: "Play a track to find its lyrics", 13, MUTED), LinearLayout.LayoutParams(-1, 34))
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
        val ctx = smbContext ?: return
        if (!force && currentLyrics.isNotEmpty()) return
        lyricsStatus = "Looking for a matching .lrc file…"
        thread(name = "redwave-lyrics") {
            try {
                val lrcPath = track.path.substringBeforeLast('.', track.path) + ".lrc"
                val file = SmbFile(lrcPath, ctx)
                if (!file.exists() || !file.isFile) throw IllegalStateException("No matching .lrc file found beside this track")
                val parsed = SmbFileInputStream(file).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.flatMap { line ->
                        val matches = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)").findAll(line)
                        matches.map { match ->
                            val minutes = match.groupValues[1].toLong()
                            val seconds = match.groupValues[2].toLong()
                            val fraction = match.groupValues[3].padEnd(3, '0').take(3).ifBlank { "000" }.toLong()
                            LyricLine((minutes * 60 + seconds) * 1000 + fraction, match.groupValues[4].trim())
                        }
                    }.toList().sortedBy { it.timeMs }
                }
                currentLyrics = parsed
                lyricsStatus = if (parsed.isEmpty()) "The .lrc file contains no timestamped lyric lines" else ""
            } catch (e: Exception) {
                currentLyrics = emptyList()
                lyricsStatus = e.message ?: "Lyrics unavailable"
            }
            runOnUiThread { if (currentScreen == "LYRICS") showLyrics() }
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
            .setMessage("Enter the server and share. RedWave checks folder access before it begins scanning.")
            .setView(box).setNegativeButton("CANCEL", null).setPositiveButton("CONNECT & SCAN", null).create()
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
                dialog.dismiss()
                connectAndScan(user.text.toString().trim(), pass.text.toString(), domain.text.toString().trim())
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

    private fun connectAndScan(username: String, password: String, domain: String) {
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
                directory.listFiles()
                smbContext = ctx
                performScan(base, ctx)
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
        val found = mutableListOf<Track>()
        var visited = 0
        try {
            fun walk(url: String, depth: Int) {
                if (depth > 32) return
                val directory = SmbFile(url, ctx)
                for (file in directory.listFiles()) {
                    visited++
                    if (file.isDirectory) {
                        walk(file.getURL().toString(), depth + 1)
                    } else {
                        val name = file.getName().trimEnd('/')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in AUDIO_EXTENSIONS) found.add(Track(name.substringBeforeLast('.'), file.getURL().toString(), file.getParent() ?: base))
                    }
                    if (visited % 40 == 0) {
                        val count = found.size
                        runOnUiThread { statusText.text = "SCANNING • " + count + " TRACKS" }
                    }
                }
            }
            walk(base, 0)
            found.sortBy { it.title.lowercase() }
            tracks.clear()
            tracks.addAll(found)
            currentTrack = -1
            isScanning = false
            scanStatus = "Scan complete"
            runOnUiThread { updateStatus(); refreshScreen(); toast("Found " + found.size + " audio files in " + visited + " entries") }
        } catch (e: Exception) {
            isScanning = false
            scanStatus = "Scan failed"
            runOnUiThread { updateStatus(); refreshScreen(); toast("Scan failed at " + base + ": " + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    private fun playTrack(track: Track) {
        try {
            val ctx = smbContext ?: throw IllegalStateException("Connect to your SMB share first")
            currentTrack = tracks.indexOfFirst { it.path == track.path }.coerceAtLeast(0)
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

    override fun onDestroy() { player.release(); super.onDestroy() }

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
