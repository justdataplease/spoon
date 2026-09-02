package com.justdataplease.spoon.data

import com.justdataplease.spoon.data.model.Recipe

/** Only remotely published Greek recipes may enter cloud-backed selection. */
internal fun eligibleRemoteRecipes(remoteRecipes: List<Recipe>): List<Recipe> =
    remoteRecipes.filter { recipe ->
        recipe.active && recipe.language == "el"
    }
