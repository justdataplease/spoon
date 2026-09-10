# Additional recipe source downloads

The user confirmed written permission for the four additional sources in the
September 2026 implementation session. Crawlers still require the explicit permission switch shown below, obey source
request pacing, and stop on access restrictions. They never solve a challenge or request account-only content.

## Giorgos Tsoulis and Yiannis Lucacos

```powershell
python -m tools.recipe_importer.crawl_tsoulis --i-have-tsoulis-permission
python -m tools.recipe_importer.crawl_lucacos --i-have-lucacos-permission
```

These sources discover canonical Greek recipe paths from the official publisher
sitemaps. The September 10 discovery found 3,541 Tsoulis recipe pages and 1,159
Lucacos pages. The complete Tsoulis download has 3,540 valid recipes plus one
audited publisher-incomplete page (native ID 410): both the embedded payload and
rendered source omit its ingredient list. Its raw page is retained and no ingredients
are invented. Tsoulis normalizes the page's embedded source recipe payload; Lucacos
normalizes scoped Recipe JSON-LD. Non-Greek variants are explicitly excluded.
The complete Lucacos download accounts for all 1,159 discovered pages: 1,156 valid
recipes plus three audited exclusions. Native ID 3479 has empty ingredient and
method articles, 8012 has no ingredient list, and 7238 is a Latin placeholder test
page. The source pages remain in the raw cache; they are not fabricated into recipes.
Lucacos publishes a **10-second crawl delay**; a complete download takes at least
about 3.2 hours. The crawler honors that delay, including during retries.

Unlike the Funky Cook and Cookpad defaults, these two commands keep checkpoints
under `tools/recipe_importer/output/tsoulis/` and
`tools/recipe_importer/output/lucacos/`. Their `--output-dir` selects the parent
output directory. They promote only complete `tsoulis-greek-full.*` and
`lucacos-greek-full.*` artifacts to that parent directory. To normalize an existing
checkpoint without another download, repeat the command with `--export-only`.
`--retry-failed` retries fetch failures, and `--max-recipes N` limits a work session
without certifying it complete.

## Funky Cook

```powershell
python -m tools.recipe_importer.crawl_funkycook --i-have-permission --raw-only
python -m tools.recipe_importer.crawl_funkycook --i-have-permission --export-only
```

Discovery reads the publisher's `sitemap_index.xml` and only its `post-sitemap*.xml`
children. The September 10 discovery contained 1,293 posts, including nested paths.
The completed download yielded 1,169 text recipes and 124 audited exclusions for
non-recipe posts or publisher pages without a complete text recipe. Exclusions and
parsing failures are written separately; an unresolved parsing failure prevents a
complete/validated manifest.

A completed export is named `funkycook-greek-full.*` and is automatically copied to
`tools/recipe_importer/output/`, the builder's default artifact directory. Incomplete
exports remain in the checkpoint directory and never replace complete builder inputs.
`--publish-dir` can select another complete-artifact destination.

Modern pages use Recipe JSON-LD, supplemented by the matching public WPRM card
for ingredient group headings, explicit amount/unit/name/notes fields, and recipe
notes. Older recipes use scoped `hrecipe` markup or
ingredients and instructions in `.entry-content`; the parser keeps ingredient and
method sections, explicit time labels, tips, and source evidence. WordPress post IDs
provide stable `funkycook_<post-id>` identity.

## Cookpad Greece

```powershell
python -m tools.recipe_importer.crawl_cookpad --i-have-permission --raw-only
python -m tools.recipe_importer.crawl_cookpad --i-have-permission --export-only
```

Cookpad's public robots file exposes no sitemap, and the public root and Greek
sitemap paths returned 404. The crawler starts at `/gr`, follows public trending
searches and their pagination, then follows Greek recipe links and ingredient/tag
search links. It excludes author profiles, edit/print/reaction endpoints, other
languages, and external hosts. Recipe IDs come from `/gr/sintages/<numeric-id>`.

This is an open public link graph, not an authoritative complete Greek catalog.
Cookpad manifests always retain `discoveryComplete: false` and `complete: false`,
even if all currently queued links are downloaded. A full-source completeness claim
would require a publisher-provided exhaustive index/export. Public recipes without
Recipe JSON-LD are parsed only from their `#ingredients` and `#steps` sections;
generic Cookpad logos are never presented as recipe photographs. Explicit bold
ingredient headings remain section titles rather than ingredients. When publisher
JSON-LD omits real ingredients because they were formatted as bold rows, the importer
reconciles the complete scoped public list with explicit amounts and reviewed
ingredient identities. It preserves the original JSON-LD and records additional
DOM ingredient lines as evidence; ordinal section names remain headings. Attached
recipe and cooking-tip cards become safe source links, not extra ingredient text.
Publisher spelling/word-order differences between JSON-LD and the visible list are
retained in reconciliation metadata without discarding the visible ingredients. The current
recipe's `cooking_time_recipe_<id>` field supplies cooking-time evidence. Complete
numeric durations and ranges can populate minutes; ambiguous prose or unquantified
extra waiting remains preserved as evidence with unknown total time, so it cannot
qualify a recipe for the quick filter.

## Checkpoints and snapshots

Funky Cook and Cookpad use `build/recipe-importer/<source>/checkpoint.sqlite`;
Tsoulis and Lucacos use the output subdirectories above. Every checkpoint stores
gzipped raw HTML in its `raw/` subdirectory. Re-running resumes without redownloading successful
pages unless their published sitemap modification time changes. A changed page is
queued again, and raw versions remain immutable so concurrent exports are consistent.
`--retry-failed` explicitly retries failed fetches. `--max-recipes N` is an
optional operator limit; the default download has no count limit. Never mark a
limited or running crawl complete.

The source JSONL, manifest, exclusions and failures are exported in that directory.
Exports read one SQLite snapshot while the WAL writer continues downloading, apply
the full record and document-size validation, and checksum the exact JSONL. Complete
exports also require an exact match between the persisted discovery URL set and the
checkpoint. Newly added URLs enter the pending queue. If a future sitemap removes
or replaces URLs, use a fresh checkpoint directory for that full snapshot; stale
cached URLs cannot silently certify completeness.
Partial snapshots retain download/discovery failures, pending counts, and a coverage
description. They may only be used through the builder's explicit partial-artifact
path; full import/retirement requires a complete manifest.

Independent sources may run in separate processes. Requests within each source
remain paced. Run at most one download process for a given checkpoint; exporting a
snapshot alongside its downloader is supported. The raw cache enables parser and
common taxonomy updates without another network download.

From the repository root, this PowerShell example starts all four downloads in
parallel with separate logs and hidden process windows. Re-running the commands
resumes persisted progress; first ensure an earlier process for that source has
finished.

```powershell
$workspacePath = (Get-Location).Path
$logDirectory = Join-Path $workspacePath "build/recipe-importer/logs"
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$pythonPath = (Get-Command python).Source
$sourceJobs = @(
    @{ Key = "tsoulis"; Permission = "--i-have-tsoulis-permission" },
    @{ Key = "lucacos"; Permission = "--i-have-lucacos-permission" },
    @{ Key = "funkycook"; Permission = "--i-have-permission" },
    @{ Key = "cookpad"; Permission = "--i-have-permission" }
)
foreach ($sourceJob in $sourceJobs) {
    $sourceKey = $sourceJob.Key
    Start-Process -FilePath $pythonPath -WindowStyle Hidden -PassThru `
        -ArgumentList @("-u", "-m", "tools.recipe_importer.crawl_$sourceKey", $sourceJob.Permission) `
        -WorkingDirectory $workspacePath `
        -RedirectStandardOutput (Join-Path $logDirectory "$sourceKey.stdout.log") `
        -RedirectStandardError (Join-Path $logDirectory "$sourceKey.stderr.log")
}
```

## Building the offline catalog

The builder's six default **complete** inputs are Akis, Argiro, Gastronomos,
Tsoulis, Lucacos, and Funky Cook, under `tools/recipe_importer/output/` with their
`<source>-greek-full.jsonl` and `.manifest.json` names. After all six are complete,
include the current validated Cookpad snapshot explicitly:

```powershell
python -m tools.recipe_importer.build_local_catalog `
    --partial-artifact build/recipe-importer/cookpad/cookpad-full.jsonl build/recipe-importer/cookpad/cookpad-full.manifest.json
```

The database retains Cookpad's incomplete coverage note and pending/discovery
counts. Cookpad is never a complete default input. If building while an indexed
source is still downloading, enumerate the available complete pairs with repeated
`--artifact JSONL MANIFEST` flags and each validated in-progress pair with
`--partial-artifact JSONL MANIFEST`; providing `--artifact` replaces the six default
pairs. Missing or invalid complete inputs fail the build rather than being skipped.
Partial source artifacts require a matching JSONL checksum, validated snapshot,
zero normalization failures, and a nonempty coverage note. They cannot be used for
full-source import or retirement.

## Official icons

The original provider images were downloaded without creative modification:

- `app/src/main/res/drawable-nodpi/source_funkycook.jpg`: the 210×210 official touch
  icon linked from Funky Cook's site,
  `https://funkycook.gr/wp-content/uploads/2015/05/logo_mobile-210x210.jpg`.
- `app/src/main/res/drawable-nodpi/source_cookpad.png`: the 152×152 official touch icon
  linked from Cookpad's Greek pages,
  `https://global-web-assets.cpcdn.com/assets/favicons/apple-touch-icon-152x152-d8ef6f4b35aee81d7d317da0ef1254f12907a4275fa70c9b5fa4f347cf221119.png`.

Android resource names omit the filename extension; the two icons therefore remain
`R.drawable.source_funkycook` and `R.drawable.source_cookpad`.
