import test from "node:test";
import assert from "node:assert/strict";
import { recipeIdFromLink, recipeIntentFromLink } from "../recipe-link.mjs";

const prefix = "https://justdataplease.github.io/spoon/recipe/";

test("canonical public recipe links build an explicit Spoon intent", () => {
  for (const id of ["akis_123", "argiro-987", "Gastronomos_123", "a", "a".repeat(128)]) {
    assert.equal(recipeIdFromLink(prefix + id), id);
    assert.equal(
      recipeIntentFromLink(prefix + id),
      `intent://recipe/${id}#Intent;scheme=spoon;package=com.spoon.app;end`,
    );
  }
});

test("private, malformed, encoded, or overlong recipe IDs are rejected", () => {
  for (const id of ["", "custom_123", "custom_", "_akis", "-akis", "a".repeat(129), "a/b", "../123", "a.b", "%61kis_123", "akis%2F123", "akis%20x", "akis x", "συνταγή", "a;package=evil", "a\n"]) {
    assert.equal(recipeIdFromLink(prefix + id), null, id);
    assert.equal(recipeIntentFromLink(prefix + id), null, id);
  }
});

test("external hosts, redirects, noncanonical paths, queries, and fragments are rejected", () => {
  for (const link of [
    "http://justdataplease.github.io/spoon/recipe/akis_123",
    "https://evil.example/spoon/recipe/akis_123",
    "https://justdataplease.github.io.evil.example/spoon/recipe/akis_123",
    "https://evil@justdataplease.github.io/spoon/recipe/akis_123",
    "https://justdataplease.github.io/recipe/akis_123",
    "https://justdataplease.github.io/spoon/recipe/akis_123/",
    prefix + "akis_123?redirect=https://evil.example",
    prefix + "akis_123?",
    prefix + "akis_123#fragment",
    prefix + "akis_123#",
    prefix + "akis_123#Intent;package=evil;end",
    "javascript:alert(1)",
    null,
    undefined,
  ]) {
    assert.equal(recipeIntentFromLink(link), null, String(link));
  }
});
