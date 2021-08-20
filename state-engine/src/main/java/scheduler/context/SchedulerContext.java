package scheduler.context;

import scheduler.Request;
import scheduler.struct.OperationChain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;


class PartitionStateManager {
    public final ArrayList<String> partition; //  list of states being responsible for

    public PartitionStateManager() {
        this.partition = new ArrayList<>();
    }
}

public abstract class SchedulerContext {
    public final PartitionStateManager partitionStateManager;
    protected final ConcurrentLinkedDeque<OperationChain> IsolatedOC;
    protected final ConcurrentLinkedDeque<OperationChain> OCwithChildren;
    public int thisThreadId;
    public ArrayDeque<Request> requests;

    protected SchedulerContext(int thisThreadId) {
        this.thisThreadId = thisThreadId;
        partitionStateManager = new PartitionStateManager();
        IsolatedOC = new ConcurrentLinkedDeque<>();
        OCwithChildren = new ConcurrentLinkedDeque<>();
    }

    public abstract boolean finished();

    protected abstract void reset();

    public void UpdateMapping(String key) {
        partitionStateManager.partition.add(key);
    }

    public void push(Request request) {
        requests.push(request);
    }

}