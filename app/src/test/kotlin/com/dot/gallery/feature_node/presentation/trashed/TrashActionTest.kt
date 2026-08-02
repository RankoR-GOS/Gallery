package com.dot.gallery.feature_node.presentation.trashed

import com.dot.gallery.feature_node.presentation.trashed.components.TrashDialogAction
import com.dot.gallery.feature_node.presentation.trashed.components.resolveTrashDialogAction
import org.junit.Assert.assertEquals
import org.junit.Test

internal class TrashActionTest {
    @Test
    fun dialogAndDispatchUseTheSameExplicitAction() {
        assertEquals(
            TrashDialogAction.TRASH,
            resolveTrashDialogAction(trashRequested = true, trashEnabled = true),
        )
        for (requested in listOf(false, true)) {
            assertEquals(
                TrashDialogAction.DELETE,
                resolveTrashDialogAction(trashRequested = requested, trashEnabled = false),
            )
        }
        assertEquals(
            TrashDialogAction.DELETE,
            resolveTrashDialogAction(trashRequested = false, trashEnabled = true),
        )
    }
}
