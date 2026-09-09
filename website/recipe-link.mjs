// Keep the public ID contract aligned with Android's RecipeShareLinks.
const RECIPE_LINK = /^https:\/\/justdataplease\.github\.io\/spoon\/recipe\/([A-Za-z0-9][A-Za-z0-9_-]{0,127})$/;

export function recipeIdFromLink(link) {
  if (typeof link !== "string") return null;
  const match = RECIPE_LINK.exec(link);
  if (!match || match[1].startsWith("custom_")) return null;
  return match[1];
}

export function recipeIntentFromLink(link) {
  const id = recipeIdFromLink(link);
  return id === null
    ? null
    : `intent://recipe/${id}#Intent;scheme=spoon;package=com.spoon.app;end`;
}
