package freenet.client.cli;
import freenet.client.ClientFactory;
import freenet.client.FreenetURI;
import freenet.client.GetRequestProcess;
import freenet.client.RequestProcess;
import freenet.client.metadata.MetadataSettings;
import freenet.config.Params;
import freenet.support.Bucket;
import freenet.support.Fields;
import freenet.support.FileBucket;
import freenet.support.TempBucketFactory;

/**
 * Command plugin for Get (ie DataRequest).
 *
 * @author oskar
 */
public class GetCommand implements ClientCommand {

    // Used by SplitFileRequestProcess via
    // MetadataSettings.  No points for clarity. --gj
    private ClientFactory factory = null;

    public GetCommand(ClientFactory factory) {
        this.factory = factory;
    }

    public String getName() {
        return "get";
    }

    public String getUsage() {
        return "get <URI>";
    }

    public String[] getDescription() {
        return new String[] {
            "Retrieve a file from Freenet. The file will be written to the file given",
            "or to stdout if there was no file argument. Metadata control documents",
            "will be followed unless --noredirect was set.",
            "FEC SplitFiles are handled transparently."
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
        int htl = p.getInt("htl");
        int splitFileHtl = (p.getParam("blockHtl") != null) ? p.getInt("blockHtl") : htl;
        MetadataSettings ms = new MetadataSettings();
        try {
            if (p.getString("requestTime").length()!=0)
                ms.setCurrentTime(Fields.dateTime(p.getString("requestTime")));

        } catch (NumberFormatException e) {
            throw new CLIException("requestTime could not be parsed: " +
                                   e.getMessage());
        }

        // Set parameters needed to run a SplitFileRequestProcess
        // in case one is spawned somewhere down the redirect chain.
        ms.setBlockHtl(splitFileHtl);
        ms.setSplitFileRetryHtlIncrement(p.getInt("retryHtlIncrement"));
        ms.setSplitFileRetries(p.getInt("retries"));
        
        ms.setHealPercentage(p.getInt("healPercentage"));
        ms.setHealingHtl(p.getInt("healingHtl"));

        ms.setSplitFileThreads(p.getInt("threads"));
        ms.setNonLocal(p.getBoolean("skipDS"));
        ms.setClientFactory(factory);
	
        ms.enableParanoidChecks(p.getBoolean("doParanoidChecks"));
	ms.setFollowContainers(p.getBoolean("followContainers"));
	
        // Temp File settings.
        String tempDir = p.getParam("tempDir");
        if (tempDir == null) {
            // Use FileBucket's temp dir selection. 
            tempDir = FileBucket.getTempDir();
            System.err.println("Using default temp directory: " + tempDir);
            System.err.println("Use --tempDir if you want to set this explictly.");
        }
        else {
            // Make sure all default constructed FileBucket() instances
            // use this value.
            FileBucket.setTempDir(tempDir);
        }

        //System.err.println("QUACK: " + ms);
        return new GetRequestProcess(uri, htl, data, new TempBucketFactory(tempDir),
                                     0, p.getParam("noredirect") == null, ms);
    }

    public boolean takesData() {
        return false;
    }

    public boolean givesData() {
        return true;
    }



}


