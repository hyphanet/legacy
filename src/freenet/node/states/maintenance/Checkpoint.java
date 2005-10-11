package freenet.node.states.maintenance;

import freenet.*;
import freenet.node.*;
import freenet.support.Checkpointed;
import freenet.support.Logger;

/**
 * Generic periodic maintenance state.
 * @author tavin
 */
public class Checkpoint extends State {

    private final Checkpointed target;

    private long next_time = -1;
    

    public Checkpoint(Checkpointed target) {
        super(Core.getRandSource().nextLong());
        this.target = target;
    }

    public final String getName() {
        return "Checkpoint: " + target.getCheckpointName();
    }

          
    /**
     * Schedules this Checkpoint on the ticker.  Only needs to be done
     * after it is first created;  afterwards it will reschedule itself.
     */
    public void schedule(Node n) {
        next_time = target.nextCheckpoint();
        if(next_time > 0)
        	n.ticker().addAbs(next_time, new CheckpointMemo());
    }
    
    public void lost(Node n) {
        Core.logger.log(this, "WTF!? Node states overflowed before I could receive my MO!  Running anyway..", Logger.ERROR);
        checkpoint(n);
    }

    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof CheckpointMemo))
            throw new BadStateException("expecting PeriodicMemo");
        checkpoint(n);
        return null;
    }

    private void checkpoint(Node n) {
        if(Core.logger.shouldLog(Logger.MINOR,this)) Core.logger.log(this, "Executing "+getName(), Logger.MINOR);
        try {
            target.checkpoint();
        }
        catch (Throwable e) {
            Core.logger.log(this, "unhandled throwable in " + getName() + ": " + e, e, Logger.ERROR);
        }
        finally {
            schedule(n);
        }
    }

    //=== machinery for scheduling on the ticker ===============================

    private final class CheckpointMemo implements NodeMessageObject {

        public final long id() {
            return id;
        }

        public final State getInitialState() {
            return Checkpoint.this;
        }

        public final boolean isExternal() {
            return false;
        }

        public final void drop(Node n) {
            Core.logger.log(CheckpointMemo.this, "rescheduling " + getName() + " due to ticker overflow", Logger.MINOR);
            n.ticker().addAbs(next_time, this);
        }

        public String toString() {
            return getName();
        }
    }
}




