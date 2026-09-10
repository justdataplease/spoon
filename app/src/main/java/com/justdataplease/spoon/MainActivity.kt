package com.justdataplease.spoon

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.spoon.ui.SpoonApp
import com.justdataplease.spoon.ui.SpoonViewModel
import com.justdataplease.spoon.ui.model.RecipeDetailUi
import com.justdataplease.spoon.ui.sharing.RecipeShareLink
import com.justdataplease.spoon.ui.theme.SpoonTheme
import com.justdataplease.spoon.widget.TodayRecipeWidgetProvider
import com.justdataplease.spoon.widget.validWidgetRecipeId
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: SpoonViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleRecipeLink(intent)
        setContent {
            SpoonTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                SpoonApp(
                    state = state,
                    viewModel = viewModel,
                    onOpenRecipe = ::openRecipe,
                    onShareRecipe = ::shareRecipe,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRecipeLink(intent)
    }

    private fun handleRecipeLink(intent: Intent?) {
        if (intent?.action == TodayRecipeWidgetProvider.OPEN_RECIPE_ACTION) {
            validWidgetRecipeId(intent.getStringExtra(TodayRecipeWidgetProvider.RECIPE_ID_EXTRA))
                ?.let(viewModel::showRecipeDetails)
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        val recipeId = RecipeShareLink.parse(intent.dataString)
        if (recipeId == null) {
            Toast.makeText(this, "Ο σύνδεσμος συνταγής δεν είναι έγκυρος.", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.openSharedRecipe(recipeId)
    }

    private fun shareRecipe(recipe: RecipeDetailUi) {
        val text = RecipeShareLink.shareText(recipe.recipeId, recipe.title, getString(R.string.app_name)) ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, recipe.title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            startActivity(Intent.createChooser(shareIntent, "Κοινοποίηση συνταγής"))
        }.onFailure {
            Toast.makeText(this, "Δεν ήταν δυνατή η κοινοποίηση της συνταγής.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openRecipe(url: String) {
        if (url.isBlank()) return
        val uri = url.toUri()
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
}
