package freenet.client;

import java.util.Enumeration;

/** Part of a general API for Freenet clients, with optional metadata handling.
  * @author tavin
  */
public interface Client {

    /** The Client instance should begin executing the request,
      * triggering the appropriate events.
      */
    void start();

    /** Cancel the request as soon as possible.  The request should 
      * move to the state CANCELLED, unless it has already 
      * reached DONE or FAILED.
      *
      * Note: This is an asynchronous call. 
      *
      * @return true unless the request already reached DONE or FAILED
      */
    boolean cancel();

    /** The Client instance should execute the request synchronously,
      * in the current thread.
      * @return  the final State of the Request
      */
    int blockingRun();

    /** After completion of a run, this returns all events that were fired.
      * For some it may be easier than dealing with realtime event listeners.
      * @return  Enumeration of ClientEvent objects
      */
    Enumeration getEvents();
}
