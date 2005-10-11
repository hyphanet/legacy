package freenet.client;

import freenet.support.Bucket;
import freenet.message.client.MetadataHint;
import java.net.MalformedURLException;

/** Represents a request to retrieve a file.
 *  Supplies hint information for handling
 *  redirects.
 * 
 * @author tavin (copied from GetRequest)
 * @author giannij
 */
public class ExtendedGetRequest extends GetRequest {

    // package scope access on purpose
    long timeSec = -1;
    int kind = -1;
    String mimeType = null; 
    String nextURI = null;
    boolean isMap = false;
    int increment = 0;
    long offset = 0;

    /** Prepares a request for data identified by a Freenet URI.
      * @param htl   The HopsToLive to give the request.
      * @param uri   The URI to request.
      * @param meta  A bucket to place the metadata in (if there is any)
      * @param data  A bucket to place the data in
      * @param nonLocal When true keys in the local DataStore are ignored.
      * @param timeSec Time value to use when calculating date based redirects.
      * @throws MalformedURLException  if the URI string is bad
      */
    public ExtendedGetRequest(int htl, String uri, Bucket meta, Bucket data,
                              boolean nonLocal, long timeSec)
                                        throws MalformedURLException {
        super(htl, new FreenetURI(uri), meta, data, nonLocal);
        this.timeSec = timeSec;
    }

    /** Prepares a request for data identified by a Freenet URI.
      * @param htl   The HopsToLive to give the request.
      * @param uri   The URI to request.
      * @param meta  A bucket to place the metadata in (if there is any)
      * @param data  A bucket to place the data in
      * @param nonLocal When true keys in the local DataStore are ignored.
      * @param timeSec Time value to use when calculating date based redirects.
      */
    public ExtendedGetRequest(int htl, FreenetURI uri, Bucket meta, Bucket data,
                              boolean nonLocal, long timeSec) {
        super(htl, uri, meta, data, nonLocal);
        this.timeSec = timeSec;
    }

    public final static int DATA = MetadataHint.DATA;
    public final static int REDIRECT =  MetadataHint.REDIRECT;
    public final static int DATEREDIRECT = MetadataHint.DATEREDIRECT;
    public final static int SPLITFILE = MetadataHint.SPLITFILE;
    public final static int TOODUMB = MetadataHint.TOODUMB;
    public final static int ERROR = MetadataHint.ERROR; 

    public final long getTimeSec() { return timeSec; }
    public final int getKind() { return kind; }
    public final String getMimeType() { return mimeType; }
    public final String getNextURI() { return nextURI; }
    public final boolean isMapFile() { return isMap; }
    // Only valid for getKind() == DATEREDIRECT
    public final int getIncrement() { return increment; }
    // Only valid for getKind() == DATEREDIRECT
    public final long getOffset() { return offset; }
}









