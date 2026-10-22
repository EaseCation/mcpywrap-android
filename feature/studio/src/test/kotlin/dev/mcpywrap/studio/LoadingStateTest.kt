package dev.mcpywrap.studio

import org.junit.Assert.*
import org.junit.Test

class LoadingStateTest {
    @Test fun emptyPagesRequireACompletedSuccessfulRead(){
        assertFalse(ProductState().canShowEmptyContent)
        assertFalse(ProductState(workspaceLoadState=ContentLoadState.LOADING,busy=true).canShowEmptyContent)
        assertFalse(ProductState(workspaceLoadState=ContentLoadState.ERROR).canShowEmptyContent)
        assertFalse(ProductState(workspaceLoadState=ContentLoadState.READY).canShowEmptyContent)
        assertTrue(ProductState(workspaceLoaded=true,workspaceLoadState=ContentLoadState.READY).canShowEmptyContent)
    }
    @Test fun refreshAndFailedRefreshDoNotTurnPriorDataIntoAnEmptyPage(){
        val loaded=ProductState(workspaceLoaded=true,workspaceLoadState=ContentLoadState.READY)
        assertFalse(loaded.copy(busy=true,workspaceLoadState=ContentLoadState.LOADING).canShowEmptyContent)
        assertFalse(loaded.copy(workspaceLoadState=ContentLoadState.ERROR).canShowEmptyContent)
        assertTrue(loaded.canShowEmptyContent)
    }
}
