package freenet.node.states.request;
import freenet.Core;
import freenet.support.Logger;

/** The RequestInitiator starts and restarts the Pending chain.
  */
public class RequestInitiator extends RequestObject {

    private long startTime;
    public Exception initException;
    
    public String toString() {
        return super.toString()+"@ "+startTime;
    }

    /**
     * @param startTime   The time at which the request was initiated.
     */
    public RequestInitiator(long id, long startTime) {
        super(id, true);
        this.startTime = startTime;
	if(Core.logger.shouldLog(Logger.DEBUG,this))
	   initException = new Exception("debug");
	else
	   initException = null;
    }

    /** @param rs  the RequestState to derive the chain ID from
     * @param startTime   The time at which the request was initiated.
     */
    public RequestInitiator(RequestState rs, long startTime) {
        this(rs.id(), startTime);
	if(Core.logger.shouldLog(Logger.DEBUG,this))
	   initException = new Exception("debug");
	else
	   initException = null;
    }

    public long startTime() {
        return startTime;
    }
}



