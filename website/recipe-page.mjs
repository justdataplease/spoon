import { recipeIntentFromLink } from "/recipe-link.mjs";

const intent = recipeIntentFromLink(window.location.href);
if (intent !== null) {
  document.title = "Άνοιξε τη συνταγή στο Spoon";
  document.getElementById("page-title").textContent = "Μια συνταγή για σένα.";
  document.getElementById("page-description").textContent =
    "Κάποιος μοιράστηκε μαζί σου μια συνταγή. Άνοιξέ τη στο Spoon για να δεις τα υλικά και την εκτέλεση.";
  const openRecipe = document.getElementById("open-recipe");
  openRecipe.href = intent;
  openRecipe.hidden = false;
}
