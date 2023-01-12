package combo.faulttolerance;

import combo.SINKCombo;
import execution.runtime.tuple.impl.Tuple;
import profiler.MeasureTools;
import profiler.Metrics;

public class FTSINKCombo extends SINKCombo {
    long lastTask = -1;
    boolean stopRecovery = false;

    @Override
    public void execute(Tuple input) throws InterruptedException {
        if (input.getBID() > lastTask) {
            if (isRecovery && !stopRecovery) {
                MeasureTools.END_RECOVERY_TIME_MEASURE(thisTaskId);
                stopRecovery = true;
            }
            Metrics.RuntimePerformance.lastTasks[thisTaskId] = input.getBID();
            latency_measure(input);
        }
    }
}