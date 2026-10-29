package com.brekwol.daybreak.edit

import com.brekwol.daybreak.engine.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateTest {
    @Test
    fun aDragIsOneUndoStep() {
        val state = EditorState(EditRecipe.Neutral)
        state.preview(EditRecipe(exposure = 0.1f))
        state.preview(EditRecipe(exposure = 0.2f))
        state.preview(EditRecipe(exposure = 0.3f))
        assertTrue(state.interacting)
        state.commit()
        assertFalse(state.interacting)
        assertTrue(state.canUndo)
        state.undo()
        assertEquals(EditRecipe.Neutral, state.recipe)
        assertFalse(state.canUndo)
        assertTrue(state.canRedo)
        state.redo()
        assertEquals(0.3f, state.recipe.exposure)
    }

    @Test
    fun aNewChangeClearsRedo() {
        val state = EditorState(EditRecipe.Neutral)
        state.set(EditRecipe(contrast = 0.2f))
        state.set(EditRecipe(contrast = 0.4f))
        state.undo()
        assertTrue(state.canRedo)
        state.set(EditRecipe(saturation = 0.1f))
        assertFalse(state.canRedo)
        state.undo()
        assertEquals(0.2f, state.recipe.contrast)
    }

    @Test
    fun noChangeMakesNoStep() {
        val state = EditorState(EditRecipe(exposure = 1f))
        state.preview(EditRecipe(exposure = 1f))
        state.commit()
        assertFalse(state.canUndo)
        assertFalse(state.hasChanges)
        state.set(EditRecipe(exposure = 2f))
        assertTrue(state.hasChanges)
    }
}
