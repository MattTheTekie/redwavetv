package com.example.redwavetv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LibraryStore(context: Context) : SQLiteOpenHelper(context, "redwave-library.db", null, 7) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tracks (path TEXT PRIMARY KEY, title TEXT NOT NULL, folder TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, artwork TEXT NOT NULL)")
        db.execSQL("CREATE INDEX tracks_title_idx ON tracks(title COLLATE NOCASE)")
        db.execSQL("CREATE INDEX tracks_folder_idx ON tracks(folder)")
        db.execSQL("CREATE INDEX tracks_album_idx ON tracks(album COLLATE NOCASE)")
        db.execSQL("CREATE INDEX tracks_artist_idx ON tracks(artist COLLATE NOCASE)")
        db.execSQL("CREATE INDEX tracks_folder_nocase_idx ON tracks(folder COLLATE NOCASE)")
        db.execSQL("CREATE TABLE scan_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, depth INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX scan_queue_path_idx ON scan_queue(path)")
        db.execSQL("CREATE TABLE scan_state (id INTEGER PRIMARY KEY CHECK(id=1), base TEXT NOT NULL, status TEXT NOT NULL, visited INTEGER NOT NULL DEFAULT 0, discovered INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE favorites (path TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE play_history (path TEXT PRIMARY KEY, played_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE hidden_folders (path TEXT PRIMARY KEY)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS scan_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, depth INTEGER NOT NULL)"); if (oldVersion < 3) db.execSQL("CREATE TABLE IF NOT EXISTS scan_state (id INTEGER PRIMARY KEY CHECK(id=1), base TEXT NOT NULL, status TEXT NOT NULL, visited INTEGER NOT NULL DEFAULT 0, discovered INTEGER NOT NULL DEFAULT 0)")
        if (oldVersion < 4) {
            db.execSQL("DELETE FROM scan_queue WHERE id NOT IN (SELECT MIN(id) FROM scan_queue GROUP BY path)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS scan_queue_path_idx ON scan_queue(path)")
        }
        if (oldVersion < 5) {
            db.execSQL("CREATE TABLE IF NOT EXISTS favorites (path TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE IF NOT EXISTS play_history (path TEXT PRIMARY KEY, played_at INTEGER NOT NULL)")
        }
        if (oldVersion < 6) {
            db.execSQL("CREATE INDEX IF NOT EXISTS tracks_artist_idx ON tracks(artist COLLATE NOCASE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS tracks_album_nocase_idx ON tracks(album COLLATE NOCASE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS tracks_folder_nocase_idx ON tracks(folder COLLATE NOCASE)")
        }
        if (oldVersion < 7) db.execSQL("CREATE TABLE IF NOT EXISTS hidden_folders (path TEXT PRIMARY KEY)")
    }
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // PRAGMA assignments return result rows on Android SQLite; execSQL rejects them.
        db.rawQuery("PRAGMA cache_size=-2048", null).use { }
        db.rawQuery("PRAGMA temp_store=FILE", null).use { }
        db.rawQuery("PRAGMA mmap_size=0", null).use { }
    }
    @Synchronized fun hideFolder(path: String) {
        val normalized = path.trimEnd('/') + "/"
        val values = ContentValues().apply { put("path", normalized) }
        writableDatabase.insertWithOnConflict("hidden_folders", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun unhideFolder(path: String) {
        writableDatabase.delete("hidden_folders", "path=?", arrayOf(path.trimEnd('/') + "/"))
    }
    fun hiddenFolders(): List<String> {
        val result = ArrayList<String>()
        readableDatabase.rawQuery("SELECT path FROM hidden_folders ORDER BY path COLLATE NOCASE", null).use { c -> while (c.moveToNext()) result.add(c.getString(0)) }
        return result
    }
    private fun visibleFolderSql(column: String = "folder"): String =
        "NOT EXISTS (SELECT 1 FROM hidden_folders hf WHERE lower(substr($column,1,length(rtrim(hf.path,'/'))))=lower(rtrim(hf.path,'/')) AND (length($column)=length(rtrim(hf.path,'/')) OR substr($column,length(rtrim(hf.path,'/'))+1,1)='/'))"

    @Synchronized fun startOrResumeScan(base: String): Boolean {
        val db = writableDatabase
        val previous = db.rawQuery("SELECT base,status FROM scan_state WHERE id=1", null).use { c -> if (c.moveToFirst()) Pair(c.getString(0), c.getString(1)) else null }
        val queued = db.rawQuery("SELECT COUNT(*) FROM scan_queue", null).use { c -> c.moveToFirst(); c.getLong(0) }
        val resume = previous?.first == base && previous.second != "COMPLETE" && queued > 0
        if (!resume) { db.delete("scan_queue", null, null); enqueueDirectory(base, 0) }
        val values = ContentValues().apply { put("id", 1); put("base", base); put("status", "RUNNING") }
        db.insertWithOnConflict("scan_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return resume
    }
    @Synchronized fun updateScanProgress(visited: Long, discovered: Long) { val v = ContentValues().apply { put("visited", visited); put("discovered", discovered) }; writableDatabase.update("scan_state", v, "id=1", null) }
    @Synchronized fun finishScan(status: String, visited: Long, discovered: Long) { updateScanProgress(visited, discovered); val v = ContentValues().apply { put("status", status) }; writableDatabase.update("scan_state", v, "id=1", null); if (status == "COMPLETE") clearScanQueue() }
    fun scanProgress(): Pair<Long, Long> = readableDatabase.rawQuery("SELECT visited,discovered FROM scan_state WHERE id=1", null).use { c -> if (c.moveToFirst()) Pair(c.getLong(0),c.getLong(1)) else Pair(0L,0L) }
    fun scanSummary(): String? = readableDatabase.rawQuery("SELECT status,visited,discovered FROM scan_state WHERE id=1", null).use { c -> if (c.moveToFirst()) "${c.getString(0)} • ${c.getLong(2)} tracks • ${c.getLong(1)} entries" else null }
    @Synchronized fun clearScanQueue() { writableDatabase.delete("scan_queue", null, null) }
    @Synchronized fun enqueueDirectory(path: String, depth: Int) { val v = ContentValues().apply { put("path", path); put("depth", depth) }; writableDatabase.insertWithOnConflict("scan_queue", null, v, SQLiteDatabase.CONFLICT_IGNORE) }
    @Synchronized fun dequeueDirectory(): Pair<String, Int>? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val item = db.rawQuery("SELECT id,path,depth FROM scan_queue ORDER BY id DESC LIMIT 1", null).use { c -> if (c.moveToFirst()) Triple(c.getLong(0), c.getString(1), c.getInt(2)) else null } ?: run { db.setTransactionSuccessful(); return null }
            // Claim the directory exactly once. The previous implementation left it in
            // the queue until completeDirectory(), so dequeueDirectory() returned it
            // forever and the scanner repeatedly traversed the same directory.
            db.delete("scan_queue", "id=?", arrayOf(item.first.toString()))
            db.setTransactionSuccessful()
            return Pair(item.second, item.third)
        } finally { db.endTransaction() }
    }
    @Synchronized fun completeDirectory(path: String) { writableDatabase.delete("scan_queue", "id=(SELECT id FROM scan_queue WHERE path=? ORDER BY id LIMIT 1)", arrayOf(path)) }
    fun clearTracks() { writableDatabase.delete("tracks", null, null) }
    fun upsertBatch(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement("INSERT OR REPLACE INTO tracks(path,title,folder,artist,album,artwork) VALUES(?,?,?,?,?,?)")
            tracks.forEach { track ->
                statement.clearBindings()
                statement.bindString(1, track.path)
                statement.bindString(2, track.title)
                statement.bindString(3, track.folder)
                statement.bindString(4, track.artist)
                statement.bindString(5, track.album)
                statement.bindString(6, track.artworkPath)
                statement.executeInsert()
            }
            statement.close()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun loadAlbumGroups(limit: Int = 60): List<Pair<String, Long>> {
        val result = ArrayList<Pair<String, Long>>(limit)
        readableDatabase.rawQuery("SELECT CASE WHEN album != '' THEN album ELSE folder END AS grouping, COUNT(*) FROM tracks WHERE ${visibleFolderSql()} GROUP BY grouping ORDER BY grouping COLLATE NOCASE LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) result.add(Pair(c.getString(0), c.getLong(1)))
        }
        return result
    }
    fun tracksInGroup(group: String, limit: Int = 100, offset: Int = 0): List<Track> {
        val result = ArrayList<Track>(limit)
        readableDatabase.rawQuery("SELECT title,path,folder,artist,album,artwork FROM tracks WHERE ${visibleFolderSql()} AND (CASE WHEN album != '' THEN album ELSE folder END)=? ORDER BY title COLLATE NOCASE LIMIT ? OFFSET ?", arrayOf(group,limit.toString(),offset.toString())).use { c ->
            while (c.moveToNext()) result.add(Track(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)))
        }
        return result
    }
    @Synchronized fun saveFavorite(track: Track, favorite: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val trackValues = ContentValues().apply {
                put("path", track.path); put("title", track.title); put("folder", track.folder)
                put("artist", track.artist); put("album", track.album); put("artwork", track.artworkPath)
            }
            db.insertWithOnConflict("tracks", null, trackValues, SQLiteDatabase.CONFLICT_REPLACE)
            if (favorite) {
                val favoriteValues = ContentValues().apply { put("path", track.path) }
                db.insertWithOnConflict("favorites", null, favoriteValues, SQLiteDatabase.CONFLICT_REPLACE)
            } else {
                db.delete("favorites", "path=?", arrayOf(track.path))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun setFavorite(path: String, favorite: Boolean) {
        if (favorite) {
            val values = ContentValues().apply { put("path", path) }
            writableDatabase.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } else writableDatabase.delete("favorites", "path=?", arrayOf(path))
    }
    fun isFavorite(path: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM favorites WHERE path=? LIMIT 1", arrayOf(path)).use { it.moveToFirst() }
    @Synchronized fun recordPlay(path: String) {
        val values = ContentValues().apply { put("path", path); put("played_at", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("play_history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun searchTracks(query: String, limit: Int = 100, offset: Int = 0): List<Track> {
        val q = "%" + query.trim() + "%"
        // Contains matching finds terms inside titles, artists, albums and folders.
        // It runs on the search executor and is paged so the UI never loads the whole library.
        return queryTrackRows(
            "SELECT title,path,folder,artist,album,artwork FROM tracks WHERE path IN (SELECT MIN(path) FROM tracks WHERE ${visibleFolderSql()} AND (title LIKE ? OR artist LIKE ? OR album LIKE ? OR folder LIKE ?) GROUP BY lower(trim(title)), lower(trim(artist)), lower(trim(album)), lower(rtrim(folder, '/'))) ORDER BY title COLLATE NOCASE LIMIT ? OFFSET ?",
            arrayOf(q,q,q,q,limit.toString(),offset.toString()), limit
        )
    }
    fun loadRecent(limit: Int = 100): List<Track> = queryTrackRows("SELECT t.title,t.path,t.folder,t.artist,t.album,t.artwork FROM tracks t INNER JOIN play_history h ON h.path=t.path WHERE ${visibleFolderSql("t.folder")} ORDER BY h.played_at DESC LIMIT ?", arrayOf(limit.toString()), limit)
    fun loadTrack(path: String): Track? = queryTrackRows("SELECT title,path,folder,artist,album,artwork FROM tracks WHERE path=? LIMIT 1", arrayOf(path), 1).firstOrNull()
    fun loadFavorites(limit: Int = 100): List<Track> = queryTrackRows("SELECT t.title,t.path,t.folder,t.artist,t.album,t.artwork FROM tracks t INNER JOIN favorites f ON f.path=t.path WHERE ${visibleFolderSql("t.folder")} ORDER BY t.title COLLATE NOCASE LIMIT ?", arrayOf(limit.toString()), limit)
    fun loadRandomTracks(limit: Int = 300, excludePath: String = ""): List<Track> {
        if (limit <= 0) return emptyList()
        val maxRowId = readableDatabase.rawQuery("SELECT COALESCE(MAX(rowid),0) FROM tracks", null).use { c -> c.moveToFirst(); c.getLong(0) }
        if (maxRowId <= 0L) return emptyList()
        // Jump to a random rowid instead of ORDER BY RANDOM(), which sorts the entire
        // 500k-track library every time Home opens or playback needs another batch.
        val start = kotlin.random.Random.nextLong(1L, maxRowId + 1L)
        val fields = "SELECT title,path,folder,artist,album,artwork FROM tracks WHERE ${visibleFolderSql()} AND "
        val exclude = if (excludePath.isBlank()) "" else "path<>? AND "
        val args1 = if (excludePath.isBlank()) arrayOf(start.toString(), limit.toString()) else arrayOf(excludePath, start.toString(), limit.toString())
        val first = queryTrackRows(fields + exclude + "rowid>=? ORDER BY rowid LIMIT ?", args1, limit)
        if (first.size >= limit) return first
        val remaining = limit - first.size
        val args2 = if (excludePath.isBlank()) arrayOf(start.toString(), remaining.toString()) else arrayOf(excludePath, start.toString(), remaining.toString())
        val second = queryTrackRows(fields + exclude + "rowid<? ORDER BY rowid LIMIT ?", args2, remaining)
        // Keep one representative of each same-folder track identity in random
        // selections too, matching the Library and Search duplicate policy.
        return (first + second).distinctBy {
            listOf(it.title.trim().lowercase(), it.artist.trim().lowercase(), it.album.trim().lowercase(), it.folder.trimEnd('/').lowercase())
        }.take(limit)
    }
    fun loadTracksInFolder(folder: String, limit: Int = 1000): List<Track> =
        queryTrackRows("SELECT title,path,folder,artist,album,artwork FROM tracks WHERE ${visibleFolderSql()} AND folder=? ORDER BY title COLLATE NOCASE LIMIT ?", arrayOf(folder, limit.toString()), limit)
    private fun queryTrackRows(sql: String, args: Array<String>, limit: Int): List<Track> {
        val result = ArrayList<Track>(limit.coerceAtMost(500))
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) result.add(Track(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5))) }
        return result
    }
    @Synchronized fun updateTrackMetadata(track: Track) {
        val values = ContentValues().apply { put("title", track.title); put("artist", track.artist); put("album", track.album); put("artwork", track.artworkPath) }
        writableDatabase.update("tracks", values, "path=?", arrayOf(track.path))
    }
    fun replaceTracks(tracks: List<Track>) {
        clearTracks()
        upsertBatch(tracks)
    }
    fun countTracks(): Long {
        // This value is used by the Home screen and status bar. A GROUP BY over
        // every metadata column makes startup crawl on 500k-song libraries.
        // Count indexed rows here; deduplication remains in search and playback views.
        readableDatabase.rawQuery("SELECT COUNT(*) FROM tracks WHERE ${visibleFolderSql()}", null).use { it.moveToFirst(); return it.getLong(0) }
    }
    fun loadTracks(limit: Int = 100, offset: Int = 0): List<Track> {
        val safeLimit = limit.coerceIn(1, 5000)
        val safeOffset = offset.coerceAtLeast(0)
        // Use the title index for a quick preview. The previous DISTINCT/GROUP BY
        // expression forced a full 500k-row grouping operation even for 8 Home rows.
        // The scanner can encounter the same SMB audio item through path aliases.
        // Match the identity rule already used by search: same title/artist/album in
        // the same folder is one library entry, while identical songs in other folders
        // remain separate.
        // Keep this page query index-friendly. Grouping half a million rows by
        // several text expressions on every tab open can freeze low-memory TV boxes.
        // De-duplicate the fetched page in memory; scanner path identity prevents
        // repeated insertion of the same SMB object.
        val rows = queryTrackRows(
            "SELECT title,path,folder,artist,album,artwork FROM tracks WHERE ${visibleFolderSql()} ORDER BY title COLLATE NOCASE LIMIT ? OFFSET ?",
            arrayOf((safeLimit * 3).coerceAtMost(15000).toString(), safeOffset.toString()), (safeLimit * 3).coerceAtMost(15000)
        )
        return rows.distinctBy {
            listOf(it.title.trim().lowercase(), it.artist.trim().lowercase(), it.album.trim().lowercase(), it.folder.trimEnd('/').lowercase())
        }.take(safeLimit)
    }
}
