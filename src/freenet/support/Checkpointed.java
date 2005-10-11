package freenet.support;

public interface Checkpointed {

    /**
     * @return  a descriptive name for the checkpoint action
     */
    String getCheckpointName();

    /**
     * @return  absolute time to schedule next checkpoint, negative to not reschedule
     */
    long nextCheckpoint();

    /**
     * Execute a maintenance checkpoint.
     */
    void checkpoint();
}


