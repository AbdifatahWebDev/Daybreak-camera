package com.brekwol.daybreak.edit

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brekwol.daybreak.engine.EditRecipe

/**
 * The recipe being edited, with undo and redo. A slider drag changes the
 * recipe live and becomes one undo step when the finger lifts.
 */
@Stable
class EditorState(initial: EditRecipe, private val original: EditRecipe = initial) {
    var recipe: EditRecipe by mutableStateOf(initial)
        private set

    /** True while a finger is on a slider, the curve or the crop frame. */
    var interacting: Boolean by mutableStateOf(false)
        private set

    private var committed: EditRecipe = initial
    private val undoStack = ArrayDeque<EditRecipe>()
    private val redoStack = ArrayDeque<EditRecipe>()

    var canUndo: Boolean by mutableStateOf(false)
        private set
    var canRedo: Boolean by mutableStateOf(false)
        private set

    /** True when the recipe differs from what the editor opened with. */
    val hasChanges: Boolean
        get() = recipe != original

    /** A live change while a gesture is in progress. */
    fun preview(next: EditRecipe) {
        interacting = true
        recipe = next
    }

    /** Ends a gesture: the change since the last step becomes one undo step. */
    fun commit() {
        interacting = false
        if (recipe == committed) return
        undoStack.addLast(committed)
        if (undoStack.size > MAX_STEPS) undoStack.removeFirst()
        redoStack.clear()
        committed = recipe
        sync()
    }

    /** A complete change in one go (a tap, a reset, a preset). */
    fun set(next: EditRecipe) {
        recipe = next
        commit()
    }

    fun undo() {
        if (interacting) commit()
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(committed)
        committed = previous
        recipe = previous
        sync()
    }

    fun redo() {
        if (interacting) commit()
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(committed)
        committed = next
        recipe = next
        sync()
    }

    private fun sync() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private companion object {
        const val MAX_STEPS = 100
    }
}
