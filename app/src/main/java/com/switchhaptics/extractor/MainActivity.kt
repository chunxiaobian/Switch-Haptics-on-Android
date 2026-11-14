package com.switchhaptics.extractor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.switchhaptics.extractor.ui.MainScreen
import com.switchhaptics.extractor.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val uiState by viewModel.uiState.collectAsState()

                val keysLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { viewModel.setKeysUri(it) }
                }

                val xciLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { viewModel.setXciUri(it) }
                }

                MainScreen(
                    uiState = uiState,
                    onPickKeys = { keysLauncher.launch(arrayOf("*/*")) },
                    onPickXci = { xciLauncher.launch(arrayOf("*/*")) },
                    onStartExtract = { viewModel.startExtraction() },
                    onSearchChange = { viewModel.updateSearchQuery(it) },
                    onSelectItem = { viewModel.selectItem(it) }
                )
            }
        }
    }
}
