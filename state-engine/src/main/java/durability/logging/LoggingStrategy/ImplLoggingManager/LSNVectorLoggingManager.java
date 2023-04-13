package durability.logging.LoggingStrategy.ImplLoggingManager;

import common.collections.Configuration;
import common.collections.OsUtils;
import common.io.ByteIO.DataInputView;
import common.io.ByteIO.InputWithDecompression.NativeDataInputView;
import common.io.ByteIO.InputWithDecompression.SnappyDataInputView;
import durability.ftmanager.FTManager;
import durability.logging.LoggingEntry.LVLogRecord;
import durability.logging.LoggingResource.ImplLoggingResources.LSNVectorLoggingResources;
import durability.logging.LoggingResult.Attachment;
import durability.logging.LoggingResult.LoggingHandler;
import durability.logging.LoggingStrategy.LoggingManager;
import durability.logging.LoggingStream.ImplLoggingStreamFactory.NIOLSNVectorStreamFactory;
import durability.recovery.RedoLogResult;
import durability.recovery.histroyviews.HistoryViews;
import durability.recovery.lsnvector.CSContext;
import durability.recovery.lsnvector.CommandPrecedenceGraph;
import durability.snapshot.LoggingOptions;
import durability.struct.Logging.LVCLog;
import durability.struct.Logging.LoggingEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import storage.SchemaRecord;
import storage.TableRecord;
import storage.datatype.DataBox;
import storage.table.BaseTable;
import storage.table.RecordSchema;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

import transaction.function.DEC;
import transaction.function.INC;
import utils.AppConfig;
import utils.SOURCE_CONTROL;
import utils.lib.ConcurrentHashMap;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.nio.file.StandardOpenOption.READ;
import static utils.FaultToleranceConstants.CompressionType.None;

public class LSNVectorLoggingManager implements LoggingManager {
    private static final Logger LOG = LoggerFactory.getLogger(LSNVectorLoggingManager.class);
    @Nonnull
    protected String loggingPath;
    protected int app;
    @Nonnull protected LoggingOptions loggingOptions;
    public ConcurrentHashMap<Integer, LVLogRecord> threadToLVLogRecord = new ConcurrentHashMap<>();
    protected int parallelNum;
    protected Map<String, BaseTable> tables;
    //Used when recovery
    public CommandPrecedenceGraph cpg = new CommandPrecedenceGraph();
    public LSNVectorLoggingManager(Map<String, BaseTable> tables, Configuration configuration) {
        this.tables = tables;
        loggingPath = configuration.getString("rootFilePath") + OsUtils.OS_wrapper("logging");
        parallelNum = configuration.getInt("parallelNum");
        loggingOptions = new LoggingOptions(parallelNum, configuration.getString("compressionAlg"));
        app = configuration.getInt("app");
        for (int i = 0; i < parallelNum; i ++) {
            this.threadToLVLogRecord.put(i, new LVLogRecord(i));
        }
    }
    public LSNVectorLoggingResources syncPrepareResource(int partitionId) {
        return new LSNVectorLoggingResources(partitionId, this.threadToLVLogRecord.get(partitionId));
    }
    @Override
    public void addLogRecord(LoggingEntry logRecord) {
        LVCLog lvcLog = (LVCLog) logRecord;
        LVLogRecord lvLogRecord = threadToLVLogRecord.get(lvcLog.threadId);
        TableRecord tableRecord = this.tables.get(lvcLog.tableName).SelectKeyRecord(lvcLog.key);
        TableRecord[] conditions = new TableRecord[lvcLog.condition.length];
        for (int i = 0; i < lvcLog.condition.length; i ++) {
            conditions[i] = this.tables.get(lvcLog.tableName).SelectKeyRecord(lvcLog.condition[i]);
        }
        lvLogRecord.addLog(lvcLog, tableRecord, parallelNum, conditions);
    }

    @Override
    public void commitLog(long groupId, int partitionId, FTManager ftManager) throws IOException {
        NIOLSNVectorStreamFactory lsnVectorStreamFactory = new NIOLSNVectorStreamFactory(loggingPath);
        LSNVectorLoggingResources resources = syncPrepareResource(partitionId);
        AsynchronousFileChannel afc = lsnVectorStreamFactory.createLoggingStream();
        Attachment attachment = new Attachment(lsnVectorStreamFactory.getPath(), groupId, partitionId,afc, ftManager);
        ByteBuffer dataBuffer = resources.createWriteBuffer(loggingOptions);
        afc.write(dataBuffer, 0, attachment, new LoggingHandler());
    }

    @Override
    public void syncRetrieveLogs(RedoLogResult redoLogResult) throws IOException, ExecutionException, InterruptedException {
        this.cpg.addContext(redoLogResult.threadId, new CSContext(redoLogResult.threadId));
        for (int i = 0; i < redoLogResult.redoLogPaths.size(); i ++) {
            Path walPath = Paths.get(redoLogResult.redoLogPaths.get(i));
            AsynchronousFileChannel afc = AsynchronousFileChannel.open(walPath, READ);
            int fileSize = (int) afc.size();
            ByteBuffer dataBuffer = ByteBuffer.allocate(fileSize);
            Future<Integer> result = afc.read(dataBuffer, 0);
            result.get();
            DataInputView inputView;
            if (loggingOptions.getCompressionAlg() != None) {
                inputView = new SnappyDataInputView(dataBuffer);//Default to use Snappy compression
            } else {
                inputView = new NativeDataInputView(dataBuffer);
            }
            byte[] object = inputView.readFullyDecompression();
            String[] strings = new String(object, StandardCharsets.UTF_8).split(" ");
            for (String log : strings) {
                LVCLog lvcLog = LVCLog.getLVCLogFromString(log);
                this.cpg.addTask(redoLogResult.threadId, lvcLog);
            }
            LOG.info("Thread " + redoLogResult.threadId + " has finished reading logs");
            SOURCE_CONTROL.getInstance().waitForOtherThreads(redoLogResult.threadId);
            start_evaluate(this.cpg.getContext(redoLogResult.threadId));
            SOURCE_CONTROL.getInstance().waitForOtherThreads(redoLogResult.threadId);
        }
    }
    private void start_evaluate(CSContext context) {
        INITIALIZE(context);
        do {
            EXPLORE(context);
            PROCESS(context);
        } while (!context.isFinished());
        RESET(context);
    }
    private void INITIALIZE(CSContext context) {
       this.cpg.init_globalLv(context);
       SOURCE_CONTROL.getInstance().waitForOtherThreads(context.threadId);
    }
    private void EXPLORE(CSContext context) {
        LVCLog lvcLog = context.tasks.pollFirst();
        while (!this.cpg.canEvaluate(lvcLog)) {

        }
        context.readyTask = lvcLog;
    }
    private void PROCESS(CSContext context) {
        switch (app) {
            case 0:
            case 3:
            case 2:
                break;
            case 1:
                SLExecute(context.readyTask);
                break;
        }
        context.scheduledTaskCount ++;
        this.cpg.updateGlobalLV(context);
    }
    private void RESET(CSContext context) {
        SOURCE_CONTROL.getInstance().waitForOtherThreads(context.threadId);
        this.cpg.reset(context);
    }
    private void SLExecute(LVCLog task) {
        if (task == null || task.isAborted)
            return;
        String table = task.tableName;
        String pKey = task.key;
        long bid = task.bid;
        if (task.condition.length > 0) {
            SchemaRecord preValue = this.tables.get(table).SelectKeyRecord(task.condition[0]).content_.readPreValues(bid);
            long sourceAccountBalance = preValue.getValues().get(1).getLong();
            AppConfig.randomDelay();
            SchemaRecord srcRecord = this.tables.get(table).SelectKeyRecord(pKey).record_;
            SchemaRecord tempo_record = new SchemaRecord(srcRecord);//tempo record
            if (task.OperationFunction.equals(INC.class.getName())) {
                tempo_record.getValues().get(1).incLong(sourceAccountBalance, Long.parseLong(task.parameter));//compute.
            } else if (task.OperationFunction.equals(DEC.class.getName())) {
                tempo_record.getValues().get(1).decLong(sourceAccountBalance, Long.parseLong(task.parameter));//compute.
            }
            this.tables.get(table).SelectKeyRecord(pKey).content_.updateMultiValues(bid, 0, false, tempo_record);
        } else {
            TableRecord src = this.tables.get(table).SelectKeyRecord(pKey);
            SchemaRecord srcRecord = src.content_.readPreValues(bid);
            List<DataBox> values = srcRecord.getValues();
            AppConfig.randomDelay();
            SchemaRecord tempo_record;
            tempo_record = new SchemaRecord(values);//tempo record
            tempo_record.getValues().get(1).incLong(Long.parseLong(task.parameter));//compute.
            src.content_.updateMultiValues(bid, 0, false, tempo_record);
        }
    }

    @Override
    public void registerTable(RecordSchema recordSchema, String tableName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean inspectAbortView(long bid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object inspectDependencyView(long groupId, String table, String from, String to, long bid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HashMap<String, List<Integer>> inspectTaskPlacing(long groupId, int threadId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HistoryViews getHistoryViews() {
        throw new UnsupportedOperationException();
    }
}