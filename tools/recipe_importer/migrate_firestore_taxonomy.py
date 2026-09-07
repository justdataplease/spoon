"""Dry-run-first category/ingredient repair of existing Firestore public documents.

The reviewed plan contains only taxonomy fields and document update-time guards.
No source payload or personal-data collection is written. Interrupted commits can
be resumed by creating a fresh plan; already-correct documents need no writes.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from typing import Any, Mapping

from .full_schema import firestore_detail_payload, firestore_recipe_payload
from .import_catalog import create_firestore_client, load_catalog, load_manifest, validate_catalog, validate_manifest
from .ingredient_taxonomy import INGREDIENT_TAXONOMY_VERSION, is_reviewed_ingredient

COLLECTION_FIELDS = {
    "spoon_recipes": ("category", "categoryLabel", "ingredientLabels", "tags"),
    "spoon_recipe_details": ("category", "categoryKeys", "categoryLabel", "ingredientLabels", "tags"),
}
IDENTITY_FIELDS = ("sourceKey", "providerRecipeId", "canonicalUrl", "active")


def digest(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def taxonomy_patch(live: Mapping, target: Mapping, *, collection: str) -> dict:
    if any(live.get(field) != target.get(field) for field in IDENTITY_FIELDS):
        raise ValueError("Live/source identity or active-state mismatch")
    return {field: target[field] for field in COLLECTION_FIELDS[collection]
            if live.get(field) != target[field]}


def load_records(catalog_directory: Path) -> list[dict]:
    records = []
    for source in ("akis", "argiro", "gastronomos"):
        path = catalog_directory / f"{source}-greek-full.jsonl"
        current = validate_catalog(load_catalog(path), have_permission=True,
                                   have_argiro_permission=True, have_gastronomos_permission=True)
        validate_manifest(current, load_manifest(path.with_suffix(".manifest.json")))
        for record in current:
            unknown = [label for label in record["ingredientLabels"] if not is_reviewed_ingredient(label)]
            if unknown:
                raise ValueError(f"Unreviewed ingredient facets in {record['id']}: {unknown}")
        records.extend(current)
    if len({record["id"] for record in records}) != len(records):
        raise ValueError("Duplicate recipe IDs across providers")
    return records


def make_plan(client: Any, records: list[dict]) -> dict:
    source_records = {record["id"]: record for record in records}
    documents = []
    sources = Counter(record["sourceKey"] for record in records)
    for collection, fields in COLLECTION_FIELDS.items():
        seen = set()
        projector = firestore_recipe_payload if collection == "spoon_recipes" else firestore_detail_payload
        for snapshot in client.collection(collection).select([*fields, *IDENTITY_FIELDS]).stream(timeout=180):
            live = snapshot.to_dict()
            if snapshot.id not in source_records:
                if live.get("active") is True:
                    raise ValueError(f"Active live document absent from validated catalog: {snapshot.reference.path}")
                continue
            record = source_records[snapshot.id]
            target = projector(record)
            patch = taxonomy_patch(live, target, collection=collection)
            documents.append({
                "collection": collection, "id": snapshot.id, "sourceKey": record["sourceKey"],
                "updateTime": snapshot.update_time.rfc3339(),
                "before": {field: live.get(field) for field in fields},
                "expected": {field: target[field] for field in fields}, "patch": patch,
            })
            seen.add(snapshot.id)
            if len(seen) % 5000 == 0:
                print(f"Read {collection}: {len(seen)}", flush=True)
        if seen != source_records.keys():
            raise ValueError(f"{collection} is missing {len(source_records.keys() - seen)} validated recipes")
        print(f"Read {collection}: {len(seen)} complete", flush=True)
    documents.sort(key=lambda row: (row["collection"], row["id"]))
    return {"version": 1, "projectId": client.project,
            "ingredientTaxonomyVersion": INGREDIENT_TAXONOMY_VERSION,
            "sourceCounts": dict(sources), "documents": documents}


def plan_summary(plan: dict) -> dict:
    changed = [row for row in plan["documents"] if row["patch"]]
    return {"planHash": digest(plan), "projectId": plan["projectId"],
            "recipeCount": sum(plan["sourceCounts"].values()),
            "documentCount": len(plan["documents"]), "documentWrites": len(changed),
            "writesByCollection": dict(Counter(row["collection"] for row in changed)),
            "changesByField": dict(Counter(field for row in changed for field in row["patch"])),
            "sourcePayloadWrites": 0, "personalDataWrites": 0}


def validate_plan(plan: dict, project_id: str, expected_hash: str) -> None:
    if digest(plan) != expected_hash or plan.get("projectId") != project_id:
        raise ValueError("Reviewed plan hash/project mismatch")
    if plan.get("version") != 1 or plan.get("ingredientTaxonomyVersion") != INGREDIENT_TAXONOMY_VERSION:
        raise ValueError("Plan vocabulary is stale")
    seen = set()
    for row in plan["documents"]:
        fields = COLLECTION_FIELDS.get(row["collection"])
        if fields is None or not row["id"] or "/" in row["id"]:
            raise ValueError("Unsafe document path")
        key = row["collection"], row["id"]
        if key in seen:
            raise ValueError("Duplicate plan document")
        seen.add(key)
        if set(row["expected"]) != set(fields) or set(row["patch"]) - set(fields):
            raise ValueError("Unsafe taxonomy field mask")
        if row["patch"] != {field: value for field, value in row["expected"].items()
                            if row["before"].get(field) != value}:
            raise ValueError("Patch differs from reviewed expected fields")
    expected_count = sum(plan["sourceCounts"].values())
    for collection in COLLECTION_FIELDS:
        if sum(row["collection"] == collection for row in plan["documents"]) != expected_count:
            raise ValueError("Incomplete collection in plan")


def commit_plan(client: Any, plan: dict, expected_hash: str, *, batch_size: int = 200) -> int:
    from google.api_core.datetime_helpers import DatetimeWithNanoseconds
    validate_plan(plan, client.project, expected_hash)
    if not 1 <= batch_size <= 500:
        raise ValueError("Invalid batch size")
    changed = [row for row in plan["documents"] if row["patch"]]
    for offset in range(0, len(changed), batch_size):
        batch = client.batch()
        for row in changed[offset:offset + batch_size]:
            reference = client.collection(row["collection"]).document(row["id"])
            option = client.write_option(last_update_time=DatetimeWithNanoseconds.from_rfc3339(row["updateTime"]))
            # update() cannot create a missing document and rejects concurrent edits.
            batch.update(reference, row["patch"], option=option)
        batch.commit(timeout=60)
        print(f"Updated {min(offset + batch_size, len(changed))}/{len(changed)} documents", flush=True)
    return len(changed)


def verify_plan(client: Any, plan: dict) -> None:
    for collection, fields in COLLECTION_FIELDS.items():
        expected = {row["id"]: row["expected"] for row in plan["documents"] if row["collection"] == collection}
        seen = set()
        for snapshot in client.collection(collection).select(list(fields)).stream(timeout=180):
            if snapshot.id in expected:
                live = snapshot.to_dict()
                if any(live.get(field) != value for field, value in expected[snapshot.id].items()):
                    raise ValueError(f"Readback mismatch: {snapshot.reference.path}")
                seen.add(snapshot.id)
        if seen != expected.keys():
            raise ValueError(f"Readback missing documents in {collection}")
        print(f"Verified {collection}: {len(seen)}", flush=True)


def publish_status(client: Any, plan: dict) -> None:
    from google.cloud.firestore import SERVER_TIMESTAMP
    batch = client.batch()
    source_metadata = {}
    for source, count in plan["sourceCounts"].items():
        rows = [{"collection": row["collection"], "id": row["id"], "taxonomy": row["expected"]}
                for row in plan["documents"] if row["sourceKey"] == source]
        metadata = {"ingredientTaxonomyVersion": INGREDIENT_TAXONOMY_VERSION,
                    "taxonomyHash": digest(rows), "taxonomyRecipeCount": count,
                    "lastTaxonomyMigrationAt": SERVER_TIMESTAMP,
                    # A partial repair cannot certify hashes of unrelated document fields.
                    # The next full import repopulates these whole-document hashes.
                    "summaryHash": None, "detailHash": None}
        batch.set(client.collection("spoon_catalog").document(f"status_{source}"), metadata, merge=True)
        source_metadata[source] = metadata
    batch.set(client.collection("spoon_catalog").document("status"),
              {"sources": source_metadata,
               "ingredientTaxonomyVersion": INGREDIENT_TAXONOMY_VERSION,
               "lastTaxonomyMigrationAt": SERVER_TIMESTAMP,
               "summaryHash": None, "detailHash": None}, merge=True)
    batch.commit(timeout=60)


def firestore_client(project_id: str, gcloud_account: str | None):
    if not gcloud_account:
        return create_firestore_client(project_id, None)[0]
    from google.cloud import firestore
    from google.oauth2.credentials import Credentials
    command = shutil.which("gcloud.cmd") or shutil.which("gcloud")
    if not command:
        raise ValueError("gcloud is unavailable")
    result = subprocess.run([command, "auth", "print-access-token", f"--account={gcloud_account}"],
                            capture_output=True, text=True, check=True)
    return firestore.Client(project=project_id, credentials=Credentials(result.stdout.strip()))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--gcloud-account")
    parser.add_argument("--catalog-directory", type=Path, default=Path(__file__).with_name("output"))
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--commit", action="store_true")
    parser.add_argument("--expected-plan-hash")
    args = parser.parse_args()
    if args.commit:
        if not args.expected_plan_hash:
            parser.error("--commit requires --expected-plan-hash from the reviewed dry run")
        plan = json.loads(args.plan.read_text(encoding="utf-8"))
        validate_plan(plan, args.project_id, args.expected_plan_hash)
        client = firestore_client(args.project_id, args.gcloud_account)
        commit_plan(client, plan, args.expected_plan_hash)
        verify_plan(client, plan)
        publish_status(client, plan)
        print(json.dumps({**plan_summary(plan), "mode": "committed-and-verified"}))
    else:
        records = load_records(args.catalog_directory)
        client = firestore_client(args.project_id, args.gcloud_account)
        plan = make_plan(client, records)
        args.plan.write_text(json.dumps(plan, ensure_ascii=False, sort_keys=True), encoding="utf-8")
        print(json.dumps({**plan_summary(plan), "mode": "dry-run"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
