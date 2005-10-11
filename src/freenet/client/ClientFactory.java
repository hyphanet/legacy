package freenet.client;

import freenet.KeyException;
import java.io.IOException;

/** Part of a general API for Freenet clients, with optional metadata handling.
  * @author tavin
  */
public interface ClientFactory {

    /** Used to instantiate a Client to handle a request.
      * @param req  the request to process
      * @return     a Client instance for this request
      * @throws UnsupportedRequestException if the class of req is not
      *                                     supported in the implementation
      * @throws KeyException if the request URI contains an unsupported keytype
      * @throws IOException  if there is an I/O error
      */
    Client getClient(Request req)
           throws UnsupportedRequestException, KeyException, IOException;

    /** @return whether this ClientFactory can generate Client instances
      *         for Request objects of the given class
      */
    boolean supportsRequest(Class reqClass);
    
    /** Is the node overloaded?
     */
    boolean isOverloaded();
    
    /** Is the node really overloaded (i.e. rejecting connections)?
     */
    boolean isReallyOverloaded();
    
}
