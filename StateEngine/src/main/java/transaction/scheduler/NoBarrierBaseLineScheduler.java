package transaction.scheduler;

import common.OperationChain;
import profiler.MeasureTools;

public class NoBarrierBaseLineScheduler extends BaseLineScheduler {

    public NoBarrierBaseLineScheduler(int tp) {
        super(tp);
    }

    @Override
    public OperationChain next(int threadId) {

        OperationChain oc = getOcForThreadAndDLevel(threadId, currentDLevelToProcess[threadId]);
        while(oc==null) {
            if(areAllOCsScheduled(threadId))
                break;
            currentDLevelToProcess[threadId] += 1;
            oc = getOcForThreadAndDLevel(threadId, currentDLevelToProcess[threadId]);
        }
        MeasureTools.BEGIN_GET_NEXT_THREAD_WAIT_TIME_MEASURE(threadId);
        while(oc!=null && oc.hasDependency()); // Wait for dependency resolution
        MeasureTools.END_GET_NEXT_THREAD_WAIT_TIME_MEASURE(threadId);

        if(oc!=null)
            scheduledOcsCount[threadId] += 1;
        return oc;
    }

}