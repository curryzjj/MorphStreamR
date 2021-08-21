package scheduler.context;

import scheduler.struct.bfs.BFSOperation;
import scheduler.struct.bfs.BFSOperationChain;

public class BFSLayeredTPGContext extends LayeredTPGContext<BFSOperation, BFSOperationChain> {

    //TODO: Make it flexible to accept other applications.
    //The table name is hard-coded.
    public BFSLayeredTPGContext(int thisThreadId, int totalThreads) {
        super(thisThreadId, totalThreads);
    }

    @Override
    protected void reset() {
        currentLevel = 0;
        totalOsToSchedule = 0;
        scheduledOPs = 0;
    }

    @Override
    public BFSOperationChain createTask(String tableName, String pKey) {
        return new BFSOperationChain(tableName, pKey);
    }

};