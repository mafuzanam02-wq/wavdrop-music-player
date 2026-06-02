package com.launchpoint.wavdrop.ui.screen.bpstatpreview

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.legacy.BpstatApplyResult
import com.launchpoint.wavdrop.data.legacy.BpstatMatchResult
import com.launchpoint.wavdrop.data.legacy.BpstatMatcher
import com.launchpoint.wavdrop.data.legacy.BlackPlayerImportResult
import com.launchpoint.wavdrop.data.legacy.BlackPlayerStatParser
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface BpstatPreviewUiState {
    data object Idle    : BpstatPreviewUiState
    data object Loading : BpstatPreviewUiState

    /** File parsed and matched; user can review and apply. */
    data class Preview(
        val parseResult: BlackPlayerImportResult,
        val matchResult: BpstatMatchResult,
        val applyError: String? = null,
    ) : BpstatPreviewUiState

    /** Apply is in progress — show spinner, block UI. */
    data class Applying(
        val parseResult: BlackPlayerImportResult,
        val matchResult: BpstatMatchResult,
    ) : BpstatPreviewUiState

    /** Apply completed successfully — show result, disable re-apply. */
    data class Applied(val result: BpstatApplyResult) : BpstatPreviewUiState

    data class Error(val message: String) : BpstatPreviewUiState
}

@HiltViewModel
class BpstatPreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BpstatPreviewUiState>(BpstatPreviewUiState.Idle)
    val uiState: StateFlow<BpstatPreviewUiState> = _uiState.asStateFlow()

    fun reset() {
        _uiState.value = BpstatPreviewUiState.Idle
    }

    // ── File loading ──────────────────────────────────────────────────────────

    fun processFile(uri: Uri) {
        _uiState.value = BpstatPreviewUiState.Loading
        viewModelScope.launch {
            _uiState.value = runCatching { readAndMatch(uri) }.getOrElse { e ->
                BpstatPreviewUiState.Error(e.message ?: "Failed to read file.")
            }
        }
    }

    private suspend fun readAndMatch(uri: Uri): BpstatPreviewUiState {
        val content = withContext(Dispatchers.IO) {
            context.contentResolver
                .openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        } ?: return BpstatPreviewUiState.Error("Could not open the selected file.")

        if (content.isBlank()) {
            return BpstatPreviewUiState.Error("The selected file is empty.")
        }

        val parseResult = BlackPlayerStatParser.parse(content)

        if (parseResult.validRows.isEmpty()) {
            val detail = if (parseResult.invalidRows.isNotEmpty())
                " ${parseResult.invalidRows.size} line(s) could not be parsed."
            else ""
            return BpstatPreviewUiState.Error("No valid rows found in the file.$detail")
        }

        val songs = withContext(Dispatchers.IO) { songRepository.songs.first() }
        val matchResult = BpstatMatcher.match(parseResult, songs)

        return BpstatPreviewUiState.Preview(parseResult, matchResult)
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    fun applyImport() {
        val preview = _uiState.value as? BpstatPreviewUiState.Preview ?: return
        if (preview.matchResult.matchedRows.isEmpty()) return

        _uiState.value = BpstatPreviewUiState.Applying(preview.parseResult, preview.matchResult)

        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    statsRepository.applyBpstatImport(
                        matchedRows    = preview.matchResult.matchedRows,
                        unmatchedCount = preview.matchResult.unmatchedCount,
                    )
                }
            }
            _uiState.value = outcome.fold(
                onSuccess = { BpstatPreviewUiState.Applied(it) },
                onFailure = { e ->
                    BpstatPreviewUiState.Preview(
                        parseResult = preview.parseResult,
                        matchResult = preview.matchResult,
                        applyError  = e.message ?: "Import failed. Please try again.",
                    )
                },
            )
        }
    }
}
