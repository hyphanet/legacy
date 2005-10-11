package freenet;

/**
 * The MessageObject interface represents any object that can be
 * handled as part of a message chain.
 *
 * @author oskar
 **/
public interface MessageObject {
    
    /**
     * @return The id number of the message chain this object
     *         belonds to.
     **/
    public long id();

    /**
     * Sets an exception that occured while this MessageObject was being
     * received/prehandled.
     * @param e  An Exception
     * defunc 
     */
    //    public void setException(Exception e);
}
