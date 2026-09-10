import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");
const appName = read("../../app/src/main/res/values/strings.xml").match(/<string name="app_name">([^<]+)<\/string>/)[1];

test("both landing pages and the recipe action use the current Android brand", () => {
  for (const file of ["../index.html", "../404.html"]) {
    const html = read(file);
    assert.ok(html.includes(appName));
    assert.match(html, /<title>[^<]*Τι θα φάμε;/);
    assert.match(html, /class="app-icon" src="\/app-icon.svg"/);
    assert.match(html, /rel="icon"[^>]*href="\/app-icon.svg"/);
    assert.match(html, /property="og:site_name" content="Τι θα φάμε;"/);
    assert.match(html, /property="og:image" content="https:\/\/justdataplease.github.io\/app-icon.png"/);
    assert.ok(html.includes("img-src 'self'"));
    assert.doesNotMatch(html, /Spoon|🥄/);
  }
  const page = read("../recipe-page.mjs");
  assert.ok(page.includes(appName));
  assert.doesNotMatch(page, /Spoon/);
});

test("website icon preserves every Android launcher path and background", () => {
  const android = read("../../app/src/main/res/drawable/ic_launcher_foreground.xml");
  const svg = read("../app-icon.svg");
  const paths = [...android.matchAll(/android:pathData="([^"]+)"/g)].map((match) => match[1]);
  assert.ok(paths.length > 0);
  for (const path of paths) assert.ok(svg.includes(`d="${path}"`));
  const color = read("../../app/src/main/res/values/ic_launcher_background.xml").match(/<color[^>]*>([^<]+)<\/color>/)[1];
  assert.ok(svg.includes(`fill="${color}"`));
  const png = readFileSync(new URL("../app-icon.png", import.meta.url));
  assert.equal(png.subarray(1, 4).toString(), "PNG");
  assert.equal(png.readUInt32BE(16), 512);
  assert.equal(png.readUInt32BE(20), 512);
});
