package com.justdataplease.spoon

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.spoon.ui.SpoonApp
import com.justdataplease.spoon.ui.SpoonViewModel
import com.justdataplease.spoon.ui.theme.SpoonTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: SpoonViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpoonTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                SpoonApp(
                    state = state,
                    viewModel = viewModel,
                    onOpenRecipe = ::openRecipe,
                )
            }
        }
    }

    private fun openRecipe(url: String) {
        if (url.isBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
