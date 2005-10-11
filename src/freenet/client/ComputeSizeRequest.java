package freenet.client;

import java.net.MalformedURLException;
import freenet.Key;
import freenet.KeyException;

/** Represents a ComputeSizeRequest
  * @author amphibian
  */
public class ComputeSizeRequest extends Request {
    
    FreenetURI uri;
    
    /** Prepares a ComputeSizeRequest for data identified by a Freenet URI.
      * @param uri The URI
      */
    public ComputeSizeRequest(String uri) throws MalformedURLException {
	this(new FreenetURI(uri));
    }
    
    /** Prepares a ComputeSizeRequest for data identified by a Freenet URI.
      * @param uri The URI
      */
    public ComputeSizeRequest(FreenetURI uri) {
	this.uri = uri;
    }
    
    /** Determine the size of the data identified by the Freenet URI.
     * This is so simple that we just run it in place.
     * NOT recommended as a template for new reuqests!
     */
    public long execute() throws KeyException {
	ClientKey ckey = AbstractClientKey.createFromRequestURI(uri);
	Key key = ckey.getKey();
	return key.size();
    }
}
