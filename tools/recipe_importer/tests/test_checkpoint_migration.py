"""Synthetic-only tests for fail-closed checkpoint migration."""

import hashlib
import json
from pathlib import Path
import sqlite3

import pytest

from tools.recipe_importer.checkpoint_migration import (
    CheckpointMigrationError,
    migrate_checkpoint,
    snapshot_checkpoint,
)


OLD_RUN_KEY = "1" * 64
NEW_RUN_KEY = "2" * 64


def create_checkpoint(
    path: Path,
    records: list[tuple[str, str, str]],
    *,
    run_key: str = OLD_RUN_KEY,
    reverse_inserts: bool = False,
) -> None:
    with sqlite3.connect(path) as connection:
        connection.execute(
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
        )
        connection.execute(
            "CREATE TABLE records ("
            "url TEXT PRIMARY KEY,lastmod TEXT NOT NULL,payload TEXT NOT NULL)"
        )
        connection.execute(
            "INSERT INTO meta(key,value) VALUES('runKey',?)",
            (run_key,),
        )
        values = list(reversed(records)) if reverse_inserts else records
        connection.executemany(
            "INSERT INTO records(url,lastmod,payload) VALUES(?,?,?)",
            values,
        )


def strict_json(value: object) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def test_snapshot_digest_is_deterministic_but_binds_exact_row_bytes(tmp_path):
    rows = [
        ("https://example.test/a", "2026-01-01", strict_json({"id": "a"})),
        ("https://example.test/b", "2026-01-02", strict_json({"id": "b"})),
    ]
    first = tmp_path / "first.sqlite3"
    second = tmp_path / "second.sqlite3"
    changed = tmp_path / "changed.sqlite3"
    create_checkpoint(first, rows)
    create_checkpoint(second, rows, reverse_inserts=True)
    create_checkpoint(
        changed,
        [rows[0], (rows[1][0], rows[1][1], '{"id": "b"}')],
    )

    first_snapshot = snapshot_checkpoint(first)
    second_snapshot = snapshot_checkpoint(second)
    changed_snapshot = snapshot_checkpoint(changed)

    assert first_snapshot == second_snapshot
    assert first_snapshot.record_count == 2
    assert first_snapshot.digest != changed_snapshot.digest


@pytest.mark.parametrize("wrong_gate", ["run_key", "digest"])
def test_exact_source_gates_fail_before_validator_or_destination(
    tmp_path,
    wrong_gate,
):
    source = tmp_path / "source.sqlite3"
    destination = tmp_path / "destination.sqlite3"
    create_checkpoint(
        source,
        [("https://example.test/a", "2026-01-01", strict_json({"id": "a"}))],
    )
    snapshot = snapshot_checkpoint(source)
    calls = []

    with pytest.raises(CheckpointMigrationError, match="mismatch"):
        migrate_checkpoint(
            source,
            destination,
            expected_source_run_key=("9" * 64 if wrong_gate == "run_key" else OLD_RUN_KEY),
            expected_source_digest=("9" * 64 if wrong_gate == "digest" else snapshot.digest),
            destination_run_key=NEW_RUN_KEY,
            validate_record=lambda record: calls.append(record) or record.payload,
        )

    assert calls == []
    assert not destination.exists()
    assert list(tmp_path.glob(".destination.sqlite3.*.tmp")) == []


def test_migration_publishes_only_validated_records_into_a_new_database(tmp_path):
    source = tmp_path / "source.sqlite3"
    destination = tmp_path / "destination.sqlite3"
    rows = [
        (
            "https://example.test/refetch",
            "2026-01-01",
            strict_json({"id": "refetch", "safe": False}),
        ),
        (
            "https://example.test/reuse",
            "2026-01-02",
            strict_json({"id": "reuse", "safe": True}),
        ),
    ]
    create_checkpoint(source, rows)
    source_bytes_before = hashlib.sha256(source.read_bytes()).hexdigest()
    source_snapshot = snapshot_checkpoint(source)

    def validator(record):
        if not record.payload["safe"]:
            return None
        return {**record.payload, "validated": True}

    result = migrate_checkpoint(
        source,
        destination,
        expected_source_run_key=OLD_RUN_KEY,
        expected_source_digest=source_snapshot.digest,
        destination_run_key=NEW_RUN_KEY,
        validate_record=validator,
    )

    assert result.source == source_snapshot
    assert result.migrated_record_count == 1
    assert result.skipped_record_count == 1
    assert result.destination.run_key == NEW_RUN_KEY
    assert result.destination.record_count == 1
    assert destination.is_file()
    assert hashlib.sha256(source.read_bytes()).hexdigest() == source_bytes_before
    with sqlite3.connect(destination) as connection:
        assert connection.execute(
            "SELECT value FROM meta WHERE key='runKey'"
        ).fetchone() == (NEW_RUN_KEY,)
        assert connection.execute(
            "SELECT url,lastmod,payload FROM records"
        ).fetchone() == (
            "https://example.test/reuse",
            "2026-01-02",
            strict_json({"id": "reuse", "safe": True, "validated": True}),
        )


def test_validator_failure_never_publishes_partial_database(tmp_path):
    source = tmp_path / "source.sqlite3"
    destination = tmp_path / "destination.sqlite3"
    create_checkpoint(
        source,
        [
            ("https://example.test/a", "1", strict_json({"id": "a"})),
            ("https://example.test/b", "2", strict_json({"id": "b"})),
        ],
    )
    snapshot = snapshot_checkpoint(source)

    def validator(record):
        if record.payload["id"] == "b":
            raise ValueError("synthetic rejection")
        return record.payload

    with pytest.raises(CheckpointMigrationError, match="validator failed.*synthetic rejection"):
        migrate_checkpoint(
            source,
            destination,
            expected_source_run_key=OLD_RUN_KEY,
            expected_source_digest=snapshot.digest,
            destination_run_key=NEW_RUN_KEY,
            validate_record=validator,
        )

    assert not destination.exists()
    assert list(tmp_path.glob(".destination.sqlite3.*.tmp")) == []


def test_existing_destination_is_never_overwritten(tmp_path):
    source = tmp_path / "source.sqlite3"
    destination = tmp_path / "destination.sqlite3"
    create_checkpoint(source, [])
    destination.write_bytes(b"keep me")
    snapshot = snapshot_checkpoint(source)

    with pytest.raises(CheckpointMigrationError, match="already exists"):
        migrate_checkpoint(
            source,
            destination,
            expected_source_run_key=OLD_RUN_KEY,
            expected_source_digest=snapshot.digest,
            destination_run_key=NEW_RUN_KEY,
            validate_record=lambda record: record.payload,
        )

    assert destination.read_bytes() == b"keep me"
