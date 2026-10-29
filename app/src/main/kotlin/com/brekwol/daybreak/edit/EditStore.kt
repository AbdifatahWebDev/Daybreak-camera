package com.brekwol.daybreak.edit

import android.content.Context
import com.brekwol.daybreak.engine.EditRecipe
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The edit behind an edited copy: which photo it came from and what was done. */
data class SavedEdit(val sourceStem: String, val recipe: EditRecipe)

/**
 * Keeps edits and the user's presets in the app's private files:
 * `edits/<copy stem>.json` for each edited copy, and `presets.json`.
 * Photos themselves are never changed; an edit always renders from the original.
 */
class EditStore(context: Context) {
    private val editsDir = File(context.filesDir, "edits")
    private val presetsFile = File(context.filesDir, "presets.json")
    private val lock = Mutex()

    private val _presets = MutableStateFlow<List<Preset>?>(null)
    /** The user's own presets, null until first loaded. */
    val presets: StateFlow<List<Preset>?> = _presets.asStateFlow()

    suspend fun editFor(copyStem: String): SavedEdit? = withContext(Dispatchers.IO) {
        val file = editFile(copyStem) ?: return@withContext null
        if (!file.isFile) return@withContext null
        runCatching {
            val json = JSONObject(file.readText())
            val source = json.getString("source")
            SavedEdit(source, EditJson.fromJson(json.getJSONObject("edit")))
        }.getOrNull()
    }

    suspend fun saveEdit(copyStem: String, edit: SavedEdit) = withContext(Dispatchers.IO) {
        val file = editFile(copyStem) ?: return@withContext
        editsDir.mkdirs()
        val json = JSONObject()
            .put("version", 1)
            .put("source", edit.sourceStem)
            .put("edit", EditJson.toJson(edit.recipe))
        writeAtomically(file, json.toString(2))
    }

    suspend fun forgetEdit(copyStem: String) = withContext(Dispatchers.IO) {
        editFile(copyStem)?.delete()
        Unit
    }

    suspend fun loadPresets(): List<Preset> = lock.withLock {
        _presets.value ?: withContext(Dispatchers.IO) { readPresets() }.also { _presets.value = it }
    }

    suspend fun addPreset(name: String, recipe: EditRecipe): Preset {
        val preset = Preset(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(Preset.MAX_NAME).ifEmpty { "My preset" },
            recipe = recipe.look,
        )
        update { it + preset }
        return preset
    }

    /** Adds a preset from a shared file. Returns null if the file isn't a preset. */
    suspend fun importPreset(text: String): Preset? {
        val preset = Preset.fromFile(text, UUID.randomUUID().toString()) ?: return null
        update { it + preset }
        return preset
    }

    suspend fun deletePreset(id: String) {
        update { list -> list.filterNot { it.id == id } }
    }

    private suspend fun update(change: (List<Preset>) -> List<Preset>) {
        lock.withLock {
            val current = _presets.value ?: withContext(Dispatchers.IO) { readPresets() }
            val next = change(current)
            withContext(Dispatchers.IO) { writePresets(next) }
            _presets.value = next
        }
    }

    private fun readPresets(): List<Preset> {
        if (!presetsFile.isFile) return emptyList()
        return runCatching {
            val array = JSONObject(presetsFile.readText()).optJSONArray("presets") ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val edit = item.optJSONObject("edit") ?: return@mapNotNull null
                Preset(
                    id = item.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    name = item.optString("name").ifEmpty { "My preset" },
                    recipe = EditJson.fromJson(edit).look,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writePresets(presets: List<Preset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("edit", EditJson.toJson(preset.recipe.look)),
            )
        }
        writeAtomically(presetsFile, JSONObject().put("version", 1).put("presets", array).toString(2))
    }

    /** Stems come from file names; anything unusual is refused rather than used as a path. */
    private fun editFile(stem: String): File? =
        if (stem.isNotEmpty() && stem.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' } &&
            !stem.startsWith(".")
        ) {
            File(editsDir, "$stem.json")
        } else {
            null
        }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(text)
        if (!part.renameTo(file)) {
            file.writeText(text)
            part.delete()
        }
    }
}
