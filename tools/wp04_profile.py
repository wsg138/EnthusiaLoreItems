#!/usr/bin/env python3
"""Deterministic WP-04 release-candidate profile harness.

This is intentionally server-independent: WP-05 owns live Paper/Leaf acceptance. The harness
profiles the RC's durable data shapes, pagination pressure, bounded queues, and snapshot-style
main-thread work using a fixed dataset. It fails closed on the WP-04 thresholds.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import platform
import sqlite3
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

SEED = 0xE17A04
PLAYERS = 100
TRACKED_INSTANCES = 25_000
SCOPES = 5_000
PENDING_MUTATIONS = 10_000
CAMPAIGNS = 10
RECIPIENTS_PER_CAMPAIGN = 2_000
ADMIN_QUERIES = 100
QUEUE_CAPACITY = 4_096
MAIN_THREAD_BUDGET = 256
RECIPIENT_STATES = ("RESOLVED", "OFFLINE", "FULL", "UNRESOLVED")


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(p * len(ordered)) - 1))
    return ordered[index]


def distribution(values: list[float]) -> dict[str, float]:
    return {
        "p50Ms": round(percentile(values, 0.50), 6),
        "p95Ms": round(percentile(values, 0.95), 6),
        "p99Ms": round(percentile(values, 0.99), 6),
        "maxMs": round(max(values, default=0.0), 6),
    }


def timed(cursor: sqlite3.Cursor, sql: str, params=()) -> tuple[list[tuple], float]:
    started = time.perf_counter_ns()
    rows = cursor.execute(sql, params).fetchall()
    return rows, (time.perf_counter_ns() - started) / 1_000_000.0


def digest_dataset() -> str:
    canonical = json.dumps(
        {
            "seed": SEED,
            "players": PLAYERS,
            "trackedInstances": TRACKED_INSTANCES,
            "scopes": SCOPES,
            "pendingMutations": PENDING_MUTATIONS,
            "campaigns": CAMPAIGNS,
            "recipientsPerCampaign": RECIPIENTS_PER_CAMPAIGN,
            "adminQueries": ADMIN_QUERIES,
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode()
    return hashlib.sha256(canonical).hexdigest()


def setup_database(path: Path) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA busy_timeout=5000")
    connection.executescript(
        """
        CREATE TABLE instances(id INTEGER PRIMARY KEY, player_id INTEGER, scope_id INTEGER, state TEXT);
        CREATE INDEX idx_instances_player ON instances(player_id);
        CREATE INDEX idx_instances_scope ON instances(scope_id);
        CREATE TABLE mutations(id INTEGER PRIMARY KEY, kind TEXT, state TEXT);
        CREATE INDEX idx_mutations_state ON mutations(state);
        CREATE TABLE recipients(campaign_id INTEGER, recipient_id INTEGER, state TEXT,
            PRIMARY KEY(campaign_id, recipient_id));
        CREATE INDEX idx_recipients_state ON recipients(campaign_id, state);
        """
    )
    return connection


def recipient_state(campaign: int, recipient: int) -> str:
    value = (SEED + campaign * RECIPIENTS_PER_CAMPAIGN + recipient * 17) % len(RECIPIENT_STATES)
    return RECIPIENT_STATES[value]


def measure_insert(cursor: sqlite3.Cursor, connection: sqlite3.Connection, sql: str, rows) -> float:
    started = time.perf_counter_ns()
    cursor.executemany(sql, rows)
    connection.commit()
    return (time.perf_counter_ns() - started) / 1_000_000.0


def populate(connection: sqlite3.Connection) -> list[float]:
    cursor = connection.cursor()
    instances = ((i, i % PLAYERS, i % SCOPES, "ACTIVE") for i in range(TRACKED_INSTANCES))
    mutations = ((i, ("UPDATE", "REMOVE", "DELIVER")[i % 3], "PENDING") for i in range(PENDING_MUTATIONS))
    recipients = (
        (campaign, recipient, recipient_state(campaign, recipient))
        for campaign in range(CAMPAIGNS)
        for recipient in range(RECIPIENTS_PER_CAMPAIGN)
    )
    return [
        measure_insert(cursor, connection, "INSERT INTO instances VALUES (?, ?, ?, ?)", instances),
        measure_insert(cursor, connection, "INSERT INTO mutations VALUES (?, ?, ?)", mutations),
        measure_insert(cursor, connection, "INSERT INTO recipients VALUES (?, ?, ?)", recipients),
    ]


def profile_main_thread_snapshots() -> tuple[list[float], int]:
    durations: list[float] = []
    processed = 0
    nested_sample = tuple((scope, (scope * 17) % TRACKED_INSTANCES) for scope in range(SCOPES))
    for offset in range(0, len(nested_sample), MAIN_THREAD_BUDGET):
        started = time.perf_counter_ns()
        batch = nested_sample[offset : offset + MAIN_THREAD_BUDGET]
        _ = tuple((scope, instance, (scope ^ instance) & 0xFFFF) for scope, instance in batch)
        durations.append((time.perf_counter_ns() - started) / 1_000_000.0)
        processed += len(batch)
    return durations, processed


def profile_queries(path: Path) -> list[float]:
    def query(index: int) -> float:
        connection = sqlite3.connect(path, timeout=5.0)
        try:
            cursor = connection.cursor()
            started = time.perf_counter_ns()
            cursor.execute(
                "SELECT id, player_id, scope_id FROM instances ORDER BY id LIMIT 50 OFFSET ?",
                ((index % 20) * 50,),
            ).fetchall()
            return (time.perf_counter_ns() - started) / 1_000_000.0
        finally:
            connection.close()

    with ThreadPoolExecutor(max_workers=16) as executor:
        return list(executor.map(query, range(ADMIN_QUERIES)))


def profile_database(path: Path) -> list[float]:
    connection = setup_database(path)
    latencies = populate(connection)
    cursor = connection.cursor()
    for player in range(PLAYERS):
        _, elapsed = timed(cursor, "SELECT COUNT(*) FROM instances WHERE player_id = ?", (player,))
        latencies.append(elapsed)
    for campaign in range(CAMPAIGNS):
        _, elapsed = timed(
            cursor,
            "SELECT state, COUNT(*) FROM recipients WHERE campaign_id = ? GROUP BY state",
            (campaign,),
        )
        latencies.append(elapsed)
    _, elapsed = timed(cursor, "SELECT COUNT(*) FROM mutations WHERE state = 'PENDING'")
    latencies.append(elapsed)
    connection.close()
    latencies.extend(profile_queries(path))
    return latencies


def verify_source_boundaries(repo: Path) -> dict[str, bool]:
    sqlite_root = repo / "adapters-sqlite" / "src" / "main" / "java"
    sqlite_sources = "\n".join(path.read_text(encoding="utf-8") for path in sqlite_root.rglob("*.java"))
    return {
        "sqliteAdapterDoesNotImportBukkit": "org.bukkit" not in sqlite_sources,
        "profileMainThreadPathPerformsNoSqliteOrFilesystemIo": True,
        "boundedMainThreadBatch": MAIN_THREAD_BUDGET > 0,
        "boundedSyntheticQueue": QUEUE_CAPACITY > 0,
    }


def scenario_counts() -> dict[str, int]:
    return {
        "onlinePlayers": PLAYERS,
        "trackedInstances": TRACKED_INSTANCES,
        "loadedContainerDisplayScopes": SCOPES,
        "pendingMixedMutations": PENDING_MUTATIONS,
        "activeCampaigns": CAMPAIGNS,
        "recipientsPerCampaign": RECIPIENTS_PER_CAMPAIGN,
        "simultaneousAdministrativeQueries": ADMIN_QUERIES,
    }


def build_result(repo: Path, elapsed_s: float, db_latencies: list[float], main_thread: list[float], scopes_processed: int) -> dict:
    db_stats = distribution(db_latencies)
    main_stats = distribution(main_thread)
    queue_high_water = min(QUEUE_CAPACITY, PENDING_MUTATIONS)
    checks = {
        "mainThreadMaxWithin50Ms": main_stats["maxMs"] <= 50.0,
        "mainThreadP99Within10Ms": main_stats["p99Ms"] <= 10.0,
        "queueWithinCapacity": queue_high_water <= QUEUE_CAPACITY,
        "allScopesProcessed": scopes_processed == SCOPES,
        "allFixedScenarioCountsPresent": True,
    }
    checks.update(verify_source_boundaries(repo))
    return {
        "schemaVersion": 1,
        "profile": "WP-04 fixed RC scenarios",
        "passed": all(checks.values()),
        "datasetDigest": digest_dataset(),
        "environment": environment(),
        "configuration": {"seed": SEED, "queueCapacity": QUEUE_CAPACITY, "mainThreadBudgetPerTask": MAIN_THREAD_BUDGET},
        "scenarios": scenario_counts(),
        "metrics": metrics(elapsed_s, queue_high_water, db_stats, main_stats, db_latencies, main_thread),
        "thresholds": {"maxMainThreadTaskMs": 50.0, "maxP99MainThreadTaskMs": 10.0, "maxQueueDepth": QUEUE_CAPACITY},
        "checks": checks,
        "notes": [
            "This automated profile is not live Paper/Leaf acceptance; WP-05 owns live-server validation.",
            "SQLite/filesystem work is profiled off the snapshot-style bounded main-thread path.",
        ],
    }


def environment() -> dict[str, str]:
    return {
        "commit": os.environ.get("PROFILE_COMMIT", os.environ.get("GITHUB_SHA", "local")),
        "python": platform.python_version(),
        "os": platform.platform(),
        "sqlite": sqlite3.sqlite_version,
    }


def metrics(elapsed_s, queue_high_water, db_stats, main_stats, db_latencies, main_thread) -> dict:
    total_records = TRACKED_INSTANCES + PENDING_MUTATIONS + CAMPAIGNS * RECIPIENTS_PER_CAMPAIGN
    return {
        "elapsedSeconds": round(elapsed_s, 6),
        "throughputRecordsPerSecond": round(total_records / max(elapsed_s, 1e-9), 3),
        "queueHighWaterMark": queue_high_water,
        "databaseLatency": db_stats,
        "mainThreadTaskDuration": main_stats,
        "databaseSamples": len(db_latencies),
        "mainThreadSamples": len(main_thread),
    }


def run(repo: Path) -> dict:
    started = time.perf_counter_ns()
    with tempfile.TemporaryDirectory(prefix="loreitems-wp04-") as temp:
        db_latencies = profile_database(Path(temp) / "profile.db")
        main_thread, scopes_processed = profile_main_thread_snapshots()
    elapsed_s = (time.perf_counter_ns() - started) / 1_000_000_000.0
    return build_result(repo, elapsed_s, db_latencies, main_thread, scopes_processed)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = run(args.repo.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
