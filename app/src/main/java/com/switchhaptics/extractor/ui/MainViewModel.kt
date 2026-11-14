package com.switchhaptics.extractor.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.switchhaptics.extractor.extractor.ExtractedBnvibItem
import com.switchhaptics.extractor.extractor.XciBnvibExtractor
import com.switchhaptics.extractor.haptics.BnvibParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val xciUri: Uri? = null,
    val xciDisplayName: String? = null,
    val keysUri: Uri? = null,
    val keysDisplayName: String? = null,
    val isExtracting: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = "请选择 prod.keys 和 .xci / .nsp 游戏镜像",
    val errorText: String? = null,
    val items: List<ExtractedBnvibItem> = emptyList(),
    val searchQuery: String = "",
    val selectedItem: ExtractedBnvibItem? = null
) {
    val canStartExtraction: Boolean
        get() = xciUri != null && keysUri != null && !isExtracting

    val filteredItems: List<ExtractedBnvibItem>
        get() = if (searchQuery.isBlank()) {
            items
        } else {
            val q = searchQuery.trim().lowercase()
            items.filter {
                it.fileName.lowercase().contains(q) || it.sourcePath.lowercase().contains(q)
            }
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val extractor = XciBnvibExtractor(application.applicationContext)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun setXciUri(uri: Uri) {
        val name = queryDisplayName(uri) ?: "selected.xci"
        _uiState.update {
            it.copy(
                xciUri = uri,
                xciDisplayName = name,
                errorText = null
            )
        }
    }

    fun setKeysUri(uri: Uri) {
        val name = queryDisplayName(uri) ?: "prod.keys"
        _uiState.update {
            it.copy(
                keysUri = uri,
                keysDisplayName = name,
                errorText = null
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectItem(item: ExtractedBnvibItem?) {
        if (item == null) {
            _uiState.update { it.copy(selectedItem = null) }
            return
        }
        if (item.info.samples.isNotEmpty() || !item.info.isValid) {
            _uiState.update { it.copy(selectedItem = item) }
            return
        }
        viewModelScope.launch {
            val detailedInfo = withContext(Dispatchers.IO) {
                BnvibParser.parse(item.savedFile.readBytes(), includeSamples = true)
            }
            _uiState.update { it.copy(selectedItem = item.copy(info = detailedInfo)) }
        }
    }

    fun startExtraction() {
        val state = _uiState.value
        val xci = state.xciUri ?: return
        val keys = state.keysUri ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExtracting = true,
                    progress = 0f,
                    errorText = null,
                    items = emptyList(),
                    statusText = "准备开始提取..."
                )
            }

            try {
                val results = extractor.extractFromXci(
                    xciUri = xci,
                    keysUri = keys,
                    onProgress = { p ->
                        _uiState.update {
                            it.copy(progress = p.progress, statusText = p.stage)
                        }
                    }
                )
                _uiState.update {
                    it.copy(
                        isExtracting = false,
                        progress = 1f,
                        items = results,
                        statusText = if (results.isEmpty()) {
                            "扫描完成，但未在 RomFS 中发现 .bnvib 文件"
                        } else {
                            "提取成功：共发现 ${results.size} 个 .bnvib 文件"
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExtracting = false,
                        errorText = e.message ?: e.javaClass.simpleName,
                        statusText = "提取失败"
                    )
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }
}
