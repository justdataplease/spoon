"""Refresh normalized categories and their display tags in provider artifacts.

The preserved source payload is immutable. A dry run reports the exact category
changes; --commit atomically replaces JSONL files and their integrity manifests.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
from typing import Any, Mapping

from .audit_source_taxonomy import source_taxonomy
from .crawl_argiro import checkpoint_run_key as argiro_run_key
from .crawl_argiro import parser_contract_hash as argiro_contract_hash
from .crawl_gastronomos import checkpoint_run_key as gastronomos_run_key
from .crawl_gastronomos import parser_contract_hash as gastronomos_contract_hash
from .full_schema import (
    collection_hash,
    document_sizes,
    ensure_full_record,
    firestore_detail_payload,
    firestore_recipe_payload,
    firestore_source_payload,
)
from .import_catalog import compute_catalog_hash, validate_manifest

SOURCES = ("akis", "argiro", "gastronomos")
TAXONOMY_FIELDS = ("categoryKeys", "category", "categoryLabel", "tags")


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    records = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            if line.strip():
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise ValueError(f"{path}:{line_number} is not an object")
                records.append(value)
    if not records:
        raise ValueError(f"{path} is empty")
    return records


def _hashes(records: list[dict[str, Any]]) -> dict[str, str]:
    return {
        "catalogHash": compute_catalog_hash(records),
        "summaryHash": collection_hash(records, firestore_recipe_payload),
        "detailHash": collection_hash(records, firestore_detail_payload),
        "sourcePayloadHash": collection_hash(records, firestore_source_payload),
    }


def _verify_existing_manifest(
    records: list[dict[str, Any]],
    manifest: Mapping[str, Any],
) -> dict[str, str]:
    hashes = _hashes(records)
    mismatches = [key for key, value in hashes.items() if manifest.get(key) != value]
    if manifest.get("complete") is not True or manifest.get("failedRecipeCount") != 0:
        mismatches.append("complete")
    if manifest.get("outputRecipeCount") != len(records):
        mismatches.append("outputRecipeCount")
    if mismatches:
        raise ValueError("Existing artifact/manifest mismatch: " + ", ".join(mismatches))
    return hashes


def _derive(record: Mapping[str, Any]) -> dict[str, Any]:
    expected = source_taxonomy(dict(record))
    return {field: expected[field] for field in TAXONOMY_FIELDS}


def _updated_manifest(
    source: str,
    records: list[dict[str, Any]],
    manifest: Mapping[str, Any],
) -> dict[str, Any]:
    result = dict(manifest)
    hashes = _hashes(records)
    result.update(hashes)
    result["catalogVersion"] = "sha256:" + hashes["catalogHash"]
    result["generatedAt"] = datetime.now(timezone.utc).isoformat()
    sizes = [(str(record["id"]), *document_sizes(record)) for record in records]
    if source == "akis":
        summary = max(sizes, key=lambda row: row[1])
        detail = max(sizes, key=lambda row: row[2])
        payload = max(sizes, key=lambda row: row[3])
        result.update({
            "maximumNormalizedDocumentBytes": summary[1],
            "maximumNormalizedDocumentId": summary[0],
            "maximumDetailDocumentBytes": detail[2],
            "maximumDetailDocumentId": detail[0],
            "maximumSourcePayloadDocumentBytes": payload[3],
            "maximumSourcePayloadDocumentId": payload[0],
        })
    else:
        result.update({
            "maximumSummaryDocumentBytes": max(row[1] for row in sizes),
            "maximumDetailDocumentBytes": max(row[2] for row in sizes),
            "maximumSourcePayloadDocumentBytes": max(row[3] for row in sizes),
        })
        if source == "argiro":
            result["parserContractHash"] = argiro_contract_hash()
            result["checkpointRunKey"] = argiro_run_key()
        else:
            result["parserContractHash"] = gastronomos_contract_hash()
            result["checkpointRunKey"] = gastronomos_run_key()
    return result


def _atomic_write(path: Path, content: str) -> None:
    temporary = path.with_name(path.name + ".taxonomy.tmp")
    try:
        temporary.write_text(content, encoding="utf-8", newline="\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def refresh_source(directory: Path, source: str, *, commit: bool) -> dict[str, Any]:
    catalog_path = directory / f"{source}-greek-full.jsonl"
    manifest_path = directory / f"{source}-greek-full.manifest.json"
    records = _load_jsonl(catalog_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    old_hashes = _verify_existing_manifest(records, manifest)
    before = Counter(str(record["category"]) for record in records)
    changed = 0
    for record in records:
        target = _derive(record)
        if any(record.get(field) != target[field] for field in TAXONOMY_FIELDS):
            changed += 1
            record.update(target)
        ensure_full_record(record)
    after = Counter(str(record["category"]) for record in records)
    updated_manifest = _updated_manifest(source, records, manifest)
    if updated_manifest["sourcePayloadHash"] != old_hashes["sourcePayloadHash"]:
        raise ValueError(f"{source} source payload hash changed")
    validate_manifest(records, updated_manifest)
    if commit:
        encoded = "".join(
            json.dumps(
                record,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            ) + "\n"
            for record in records
        )
        _atomic_write(catalog_path, encoded)
        _atomic_write(
            manifest_path,
            json.dumps(updated_manifest, ensure_ascii=False, sort_keys=True) + "\n",
        )
    return {
        "source": source,
        "recipeCount": len(records),
        "changedRecipes": changed,
        "categoriesBefore": dict(sorted(before.items())),
        "categoriesAfter": dict(sorted(after.items())),
        "catalogHash": updated_manifest["catalogHash"],
        "sourcePayloadHash": updated_manifest["sourcePayloadHash"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog-directory",
        type=Path,
        default=Path(__file__).with_name("output"),
    )
    parser.add_argument("--commit", action="store_true")
    args = parser.parse_args()
    result = [
        refresh_source(args.catalog_directory, source, commit=args.commit)
        for source in SOURCES
    ]
    print(json.dumps({"mode": "committed" if args.commit else "dry-run", "sources": result}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
