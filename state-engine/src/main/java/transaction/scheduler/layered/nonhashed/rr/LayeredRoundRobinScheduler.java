package transaction.scheduler.layered.nonhashed.rr;

import common.OperationChain;
import transaction.scheduler.layered.nonhashed.LayeredNonHashScheduler;

import java.util.ArrayList;
import java.util.List;

public abstract class LayeredRoundRobinScheduler extends LayeredNonHashScheduler<List<OperationChain>> {
    RRContext<List<OperationChain>> context;
    public LayeredRoundRobinScheduler(int tp) {
        context = new RRContext<>(tp, ArrayList::new);
    }

    /**
     * @param threadId
     * @return
     */
    protected OperationChain Distribute(int threadId) {
        List<OperationChain> ocs = context.layeredOCBucketGlobal.get(context.currentLevel[threadId]);
        OperationChain oc = null;
        int indexOfOC = context.indexOfNextOCToProcess[threadId];
        if (ocs != null) {
            if (ocs.size() > indexOfOC) {
                oc = ocs.get(indexOfOC);
                context.indexOfNextOCToProcess[threadId] = indexOfOC + context.totalThreads;
            } else {
                context.indexOfNextOCToProcess[threadId] = indexOfOC - ocs.size();
            }
        }
        return oc;
    }

    @Override
    public boolean Finished(int threadId) {
        return context.finished(threadId);
    }
}