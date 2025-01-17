package durability.snapshot.SnapshotResult;

import durability.struct.Result.persistResult;

import java.io.Serializable;
import java.nio.file.Path;

/**
 * The snapshot result
 * Snapshot Path
 * Offset
 * */
public class SnapshotResult implements Serializable, persistResult {
    public final String path;
    public final long snapshotId;
    public final int partitionId;
    public transient double size;//in KB


    public SnapshotResult(long snapshotId, int partitionId, String path) {
        this.snapshotId = snapshotId;
        this.partitionId = partitionId;
        this.path = path;
    }
}
