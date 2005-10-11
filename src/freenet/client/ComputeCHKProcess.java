package freenet.client;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.support.Bucket;
import freenet.support.ArrayBucket;
import freenet.Core;
import freenet.support.Logger;
/**
 * Simple process to issue a single ComputeCHKRequest.
 *
 * @author oskar
 */

public class ComputeCHKProcess extends SingleRequestProcess {

    public String ciphername;
    protected Metadata metadata;
    protected MetadataSettings msettings;

    public ComputeCHKProcess(String cipherName, Metadata metadata, MetadataSettings ms,
			     Bucket data) {
        super(data);
        this.metadata = metadata;
	this.msettings = ms;
    }

    public Request getNextRequest() {
	Bucket b = new ArrayBucket();
	if(metadata != null) {
	    try {
		java.io.OutputStream os = b.getOutputStream();
		metadata.writeTo(os);
		os.close();
		//metadata.writeTo(System.err);
	    } catch (java.io.IOException e) {
		origThrowable = e;
		error = "ComputeCHKProcess failed due to IOException: "+e;
		Core.logger.log(this, "ComputeCHKProcess failed due to IOException",
				e, Logger.ERROR);
		return null;
	    }
	}// else
	    //System.err.println("metadata null");
	//System.err.println("Written "+b.size()+" bytes.");
	return getNextRequest(new ComputeCHKRequest(ciphername, b, data));
    }

    public Metadata getMetadata() {
	return metadata;
    }

}
