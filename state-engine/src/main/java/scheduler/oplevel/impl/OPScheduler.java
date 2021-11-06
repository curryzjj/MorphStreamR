package scheduler.oplevel.impl;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import profiler.MeasureTools;
import scheduler.Request;
import scheduler.impl.IScheduler;
import scheduler.oplevel.context.OPSchedulerContext;
import scheduler.oplevel.struct.AbstractOperation;
import scheduler.oplevel.struct.MetaTypes;
import scheduler.oplevel.struct.Operation;
import scheduler.oplevel.struct.TaskPrecedenceGraph;
import storage.SchemaRecord;
import storage.TableRecord;
import storage.datatype.DataBox;
import transaction.function.DEC;
import transaction.function.INC;
import utils.SOURCE_CONTROL;
import utils.UDF;

import java.util.List;

import static content.common.CommonMetaTypes.AccessType.*;

public abstract class OPScheduler<Context extends OPSchedulerContext, Task> implements IScheduler<Context> {
    private static final Logger log = LoggerFactory.getLogger(OPScheduler.class);
    public final int delta;//range of each partition. depends on the number of op in the stage.
    public final TaskPrecedenceGraph<Context> tpg; // TPG to be maintained in this global instance.

    public OPScheduler(int totalThreads, int NUM_ITEMS) {
        delta = (int) Math.ceil(NUM_ITEMS / (double) totalThreads); // Check id generation in DateGenerator.
        this.tpg = new TaskPrecedenceGraph<>(totalThreads, delta, NUM_ITEMS);
    }

    /**
     * state to thread mapping
     *
     * @param key
     * @param delta
     * @return
     */
    public static int getTaskId(String key, Integer delta) {
        Integer _key = Integer.valueOf(key);
        return _key / delta;
    }

    public Context getTargetContext(String key) {
        // the thread to submit the operation may not be the thread to execute it.
        // we need to find the target context this thread is mapped to.
        int threadId =  Integer.parseInt(key) / delta;
        return tpg.threadToContextMap.get(threadId);
    }

    public Context getTargetContext(TableRecord d_record) {
        // the thread to submit the operation may not be the thread to execute it.
        // we need to find the target context this thread is mapped to.
        int threadId = getTaskId(d_record.record_.GetPrimaryKey(), delta);
        return tpg.threadToContextMap.get(threadId);
    }

    /**
     * Used by tpgScheduler.
     *
     * @param operation
     * @param mark_ID
     * @param clean
     */
    public void execute(Operation operation, long mark_ID, boolean clean) {
//        log.trace("++++++execute: " + operation);
        // if the operation is in state aborted or committable or committed, we can bypass the execution
        if (operation.getOperationState().equals(MetaTypes.OperationStateType.ABORTED)) {
            //otherwise, skip (those +already been tagged as aborted).
            return;
        }
        int success;
        if (operation.accessType.equals(READ_WRITE_COND_READ)) {
            success = operation.success[0];
            Transfer_Fun(operation, mark_ID, clean);
            // check whether needs to return a read results of the operation
            if (operation.record_ref != null) {
                operation.record_ref.setRecord(operation.d_record.content_.readPreValues(operation.bid));//read the resulting tuple.
            }
            // operation success check, number of operation succeeded does not increase after execution
            if (operation.success[0] == success) {
                operation.isFailed = true;
            }
        } else if (operation.accessType.equals(READ_WRITE_COND)) {
            success = operation.success[0];
            Transfer_Fun(operation, mark_ID, clean);
            // operation success check, number of operation succeeded does not increase after execution
            if (operation.success[0] == success) {
                operation.isFailed = true;
            }
        } else if (operation.accessType.equals(READ_WRITE)) {
            Depo_Fun(operation, mark_ID, clean);
        } else {
            throw new UnsupportedOperationException();
        }

        assert operation.getOperationState() != MetaTypes.OperationStateType.EXECUTED;
    }

    // DD: Transfer event processing
    protected void Transfer_Fun(AbstractOperation operation, long previous_mark_ID, boolean clean) {
        SchemaRecord preValues = operation.condition_records[0].content_.readPreValues(operation.bid);
//        if (preValues == null) {
//            log.info("Failed to read condition records[0]" + operation.condition_records[0].record_.GetPrimaryKey());
//            log.info("Its version size:" + ((T_StreamContent) operation.condition_records[0].content_).versions.size());
//            for (Map.Entry<Long, SchemaRecord> schemaRecord : ((T_StreamContent) operation.condition_records[0].content_).versions.entrySet()) {
//                log.info("Its contents:" + schemaRecord.getKey() + " value:" + schemaRecord.getValue() + " current bid:" + operation.bid);
//            }
//            log.info("TRY reading:" + operation.condition_records[0].content_.readPreValues(operation.bid));//not modified in last round);
//        }
        final long sourceAccountBalance = preValues.getValues().get(1).getLong();

        if (sourceAccountBalance > operation.condition.arg1
                && sourceAccountBalance > operation.condition.arg2) {
            // read
            SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
            SchemaRecord tempo_record = new SchemaRecord(srcRecord);//tempo record
            // apply function
            UDF.randomDelay();
            if (operation.function instanceof INC) {
                tempo_record.getValues().get(1).incLong(sourceAccountBalance, operation.function.delta_long);//compute.
            } else if (operation.function instanceof DEC) {
                tempo_record.getValues().get(1).decLong(sourceAccountBalance, operation.function.delta_long);//compute.
            } else
                throw new UnsupportedOperationException();
            operation.d_record.content_.updateMultiValues(operation.bid, previous_mark_ID, clean, tempo_record);//it may reduce NUMA-traffic.
            synchronized (operation.success) {
                operation.success[0]++;
            }
        }
//        else {
//            log.info("++++++ operation failed: "
//                    + sourceAccountBalance + "-" + operation.condition.arg1
//                    + " : " + sourceAccountBalance + "-" + operation.condition.arg2
////                    + " : " + sourceAssetValue + "-" + operation.condition.arg3
//                    + " condition: " + operation.condition);
//        }
    }

    protected void Depo_Fun(AbstractOperation operation, long mark_ID, boolean clean) {
        SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
        List<DataBox> values = srcRecord.getValues();
        //apply function to modify..
        SchemaRecord tempo_record;
        tempo_record = new SchemaRecord(values);//tempo record
        UDF.randomDelay();
        tempo_record.getValues().get(1).incLong(operation.function.delta_long);//compute.
        operation.s_record.content_.updateMultiValues(operation.bid, mark_ID, clean, tempo_record);//it may reduce NUMA-traffic.
    }

    @Override
    public void AddContext(int threadId, Context context) {
        tpg.threadToContextMap.put(threadId, context);
        tpg.setOCs(context);
    }

    /**
     * Submit requests to target thread --> data shuffling is involved.
     *
     * @param context
     * @param request
     * @return
     */
    @Override
    public boolean SubmitRequest(Context context, Request request) {
        context.push(request);
        return false;
    }

    protected abstract void DISTRIBUTE(Task task, Context context);

    @Override
    public void RESET(Context context) {
        SOURCE_CONTROL.getInstance().oneThreadCompleted();
        context.reset();
        tpg.reset(context);
    }

    protected abstract void NOTIFY(Operation operation, Context context);

    public void start_evaluation(Context context, long mark_ID, int num_events) {
        int threadId = context.thisThreadId;

        INITIALIZE(context);

        do {
            MeasureTools.BEGIN_SCHEDULE_EXPLORE_TIME_MEASURE(threadId);
            EXPLORE(context);
            MeasureTools.END_SCHEDULE_EXPLORE_TIME_MEASURE(threadId);
            MeasureTools.BEGIN_SCHEDULE_USEFUL_TIME_MEASURE(threadId);
            PROCESS(context, mark_ID);
            MeasureTools.END_SCHEDULE_USEFUL_TIME_MEASURE(threadId);
        } while (!FINISHED(context));
        RESET(context);//
        MeasureTools.SCHEDULE_TIME_RECORD(threadId, num_events);
    }
}
