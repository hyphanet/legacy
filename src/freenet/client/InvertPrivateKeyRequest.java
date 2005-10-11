package freenet.client;

/** Represents a request to calculate a public SSK from
 * a private one. (InvertPrivateKey in FCP)
 * @author giannij
 */
public class InvertPrivateKeyRequest extends Request {
    String privateValue = null;
    String publicValue = null;
    
    /**
     * Create a request to invert a private SSK key to it's
     * public value.
     * <p>
     * @param privateValue can be either a raw private SSK key
     *         or a legal private SSK insert URL.
     */
    public InvertPrivateKeyRequest(String privateValue) {
        super();
        this.privateValue = privateValue;
    }

    /** @return the generated URI or null if request incomplete
      */
    public String  getPublicValue() {
        return publicValue;
    }

}







