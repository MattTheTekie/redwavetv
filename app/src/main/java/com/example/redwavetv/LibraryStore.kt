package com.example.redwavetv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LibraryStore(context: Context) : SQLiteOpenHelper(context, "redwave-library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tracks (path TEXT PRIMARY KEY, title TEXT NOT NULL, folder TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, artwork TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun replaceTracks(tracks: List<Track>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("tracks", null, null)
            tracks.forEach { track ->
                val values = ContentValues().apply {
                    put("path", track.path)
                    put("title", track.title)
                    put("folder", track.folder)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("artwork", track.artworkPath)
                }
                db.insertWithOnConflict("tracks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun loadTracks(): List<Track> {
        val result = mutableListOf<Track>()
        val cursor = readableDatabase.query("tracks", arrayOf("path", "title", "folder", "artist", "album", "artwork"), null, null, null, null, "title COLLATE NOCASE")
        cursor.use {
            while (it.moveToNext()) {
                result.add(Track(it.getString(1), it.getString(0), it.getString(2), it.getString(3), it.getString(4), it.getString(5)))
            }
        }
        return result
    }
}
