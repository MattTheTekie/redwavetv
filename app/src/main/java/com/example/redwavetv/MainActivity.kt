package com.example.redwavetv

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import android.net.Uri
import jcifs.smb.SmbFileInputStream
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties
import kotlin.concurrent.thread

private const val BG = 0xFF101014.toInt()
private const val PANEL = 0xFF1C1C23.toInt()
private const val RED = 0xFFF04455.toInt()
private const val WHITE = 0xFFF4F3F5.toInt()
private const val MUTED = 0xFFAAA8B2.toInt()

data class Track(val title: String, val path: String, val folder: String)

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var player: ExoPlayer
    private val tracks = mutableListOf<Track>()
    private var smbContext: CIFSContext? = null
    private var currentShare = ""
    private var currentTrack = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        player = ExoPlayer.Builder(this).build()
        buildShell()
        showHome()
    }

    private fun buildShell() {
        root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG); isFocusable = true; isFocusableInTouchMode = true }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 18, 20); setBackgroundColor(0xFF15151B.toInt()) }
        nav.addView(label("REDWAVE", 24, RED, true), LinearLayout.LayoutParams(-1, 64))
        listOf("HOME", "ALBUMS", "FOLDERS", "PLAY QUEUE", "SMB SETTINGS").forEachIndexed { i, s ->
            val b = button(s) { when (i) { 0 -> showHome(); 1 -> showAlbums(); 2 -> showFolders(); 3 -> showQueue(); else -> showSmbDialog() } }
            nav.addView(b, LinearLayout.LayoutParams(-1, 58).apply { bottomMargin = 8 })
        }
        root.addView(nav, LinearLayout.LayoutParams(220, -1))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 24, 30, 18) }
        root.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
    }

    private fun showHome() {
        content.removeAllViews()
        content.addView(label("Your music, everywhere.", 30, WHITE, true))
        content.addView(label(if (currentShare.isBlank()) "Connect to an SMB share to scan your library" else "Library: $currentShare  •  ${tracks.size} tracks", 15, MUTED), LinearLayout.LayoutParams(-1, 42))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("＋  ADD SMB SHARE") { showSmbDialog() })
        actions.addView(button("↻  RESCAN") { scanShare() })
        content.addView(actions)
        content.addView(label("RECENTLY DISCOVERED", 16, RED, true), LinearLayout.LayoutParams(-1, 48))
        if (tracks.isEmpty()) {
            val empty = label("No music scanned yet. Add your NAS/server share to begin.", 20, MUTED)
            empty.gravity = Gravity.CENTER
            content.addView(empty, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val list = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@MainActivity, 4); adapter = TrackAdapter(tracks) { playTrack(it) }; setHasFixedSize(true) }
            content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        addMiniPlayer()
    }

    private fun showAlbums() {
        content.removeAllViews(); content.addView(label("ALBUMS", 28, WHITE, true))
        content.addView(label("Album grouping uses folder names in this starter build. Embedded tag/artwork parsing is a planned extension.", 15, MUTED))
        val groups = tracks.groupBy { it.folder.substringAfterLast('/').ifBlank { "Unknown album" } }
        groups.forEach { (album, songs) ->
            val b = button("♫   $album     ·     ${songs.size} tracks") { showTrackList(album, songs) }
            content.addView(b, LinearLayout.LayoutParams(-1, 64))
        }
        addMiniPlayer()
    }

    private fun showFolders() {
        content.removeAllViews(); content.addView(label("FOLDERS", 28, WHITE, true))
        tracks.groupBy { it.folder }.forEach { (folder, songs) ->
            content.addView(button("▣  $folder   (${songs.size})") { showTrackList(folder, songs) }, LinearLayout.LayoutParams(-1, 58))
        }
        addMiniPlayer()
    }

    private fun showTrackList(title: String, songs: List<Track>) {
        content.removeAllViews(); content.addView(label(title, 26, WHITE, true))
        songs.forEach { t -> content.addView(button("▶   ${t.title}") { playTrack(t) }, LinearLayout.LayoutParams(-1, 54)) }
        addMiniPlayer()
    }

    private fun showQueue() {
        content.removeAllViews(); content.addView(label("PLAY QUEUE", 28, WHITE, true))
        tracks.forEach { t -> content.addView(button(t.title) { playTrack(t) }, LinearLayout.LayoutParams(-1, 52)) }
        addMiniPlayer()
    }

    private fun addMiniPlayer() {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12, 8, 12, 8); setBackgroundColor(PANEL) }
        val now = label(if (tracks.isEmpty()) "Nothing playing" else "${tracks.getOrNull(currentTrack)?.title ?: "Ready"}", 15, WHITE, true)
        bar.addView(now, LinearLayout.LayoutParams(0, 48, 1f))
        bar.addView(button("|◀") { player.seekToPrevious() })
        bar.addView(button(if (player.isPlaying) "Ⅱ" else "▶") { if (player.isPlaying) player.pause() else player.play(); showHome() })
        bar.addView(button("▶|") { player.seekToNext() })
        content.addView(bar, LinearLayout.LayoutParams(-1, 64))
    }

    private fun showSmbDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val host = field("Server IP or hostname")
        val share = field("Share name (e.g. Music)")
        val user = field("Username (optional)")
        val pass = field("Password (optional)").apply { inputType = 129 }
        val path = field("Subfolder (optional, e.g. Library)")
        listOf(host, share, user, pass, path).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("Connect to SMB2 / SMB3").setView(box)
            .setNegativeButton("CANCEL", null).setPositiveButton("CONNECT & SCAN") { _, _ ->
                val server = host.text.toString().trim(); val sh = share.text.toString().trim()
                if (server.isNotEmpty() && sh.isNotEmpty()) {
                    val sub = path.text.toString().trim().trim('/')
                    currentShare = "smb://$server/$sh/" + if (sub.isEmpty()) "" else "$sub/"
                    connectAndScan(server, user.text.toString(), pass.text.toString())
                }
            }.show()
    }

    private fun field(hintText: String): EditText = EditText(this).apply { hint = hintText; setSingleLine(true); setTextColor(WHITE); setHintTextColor(MUTED); textSize = 16f }

    private fun connectAndScan(server: String, username: String, password: String) {
        thread {
            try {
                val props = Properties().apply { setProperty("jcifs.smb.client.minVersion", "SMB210"); setProperty("jcifs.smb.client.maxVersion", "SMB311") }
                var ctx: CIFSContext = BaseContext(PropertyConfiguration(props))
                if (username.isNotBlank()) ctx = ctx.withCredentials(NtlmPasswordAuthenticator("", username, password))
                smbContext = ctx
                runOnUiThread { showHome() }
                scanShare()
            } catch (e: Exception) { runOnUiThread { toast("SMB connection failed: ${e.message}") } }
        }
    }

    private fun scanShare() {
        val ctx = smbContext ?: run { showSmbDialog(); return }
        val base = currentShare
        thread {
            val found = mutableListOf<Track>()
            try {
                fun walk(url: String, depth: Int) {
                    if (depth > 20) return
                    val dir = SmbFile(url, ctx)
                    for (f in dir.listFiles()) {
                        if (f.isDirectory) walk(f.url.toString(), depth + 1)
                        else if (f.name.lowercase().let { it.endsWith(".mp3") || it.endsWith(".flac") || it.endsWith(".m4a") || it.endsWith(".aac") || it.endsWith(".ogg") || it.endsWith(".wav") || it.endsWith(".opus") }) {
                            found.add(Track(f.name.substringBeforeLast('.'), f.url.toString(), f.parent ?: base))
                        }
                    }
                }
                walk(base, 0)
                found.sortBy { it.title.lowercase() }
                tracks.clear(); tracks.addAll(found)
                runOnUiThread { showHome(); toast("Found ${found.size} audio files") }
            } catch (e: Exception) { runOnUiThread { toast("Scan failed: ${e.message}") } }
        }
    }

    private fun playTrack(track: Track) {
        try {
            val idx = tracks.indexOfFirst { it.path == track.path }.coerceAtLeast(0); currentTrack = idx
            val ctx = smbContext ?: throw IllegalStateException("SMB session is not connected")
            val factory = object : DataSource.Factory {
                override fun createDataSource(): DataSource = SmbStreamDataSource(ctx)
            }
            val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(track.path))
            player.setMediaSource(source); player.prepare(); player.play()
            showHome()
        } catch (e: Exception) { toast("Playback error: ${e.message}") }
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text; isFocusable = true; isFocusableInTouchMode = true; setTextColor(WHITE); textSize = 13f; setBackgroundColor(PANEL)
        setOnFocusChangeListener { v, has -> v.setBackgroundColor(if (has) RED else PANEL) }
        setOnClickListener { action() }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); return true }
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
                if (skipped <= 0) { if (stream!!.read() == -1) break else remaining-- } else remaining -= skipped
            }
            val length = file.length() - dataSpec.position
            return if (dataSpec.length != -1L) minOf(length, dataSpec.length) else length
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream?.read(buffer, offset, length) ?: -1
        override fun getUri(): Uri? = uri
        override fun close() { try { stream?.close() } finally { stream = null; uri = null } }
    }

    private inner class TrackAdapter(private val items: List<Track>, private val click: (Track) -> Unit) : RecyclerView.Adapter<TrackVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackVH {
            val v = TextView(parent.context).apply { setPadding(18, 18, 18, 18); textSize = 16f; setTextColor(WHITE); setBackgroundColor(PANEL); isFocusable = true; isFocusableInTouchMode = true; setOnFocusChangeListener { view, focus -> view.setBackgroundColor(if (focus) RED else PANEL) } }
            return TrackVH(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: TrackVH, position: Int) { holder.text.text = "♫\n${items[position].title}"; holder.text.setOnClickListener { click(items[position]) } }
    }
    private class TrackVH(val text: TextView) : RecyclerView.ViewHolder(text)
}
