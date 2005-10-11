package freenet.client;
import freenet.support.Bucket;
/**
 * Simple process to issue a single ComputeSHA1Request.
 *
 * @author giannij
 */
public class ComputeSHA1Process extends SingleRequestProcess {
    public ComputeSHA1Process(Bucket data) {
        super(data);
    }
    public Request getNextRequest() {
        return getNextRequest(new ComputeSHA1Request(data));
    }
}
