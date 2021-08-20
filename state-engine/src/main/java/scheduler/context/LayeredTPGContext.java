package scheduler.context;

import scheduler.struct.Operation;
import scheduler.struct.OperationChain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;

public class LayeredTPGContext extends SchedulerContext {

    public HashMap<Integer, ArrayList<OperationChain>> layeredOCBucketGlobal;// <LevelID, ArrayDeque<OperationChain>
    public int currentLevel;
    public int currentLevelIndex;
    public int totalThreads;
    public int maxLevel;//total number of operations to process per thread.
    public int scheduledOPs;//current number of operations processed per thread.
    public int totalOsToSchedule;//total number of operations to process per thread.
    public OperationChain ready_oc;//ready operation chain per thread.
    public ArrayDeque<Operation> abortedOperations;//aborted operations per thread.
    public boolean aborted;//if any operation is aborted during processing.

    //TODO: Make it flexible to accept other applications.
    //The table name is hard-coded.
    public LayeredTPGContext(int thisThreadId, int totalThreads) {
        super(thisThreadId);
        this.totalThreads = totalThreads;
        this.layeredOCBucketGlobal = new HashMap<>();
        this.abortedOperations = new ArrayDeque<>();
        requests = new ArrayDeque<>();
    }

    @Override
    protected void reset() {
        currentLevel = 0;
        totalOsToSchedule = 0;
        scheduledOPs = 0;
    }

    public ArrayList<OperationChain> OCSCurrentLayer() {
        return layeredOCBucketGlobal.get(currentLevel);
    }

    @Override
    public boolean finished() {
        return scheduledOPs == totalOsToSchedule && !aborted;
    }

};