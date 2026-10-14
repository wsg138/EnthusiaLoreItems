"""Safety regressions: python3 -m unittest discover -s tools -p 'test_legacy_block_key_audit.py' -v."""
import json
import shutil
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from legacy_block_key_audit import audit, classify, inspect_projection_links


class KeyFormatTests(unittest.TestCase):
    def test_historical_overworld_and_negative_coordinates(self):
        self.assertEqual(("legacy_world_name", "minecraft:overworld"),
                         classify("BLOCK_CONTAINER", "minecraft:overworld:-1:70:8"))

    def test_uuid_block_is_not_legacy_even_nested(self):
        key = "block-v2:11111111-2222-3333-4444-555555555555:-1:70:8"
        self.assertEqual(("modern_block_v2", None), classify("BLOCK_CONTAINER", key))
        self.assertEqual(("modern_block_v2", None),
                         classify("NESTED_CONTAINER", "root:BLOCK_CONTAINER:" + key))

    def test_both_chest_halves_retained_in_key(self):
        key = "double-chest:11111111-2222-3333-4444-555555555555:-1:70:8|0:70:8:left=1"
        self.assertEqual(("modern_double_chest", None), classify("BLOCK_CONTAINER", key))

    def test_never_guess_unrecognized_or_malformed(self):
        self.assertEqual(("malformed_versioned_root", None),
                         classify("BLOCK_CONTAINER", "block-v2:bad-uuid:1:2:3"))
        self.assertEqual(("other_nested_root", None),
                         classify("NESTED_CONTAINER", "root:PLAYER_INVENTORY:minecraft:world:1:2:3"))

    def test_world_named_entity_holder_is_counted_but_never_mapped(self):
        entity = "11111111-2222-3333-4444-555555555555"
        key = f"minecraft:overworld:entity:{entity}"
        self.assertEqual(("world_named_entity_inventory", None),
                         classify("BLOCK_CONTAINER", key))
        self.assertEqual(("world_named_entity_inventory", None),
                         classify("NESTED_CONTAINER", "root:BLOCK_CONTAINER:" + key))
        self.assertEqual(("not_block_location", None),
                         classify("DROPPED_ITEM", key))

    def test_dropped_item_key_is_not_a_block_location(self):
        self.assertEqual(("not_block_location", None),
                         classify("DROPPED_ITEM", "minecraft:overworld:1:2:3"))


class OfflineSQLiteTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "snapshot.sqlite"
        with sqlite3.connect(self.path) as connection:
            connection.executescript("""
                CREATE TABLE instance_current_state (location_type TEXT,location_key TEXT,state TEXT);
                CREATE TABLE instance_observations (
                    observation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    instance_id TEXT NOT NULL,
                    location_type TEXT,location_key TEXT
                );
                CREATE INDEX idx_test_instance ON instance_observations(instance_id);
                INSERT INTO instance_current_state VALUES
                    ('BLOCK_CONTAINER','minecraft:world:-1:70:8','LAST_CONFIRMED'),
                    ('NESTED_CONTAINER','root:BLOCK_CONTAINER:minecraft:world:2:70:8','CONFLICTING'),
                    ('BLOCK_CONTAINER','block-v2:11111111-2222-3333-4444-555555555555:2:70:8','CONFIRMED_NOW');
                INSERT INTO instance_observations(instance_id,location_type,location_key) VALUES
                    ('legacy-1','BLOCK_CONTAINER','minecraft:world:-1:70:8'),
                    ('legacy-1','BLOCK_CONTAINER','minecraft:world:-1:70:8'),
                    ('dropped-1','DROPPED_ITEM','minecraft:world:1:2:3');
            """)

    def test_counts_without_database_mutation_or_uuid_guess(self):
        before = self.path.read_bytes()
        result = audit(self.path)
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(3, result["tables"]["instance_current_state"]["rows"])
        self.assertEqual(2, result["tables"]["instance_current_state"]["categories"]["legacy_world_name"])
        self.assertEqual(2, result["tables"]["instance_observations"]["categories"]["legacy_world_name"])
        self.assertEqual({"CONFLICTING": 1, "LAST_CONFIRMED": 1},
                         result["tables"]["instance_current_state"]["legacy_current_state_counts"])
        self.assertEqual({}, result["tables"]["instance_observations"]["legacy_current_state_counts"])
        self.assertEqual("UNRESOLVED",
                         result["tables"]["instance_current_state"]["legacy_world_name_groups"][0]["classification"])
        self.assertFalse(result["assessment"]["world_uuid_inferred"])
        self.assertFalse(result["assessment"]["safe_to_deploy"])
        self.assertFalse(result["empty_tracking_snapshot"]["eligible_for_empty_database_path"])

    def test_entity_world_named_keys_are_aggregated_without_entity_identifiers(self):
        entity = '11111111-2222-3333-4444-555555555555'
        with sqlite3.connect(self.path) as connection:
            connection.execute(
                'INSERT INTO instance_current_state VALUES (?,?,?)',
                ('BLOCK_CONTAINER', f'minecraft:overworld:entity:{entity}',
                 'CONFIRMED_NOW'))
            connection.execute(
                'INSERT INTO instance_observations(instance_id,location_type,location_key) '
                'VALUES (?,?,?)',
                ('private-entity-test', 'BLOCK_CONTAINER',
                 f'minecraft:overworld:entity:{entity}'))
            connection.commit()
        report = audit(self.path)
        self.assertEqual(1, report['tables']['instance_current_state']
                         ['categories']['world_named_entity_inventory'])
        self.assertEqual(1, report['tables']['instance_observations']
                         ['categories']['world_named_entity_inventory'])
        self.assertNotIn(entity, json.dumps(report))
        self.assertNotIn('private-entity-test', json.dumps(report))
        self.assertFalse(report['assessment']['safe_to_deploy'])

    def test_history_overlap_reports_one_and_multiple_observed_uuids_without_mapping(self):
        first = '11111111-2222-3333-4444-555555555555'
        second = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
        with sqlite3.connect(self.path) as connection:
            connection.executemany(
                'INSERT INTO instance_observations(instance_id,location_type,location_key) '
                'VALUES (?,?,?)', [
                    ('mixed-one', 'BLOCK_CONTAINER', 'minecraft:world:-1:70:8'),
                    ('mixed-one', 'BLOCK_CONTAINER', f'block-v2:{first}:-1:70:8'),
                    ('mixed-one', 'NESTED_CONTAINER',
                     f'root:BLOCK_CONTAINER:block-v2:{first}:-1:70:8'),
                    ('mixed-two', 'BLOCK_CONTAINER', 'minecraft:world:20:70:8'),
                    ('mixed-two', 'BLOCK_CONTAINER', f'block-v2:{first}:20:70:8'),
                    ('mixed-two', 'BLOCK_CONTAINER', f'block-v2:{second}:20:70:8'),
                    ('mixed-reverse', 'BLOCK_CONTAINER', f'block-v2:{first}:5:70:8'),
                    ('mixed-reverse', 'NESTED_CONTAINER',
                     'root:BLOCK_CONTAINER:minecraft:world:5:70:8'),
                    ('modern-only', 'BLOCK_CONTAINER', f'block-v2:{first}:22:70:8'),
                ])
            connection.commit()
        before = self.path.read_bytes()
        result = audit(self.path)
        self.assertEqual(before, self.path.read_bytes())
        risk = result['historical_instance_overlap']
        self.assertEqual('REVIEW_ONLY_NO_WORLD_MAPPING', risk['resolution'])
        self.assertEqual({
            'legacy_without_modern_history': 1,
            'legacy_with_one_observed_modern_world': 2,
            'legacy_with_multiple_observed_modern_worlds': 1,
            'modern_without_legacy_history': 1,
            'neither_legacy_nor_modern_block_history': 1,
        }, risk['risk_counts'])
        self.assertEqual({'legacy_first': 2, 'modern_first': 1},
                         risk['mixed_key_arrival_order_counts'])
        self.assertNotIn('mixed-reverse', json.dumps(risk))
        self.assertNotIn('mixed-two', json.dumps(risk))
        self.assertFalse(result['assessment']['world_uuid_inferred'])

    def test_arrival_order_uses_durable_ids_not_index_iteration_order(self):
        from legacy_block_key_audit import inspect_history_overlap
        first = '11111111-2222-3333-4444-555555555555'
        with sqlite3.connect(self.path) as connection:
            connection.executemany(
                'INSERT INTO instance_observations(instance_id,location_type,location_key) '
                'VALUES (?,?,?)', [
                    ('reverse-index-case', 'BLOCK_CONTAINER', f'block-v2:{first}:3:70:8'),
                    ('reverse-index-case', 'BLOCK_CONTAINER', 'minecraft:world:3:70:8'),
                ])
            # A descending index can deliver a newer row before its older
            # counterpart; the earliest observation_id still wins.
            connection.execute(
                'CREATE INDEX idx_reverse_arrival '
                'ON instance_observations(instance_id, observation_id DESC)')
            expected = inspect_history_overlap(connection)
            connection.execute('PRAGMA reverse_unordered_selects=ON')
            actual = inspect_history_overlap(connection)
        self.assertEqual(expected, actual)
        self.assertEqual(1, actual['mixed_key_arrival_order_counts']['modern_first'])
        self.assertNotIn('reverse-index-case', json.dumps(actual))

    def test_duplicate_legacy_events_are_one_instance_not_two(self):
        overlap = audit(self.path)['historical_instance_overlap']['risk_counts']
        self.assertEqual(1, overlap['legacy_without_modern_history'])
        self.assertEqual(1, overlap['neither_legacy_nor_modern_block_history'])
    def test_consistent_sqlite_backup_includes_committed_wal_without_future_writes(self):
        # Entirely synthetic source: a live server database is never touched.
        with sqlite3.connect(self.path) as source:
            self.assertEqual('wal', source.execute('PRAGMA journal_mode=WAL').fetchone()[0])
            source.execute(
                "INSERT INTO instance_current_state VALUES (?,?,?)",
                ('BLOCK_CONTAINER', 'minecraft:world:7:70:8', 'LAST_CONFIRMED'))
            source.commit()
            snapshot = self.path.with_name('consistent-offline-copy.sqlite')
            with sqlite3.connect(snapshot) as target:
                source.backup(target)
        frozen = audit(snapshot)
        self.assertEqual(3, frozen['tables']['instance_current_state']
                         ['categories']['legacy_world_name'])
        self.assertEqual(2, frozen['tables']['instance_current_state']
                         ['legacy_current_state_counts']['LAST_CONFIRMED'])
        self.assertEqual('ok', frozen['quick_check'])
        # Later changes to the source are never visible in the offline snapshot.
        with sqlite3.connect(self.path) as source:
            source.execute(
                "INSERT INTO instance_current_state VALUES (?,?,?)",
                ('BLOCK_CONTAINER', 'minecraft:world:9:70:8', 'CONFIRMED_NOW'))
            source.commit()
        self.assertEqual(frozen, audit(snapshot))
    def test_wal_checkpoint_backup_restore_rehearsal_keeps_exact_prior_history(self):
        # This is a disposable restore, never a copy of a running SMP file.
        snapshot = self.path.with_name('wal-consistent-recovery.sqlite')
        restored = self.path.with_name('restored-separately.sqlite')
        with sqlite3.connect(self.path) as source:
            self.assertEqual('wal', source.execute('PRAGMA journal_mode=WAL').fetchone()[0])
            source.execute(
                "INSERT INTO instance_observations(instance_id, location_type, location_key) "
                "VALUES (?,?,?)",
                ('pre-checkpoint', 'BLOCK_CONTAINER', 'minecraft:world:-17:64:0'))
            source.commit()
            with sqlite3.connect(snapshot) as backup:
                source.backup(backup)
            expected = audit(snapshot)
            before_bytes = snapshot.read_bytes()
            # Mutate only the throwaway *source*, then force the WAL onto the
            # main DB file. A subsequent restore must not inherit these writes.
            source.execute(
                "INSERT INTO instance_observations(instance_id, location_type, location_key) "
                "VALUES (?,?,?)",
                ('post-checkpoint', 'BLOCK_CONTAINER', 'minecraft:world:-16:64:0'))
            source.commit()
            checkpoint = source.execute('PRAGMA wal_checkpoint(TRUNCATE)').fetchone()
            self.assertEqual(0, checkpoint[0])
            self.assertNotEqual(expected['tables']['instance_observations']['rows'],
                                audit(self.path)['tables']['instance_observations']['rows'])
        shutil.copyfile(snapshot, restored)
        self.assertEqual(expected, audit(restored))
        self.assertEqual(before_bytes, snapshot.read_bytes())
        self.assertEqual('ok', audit(restored)['quick_check'])
        self.assertFalse(audit(restored)['assessment']['safe_to_deploy'])

    def test_cli_explicit_offline_acknowledgement(self):
        command = [sys.executable, str(Path(__file__).with_name("legacy_block_key_audit.py")),
                   str(self.path)]
        refused = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertNotEqual(0, refused.returncode)
        self.assertIn("--confirm-offline-copy", refused.stderr)
        allowed = subprocess.run(command + ["--confirm-offline-copy"],
                                 capture_output=True, text=True, check=False)
        self.assertEqual(0, allowed.returncode, allowed.stderr)
        self.assertEqual(2, json.loads(allowed.stdout)["tables"]
                         ["instance_current_state"]["categories"]["legacy_world_name"])

    def test_never_overwrites_source_path_with_report(self):
        command = [sys.executable, str(Path(__file__).with_name("legacy_block_key_audit.py")),
                   str(self.path), "--confirm-offline-copy", "--output", str(self.path)]
        denied = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertNotEqual(0, denied.returncode)

    def test_report_output_refuses_overwriting_existing_backup_or_report(self):
        original = self.path.with_name('existing-backup.sqlite')
        original.write_bytes(b'preexisting critical backup')
        command = [sys.executable, str(Path(__file__).with_name(
                   'legacy_block_key_audit.py')), str(self.path),
                   '--confirm-offline-copy', '--output', str(original)]
        refused = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertNotEqual(0, refused.returncode)
        self.assertIn('Audit report refused:', refused.stderr)
        self.assertEqual(b'preexisting critical backup', original.read_bytes())
        new_report = self.path.with_name('new-report.json')
        allowed = subprocess.run(command[:-1] + [str(new_report)],
                                 capture_output=True, text=True, check=False)
        self.assertEqual(0, allowed.returncode, allowed.stderr)
        self.assertIn('projection_link_consistency', json.loads(new_report.read_text()))
        refused_again = subprocess.run(command[:-1] + [str(new_report)],
                                       capture_output=True, text=True, check=False)
        self.assertNotEqual(0, refused_again.returncode)
    def test_invalid_schema_is_not_a_successful_report(self):
        empty = self.path.with_name("not-loreitems.sqlite")
        with sqlite3.connect(empty):
            pass
        with self.assertRaises(ValueError):
            audit(empty)


class ProjectionLinkPreflightTests(unittest.TestCase):
    def test_inconsistent_current_states_do_not_mutate_offline_sqlite(self):
        with tempfile.TemporaryDirectory() as folder:
            database = Path(folder) / 'links.sqlite'
            with sqlite3.connect(database) as source:
                source.executescript("""
                    CREATE TABLE instance_observations(
                        observation_id INTEGER PRIMARY KEY,
                        instance_id TEXT NOT NULL,
                        confidence TEXT NOT NULL,
                        location_type TEXT NOT NULL,
                        location_key TEXT NOT NULL,
                        container_path TEXT,
                        observed_at INTEGER
                    );
                    CREATE TABLE instance_current_state(
                        instance_id TEXT PRIMARY KEY,
                        state TEXT, location_type TEXT, location_key TEXT,
                        container_path TEXT, last_observation_id INTEGER,
                        updated_at INTEGER
                    );
                    INSERT INTO instance_observations VALUES
                       (1,'a','CONFIRMED_NOW','BLOCK_CONTAINER','minecraft:world:1:2:3','slot:0',900),
                       (2,'b','CONFLICTING','BLOCK_CONTAINER','minecraft:world:2:2:3','slot:1',900),
                       (3,'d','CONFLICTING','DUPLICATE_CONFLICT','instance:d','copy1=old;copy2=new',900),
                       (4,'j','CONFIRMED_NOW','BLOCK_CONTAINER','minecraft:world:5:2:3','slot:1',900),
                       (5,'k','CONFIRMED_NOW','BLOCK_CONTAINER','minecraft:world:6:2:3','slot:1',1000);
                    INSERT INTO instance_current_state VALUES
                       ('a','CONFIRMED_NOW','BLOCK_CONTAINER','minecraft:world:1:2:3','slot:0',1,1000),
                       ('b','LAST_CONFIRMED','BLOCK_CONTAINER','minecraft:world:2:2:3','slot:1',2,1000),
                       ('c','CONFIRMED_NOW','BLOCK_CONTAINER','minecraft:world:3:2:3','slot:1',99,1000),
                       ('d','CONFLICTING','DUPLICATE_CONFLICT','instance:d','wrong-location',3,1000),
                       ('e','CONFIRMED_NOW','BLOCK_CONTAINER','minecraft:world:2:2:3','slot:1',2,1000),
                       ('f','MISSING_UNRESOLVED',NULL,NULL,NULL,NULL,1000),
                       ('g','MISSING_UNRESOLVED',NULL,'invalid',NULL,NULL,1000),
                       ('h','CONFIRMED_NOW','BLOCK_CONTAINER','minecraft:world:4:2:3','slot:1',NULL,1000),
                       ('i','UNKNOWN','BLOCK_CONTAINER','minecraft:world:1:2:3','slot:0',1,1000),
                       ('j','LAST_CONFIRMED','BLOCK_CONTAINER','minecraft:world:5:2:3','slot:1',4,1000),
                       ('k','CONFIRMED_NOW','BLOCK_CONTAINER','minecraft:world:6:2:3','slot:1',5,900);
                """)
            before = database.read_bytes()
            with sqlite3.connect(database) as source:
                findings = inspect_projection_links(source)
            self.assertEqual(before, database.read_bytes())
            self.assertEqual('REVIEW_REQUIRED', findings['assessment'])
            self.assertEqual(8, findings['inconsistent_rows'])
            self.assertEqual({
                'linked_projection_valid': 2, 'state_confidence_mismatch': 1,
                'dangling_observation_pointer': 1, 'projection_location_mismatch': 1,
                'cross_instance_observation_pointer': 1, 'valid_unresolved': 1,
                'invalid_unresolved': 1, 'missing_observation_pointer': 1,
                'unknown_projection_state': 1, 'projection_predates_observation': 1,
            }, findings['counts'])

    def test_last_confirmed_can_reference_confirmed_now_without_failing(self):
        from legacy_block_key_audit import confidence_matches
        self.assertTrue(confidence_matches('LAST_CONFIRMED', 'CONFIRMED_NOW'))
        self.assertTrue(confidence_matches('LAST_CONFIRMED', 'LAST_CONFIRMED'))
        self.assertFalse(confidence_matches('CONFIRMED_NOW', 'LAST_CONFIRMED'))
        self.assertFalse(confidence_matches('CONFLICTING', 'CONFIRMED_NOW'))
    def test_minimal_schema_is_reported_incompatible_not_assumed_healthy(self):
        with sqlite3.connect(':memory:') as source:
            source.execute('CREATE TABLE instance_current_state (location_type TEXT)')
            source.execute('CREATE TABLE instance_observations (location_type TEXT)')
            findings = inspect_projection_links(source)
        self.assertFalse(findings['schema_compatible'])
        self.assertEqual('REVIEW_REQUIRED_SCHEMA_INCOMPLETE', findings['assessment'])

    def test_projection_link_report_never_contains_instance_ids(self):
        with tempfile.TemporaryDirectory() as folder:
            database = Path(folder) / 'snapshot.sqlite'
            with sqlite3.connect(database) as source:
                source.executescript("""
                    CREATE TABLE instance_current_state (location_type TEXT, location_key TEXT, state TEXT);
                    CREATE TABLE instance_observations (instance_id TEXT, location_type TEXT, location_key TEXT);
                    INSERT INTO instance_observations VALUES ('private-person', 'BLOCK_CONTAINER', 'minecraft:world:1:2:3');
                """)
            report = audit(database)
            self.assertNotIn('private-person', json.dumps(report))
            self.assertEqual('REVIEW_REQUIRED_SCHEMA_INCOMPLETE',
                             report['projection_link_consistency']['assessment'])
            self.assertEqual('REVIEW_REQUIRED_SCHEMA_INCOMPLETE',
                             report['historical_instance_overlap']['resolution'])
            self.assertEqual({}, report['historical_instance_overlap']
                             ['mixed_key_arrival_order_counts'])



class EmptyTrackingGateTests(unittest.TestCase):
    def test_zero_row_snapshot_is_only_a_clean_test_path_not_release_approval(self):
        with tempfile.TemporaryDirectory() as folder:
            database = Path(folder) / "empty.sqlite"
            with sqlite3.connect(database) as connection:
                connection.executescript("""
                    CREATE TABLE instance_current_state (
                        instance_id TEXT, state TEXT, location_type TEXT,
                        location_key TEXT, container_path TEXT,
                        last_observation_id INTEGER, updated_at INTEGER
                    );
                    CREATE TABLE instance_observations (
                        observation_id INTEGER, instance_id TEXT, confidence TEXT,
                        location_type TEXT, location_key TEXT, container_path TEXT,
                        observed_at INTEGER
                    );
                """)
            report = audit(database)
            self.assertTrue(report["empty_tracking_snapshot"]
                            ["eligible_for_empty_database_path"])
            self.assertFalse(report["assessment"]["safe_to_deploy"])
            command = [sys.executable, str(Path(__file__).with_name(
                "legacy_block_key_audit.py")), str(database),
                "--confirm-offline-copy", "--require-empty-tracking"]
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(json.loads(result.stdout)["empty_tracking_snapshot"]
                            ["eligible_for_empty_database_path"])
            with sqlite3.connect(database) as connection:
                connection.execute(
                    "INSERT INTO instance_observations "
                    "(observation_id,instance_id,confidence,location_type,"
                    "location_key,container_path,observed_at) VALUES "
                    "(1,'historical-item','CONFIRMED_NOW','BLOCK_CONTAINER',"
                    "'minecraft:world:1:70:8','slot:0',1000)")
                connection.commit()
            refused = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(3, refused.returncode)
            self.assertIn("not verified empty/compatible", refused.stderr)
            self.assertNotIn("historical-item", refused.stderr)

if __name__ == "__main__":
    unittest.main()
