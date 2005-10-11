package freenet.node.simulator;

import freenet.Core;
import freenet.node.rt.KeyspaceEstimator;

/**
 * Class to store the final state of a request
 */
public class RequestStatus {

    boolean finished;
    boolean success;
    int reason;
    long id;
    int remainingHTL = -1;
    int hopsTaken = 0;
    int hopsSinceReset = 0;
    static final int NO_REASON = 0;
    static final int COLLISION = 1;
    static final int STORED_INSERT = 2;
    static final int DATA_FOUND = 3;
    static final int DATA_NOT_FOUND = 4;
    static long idCounter = 0;
    Node dataSource;
    KeyspaceEstimator sourceE;
    KeyspaceEstimator sourceEpDNF;
    KeyspaceEstimator sourceEtSuccess;
    KeyspaceEstimator sourceEtFailure;


    public RequestStatus() {
        clear();
    }
    
    String reason() {
        switch(reason) {
        	case NO_REASON:
        	    return "none";
        	case COLLISION:
        	    return "collision";
        	case STORED_INSERT:
        	    return "stored insert";
        	case DATA_FOUND:
        	    return "data found";
        	case DATA_NOT_FOUND:
        	    return "data not found";
        	default:
        	    return "unknown: "+reason;
        }
    }
    
    public String toString() {
        return super.toString()+": request #"+id+" finished="+finished+
        	", success="+success+", remaining HTL="+remainingHTL+
        	", hopsTaken="+hopsTaken+", reason="+reason();
    }
    
    /**
     * Clear this object status and prepare it
     * for re-use.
     */
    public void clear() {
        finished = false;
        success = false;
        reason = NO_REASON;
        id = idCounter++;
        remainingHTL = -1;
        hopsTaken = 0;
        hopsSinceReset = 0;
    }
    
    public void succeed() {
        finished = true;
        success = true;
    }
    
    public void fail() {
        finished = true;
        success = false;
    }

    /**
     * Collision on node supplied.
     */
    public void collision(Node n) {
        reason = COLLISION;
        succeed();
    }

    /**
     * @return true if the request succeeded.
     */
    public boolean succeeded() {
        return success;
    }

    public void stored() {
        reason = STORED_INSERT;
        succeed();
    }

    public void dataFound(int remainingHTL) {
        reason = DATA_FOUND;
        this.remainingHTL = remainingHTL;
        succeed();
    }

    public void dataNotFound() {
        reason = DATA_NOT_FOUND;
        fail();
    }

    public void hop() {
        hopsTaken++;
    }
    
    public void hopSinceReset(Node n) {
        hopsSinceReset++;
        if(Core.getRandSource().nextFloat() < 0.05) {
            hopsSinceReset = 0;
            dataSource = n;
            sourceE = sourceEpDNF = sourceEtFailure = sourceEtSuccess = null;
        }
        // Replace estimators if possible
        Peer p = n.peerFor(dataSource);
        if(p != null) {
            sourceE = p.e;
            sourceEpDNF = p.epDNF;
            sourceEtFailure = p.etFailure;
            sourceEtSuccess = p.etSuccess;
        }
    }
}
