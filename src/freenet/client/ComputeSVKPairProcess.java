package freenet.client;
import freenet.support.Bucket;
/**
 * Single request process for computing a CHK value.
 *
 * @author oskar
 */
public class ComputeSVKPairProcess extends SingleRequestProcess {

    public ComputeSVKPairProcess(Bucket data) {
        super(data);
    }

    public Request getNextRequest() {
        return getNextRequest(new ComputeSVKPairRequest());
    }
    
    public String getPrivateKey() {
        return (dr == null ? null : 
                Base64.encode(((ComputeSVKPairRequest) dr).getPrivateKey()));
    }

    public String getPublicKey() {
        return (dr == null ? null : 
                Base64.encode(((ComputeSVKPairRequest) dr).getPublicKey()));
    }
    


}




