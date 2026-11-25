package com.example.redwavetv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LibraryStore(context: Context) : SQLiteOpenHelper(context, "redwave-library.db", null, 5) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tracks (path TEXT PRIMARY KEY, title TEXT NOT NULL, folder TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, artwork TEXT NOT NULL)")
        db.execSQL("CREATE INDEX tracks_title_idx ON tracks(title COLLATE NOCASE)")
        db.execSQL("CREATE INDEX tracks_folder_idx ON tracks(folder)")
        db.execSQL("CREATE INDEX tracks_album_idx ON tracks(album)")
        db.execSQL("CREATE TABLE scan_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, depth INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX scan_queue_path_idx ON scan_queue(path)")
        db.execSQL("CREATE TABLE scan_state (id INTEGER PRIMARY KEY CHECK(id=1), base TEXT NOT NULL, status TEXT NOT NULL, visited INTEGER NOT NULL DEFAULT 0, discovered INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE favorites (path TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE play_history (path TEXT PRIMARY KEY, played_at INTEGER NOT NULL)")
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
    }
    override fun onConfigure(db: SQLiteDatabase) {\n        super.onConfigure(db)\n        // PRAGMA assignments return result rows on Android SQLite; execSQL rejects them.\n        db.rawQuery("PRAGMA cache_size=-2048", null).use { }\n        db.rawQuery("PRAGMA temp_store=FILE", null).use { }\n        db.rawQuery("PRAGMA mmap_size=0", null).use { }\n    }
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
        readableDatabase.rawQuery("SELECT CASE WHEN album != '' THEN album ELSE folder END AS grouping, COUNT(*) FROM tracks GROUP BY grouping ORDER BY grouping COLLATE NOCASE LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) result.add(Pair(c.getString(0), c.getLong(1)))
        }
        return result
    }
    fun tracksInGroup(group: String, limit: Int = 100, offset: Int = 0): List<Track> {
        val result = ArrayList<Track>(limit)
        readableDatabase.rawQuery("SELECT title,path,folder,artist,album,artwork FROM tracks WHERE (CASE WHEN album != '' THEN album ELSE folder END)=? ORDER BY title COLLATE NOCASE LIMIT ? OFFSET ?", arrayOf(group,limit.toString(),offset.toString())).use { c ->
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
    fun searchTracks(query: String, limit: Int = 100): List<Track> {
        val q = "%${query.trim()}%"
        return queryTrackRows("SELECT title,path,folder,artist,album,artwork FROM tracks WHERE title LIKE ? OR artist LIKE ? OR album LIKE ? OR folder LIKE ? ORDER BY title COLLATE NOCASE LIMIT ?", arrayOf(q,q,q,q,limit.toString()), limit)
    }
    fun loadRecent(limit: Int = 100): List<Track> = queryTrackRows("SELECT t.title,t.path,t.folder,t.artist,t.album,t.artwork FROM tracks t INNER JOIN play_history h ON h.path=t.path ORDER BY h.played_at DESC LIMIT ?", arrayOf(limit.toString()), limit)
    fun loadFavorites(limit: Int = 100): List<Track> = queryTrackRows("SELECT t.title,t.path,t.folder,t.artist,t.album,t.artwork FROM tracks t INNER JOIN favorites f ON f.path=t.path ORDER BY t.title COLLATE NOCASE LIMIT ?", arrayOf(limit.toString()), limit)
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
        readableDatabase.rawQuery("SELECT COUNT(*) FROM tracks", null).use { it.moveToFirst(); return it.getLong(0) }
    }
    fun loadTracks(limit: Int = 100, offset: Int = 0): List<Track> {
        val result = ArrayList<Track>(limit.coerceAtMost(5000))
        readableDatabase.query("tracks", arrayOf("path","title","folder","artist","album","artwork"), null, null, null, null, "title COLLATE NOCASE", "$limit OFFSET $offset").use { cursor ->
            while (cursor.moveToNext()) result.add(Track(cursor.getString(1),cursor.getString(0),cursor.getString(2),cursor.getString(3),cursor.getString(4),cursor.getString(5)))
        }
        return result
    }
}
