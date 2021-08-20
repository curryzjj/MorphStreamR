package scheduler.impl;


import content.T_StreamContent;
import profiler.MeasureTools;
import scheduler.Request;
import scheduler.context.SchedulerContext;
import scheduler.struct.AbstractOperation;
import scheduler.struct.Operation;
import scheduler.struct.TaskPrecedenceGraph;
import storage.SchemaRecord;
import storage.TableRecord;
import storage.datatype.DataBox;
import transaction.function.DEC;
import transaction.function.INC;
import utils.SOURCE_CONTROL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@lombok.extern.slf4j.Slf4j
public abstract class Scheduler<Context extends SchedulerContext, Task> implements IScheduler<Context> {
    public final int delta;//range of each partition. depends on the number of op in the stage.
    public final TaskPrecedenceGraph tpg; // TPG to be maintained in this global instance.
    public final Map<Integer, Context> threadToContextMap;

    protected Scheduler(int totalThreads, int NUM_ITEMS) {
        delta = (int) Math.ceil(NUM_ITEMS / (double) totalThreads); // Check id generation in DateGenerator.
        this.tpg = new TaskPrecedenceGraph(totalThreads, delta);
        threadToContextMap = new HashMap<>();
    }

    //TODO: key divide by key range to determine responsible thread.
    public static int getTaskId(String key, Integer delta) {
        Integer _key = Integer.valueOf(key);
        return _key / delta;
    }

    // DD: Transfer event processing
    protected void Transfer_Fun(AbstractOperation operation, long previous_mark_ID, boolean clean) {

        SchemaRecord preValues = operation.condition_records[0].content_.readPreValues(operation.bid);
        SchemaRecord preValues1 = operation.condition_records[1].content_.readPreValues(operation.bid);
        if (preValues == null) {
            log.info("Failed to read condition records[0]" + operation.condition_records[0].record_.GetPrimaryKey());
            log.info("Its version size:" + ((T_StreamContent) operation.condition_records[0].content_).versions.size());
            for (Map.Entry<Long, SchemaRecord> schemaRecord : ((T_StreamContent) operation.condition_records[0].content_).versions.entrySet()) {
                log.info("Its contents:" + schemaRecord.getKey() + " value:" + schemaRecord.getValue() + " current bid:" + operation.bid);
            }
            log.info("TRY reading:" + operation.condition_records[0].content_.readPreValues(operation.bid));//not modified in last round);
        }
        if (preValues1 == null) {
            log.info("Failed to read condition records[1]" + operation.condition_records[1].record_.GetPrimaryKey());
            log.info("Its version size:" + ((T_StreamContent) operation.condition_records[1].content_).versions.size());
            for (Map.Entry<Long, SchemaRecord> schemaRecord : ((T_StreamContent) operation.condition_records[1].content_).versions.entrySet()) {
                log.info("Its contents:" + schemaRecord.getKey() + " value:" + schemaRecord.getValue() + " current bid:" + operation.bid);
            }
            log.info("TRY reading:" + ((T_StreamContent) operation.condition_records[1].content_).versions.get(operation.bid));//not modified in last round);
        }
        final long sourceAccountBalance = preValues.getValues().get(1).getLong();
        final long sourceAssetValue = preValues1.getValues().get(1).getLong();

        if (sourceAccountBalance > operation.condition.arg1
                && sourceAccountBalance > operation.condition.arg2
                && sourceAssetValue > operation.condition.arg3) {
            // read
            SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
            SchemaRecord tempo_record = new SchemaRecord(srcRecord);//tempo record
            // apply function
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
        } else {
            log.info("++++++ operation failed: "
                    + sourceAccountBalance + "-" + operation.condition.arg1
                    + " : " + sourceAccountBalance + "-" + operation.condition.arg2
                    + " : " + sourceAssetValue + "-" + operation.condition.arg3
                    + " condition: " + operation.condition);
        }
    }

    protected void Depo_Fun(AbstractOperation operation, long mark_ID, boolean clean) {
        SchemaRecord srcRecord = operation.s_record.content_.readPreValues(operation.bid);
        List<DataBox> values = srcRecord.getValues();
        //apply function to modify..
        SchemaRecord tempo_record;
        tempo_record = new SchemaRecord(values);//tempo record
        tempo_record.getValues().get(1).incLong(operation.function.delta_long);//compute.
        operation.s_record.content_.updateMultiValues(operation.bid, mark_ID, clean, tempo_record);//it may reduce NUMA-traffic.
        synchronized (operation.success) {
            operation.success[0]++;
        }
    }

    protected abstract void DISTRIBUTE(Task task, Context context);

    @Override
    public boolean FINISHED(Context context) {
        return context.finished();
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

    @Override
    public void RESET() {
        SOURCE_CONTROL.getInstance().oneThreadCompleted();
    }

    @Override
    public void TxnSubmitBegin(Context context) {
        context.requests.clear();
    }

    @Override
    public void TxnSubmitFinished(Context context) {
        MeasureTools.BEGIN_TPG_CONSTRUCTION_TIME_MEASURE(context.thisThreadId);
        // the data structure to store all operations created from the txn, store them in order, which indicates the logical dependency
        List<Operation> operationGraph = new ArrayList<>();
        for (Request request : context.requests) {
            long bid = request.txn_context.getBID();
            Operation set_op = null;
            switch (request.accessType) {
                case READ_WRITE_COND: // they can use the same method for processing
                case READ_WRITE:
                    set_op = new Operation(getTargetContext(request.d_record), request.table_name, request.txn_context, bid, request.accessType,
                            request.d_record, request.function, request.condition, request.condition_records, request.success);
                    break;
                case READ_WRITE_COND_READ:
                    set_op = new Operation(getTargetContext(request.d_record), request.table_name, request.txn_context, bid, request.accessType,
                            request.d_record, request.record_ref, request.function, request.condition, request.condition_records, request.success);
                    break;
            }
            operationGraph.add(set_op);
            tpg.setupOperationTDFD(set_op, request);
        }

        // 4. send operation graph to tpg for tpg construction
//        tpg.setupOperationLD(operationGraph);//TODO: this is bad refactor.
        MeasureTools.END_TPG_CONSTRUCTION_TIME_MEASURE(context.thisThreadId);
    }

    @Override
    public void AddContext(int threadId, Context context) {
        threadToContextMap.put(threadId, context);
    }

    @Override
    public Context getTargetContext(TableRecord d_record) {
        // the thread to submit the operation may not be the thread to execute it.
        // we need to find the target context this thread is mapped to.
        int threadId = getTaskId(d_record.record_.GetPrimaryKey(), delta);
        return threadToContextMap.get(threadId);
    }
}