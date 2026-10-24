package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.sqlite.SQLitePhysicalProofReconciliationPlanner.*;
import net.enthusia.loreitems.sqlite.SQLitePhysicalProofReconciliationWriter.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises real SQLite transaction semantics in temporary files, never a production DB. */
class SQLitePhysicalProofReconciliationWriterTest {
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID WORLD = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String LEGACY = "minecraft:world:15:70:8";
    private static final String SLOT = "slot:0";
    @TempDir Path temp;

    @Test void appendCasReplayAndWalReopenPreserveOriginalHistory() throws Exception {
        Fixture f = fixture("replay.sqlite");
        try (Connection c = open(f.path)) {
            String old = history(c, f.sourceId);
            Result applied = SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), f.proof());
            assertEquals(Status.APPLIED, applied.status());
            assertTrue(applied.observationId() > f.sourceId);
            assertEquals("LAST_CONFIRMED", scalar(c,
                    "SELECT state FROM instance_current_state WHERE instance_id = '" + ID + "'"));
            assertEquals("physical_proof_DISPOSABLE_REHEARSAL", scalar(c,
                    "SELECT source FROM instance_observations WHERE observation_id = " + applied.observationId()));
            assertEquals(old, history(c, f.sourceId));
            assertEquals(2, count(c, "instance_observations"));
            assertEquals(1, count(c, "audit_events"));
            assertEquals(1, count(c, "reconciliation_rehearsal_receipts"));
            assertEquals(Status.ALREADY_APPLIED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), f.proof()).status());
            assertEquals(2, count(c, "instance_observations"));
        }
        try (Connection reopened = open(f.path)) {
            assertEquals(Status.ALREADY_APPLIED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(
                            reopened, f.current(), f.proof()).status());
            assertEquals(2, count(reopened, "instance_observations"));
            assertEquals(1, count(reopened, "audit_events"));
            assertEquals("ok", scalar(reopened, "PRAGMA integrity_check"));
            assertEquals("0", scalar(reopened, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test void wrongReplayPayloadDoesNotWriteOrReinterpretLocation() throws Exception {
        Fixture f = fixture("mismatch.sqlite");
        try (Connection c = open(f.path)) {
            assertEquals(Status.APPLIED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), f.proof()).status());
            PhysicalProof changed = f.proof(WORLD, true, 1, 5, 1101);
            assertEquals(Status.REFUSED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), changed).status());
            assertEquals(2, count(c, "instance_observations"));
        }
    }

    @Test void missingMarkerIsHardFailureBeforeAnyWrite() throws Exception {
        Fixture f = fixture("marker.sqlite");
        try (Connection c = open(f.path); Statement s = c.createStatement()) {
            s.executeUpdate("DROP TABLE reconciliation_rehearsal_guard");
            assertThrows(SQLException.class,
                    () -> SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), f.proof()));
            assertEquals(1, count(c, "instance_observations"));
        }
    }

    @Test void rejectsHistoryOnlyAndStaleRevision() throws Exception {
        Fixture f = fixture("stale.sqlite");
        try (Connection c = open(f.path); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE instance_current_state SET state_revision = 1");
            assertEquals(Status.REFUSED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), f.proof()).status());
            assertEquals(1, count(c, "instance_observations"));
        }
        Fixture modern = fixture("modern.sqlite");
        try (Connection c = open(modern.path); Statement s = c.createStatement()) {
            String location = "block-v2:" + WORLD + ":15:70:8";
            s.executeUpdate("UPDATE instance_current_state SET location_key = '" + location + "'");
            assertEquals(Status.REFUSED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(
                            c, modern.current(), modern.proof()).status());
            assertEquals(1, count(c, "instance_observations"));
        }
    }

    @Test void rejectsUnloadedDoubleHalfDuplicateAndUnverifiedLineage() throws Exception {
        Fixture f = fixture("partial.sqlite");
        try (Connection c = open(f.path)) {
            assertEquals(Status.REFUSED, SQLitePhysicalProofReconciliationWriter.rehearse(
                    c, f.current(), f.proof(WORLD, false, 1, 5, 1100)).status());
            assertEquals(Status.REFUSED, SQLitePhysicalProofReconciliationWriter.rehearse(
                    c, f.current(), f.proof(WORLD, true, 2, 5, 1100)).status());
            assertEquals(Status.REFUSED, SQLitePhysicalProofReconciliationWriter.rehearse(
                    c, f.current(), f.proof(WORLD, true, 1, 6, 1100)).status());
            assertEquals(1, count(c, "instance_observations"));
        }
    }

    @Test void unsettledOrUndurableFenceBlocksWrites() throws Exception {
        Fixture f = fixture("fence.sqlite");
        try (Connection c = open(f.path); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE reconciliation_rehearsal_fence SET dirty = 1");
            assertEquals(Status.REFUSED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), f.proof()).status());
            s.executeUpdate("UPDATE reconciliation_rehearsal_fence SET dirty = 0, ack_durable = 0");
            assertEquals(Status.REFUSED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), f.proof()).status());
            assertEquals(1, count(c, "instance_observations"));
        }
    }

    @Test void activeAnomalyBlocksOtherwiseEligibleRehearsal() throws Exception {
        Fixture f = fixture("anomaly.sqlite");
        try (Connection c = open(f.path); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO instance_anomalies(anomaly_id,instance_id,definition_id,"
                    + "anomaly_type,status,detail,first_seen_at,last_seen_at) VALUES("
                    + "'anom','" + ID + "','" + f.definitionId + "','DUPLICATE_INSTANCE',"
                    + "'OPEN','synthetic conflict',10,10)");
            assertEquals(Status.REFUSED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), f.proof()).status());
            assertEquals(1, count(c, "instance_observations"));
        }
    }

    @Test void injectedFailuresRollbackAllFourStages() throws Exception {
        for (Failpoint at : new Failpoint[]{ Failpoint.AFTER_OBSERVATION,
                Failpoint.AFTER_CAS, Failpoint.AFTER_RECEIPT, Failpoint.AFTER_AUDIT }) {
            Fixture f = fixture("rollback-" + at + ".sqlite");
            try (Connection c = open(f.path)) {
                String original = history(c, f.sourceId);
                assertThrows(SQLException.class,
                        () -> SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), f.proof(), at));
                assertEquals(original, history(c, f.sourceId));
                assertEquals(1, count(c, "instance_observations"));
                assertEquals(0, count(c, "reconciliation_rehearsal_receipts"));
                assertEquals(0, count(c, "audit_events"));
                assertEquals("0", scalar(c,
                        "SELECT state_revision FROM instance_current_state WHERE instance_id = '" + ID + "'"));
            }
            try (Connection reopened = open(f.path)) {
                assertEquals(1, count(reopened, "instance_observations"));
                assertEquals(Status.APPLIED, SQLitePhysicalProofReconciliationWriter.rehearse(
                        reopened, f.current(), f.proof()).status());
            }
        }
    }

    @Test void auditInsertFailureRollsBackObservationAndCas() throws Exception {
        Fixture f = fixture("audit.sqlite");
        try (Connection c = open(f.path); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TRIGGER deny_rehearsal_audit BEFORE INSERT ON audit_events "
                    + "BEGIN SELECT RAISE(ABORT, 'synthetic audit failure'); END");
            assertThrows(SQLException.class,
                    () -> SQLitePhysicalProofReconciliationWriter.rehearse(c, f.current(), f.proof()));
            assertEquals(1, count(c, "instance_observations"));
            assertEquals(0, count(c, "reconciliation_rehearsal_receipts"));
            assertEquals("0", scalar(c,
                    "SELECT state_revision FROM instance_current_state WHERE instance_id = '" + ID + "'"));
        }
    }

    @Test void walWriterLockFailsClosedAndReopensCleanly() throws Exception {
        Fixture f = fixture("lock.sqlite");
        try (Connection lock = open(f.path); Connection other = open(f.path);
                Statement first = lock.createStatement(); Statement second = other.createStatement()) {
            second.execute("PRAGMA busy_timeout = 10");
            first.execute("BEGIN IMMEDIATE");
            assertThrows(SQLException.class, () -> SQLitePhysicalProofReconciliationWriter.rehearse(
                    other, f.current(), f.proof()));
            first.execute("ROLLBACK");
            assertEquals(1, count(other, "instance_observations"));
            assertEquals(Status.APPLIED, SQLitePhysicalProofReconciliationWriter.rehearse(
                    other, f.current(), f.proof()).status());
        }
    }

    @Test void fullGeometryInvertedHalvesProducesCanonicalSlot53() throws Exception {
        Fixture f = fixture("chest.sqlite");
        try (Connection c = open(f.path); Statement s = c.createStatement()) {
            String old = "minecraft:world:16:70:8";
            s.executeUpdate("UPDATE instance_current_state SET location_key='" + old
                    + "',container_path='slot:26'");
            s.executeUpdate("UPDATE instance_observations SET location_key='" + old
                    + "',container_path='slot:26' WHERE observation_id=" + f.sourceId);
            Geometry geometry = new Geometry(new Position(15,70,8),new Position(16,70,8),
                    new Position(15,70,8),true,true,true,true);
            PhysicalProof proof = new PhysicalProof(ID,WORLD,Holder.DOUBLE_CHEST,geometry,
                    "slot:53",53,1,0,f.sourceId,5,1100,0,true,true,false,true);
            CurrentEvidence current = new CurrentEvidence(ID,new LocationDescriptor(
                    LocationDescriptor.Type.BLOCK_CONTAINER,old,"slot:26"),
                    InstanceCurrentState.State.CONFIRMED_NOW,0,f.sourceId,1000,5,false);
            Result result = SQLitePhysicalProofReconciliationWriter.rehearse(c,current,proof);
            assertEquals(Status.APPLIED,result.status());
            assertEquals("slot:53",scalar(c,
                    "SELECT container_path FROM instance_current_state WHERE instance_id='" + ID + "'"));
        }
    }

    @Test void negativeWorldCoordinatesAndNestedPathsAreNotGuessed() throws Exception {
        Fixture negative = fixture("negative.sqlite");
        try (Connection c = open(negative.path); Statement s = c.createStatement()) {
            String old = "minecraft:world:-17:70:-1";
            s.executeUpdate("UPDATE instance_current_state SET location_key='" + old + "'");
            s.executeUpdate("UPDATE instance_observations SET location_key='" + old
                    + "' WHERE observation_id=" + negative.sourceId);
            CurrentEvidence current = new CurrentEvidence(ID,
                    new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,old,SLOT),
                    InstanceCurrentState.State.CONFIRMED_NOW,0,negative.sourceId,1000,5,false);
            PhysicalProof witness = new PhysicalProof(ID,WORLD,Holder.SINGLE_CHEST,
                    new Geometry(new Position(-17,70,-1),null,null,true,false,true,true),
                    SLOT,0,1,0,negative.sourceId,5,1100,0,true,true,false,true);
            assertEquals(Status.APPLIED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(c,current,witness).status());
            assertEquals("block-v2:" + WORLD + ":-17:70:-1",
                    scalar(c,"SELECT location_key FROM instance_current_state WHERE instance_id='" + ID + "'"));
        }

        Fixture nested = fixture("nested.sqlite");
        try (Connection c = open(nested.path); Statement s = c.createStatement()) {
            String old = "root:BLOCK_CONTAINER:" + LEGACY;
            String path = "slot:0/shulker:5";
            s.executeUpdate("UPDATE instance_current_state SET location_type='NESTED_CONTAINER',"
                    + "location_key='" + old + "',container_path='" + path + "'");
            s.executeUpdate("UPDATE instance_observations SET location_type='NESTED_CONTAINER',"
                    + "location_key='" + old + "',container_path='" + path
                    + "' WHERE observation_id=" + nested.sourceId);
            CurrentEvidence current = new CurrentEvidence(ID,
                    new LocationDescriptor(LocationDescriptor.Type.NESTED_CONTAINER,old,path),
                    InstanceCurrentState.State.CONFIRMED_NOW,0,nested.sourceId,1000,5,false);
            PhysicalProof witness = new PhysicalProof(ID,WORLD,Holder.SINGLE_CHEST,
                    new Geometry(new Position(15,70,8),null,null,true,false,true,true),
                    path,0,1,0,nested.sourceId,5,1100,0,true,true,false,true);
            assertEquals(Status.APPLIED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(c,current,witness).status());
            assertEquals("root:BLOCK_CONTAINER:block-v2:" + WORLD + ":15:70:8",
                    scalar(c,"SELECT location_key FROM instance_current_state WHERE instance_id='" + ID + "'"));
            assertEquals(path,scalar(c,
                    "SELECT container_path FROM instance_current_state WHERE instance_id='" + ID + "'"));
        }
    }

    @Test void reversedPhysicalLeftHalfMapsLocalSlotZeroToCombined27() throws Exception {
        Fixture f = fixture("orientation.sqlite");
        try (Connection c = open(f.path)) {
            Geometry geometry = new Geometry(new Position(15,70,8),new Position(16,70,8),
                    new Position(16,70,8),true,true,true,true);
            PhysicalProof witness = new PhysicalProof(ID,WORLD,Holder.DOUBLE_CHEST,geometry,
                    "slot:27",27,1,0,f.sourceId,5,1100,0,true,true,false,true);
            assertEquals(Status.APPLIED,
                    SQLitePhysicalProofReconciliationWriter.rehearse(c,f.current(),witness).status());
            assertEquals("slot:27",scalar(c,
                    "SELECT container_path FROM instance_current_state WHERE instance_id='" + ID + "'"));
        }
    }

    @Test void modifiedSourceReceiptFailsClosedDespiteUnchangedProjection() throws Exception {
        Fixture f = fixture("source-mismatch.sqlite");
        try (Connection c = open(f.path); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE instance_observations SET location_key='minecraft:other:15:70:8'"
                    + " WHERE observation_id=" + f.sourceId);
            assertEquals(Status.REFUSED,SQLitePhysicalProofReconciliationWriter.rehearse(
                    c,f.current(),f.proof()).status());
            assertEquals(1,count(c,"instance_observations"));
        }
    }

    private Fixture fixture(String filename) throws SQLException {
        Path path = temp.resolve(filename);
        String definition = "22222222-3333-4444-5555-666666666666";
        try (Connection c = open(path); Statement s = c.createStatement()) {
            new MigrationRunner().migrate(c);
            s.executeUpdate("CREATE TABLE reconciliation_rehearsal_guard(marker TEXT PRIMARY KEY)");
            s.executeUpdate("INSERT INTO reconciliation_rehearsal_guard VALUES "
                    + "('LOREITEMS_DISPOSABLE_REHEARSAL_ONLY_V1')");
            s.executeUpdate("CREATE TABLE reconciliation_rehearsal_fence ("
                    + "instance_id TEXT PRIMARY KEY, generation INTEGER NOT NULL, "
                    + "dirty INTEGER NOT NULL, ack_durable INTEGER NOT NULL, "
                    + "source_observation_id INTEGER NOT NULL,old_updated_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE reconciliation_rehearsal_receipts ("
                    + "instance_id TEXT NOT NULL,source_observation_id INTEGER NOT NULL,"
                    + "generation INTEGER NOT NULL,new_observation_id INTEGER NOT NULL,"
                    + "location_key TEXT NOT NULL,container_path TEXT NOT NULL,"
                    + "captured_at INTEGER NOT NULL,old_state TEXT NOT NULL,"
                    + "old_revision INTEGER NOT NULL,old_updated_at INTEGER NOT NULL,"
                    + "PRIMARY KEY(instance_id,source_observation_id,generation),"
                    + "UNIQUE(new_observation_id))");
            s.executeUpdate("INSERT INTO lore_definitions"
                    + "(definition_id,lookup_key,display_name,current_revision,created_at) VALUES "
                    + "('" + definition + "','rehearsal_item','Test Item',1,10)");
            s.executeUpdate("INSERT INTO lore_definition_revisions"
                    + "(definition_id,revision,codec_version,template_blob,created_at) VALUES "
                    + "('" + definition + "',1,1,X'01',10)");
            s.executeUpdate("INSERT INTO lore_instances"
                    + "(instance_id,definition_id,applied_revision,desired_revision,"
                    + "lifecycle_state,created_at) VALUES "
                    + "('" + ID + "','" + definition + "',1,1,'ACTIVE',10)");
            s.executeUpdate("INSERT INTO instance_observations"
                    + "(instance_id,definition_id,location_type,location_key,container_path,"
                    + "confidence,source,observed_at) VALUES ('" + ID + "','" + definition
                    + "','BLOCK_CONTAINER','" + LEGACY + "','" + SLOT
                    + "','CONFIRMED_NOW','synthetic-original',900)");
            long receipt = Long.parseLong(scalar(c, "SELECT MAX(observation_id) FROM instance_observations"));
            s.executeUpdate("INSERT INTO instance_current_state(instance_id,state,location_type,"
                    + "location_key,container_path,last_observation_id,state_revision,updated_at) VALUES ('"
                    + ID + "','CONFIRMED_NOW','BLOCK_CONTAINER','" + LEGACY + "','" + SLOT
                    + "'," + receipt + ",0,1000)");
            s.executeUpdate("INSERT INTO reconciliation_rehearsal_fence VALUES ('" + ID
                    + "',5,0,1," + receipt + ",1000)");
            return new Fixture(path, definition, receipt);
        }
    }

    private static Connection open(Path path) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA journal_mode = WAL");
        }
        return c;
    }

    private static int count(Connection c, String table) throws SQLException {
        return Integer.parseInt(scalar(c, "SELECT COUNT(*) FROM " + table));
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    private static String history(Connection c, long id) throws SQLException {
        try (PreparedStatement q = c.prepareStatement(
                "SELECT instance_id || '|' || definition_id || '|' || location_type || '|' || "
              + "location_key || '|' || container_path || '|' || confidence || '|' || "
              + "source || '|' || observed_at FROM instance_observations WHERE observation_id = ?")) {
            q.setLong(1,id);
            try (ResultSet r = q.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    private record Fixture(Path path, String definitionId, long sourceId) {
        CurrentEvidence current() {
            return new CurrentEvidence(ID,new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                    LEGACY,SLOT),InstanceCurrentState.State.CONFIRMED_NOW,0,sourceId,1000,5,false);
        }
        PhysicalProof proof() { return proof(WORLD,true,1,5,1100); }
        PhysicalProof proof(UUID world, boolean fullyLoaded, int count, long generation, long captured) {
            return new PhysicalProof(ID, world, Holder.SINGLE_CHEST,
                    new Geometry(new Position(15,70,8),null,null,fullyLoaded,false,true,true),
                    SLOT,0,count,0,sourceId,generation,captured,0,true,true,false,true);
        }
    }
}
