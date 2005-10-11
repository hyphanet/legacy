package freenet.client;

import java.net.MalformedURLException;

import freenet.keys.SVK;
import freenet.support.Bucket;
import freenet.support.NullBucket;

/** Represents a request to insert a file.
  * @author tavin
  */
public class PutRequest extends Request {

    int htl;
    FreenetURI uri;
    String cipherName;
    Bucket meta, data;
    ClientKey clientKey;
    boolean nonLocal;
    
    /** Prepares an insert of the supplied data.
      * @param htl         The HopsToLive to give the request.
      * @param uri         The URI to insert under.
      * @param meta        A stream of Freenet standard client metadata.
      * @param data        A stream of the data to insert.
      * @param cipherName  The block cipher to use.  null defaults to Twofish
      * @throws MalformedURLException  if the URI string is bad
      */
    public PutRequest(int htl, String uri, String cipherName, Bucket meta, Bucket data)
                                    throws MalformedURLException, InsertSizeException {
        this(htl, uri, cipherName, meta, data, false);
    }
    /** Prepares an insert of the supplied data.
      * @param htl         The HopsToLive to give the request.
      * @param uri         The URI to insert under.
      * @param meta        A stream of Freenet standard client metadata.
      * @param data        A stream of the data to insert.
      * @param cipherName  The block cipher to use.  null defaults to Twofish
      * @param skipDS      Remove the key from the local datastore before inserting
      * @throws MalformedURLException  if the URI string is bad
      */
    public PutRequest(int htl, String uri, String cipherName, Bucket meta, Bucket data, boolean skipDS)
                                    throws MalformedURLException, InsertSizeException {
        this(htl, new FreenetURI(uri), cipherName, meta, data, skipDS);
    }

    /** Prepares an insert of the supplied data.
      * @param htl         The HopsToLive to give the request.
      * @param uri         The URI to insert under.
      * @param meta        A stream of Freenet standard client metadata.
      * @param data        A stream of the data to insert.
      * @param cipherName  The block cipher to use.  null defaults to Twofish
      */
    public PutRequest(int htl, FreenetURI uri, String cipherName, Bucket meta, Bucket data)
                                                            throws InsertSizeException {
      this(htl, uri, cipherName, meta, data, false);
    }
    /** Prepares an insert of the supplied data.
      * @param htl         The HopsToLive to give the request.
      * @param uri         The URI to insert under.
      * @param meta        A stream of Freenet standard client metadata.
      * @param data        A stream of the data to insert.
      * @param cipherName  The block cipher to use.  null defaults to Twofish
      * @param skipDS      Remove the key from the local datastore before inserting
      */
    public PutRequest(int htl, FreenetURI uri, String cipherName, Bucket meta, Bucket data, boolean skipDS)
                                                            throws InsertSizeException {
        super();
        if (!uri.getKeyType().equals("CHK") &&
            meta.size() + data.size() > SVK.SVK_MAXSIZE)
            throw new InsertSizeException(
                "SVKs, KSKs, and SSKs cannot be more than "+SVK.SVK_MAXSIZE+" bytes."
            );
        
        this.htl        = htl;
        this.uri        = uri;
        //this.cipherName = (cipherName == null ? "Twofish" : cipherName);
        // the Client instance should probably be responsible for choosing the default
        this.cipherName = cipherName;
        this.meta       = meta;
	if(this.meta == null) this.meta = new NullBucket();
        this.data       = data;
	if(this.data == null) this.data = new NullBucket();
	this.nonLocal   = skipDS;
    }

    /** Returns the URI -- useful if the URI was set to just
      * SVK@ or CHK@ at insert and you want to see what it became.
      * Only valid after a Client instance has been obtained for
      * the request.
      * @return the correct FreenetURI or null if the request is incomplete
      */
    public FreenetURI getURI() {
        return clientKey == null ? null : clientKey.getURI();
    }

    /** Get the private key for use in constructing an insert URI.
      * (Just Base64.encode() the return value).
      * @return the raw bytes which form the private key if it's an SVK
      *         or null if the request is incomplete
      */
    public byte[] getPrivateKey() {
        return clientKey != null && clientKey instanceof ClientSVK
            ? ((ClientSVK) clientKey).getPrivateKey()
            : null;
    }

    /** Get the public key.
      * @return the raw bytes which form the public key if it's an SVK
      *         or null if the request is incomplete
      */
    public byte[] getPublicKey() {
        return clientKey != null && clientKey instanceof ClientSVK
            ? ((ClientSVK) clientKey).getPublicKey()
            : null;
    }

    /** Get the public key fingerprint.
      * @return the raw bytes which form the public key fingerprint
      *         if it's an SVK or null if the request is incomplete
      */
    public byte[] getPublicKeyFingerPrint() {
        return clientKey != null && clientKey instanceof ClientSVK
            ? ((ClientSVK) clientKey).getPublicKeyFingerPrint()
            : null;
    }

    public boolean getNonLocal() {
      return nonLocal;
    }
}




