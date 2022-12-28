package components.operators.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.util.concurrent.BrokenBarrierException;

public abstract class TransactionalSpout extends AbstractSpout implements Checkpointable {
    private static final Logger LOG = LoggerFactory.getLogger(TransactionalSpout.class);
    public double target_Hz;
    public int punctuation_interval;
    public int snapshot_interval;
    public boolean arrivalControl;
    public volatile int control = 0;//control how many elements in each epoch.
    public int _combo_bid_size = 1;
    public int counter = 0;
    public transient BufferedWriter writer;
    public int taskId;
    public int ccOption;
    public int ftOption;
    public long bid = 0;//local bid.
    public int empty = 0;//execute without emit.

    protected TransactionalSpout(Logger log, int fid) {
        super(log);
        this.fid = fid;
    }

    public double getEmpty() {
        return empty;
    }

    @Override
    public abstract void nextTuple() throws InterruptedException;

    /**
     * THIS IS USED ONLY WHEN "enable_app_combo" is true.
     * Everytime a thread emits "batch_size" tuples, it emits a signal to trigger txn processing.
     *
     * @param counter
     */
    @Override
    public boolean model_switch(int counter) {
        return (counter % punctuation_interval == 0);
    }

}
