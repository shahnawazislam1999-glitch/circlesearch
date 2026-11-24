package com.personal.circlesearch.clip

import com.personal.circlesearch.R

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Group(val id: String, var name: String)

/** For image cards, [text] is an optional caption. */
data class Item(
    val id: String,
    var text: String,
    val image: String?,
    var groupId: String?,
    var pinned: Boolean,
    var uses: Int,
    var created: Long,
    var color: Int = 0,          // sticky-note colour (0 = not assigned yet)
    var sx: Float = 0f,          // position + size on the sticky board (board units = dp)
    var sy: Float = 0f,
    var sw: Float = 0f,
    var sh: Float = 0f
)

/** All data lives here. Every list mutation happens on the main thread. */
object Store {
    lateinit var app: Context
    lateinit var prefs: SharedPreferences
    val items = ArrayList<Item>()
    val groups = ArrayList<Group>()
    @Volatile var ignoreClipUntil = 0L
    var onChange: (() -> Unit)? = null
    private var inited = false
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (inited) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("pinclip", Context.MODE_PRIVATE)
        val s = prefs.getString("data", null)
        if (s != null) parse(s, groups, items)
        inited = true
        if (assignMissingColors()) save()
    }

    private fun notifyChange() { onChange?.invoke() }

    // ───────────── sticky colours + board layout ─────────────

    fun nextColor(): Int {
        val idx = prefs.getInt("cidx", 0)
        prefs.edit().putInt("cidx", idx + 1).apply()
        return PALETTE[idx % PALETTE.size]
    }

    /** Gives every card without a colour the next colour in the palette (oldest first). Returns true if anything changed. */
    fun assignMissingColors(): Boolean {
        val missing = items.filter { it.color == 0 }.sortedBy { it.created }
        missing.forEach { it.color = nextColor() }
        return missing.isNotEmpty()
    }

    fun setColor(i: Item, c: Int) { i.color = c; save(); notifyChange() }

    fun saveLayout() = save()

    fun bringToFront(i: Item) {
        if (items.lastOrNull() === i) return
        items.remove(i)
        items.add(i)
        save()
    }

    private fun imgAspect(i: Item): Float {
        return try {
            val bo = BitmapFactory.Options()
            bo.inJustDecodeBounds = true
            BitmapFactory.decodeFile(imageFile(i).path, bo)
            if (bo.outWidth > 0) bo.outHeight.toFloat() / bo.outWidth else 0.75f
        } catch (e: Exception) { 0.75f }
    }

    /** Places every card that has no spot on the board yet (newest first, in three columns). No change callback. */
    fun ensureLayout() {
        if (items.none { it.sw <= 0f }) return
        val colW = 176f
        val cols = 3
        val bottoms = FloatArray(cols) { 12f }
        items.filter { it.sw > 0f }.forEach {
            val c = Math.round((it.sx - 12f) / colW).coerceIn(0, cols - 1)
            bottoms[c] = maxOf(bottoms[c], it.sy + it.sh + 12f)
        }
        items.filter { it.sw <= 0f }.sortedByDescending { it.created }.forEach {
            var c = 0
            for (k in 1 until cols) if (bottoms[k] < bottoms[c]) c = k
            it.sw = 164f
            it.sh = if (it.image != null) {
                (164f * imgAspect(it) + 12f + (if (it.text.isNotBlank()) 30f else 0f)).coerceIn(90f, 360f)
            } else {
                val lines = it.text.split("\n").sumOf { ln -> maxOf(1, Math.ceil(ln.length / 22.0).toInt()) }
                (22f + lines * 17f).coerceIn(90f, 240f)
            }
            it.sx = 12f + c * colW
            it.sy = bottoms[c]
            bottoms[c] += it.sh + 12f
        }
        save()
    }

    fun imageFile(i: Item) = File(app.filesDir, "img/" + i.image)
    fun thumb(i: Item) = File(app.filesDir, "img/" + i.id + ".jpg")

    /** {date} and {time} placeholders are expanded when a card is pasted. */
    fun expand(t: String): String {
        val now = Date()
        return t.replace("{date}", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now))
            .replace("{time}", SimpleDateFormat("HH:mm", Locale.getDefault()).format(now))
    }

    private fun parse(s: String, gOut: MutableList<Group>, iOut: MutableList<Item>) {
        try {
            val o = JSONObject(s)
            val ga = o.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until ga.length()) {
                val g = ga.getJSONObject(i)
                gOut.add(Group(g.getString("id"), g.getString("name")))
            }
            val ia = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until ia.length()) {
                val x = ia.getJSONObject(i)
                iOut.add(
                    Item(
                        x.getString("id"),
                        x.optString("text", ""),
                        x.optString("image", "").ifEmpty { null },
                        x.optString("group", "").ifEmpty { null },
                        x.optBoolean("pinned", false),
                        x.optInt("uses", 0),
                        x.optLong("created", 0L),
                        x.optInt("col", 0),
                        x.optDouble("sx", 0.0).toFloat(),
                        x.optDouble("sy", 0.0).toFloat(),
                        x.optDouble("sw", 0.0).toFloat(),
                        x.optDouble("sh", 0.0).toFloat()
                    )
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun json(): String {
        val ga = JSONArray()
        groups.forEach { ga.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val ia = JSONArray()
        items.forEach {
            ia.put(
                JSONObject().put("id", it.id).put("text", it.text).put("image", it.image ?: "")
                    .put("group", it.groupId ?: "").put("pinned", it.pinned)
                    .put("uses", it.uses).put("created", it.created)
                    .put("col", it.color).put("sx", it.sx.toDouble()).put("sy", it.sy.toDouble())
                    .put("sw", it.sw.toDouble()).put("sh", it.sh.toDouble())
            )
        }
        return JSONObject().put("app", "PinClip").put("version", 1).put("groups", ga).put("items", ia).toString()
    }

    private fun save() { prefs.edit().putString("data", json()).apply() }

    fun addText(text: String, group: String? = null): Boolean {
        if (text.isBlank()) return false
        val ex = items.firstOrNull { it.image == null && it.text == text }
        if (ex != null) {
            ex.created = System.currentTimeMillis()
            save(); notifyChange()
            return false
        }
        items.add(Item(UUID.randomUUID().toString(), text, null, group, false, 0, System.currentTimeMillis(), nextColor()))
        save(); notifyChange()
        return true
    }

    private fun makeThumb(f: File, id: String) {
        try {
            val bo = BitmapFactory.Options()
            bo.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, bo)
            if (bo.outWidth <= 0) return
            var s = 1
            while (bo.outWidth / s > 640) s *= 2
            val so = BitmapFactory.Options()
            so.inSampleSize = s
            val t = BitmapFactory.decodeFile(f.path, so) ?: return
            File(f.parentFile, "$id.jpg").outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Exception) {
        }
    }

    /** Copies the image into private storage (safe to call from any thread). */
    fun addImage(uri: Uri, group: String? = null) {
        try {
            val id = UUID.randomUUID().toString()
            val dir = File(app.filesDir, "img")
            dir.mkdirs()
            val name = "$id.png"
            val f = File(dir, name)
            val ins = app.contentResolver.openInputStream(uri) ?: return
            ins.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val bo = BitmapFactory.Options()
            bo.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, bo)
            if (bo.outWidth <= 0) { f.delete(); return }
            makeThumb(f, id)
            main.post {
                items.add(Item(id, "", name, group, false, 0, System.currentTimeMillis(), nextColor()))
                save(); notifyChange()
                
            }
        } catch (e: Exception) {
        }
    }

    fun update(i: Item, text: String, group: String?) { i.text = text; i.groupId = group; save(); notifyChange() }
    fun togglePin(i: Item) { i.pinned = !i.pinned; save(); notifyChange() }
    fun touch(i: Item) { i.uses++; save(); notifyChange() }
    fun setGroup(i: Item, g: String?) { i.groupId = g; save(); notifyChange() }

    fun delete(i: Item) {
        items.remove(i)
        if (i.image != null) { imageFile(i).delete(); thumb(i).delete(); ImgCache.drop(i.id) }
        save(); notifyChange()
    }

    fun clearUnpinned() {
        items.filter { !it.pinned }.forEach {
            if (it.image != null) { imageFile(it).delete(); thumb(it).delete() }
        }
        items.removeAll { !it.pinned }
        save(); notifyChange()
    }

    fun addGroup(name: String) {
        if (name.isBlank()) return
        groups.add(Group(UUID.randomUUID().toString(), name.trim()))
        save(); notifyChange()
    }

    fun renameGroup(g: Group, name: String) { if (name.isNotBlank()) { g.name = name.trim(); save(); notifyChange() } }

    fun deleteGroup(id: String) {
        groups.removeAll { it.id == id }
        items.forEach { if (it.groupId == id) it.groupId = null }
        save(); notifyChange()
    }

    fun deleteMany(ids: List<String>) {
        val set = ids.toHashSet()
        items.filter { set.contains(it.id) }.forEach {
            if (it.image != null) { imageFile(it).delete(); thumb(it).delete() }
        }
        items.removeAll { set.contains(it.id) }
        save(); notifyChange()
    }

    fun moveMany(ids: List<String>, g: String?) {
        val set = ids.toHashSet()
        items.forEach { if (set.contains(it.id)) it.groupId = g }
        save(); notifyChange()
    }

    fun touchMany(list: List<Item>) {
        list.forEach { it.uses++ }
        save(); notifyChange()
    }

    @Volatile var lastClip = ""

    /** Short rolling log shown on the home screen so you can see what auto-detect is doing. */
    fun log(msg: String) {
        try {
            val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val old = prefs.getString("log", "") ?: ""
            val lines = (old.split("\n").filter { it.isNotEmpty() } + "$t $msg").takeLast(6)
            prefs.edit().putString("log", lines.joinToString("\n")).apply()
        } catch (e: Exception) {
        }
    }

    fun exportText(): String =
        items.filter { it.image == null }.joinToString("\n\n-----\n\n") { it.text }

    // ───────────── backup / restore (zip: data.json + img/*) ─────────────

    /** Call on the main thread, then hand the results to [writeZip] on any thread. */
    fun backupJson(): String = json()

    fun backupImages(): List<Pair<String, File>> =
        items.filter { it.image != null && imageFile(it).exists() }.map { it.image!! to imageFile(it) }

    fun writeZip(out: OutputStream, js: String, imgs: List<Pair<String, File>>) {
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("data.json"))
            z.write(js.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            try {
                val bf = BoardStore.file()
                if (bf.exists()) {
                    z.putNextEntry(ZipEntry("board.json"))
                    z.write(bf.readBytes())
                    z.closeEntry()
                }
            } catch (e: Exception) {
            }
            for ((n, f) in imgs) {
                z.putNextEntry(ZipEntry("img/$n"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    /** Call from a background thread. [done] runs on the main thread with the number of cards added, or -1 on error. */
    fun importZip(inp: InputStream, replace: Boolean, done: (Int) -> Unit) {
        try {
            val dir = File(app.filesDir, "img")
            dir.mkdirs()
            var js: String? = null
            var boardJs: String? = null
            ZipInputStream(inp).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val n = e.name
                    if (!e.isDirectory) {
                        if (n == "data.json") {
                            js = z.readBytes().toString(Charsets.UTF_8)
                        } else if (n == "board.json") {
                            boardJs = z.readBytes().toString(Charsets.UTF_8)
                        } else if (n.startsWith("img/")) {
                            val fn = File(n).name
                            if (fn.isNotEmpty()) File(dir, fn).outputStream().use { o -> z.copyTo(o) }
                        }
                    }
                    z.closeEntry()
                    e = z.nextEntry
                }
            }
            val text = js
            if (text == null) { main.post { done(-1) }; return }
            val gs = ArrayList<Group>()
            val its = ArrayList<Item>()
            parse(text, gs, its)
            its.filter { it.image != null }.forEach { makeThumb(File(dir, it.image!!), it.id) }
            val bj = boardJs
            if (bj != null) {
                try {
                    val bf = BoardStore.file()
                    val old = if (!replace && bf.exists()) bf.readText() else null
                    bf.writeText(if (old == null) bj else BoardStore.mergeText(old, bj))
                } catch (e: Exception) {
                }
            }
            main.post {
                if (bj != null) BoardStore.reload()
                if (replace) {
                    items.filter { old -> old.image != null && its.none { n -> n.id == old.id } }
                        .forEach { old -> imageFile(old).delete(); thumb(old).delete() }
                    items.clear()
                    groups.clear()
                }
                var c = 0
                gs.forEach { g -> if (groups.none { it.id == g.id }) groups.add(g) }
                its.forEach { x ->
                    if (items.none { it.id == x.id } && (x.image == null || imageFile(x).exists())) {
                        items.add(x)
                        c++
                    }
                }
                assignMissingColors()
                save(); notifyChange()
                done(c)
            }
        } catch (e: Exception) {
            main.post { done(-1) }
        }
    }
}
