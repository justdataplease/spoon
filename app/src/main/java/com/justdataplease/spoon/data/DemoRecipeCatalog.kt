package com.justdataplease.spoon.data

import com.justdataplease.spoon.data.model.MealCategory
import com.justdataplease.spoon.data.model.Recipe

/**
 * Small, original metadata-only catalog that keeps the app useful before an importer is run.
 * These are not copies of publisher recipes and intentionally contain no instructions or ingredients.
 */
object DemoRecipeCatalog {
    val recipes: List<Recipe> = listOf(
        recipe("demo-legumes-lemon-lentils", "Λεμονάτες φακές με ψητό καρότο", MealCategory.LEGUMES, 8.7, 25, 5),
        recipe("demo-legumes-tomato-chickpeas", "Ρεβίθια ντομάτας με κύμινο", MealCategory.LEGUMES, 8.4, 35, 7),
        recipe("demo-legumes-herb-beans", "Φασόλια με μυρωδικά και λεμόνι", MealCategory.LEGUMES, 8.1, 45, 10),

        recipe("demo-poultry-lemon-pan", "Κοτόπουλο λεμονιού στο τηγάνι", MealCategory.POULTRY, 9.0, 25, 5),
        recipe("demo-poultry-paprika-bites", "Μπουκιές κοτόπουλου με πάπρικα", MealCategory.POULTRY, 8.5, 30, 7),
        recipe("demo-poultry-orange-turkey", "Γαλοπούλα με πορτοκάλι και θυμάρι", MealCategory.POULTRY, 8.0, 50, 10),

        recipe("demo-vegetables-tahini-tray", "Ψητά λαχανικά με ταχίνι", MealCategory.VEGETABLES, 8.8, 20, 5),
        recipe("demo-vegetables-mushroom-polenta", "Μανιτάρια με κρεμώδη πολέντα", MealCategory.VEGETABLES, 8.6, 35, 8),
        recipe("demo-vegetables-stuffed-zucchini", "Κολοκύθια γεμιστά με αρωματικό ρύζι", MealCategory.VEGETABLES, 8.2, 55, 11),

        recipe("demo-meat-apple-pork", "Χοιρινό με μήλο και θυμάρι", MealCategory.MEAT, 8.6, 30, 5),
        recipe("demo-meat-mint-meatballs", "Κεφτεδάκια φούρνου με δυόσμο", MealCategory.MEAT, 8.9, 45, 8),
        recipe("demo-meat-slow-tomato-beef", "Μοσχάρι ντομάτας αργού μαγειρέματος", MealCategory.MEAT, 9.1, 70, 12),

        recipe("demo-fish-lemon-parcel", "Ψάρι στη λαδόκολλα με λεμόνι", MealCategory.FISH, 9.0, 25, 5),
        recipe("demo-fish-mustard-salmon", "Σολομός με μουστάρδα και άνηθο", MealCategory.FISH, 8.8, 35, 7),
        recipe("demo-fish-tomato-prawns", "Γαρίδες ντομάτας με μυρωδικά", MealCategory.FISH, 8.3, 45, 10),

        recipe("demo-street-mushroom-pita", "Πίτα με μανιτάρια και δροσερή σος", MealCategory.STREET_FOOD, 8.4, 20, 5),
        recipe("demo-street-chicken-wrap", "Τυλιχτό κοτόπουλου με τραγανά λαχανικά", MealCategory.STREET_FOOD, 8.7, 35, 8),
        recipe("demo-street-spiced-burger", "Μπέργκερ με μπαχαρικά και ψητό κρεμμύδι", MealCategory.STREET_FOOD, 8.9, 50, 11),

        recipe("demo-pasta-tomato-spaghetti", "Σπαγγέτι με φρέσκια ντομάτα", MealCategory.PASTA_RICE, 8.5, 20, 4),
        recipe("demo-pasta-mushroom-orzo", "Κριθαρότο με μανιτάρια", MealCategory.PASTA_RICE, 8.8, 35, 8),
        recipe("demo-rice-lemon-herbs", "Ρύζι λεμονιού με πράσινα μυρωδικά", MealCategory.PASTA_RICE, 8.0, 45, 10),
    )

    private fun recipe(
        id: String,
        title: String,
        category: MealCategory,
        rating: Double,
        prepMinutes: Int,
        stepCount: Int,
    ) = Recipe(
        id = id,
        title = title,
        category = category.key,
        rating = rating,
        prepMinutes = prepMinutes,
        stepCount = stepCount,
        cookMinutes = (prepMinutes / 2).coerceAtLeast(10),
        totalMinutes = prepMinutes + (prepMinutes / 2).coerceAtLeast(10),
        preparationCount = when {
            stepCount <= 5 -> 1
            stepCount <= 9 -> 2
            else -> 3
        },
        sourceName = "Δείγμα εφαρμογής",
        source = "demo",
        sourceKey = "demo",
        tags = listOf("demo", category.key),
    )
}
