package com.example.redwavetv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LibraryStore(context: Context) : SQLiteOpenHelper(context, "redwave-library.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tracks (path TEXT PRIMARY KEY, title TEXT NOT NULL, folder TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, artwork TEXT NOT NULL)")
        db.execSQL("CREATE INDEX tracks_title_idx ON tracks(title COLLATE NOCASE)")
        db.execSQL("CREATE INDEX tracks_folder_idx ON tracks(folder)")
        db.execSQL("CREATE INDEX tracks_album_idx ON tracks(album)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
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
