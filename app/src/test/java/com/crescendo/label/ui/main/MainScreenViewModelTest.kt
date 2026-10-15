package com.crescendo.label.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenViewModelTest {

    @Test
    fun uiState_initiallyPrompt() {
        val viewModel = MainScreenViewModel()
        val state = viewModel.uiState.value
        assertTrue(state is MainScreenUiState.Prompt)
        assertEquals(true, (state as MainScreenUiState.Prompt).nfcEnabled)
    }

    @Test
    fun uiState_updateNfcStatus() {
        val viewModel = MainScreenViewModel()
        viewModel.updateNfcStatus(false)
        val state = viewModel.uiState.value
        assertTrue(state is MainScreenUiState.Prompt)
        assertEquals(false, (state as MainScreenUiState.Prompt).nfcEnabled)
    }

    @Test
    fun uiState_resetToPrompt() {
        val viewModel = MainScreenViewModel()
        viewModel.resetToPrompt()
        val state = viewModel.uiState.value
        assertTrue(state is MainScreenUiState.Prompt)
    }
}
