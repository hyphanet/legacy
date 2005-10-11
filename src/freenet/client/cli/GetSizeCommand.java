package freenet.client.cli;

import freenet.KeyException;
import freenet.client.ComputeSizeRequest;
import freenet.client.FreenetURI;
import freenet.client.RequestProcess;
import freenet.config.Params;
import freenet.support.Bucket;

/**
 * Command plugin for getting the size of a key
 */
public class GetSizeCommand implements ClientCommand {
    public GetSizeCommand() {
    }
    
    public String getName() {
	return "getsize";
    }
    
    public String getUsage() {
	return "getsize <URI>";
    }
    
    public String[] getDescription() {
	return new String[] {
	    "Return the maximum size of a file in Freenet, given its URI.",
	    "This includes the metadata. The size will be written to ",
	    "standard output. Splitfiles are not handled, nor even redirects ",
	    "- this is just the size of *this key*."
	};
    }
    
    public int argCount() {
	return 1;
    }
    
    public RequestProcess getProcess(Params p, Bucket metadata,
				     Bucket data) throws CLIException {
	if (p.getNumArgs() < 2)
            throw new CLIException("get command requires URI as argument");
        FreenetURI uri;
        try {
            uri = new FreenetURI(p.getArg(1));
        } catch (java.net.MalformedURLException e) {
            throw new CLIException("malformed URI: " + e);
        }
	ComputeSizeRequest r = new ComputeSizeRequest(uri);
	try {
	    System.out.println("Maximum Size: "+r.execute()+" bytes");
	} catch (KeyException e) {
	    CLIException c = new CLIException("Failed to compute max size: "+
					      e.toString());
	    c.initCause(e);
	    throw c;
	}
	return null;
    }
    
    public boolean takesData() {
	return false;
    }
    
    public boolean givesData() {
	return false;
    }
}
