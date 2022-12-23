package db;

import durability.manager.FTManager;
import storage.EventManager;
import storage.StorageManager;
import storage.TableRecord;
import storage.table.RecordSchema;

import java.io.IOException;

public abstract class Database {
    public int numTransactions = 0;//current number of activate transactions
    StorageManager storageManager;
    EventManager eventManager;

    public EventManager getEventManager() {
        return eventManager;
    }
//	public transient TxnParam param;

    /**
     * Close this database.
     */
    public synchronized void close() throws IOException {
        storageManager.close();
    }

    /**
     *
     */
    public void dropAllTables() throws IOException {
        storageManager.dropAllTables();
    }

    /**
     * @param tableSchema
     * @param tableName
     * @param partition_num
     * To snapshot and recover in parallel, we need separate table for each partition
     */
    public void createTable(RecordSchema tableSchema, String tableName, int partition_num, int num_items) {
        try {
            storageManager.createTable(tableSchema, tableName, partition_num, num_items);
        } catch (DatabaseException e) {
            e.printStackTrace();
        }
    }

    public abstract void InsertRecord(String table, TableRecord record, int partition_id) throws DatabaseException;

    public StorageManager getStorageManager() {
        return storageManager;
    }

    /**
     * To parallel take a snapshot
     *
     * @param snapshotId
     * @param partitionId
     * @throws Exception
     */
    public abstract void parallelSnapshot(final long snapshotId, final int partitionId, final FTManager ftManager) throws Exception;
}
