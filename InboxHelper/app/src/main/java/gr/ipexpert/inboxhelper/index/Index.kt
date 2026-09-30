package gr.ipexpert.inboxhelper.index

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Unified communication item: one Outlook message, Teams message, calendar event, phone message or note.
 * This is the knowledge/index layer. Microsoft 365 stays the source of truth; this is a searchable cache
 * of metadata + text used for retrieval (RAG), cross-channel context and search.
 */
data class Item(
    val id: String,                 // "<source>:<externalId>"
    val source: String,
    val externalId: String,
    val threadKey: String,          // conversation this item belongs to
    val subject: String,
    val senderName: String,
    val senderAddr: String,
    val recipients: String,         // comma-separated addresses / names (to + cc)
    val fromMe: Boolean,
    val time: Long,
    val body: String,
    val hasAttachments: Boolean = false,
    val attachmentNames: String = "",
    val webLink: String = "",
    val folder: String = "",
    val extra: String = "",         // JSON with source-specific ids (chatId, teamId, channelId, rootId, to, cc)
) {
    fun extraJson(): JSONObject = try { JSONObject(extra.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
}

data class SyncState(
    val key: String,
    val delta: String = "",         // nextLink (in progress) or deltaLink (done)
    val lastSync: Long = 0,
    val status: String = "idle",    // idle | syncing | healthy | error | disabled | throttled
    val error: String = "",
    val discovered: Int = 0,
    val indexed: Int = 0,
    val failed: Int = 0,
    val extra: String = "",         // JSON: per-chat/channel checkpoints etc.
) {
    fun extraJson(): JSONObject = try { JSONObject(extra.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
}

object Text {
    private val nonWord = Regex("[^\\p{L}\\p{N}@._-]+")
    private val marks = Regex("\\p{Mn}+")

    /** Lower-case, strip accents (Greek τόνοι too), keep letters/digits: used for both indexing and queries. */
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(marks, "").replace(nonWord, " ").trim()

    private val stop = setOf(
        "the", "and", "for", "you", "your", "are", "with", "that", "this", "have", "from", "will", "can", "please", "thanks",
        "thank", "regards", "best", "hello", "dear", "would", "could", "about", "there", "their", "what", "when", "which",
        "also", "just", "been", "was", "were", "they", "them", "our", "out", "not", "but", "all", "any", "has", "had",
        "και", "για", "που", "στο", "στη", "στην", "στον", "την", "τον", "της", "του", "των", "τις", "τους", "μια", "ενα",
        "απο", "οτι", "θα", "να", "με", "σε", "δεν", "ειναι", "εχω", "εχει", "αν", "ως", "πως", "καλημερα", "ευχαριστω", "γεια",
    )

    fun terms(s: String): List<String> = norm(s).split(' ').filter { it.length >= 3 && it !in stop && !it.all { c -> c.isDigit() } }

    /** Strips HTML (Teams bodies) to readable text. */
    fun stripHtml(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n").trim()
}

class IndexDb(ctx: Context) : SQLiteOpenHelper(ctx, "index.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE items(
            id TEXT UNIQUE NOT NULL, source TEXT, external_id TEXT, thread_key TEXT, subject TEXT,
            sender_name TEXT, sender_addr TEXT, recipients TEXT, from_me INTEGER, time INTEGER, body TEXT,
            has_att INTEGER, att_names TEXT, web_link TEXT, folder TEXT, extra TEXT)""")
        db.execSQL("CREATE INDEX idx_items_thread ON items(thread_key, time)")
        db.execSQL("CREATE INDEX idx_items_time ON items(time)")
        db.execSQL("CREATE INDEX idx_items_sender ON items(sender_addr)")
        db.execSQL("CREATE INDEX idx_items_source ON items(source, folder)")
        // Full-text index on normalised text. docid = items.rowid.
        db.execSQL("CREATE VIRTUAL TABLE items_fts USING fts4(norm)")
        db.execSQL("CREATE TABLE entities(item_id TEXT, type TEXT, name TEXT, norm TEXT)")
        db.execSQL("CREATE INDEX idx_ent_norm ON entities(norm)")
        db.execSQL("CREATE INDEX idx_ent_item ON entities(item_id)")
        db.execSQL("""CREATE TABLE sync_state(key TEXT PRIMARY KEY, delta TEXT, last_sync INTEGER, status TEXT, error TEXT,
            discovered INTEGER, indexed INTEGER, failed INTEGER, extra TEXT)""")
        db.execSQL("CREATE TABLE sync_failures(key TEXT, ref TEXT, error TEXT, attempts INTEGER, time INTEGER, PRIMARY KEY(key, ref))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

object Index {
    private lateinit var helper: IndexDb
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun init(ctx: Context) { helper = IndexDb(ctx.applicationContext) }

    // ------------------------------------------------------------------ items

    private fun cv(i: Item) = ContentValues().apply {
        put("id", i.id); put("source", i.source); put("external_id", i.externalId); put("thread_key", i.threadKey)
        put("subject", i.subject); put("sender_name", i.senderName); put("sender_addr", i.senderAddr.lowercase(Locale.ROOT))
        put("recipients", i.recipients.lowercase(Locale.ROOT)); put("from_me", if (i.fromMe) 1 else 0); put("time", i.time)
        put("body", i.body); put("has_att", if (i.hasAttachments) 1 else 0); put("att_names", i.attachmentNames)
        put("web_link", i.webLink); put("folder", i.folder); put("extra", i.extra)
    }

    private fun row(c: Cursor) = Item(
        id = c.getString(0), source = c.getString(1) ?: "", externalId = c.getString(2) ?: "", threadKey = c.getString(3) ?: "",
        subject = c.getString(4) ?: "", senderName = c.getString(5) ?: "", senderAddr = c.getString(6) ?: "",
        recipients = c.getString(7) ?: "", fromMe = c.getInt(8) == 1, time = c.getLong(9), body = c.getString(10) ?: "",
        hasAttachments = c.getInt(11) == 1, attachmentNames = c.getString(12) ?: "", webLink = c.getString(13) ?: "",
        folder = c.getString(14) ?: "", extra = c.getString(15) ?: "",
    )

    private const val COLS = "id, source, external_id, thread_key, subject, sender_name, sender_addr, recipients, from_me, time, body, has_att, att_names, web_link, folder, extra"

    /** Inserts or updates an item and its full-text/entity rows. Returns true when it was new. */
    fun upsert(i: Item): Boolean {
        val d = db
        d.beginTransaction()
        try {
            val existing = d.rawQuery("SELECT rowid FROM items WHERE id = ?", arrayOf(i.id)).use { if (it.moveToFirst()) it.getLong(0) else -1L }
            val rowid: Long
            if (existing >= 0) {
                d.update("items", cv(i), "rowid = ?", arrayOf(existing.toString()))
                rowid = existing
            } else {
                rowid = d.insert("items", null, cv(i))
            }
            val norm = Text.norm(listOf(i.subject, i.senderName, i.senderAddr, i.recipients, i.attachmentNames, i.body).joinToString(" "))
            d.execSQL("INSERT OR REPLACE INTO items_fts(docid, norm) VALUES(?, ?)", arrayOf<Any>(rowid, norm))
            d.delete("entities", "item_id = ?", arrayOf(i.id))
            Entities.extract(i).forEach { e ->
                d.insert("entities", null, ContentValues().apply { put("item_id", i.id); put("type", e.type); put("name", e.name); put("norm", Text.norm(e.name)) })
            }
            d.setTransactionSuccessful()
            return existing < 0
        } finally {
            d.endTransaction()
        }
    }

    fun delete(id: String) {
        val d = db
        val rowid = d.rawQuery("SELECT rowid FROM items WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        if (rowid < 0) return
        d.delete("items", "rowid = ?", arrayOf(rowid.toString()))
        d.execSQL("DELETE FROM items_fts WHERE docid = ?", arrayOf<Any>(rowid))
        d.delete("entities", "item_id = ?", arrayOf(id))
    }

    fun get(id: String): Item? = db.rawQuery("SELECT $COLS FROM items WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) row(it) else null }

    fun thread(threadKey: String, limit: Int = 60): List<Item> =
        db.rawQuery("SELECT $COLS FROM items WHERE thread_key = ? ORDER BY time DESC LIMIT $limit", arrayOf(threadKey)).use { c ->
            val out = mutableListOf<Item>(); while (c.moveToNext()) out += row(c); out.reversed()
        }

    /** Thread keys with activity since [since], newest first. */
    fun activeThreads(since: Long, sources: List<String>): List<Pair<String, Long>> {
        if (sources.isEmpty()) return emptyList()
        val q = sources.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT thread_key, MAX(time) t FROM items WHERE time >= ? AND source IN ($q) GROUP BY thread_key ORDER BY t DESC LIMIT 400",
            arrayOf(since.toString()) + sources.toTypedArray(),
        ).use { c -> val out = mutableListOf<Pair<String, Long>>(); while (c.moveToNext()) out += c.getString(0) to c.getLong(1); out }
    }

    fun byIds(ids: Collection<String>): List<Item> {
        if (ids.isEmpty()) return emptyList()
        val q = ids.joinToString(",") { "?" }
        return db.rawQuery("SELECT $COLS FROM items WHERE id IN ($q)", ids.toTypedArray()).use { c ->
            val out = mutableListOf<Item>(); while (c.moveToNext()) out += row(c); out
        }
    }

    fun byRowids(rowids: Collection<Long>): List<Item> {
        if (rowids.isEmpty()) return emptyList()
        return db.rawQuery("SELECT $COLS FROM items WHERE rowid IN (${rowids.joinToString(",")})", null).use { c ->
            val out = mutableListOf<Item>(); while (c.moveToNext()) out += row(c); out
        }
    }

    /** Full-text query (FTS4 syntax, terms already normalised). Returns rowids. */
    fun fts(match: String, limit: Int = 300): List<Long> = try {
        db.rawQuery("SELECT docid FROM items_fts WHERE items_fts MATCH ? LIMIT $limit", arrayOf(match)).use { c ->
            val out = mutableListOf<Long>(); while (c.moveToNext()) out += c.getLong(0); out
        }
    } catch (_: Exception) { emptyList() }

    fun docFreq(term: String): Int = try {
        db.rawQuery("SELECT count(*) FROM items_fts WHERE items_fts MATCH ?", arrayOf(term)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (_: Exception) { 0 }

    fun byParticipant(addr: String, limit: Int = 80): List<Item> {
        val a = addr.lowercase(Locale.ROOT)
        if (a.isBlank()) return emptyList()
        return db.rawQuery("SELECT $COLS FROM items WHERE sender_addr = ? OR recipients LIKE ? ORDER BY time DESC LIMIT $limit", arrayOf(a, "%$a%")).use { c ->
            val out = mutableListOf<Item>(); while (c.moveToNext()) out += row(c); out
        }
    }

    fun entityItems(normName: String, limit: Int = 60): List<String> =
        db.rawQuery("SELECT DISTINCT item_id FROM entities WHERE norm = ? LIMIT $limit", arrayOf(normName)).use { c ->
            val out = mutableListOf<String>(); while (c.moveToNext()) out += c.getString(0); out
        }

    fun entitiesFor(itemIds: Collection<String>): List<Pair<String, String>> {
        if (itemIds.isEmpty()) return emptyList()
        val q = itemIds.joinToString(",") { "?" }
        return db.rawQuery("SELECT type, name FROM entities WHERE item_id IN ($q)", itemIds.toTypedArray()).use { c ->
            val out = mutableListOf<Pair<String, String>>(); while (c.moveToNext()) out += c.getString(0) to c.getString(1); out
        }
    }

    /** Most frequent entities overall (for the knowledge screen). */
    fun topEntities(limit: Int = 60): List<Triple<String, String, Int>> =
        db.rawQuery("SELECT type, name, count(DISTINCT item_id) n FROM entities GROUP BY norm, type ORDER BY n DESC LIMIT $limit", null).use { c ->
            val out = mutableListOf<Triple<String, String, Int>>(); while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), c.getInt(2)); out
        }

    fun count(source: String? = null, folder: String? = null): Int {
        val where = mutableListOf<String>(); val args = mutableListOf<String>()
        if (source != null) { where += "source = ?"; args += source }
        if (folder != null) { where += "folder = ?"; args += folder }
        val sql = "SELECT count(*) FROM items" + if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        return db.rawQuery(sql, args.toTypedArray()).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun countSince(source: String, folder: String, since: Long): Int =
        db.rawQuery("SELECT count(*) FROM items WHERE source = ? AND folder = ? AND time >= ?", arrayOf(source, folder, since.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun deleteSource(source: String) {
        val d = db
        d.execSQL("DELETE FROM items_fts WHERE docid IN (SELECT rowid FROM items WHERE source = ?)", arrayOf<Any>(source))
        d.execSQL("DELETE FROM entities WHERE item_id IN (SELECT id FROM items WHERE source = ?)", arrayOf<Any>(source))
        d.delete("items", "source = ?", arrayOf(source))
    }

    fun deleteOlderThan(cutoff: Long) {
        val d = db
        d.execSQL("DELETE FROM items_fts WHERE docid IN (SELECT rowid FROM items WHERE time < ?)", arrayOf<Any>(cutoff))
        d.execSQL("DELETE FROM entities WHERE item_id IN (SELECT id FROM items WHERE time < ?)", arrayOf<Any>(cutoff))
        d.delete("items", "time < ?", arrayOf(cutoff.toString()))
    }

    // ------------------------------------------------------------------ sync state

    fun state(key: String): SyncState = db.rawQuery(
        "SELECT key, delta, last_sync, status, error, discovered, indexed, failed, extra FROM sync_state WHERE key = ?", arrayOf(key),
    ).use { c ->
        if (!c.moveToFirst()) SyncState(key) else SyncState(
            c.getString(0), c.getString(1) ?: "", c.getLong(2), c.getString(3) ?: "idle", c.getString(4) ?: "",
            c.getInt(5), c.getInt(6), c.getInt(7), c.getString(8) ?: "",
        )
    }

    fun saveState(s: SyncState) {
        db.insertWithOnConflict("sync_state", null, ContentValues().apply {
            put("key", s.key); put("delta", s.delta); put("last_sync", s.lastSync); put("status", s.status); put("error", s.error)
            put("discovered", s.discovered); put("indexed", s.indexed); put("failed", s.failed); put("extra", s.extra)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allStates(): List<SyncState> = db.rawQuery("SELECT key FROM sync_state", null).use { c ->
        val keys = mutableListOf<String>(); while (c.moveToNext()) keys += c.getString(0); keys
    }.map { state(it) }

    fun clearStates(prefix: String) { db.delete("sync_state", "key LIKE ?", arrayOf("$prefix%")) }

    fun addFailure(key: String, ref: String, error: String) {
        // Plain INSERT/UPDATE (UPSERT syntax needs SQLite 3.24, newer than Android 8–10 ship).
        val now = System.currentTimeMillis()
        val updated = db.compileStatement("UPDATE sync_failures SET attempts = attempts + 1, error = ?, time = ? WHERE key = ? AND ref = ?").use {
            it.bindString(1, error.take(300)); it.bindLong(2, now); it.bindString(3, key); it.bindString(4, ref); it.executeUpdateDelete()
        }
        if (updated == 0) db.insert("sync_failures", null, ContentValues().apply {
            put("key", key); put("ref", ref); put("error", error.take(300)); put("attempts", 1); put("time", now)
        })
    }

    fun clearFailure(key: String, ref: String) { db.delete("sync_failures", "key = ? AND ref = ?", arrayOf(key, ref)) }

    fun failures(): List<Triple<String, String, String>> =
        db.rawQuery("SELECT key, ref, error FROM sync_failures ORDER BY time DESC LIMIT 100", null).use { c ->
            val out = mutableListOf<Triple<String, String, String>>(); while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), c.getString(2)); out
        }

    fun failureCount(): Int = db.rawQuery("SELECT count(*) FROM sync_failures", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
