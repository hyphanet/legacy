package freenet.support;
import freenet.MessageObject;

/**
 * MessageObjects that fullfill this interface will receive a 
 * TickerToken when scheduled, with which they can cancler their own 
 * execution.
 *
 * @author oskar
 */

public interface Schedulable extends MessageObject {

    /**
     * Gives this object a Token showing it has been scheduled by the ticker. 
     * @param tt  A token with which this object can control it's execution
     *            by the ticker.
     */
    public void getToken(TickerToken tt);
    
}
