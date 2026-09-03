"""Fail-closed migration primitives for immutable recipe checkpoint snapshots.

This module is deliberately provider-neutral.  A provider integration supplies
the exact accepted old run key/digest and a validator that decides, record by
record, whether a cached normalized payload is provably reusable.  The source
database is opened read-only and the destination is published only after a
complete temporary database has been committed and verified.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import sqlite3
import tempfile
from typing import Any, Iterator
from urllib.parse import quote


SNAPSHOT_FORMAT = b"spoon-checkpoint-snapshot-v1\0"
SHA256_RE = re.compile(r"[0-9a-f]{64}")


class CheckpointMigrationError(ValueError):
    """Raised before a migrated checkpoint can be published."""


@dataclass(frozen=True)
class CheckpointSnapshot:
    run_key: str
    digest: str
    record_count: int
    metadata_count: int


@dataclass(frozen=True)
class CheckpointRecord:
    url: str
    lastmod: str
    payload: Mapping[str, Any]


@dataclass(frozen=True)
class CheckpointMigrationResult:
    source: CheckpointSnapshot
    destination: CheckpointSnapshot
    migrated_record_count: int
    skipped_record_count: int


RecordValidator = Callable[[CheckpointRecord], Mapping[str, Any] | None]


def _read_only_uri(path: Path) -> str:
    normalized = quote(path.resolve().as_posix(), safe="/:")
    return f"file:{normalized}?mode=ro"


@contextmanager
def _read_transaction(path: Path) -> Iterator[sqlite3.Connection]:
    if not path.is_file():
        raise CheckpointMigrationError(f"checkpoint does not exist: {path}")
    connection = sqlite3.connect(
        _read_only_uri(path),
        uri=True,
        timeout=5,
    )
    try:
        connection.execute("PRAGMA query_only=ON")
        connection.execute("BEGIN")
        _validate_checkpoint_schema(connection)
        yield connection
    finally:
        connection.rollback()
        connection.close()


def _validate_checkpoint_schema(connection: sqlite3.Connection) -> None:
    tables = [
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        )
    ]
    if tables != ["meta", "records"]:
        raise CheckpointMigrationError(
            f"unsupported checkpoint tables: {tables!r}"
        )

    expected = {
        "meta": [("key", "TEXT", 1), ("value", "TEXT", 0)],
        "records": [
            ("url", "TEXT", 1),
            ("lastmod", "TEXT", 0),
            ("payload", "TEXT", 0),
        ],
    }
    for table, expected_columns in expected.items():
        columns = [
            (str(row[1]), str(row[2]).upper(), int(row[5]))
            for row in connection.execute(f"PRAGMA table_info({table})")
        ]
        if columns != expected_columns:
            raise CheckpointMigrationError(
                f"unsupported {table} schema: {columns!r}"
            )


def _framed_update(digest: Any, value: str) -> None:
    encoded = value.encode("utf-8")
    digest.update(len(encoded).to_bytes(8, "big"))
    digest.update(encoded)


def _snapshot_from_connection(
    connection: sqlite3.Connection,
) -> CheckpointSnapshot:
    run_keys = connection.execute(
        "SELECT value FROM meta WHERE key='runKey'"
    ).fetchall()
    if len(run_keys) != 1 or not isinstance(run_keys[0][0], str) or not run_keys[0][0]:
        raise CheckpointMigrationError("checkpoint must contain one non-empty runKey")
    run_key = run_keys[0][0]

    metadata_count = int(connection.execute("SELECT COUNT(*) FROM meta").fetchone()[0])
    record_count = int(connection.execute("SELECT COUNT(*) FROM records").fetchone()[0])
    digest = hashlib.sha256(SNAPSHOT_FORMAT)
    digest.update(metadata_count.to_bytes(8, "big"))
    for key, value in connection.execute(
        "SELECT key,value FROM meta ORDER BY key COLLATE BINARY"
    ):
        if not isinstance(key, str) or not isinstance(value, str):
            raise CheckpointMigrationError("checkpoint metadata must be text")
        _framed_update(digest, key)
        _framed_update(digest, value)
    digest.update(record_count.to_bytes(8, "big"))
    for url, lastmod, payload in connection.execute(
        "SELECT url,lastmod,payload FROM records ORDER BY url COLLATE BINARY"
    ):
        if not all(isinstance(value, str) for value in (url, lastmod, payload)):
            raise CheckpointMigrationError("checkpoint record columns must be text")
        _framed_update(digest, url)
        _framed_update(digest, lastmod)
        _framed_update(digest, payload)
    return CheckpointSnapshot(
        run_key=run_key,
        digest=digest.hexdigest(),
        record_count=record_count,
        metadata_count=metadata_count,
    )


def snapshot_checkpoint(path: Path) -> CheckpointSnapshot:
    """Return a deterministic digest without mutating the checkpoint."""

    with _read_transaction(path) as connection:
        return _snapshot_from_connection(connection)


def _decode_payload(raw_payload: str, *, url: str) -> Mapping[str, Any]:
    def object_without_duplicate_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise CheckpointMigrationError(
                    f"checkpoint payload for {url} has duplicate key {key!r}"
                )
            result[key] = value
        return result

    try:
        payload = json.loads(
            raw_payload,
            object_pairs_hook=object_without_duplicate_keys,
            parse_constant=lambda value: (_ for _ in ()).throw(
                CheckpointMigrationError(
                    f"checkpoint payload for {url} contains {value}"
                )
            ),
        )
    except (json.JSONDecodeError, UnicodeError) as exc:
        raise CheckpointMigrationError(
            f"checkpoint payload for {url} is not strict JSON"
        ) from exc
    if not isinstance(payload, Mapping):
        raise CheckpointMigrationError(
            f"checkpoint payload for {url} must be an object"
        )
    return payload


def _create_checkpoint(path: Path, run_key: str) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA journal_mode=DELETE")
    connection.execute("PRAGMA synchronous=FULL")
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
    return connection


def _remove_temporary_files(path: Path) -> None:
    for candidate in (path, Path(f"{path}-journal"), Path(f"{path}-wal"), Path(f"{path}-shm")):
        candidate.unlink(missing_ok=True)


def migrate_checkpoint(
    source_path: Path,
    destination_path: Path,
    *,
    expected_source_run_key: str,
    expected_source_digest: str,
    destination_run_key: str,
    validate_record: RecordValidator,
) -> CheckpointMigrationResult:
    """Copy only validator-approved records into a newly published checkpoint.

    ``None`` from ``validate_record`` deliberately skips a row so the next
    crawl refetches it.  Any exception or malformed return aborts the entire
    migration and leaves the destination absent.
    """

    source_path = Path(source_path)
    destination_path = Path(destination_path)
    if source_path.resolve() == destination_path.resolve():
        raise CheckpointMigrationError("source and destination must differ")
    if destination_path.exists():
        raise CheckpointMigrationError("destination checkpoint already exists")
    if not expected_source_run_key or not destination_run_key:
        raise CheckpointMigrationError("checkpoint run keys must be non-empty")
    if expected_source_run_key == destination_run_key:
        raise CheckpointMigrationError("destination run key must be new")
    if not SHA256_RE.fullmatch(expected_source_digest):
        raise CheckpointMigrationError(
            "expected source digest must be canonical lowercase SHA-256"
        )
    if not callable(validate_record):
        raise CheckpointMigrationError("validate_record callback is required")

    temporary_path: Path | None = None
    target: sqlite3.Connection | None = None
    published = False
    try:
        with _read_transaction(source_path) as source:
            source_snapshot = _snapshot_from_connection(source)
            if not hmac.compare_digest(
                source_snapshot.run_key,
                expected_source_run_key,
            ):
                raise CheckpointMigrationError("source checkpoint runKey mismatch")
            if not hmac.compare_digest(
                source_snapshot.digest,
                expected_source_digest,
            ):
                raise CheckpointMigrationError("source checkpoint digest mismatch")

            # Create no output—not even a temporary file—until both immutable
            # source identity gates have passed inside the same read snapshot.
            destination_path.parent.mkdir(parents=True, exist_ok=True)
            temporary_fd, temporary_name = tempfile.mkstemp(
                prefix=f".{destination_path.name}.",
                suffix=".tmp",
                dir=destination_path.parent,
            )
            os.close(temporary_fd)
            temporary_path = Path(temporary_name)
            target = _create_checkpoint(temporary_path, destination_run_key)
            migrated = 0
            skipped = 0
            for url, lastmod, raw_payload in source.execute(
                "SELECT url,lastmod,payload FROM records ORDER BY url COLLATE BINARY"
            ):
                payload = _decode_payload(raw_payload, url=url)
                checkpoint_record = CheckpointRecord(
                    url=url,
                    lastmod=lastmod,
                    payload=payload,
                )
                try:
                    validated = validate_record(checkpoint_record)
                except Exception as exc:
                    raise CheckpointMigrationError(
                        f"validator failed for {url}: {exc}"
                    ) from exc
                if validated is None:
                    skipped += 1
                    continue
                if not isinstance(validated, Mapping):
                    raise CheckpointMigrationError(
                        f"validator for {url} must return an object or None"
                    )
                try:
                    encoded = json.dumps(
                        dict(validated),
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                        allow_nan=False,
                    )
                except (TypeError, ValueError) as exc:
                    raise CheckpointMigrationError(
                        f"validator for {url} returned a non-JSON payload"
                    ) from exc
                target.execute(
                    "INSERT INTO records(url,lastmod,payload) VALUES(?,?,?)",
                    (url, lastmod, encoded),
                )
                migrated += 1
            target.commit()
            target.close()
            target = None

        with _read_transaction(temporary_path) as completed:
            destination_snapshot = _snapshot_from_connection(completed)
        if destination_snapshot.run_key != destination_run_key:
            raise CheckpointMigrationError("destination runKey verification failed")
        if destination_snapshot.record_count != migrated:
            raise CheckpointMigrationError("destination record count verification failed")

        with temporary_path.open("r+b") as database_file:
            os.fsync(database_file.fileno())
        if destination_path.exists():
            raise CheckpointMigrationError("destination checkpoint appeared during migration")
        os.replace(temporary_path, destination_path)
        published = True
        return CheckpointMigrationResult(
            source=source_snapshot,
            destination=destination_snapshot,
            migrated_record_count=migrated,
            skipped_record_count=skipped,
        )
    finally:
        if target is not None:
            target.close()
        if not published and temporary_path is not None:
            _remove_temporary_files(temporary_path)
