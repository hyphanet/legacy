package freenet.client;

/** Represents a request to create a new SVK keypair
  * (GenerateSVKPair in FCP)
  * @author tavin
  */
public class ComputeSVKPairRequest extends Request {

    ClientSVK clientKey;
    
    /**
      */
    public ComputeSVKPairRequest() {
        super();
    }

    /** Get the private key for use in constructing an insert URI.
      * (Just Base64.encode() the return value).
      * @return the raw bytes which form the private key
      *         or null if the request is incomplete
      */
    public byte[] getPrivateKey() {
        return clientKey == null ? null : clientKey.getPrivateKey();
    }
    
    /** Get the public key.
      * @return the raw bytes which form the public key
      *         or null if the request is incomplete
      */
    public byte[] getPublicKey() {
        return clientKey == null ? null : clientKey.getPublicKey();
    }
       
    /** Get the public key fingerprint for use in constructing a request URI.
      * (Just Base64.encode() the return value).
      * @return the raw bytes which form the public key fingerprint
      *         or null if the request is incomplete
      */
    public byte[] getPublicKeyFingerPrint() {
        return clientKey == null ? null : clientKey.getPublicKeyFingerPrint();
    }
}


