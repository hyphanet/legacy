package freenet.node.simulator.newsim;

/**
 * Base for InsertContext and RequestContext
 */
public class BaseContext {

    static final int NO_FAILURE = -1;
    static final int INSERT_FAILED = 1;
    static final int REJECTED_LOOP = 2;
    static final int DATA_NOT_FOUND = 3;
    
    static int idCounter = 0;
    
    final int id;
    
    int lastFailureCode = -1;
    
    int hopsToLive;
    
    public String lastFailureCodeToString() {
        switch(lastFailureCode) {
        	case NO_FAILURE:
        	    return "no failure";
        	case INSERT_FAILED:
        	    return "insert failed";
        	case REJECTED_LOOP:
        	    return "query rejected (loop)";
        	case DATA_NOT_FOUND:
        	    return "data not found";
        }
        return "unrecognized code: "+lastFailureCode;
    }

    public BaseContext(int htl) {
        hopsToLive = htl;
        id = idCounter++;
    }

    public void stepHTL() {
        // FIXME: implement extra probabilistic hops according to real network?
        hopsToLive--;
    }
    
}
