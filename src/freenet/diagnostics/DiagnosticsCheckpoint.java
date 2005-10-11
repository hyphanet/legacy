package freenet.diagnostics;

import freenet.support.Checkpointed;

/**
 * Does polling and aggregation of diagnostics at regular intervals.
 * Adapted from Oskar's freenet.node.states.maintenance.DiagnosticsPeriod.
 * @author tavin
 * @author oskar
 */
public class DiagnosticsCheckpoint implements Checkpointed {

    private final AutoPoll autopoll;
    private final Diagnostics diagnostics;
    
    // delay first execution 15 seconds
    private long nextTime = 15000 + System.currentTimeMillis();


    public DiagnosticsCheckpoint(AutoPoll autopoll, Diagnostics diagnostics) {
        this.autopoll = autopoll;
        this.diagnostics = diagnostics;
    }

    public final String getCheckpointName() {
        return "Polling and aggregation of diagnostics.";
    }

    public final long nextCheckpoint() {
        return nextTime;
    }

    public final void checkpoint() {
        autopoll.doPolling();
        nextTime = diagnostics.aggregateVars();
    }
}
