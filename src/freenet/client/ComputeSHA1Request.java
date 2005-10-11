package freenet.client;

import freenet.support.Bucket;

/** Request to SHA-1 hash a file.
  * @author giannij
  */
public class ComputeSHA1Request extends Request {
    Bucket data = null;
    String sha1 = null;
    public ComputeSHA1Request(Bucket data) {
        super();
        this.data = data;
    }

    /** 
     * @return the generated SHA-1 hash.
     */
    public String getSHA1() {
        return sha1;
    }
}







