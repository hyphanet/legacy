package freenet.support;
import freenet.MessageObject;

/**
 * TickerTokens are passed to scheduled objects so they can deschedule
 * themselves.
 */

public interface TickerToken {

    /**
     * Retuns the scheduled object for which this token was generated.
     * @return The object the TickerToken pertains to.
     */
    public MessageObject getOwner();

    // in the wrong context, the below text could be misunderstood :-)
    // maybe I should name the class "Texas"
    /**
     * Cancels the owner from being executed as scheduled by the ticker.
     * @return True if the owners execution was canceled, false if it could 
     *         not be canceled (already executed).
     */
    public boolean cancel();
    
}
