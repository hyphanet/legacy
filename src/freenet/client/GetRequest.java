package freenet.client;

import freenet.support.Bucket;
import java.net.MalformedURLException;

/** Represents a request to retrieve a file.
  * @author tavin
  */
public class GetRequest extends Request {

    int htl;
    FreenetURI uri;
    Bucket meta, data;
    boolean nonLocal;

    /** Prepares a request for data identified by a Freenet URI.
      * @param htl   The HopsToLive to give the request.
      * @param uri   The URI to request.
      * @param meta  A bucket to place the metadata in (if there is any)
      * @param data  A bucket to place the data in
      * @param nonLocal When true keys in the local DataStore are ignored.
      * @throws MalformedURLException  if the URI string is bad
      */
    public GetRequest(int htl, String uri, Bucket meta, Bucket data, boolean nonLocal)
                                        throws MalformedURLException {
        this(htl, new FreenetURI(uri), meta, data, nonLocal);
    }

    /** Prepares a request for data identified by a Freenet URI.
      * @param htl   The HopsToLive to give the request.
      * @param uri   The URI to request.
      * @param meta  A bucket to place the metadata in (if there is any)
      * @param data  A bucket to place the data in
      * @param nonLocal When true keys in the local DataStore are ignored.
      */
    public GetRequest(int htl, FreenetURI uri, Bucket meta, Bucket data, boolean nonLocal) {
        super();
        this.htl  = htl;
        this.uri  = uri;
        this.meta = meta;
        this.data = data;
        this.nonLocal = nonLocal;
    }

    /**
     * Convenience overload which sets nonLocal false.
     **/
    public GetRequest(int htl, String uri, Bucket meta, Bucket data)
        throws MalformedURLException {
        this(htl, new FreenetURI(uri), meta, data, false);
    }

    /**
     * Convenience overload which sets nonLocal false.
     **/
    public GetRequest(int htl, FreenetURI uri, Bucket meta, Bucket data) {
        this(htl, uri, meta, data, false);
    }

    public boolean getNonLocal() {
    	return nonLocal;
    }

}





