package freenet.client.cli;
import java.io.IOException;

import freenet.client.ClientFactory;
import freenet.client.FreenetURI;
import freenet.client.PutRequestProcess;
import freenet.client.RequestProcess;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.InfoPart;
import freenet.client.metadata.InvalidPartException;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.MimeTypeUtils;
import freenet.client.metadata.Redirect;
import freenet.client.metadata.SplitFile;
import freenet.config.Params;
import freenet.support.Bucket;
import freenet.support.Fields;
import freenet.support.FileBucket;
import freenet.support.TempBucketFactory;
/**
 * Command pluging for Put (ie InsertRequest).
 *
 * @author oskar
 */
public class PutCommand implements ClientCommand {

    // REDFLAG: remove comment if there really is not better way to do this.
    // Used by SplitFileRequestProcess via
    // MetadataSettings.  No points for clarity. --gj
    private ClientFactory factory = null;

    public PutCommand(ClientFactory factory) {
        this.factory = factory;
    }

    public String getName() {
        return "put";
    }

    public String getUsage() {
        return "put <URI>";
    }

    public String[] getDescription() {
        return new String[] {
            "Puts a single file into Freenet. If the key is not a CHK and --noredirect",
            "is not given, a redirect to the file will be created with the given URI",
            "pointing to the actual data. If metadata is given through --metadata",
            "and it is a Control document that applies to this URI, it will be",
            "inserted as well, otherwise the metadata will be inserted with the data."
        };
    }

    public int argCount() {
        return 1;
    }

    public RequestProcess getProcess(Params p, Bucket mdata, 
                                     Bucket data) throws CLIException {

        if (p.getNumArgs() < 2)
            throw new CLIException("put command requires URI as argument");
        FreenetURI uri;
        int htl = p.getInt("htl");
        Metadata metadata = null;

        MetadataSettings ms = new MetadataSettings();
        try {
            if (p.getString("requestTime").length()!=0)
                ms.setCurrentTime(Fields.dateTime(p.getString("requestTime")));
        } catch (NumberFormatException e) {
            throw new CLIException("requestTime could not be parsed: " +
                                   e.getMessage());
        }

	if (mdata instanceof freenet.support.NullBucket)
	    mdata = null;

        try {
           uri = new FreenetURI(p.getArg(1));
           if (mdata != null) {
               metadata = new Metadata(mdata.getInputStream(), ms);
//  	       System.err.println("Creating new metadata: \n"+metadata);
	       if(metadata.getSettings() == null) 
		   throw new NullPointerException("wtf?");
	   }
        } catch (java.net.MalformedURLException e) {
            throw new CLIException("Malformed URI: " + e);
        } catch (IOException e) {
            e.printStackTrace();
            throw new CLIException("IOException reading metadata: " +
                                   e);
        } catch (InvalidPartException e) {
            throw new CLIException("Invalid part in metadata: " + 
                                   e);
        }
//         System.err.println("LALA: " + metadata);
	boolean noredirect = p.getParam("noredirect") != null;
        if (!uri.getKeyType().equals("CHK") && 
             !noredirect) {
            try {
		DocumentCommand redirect;
		if(metadata == null)
		    redirect = new DocumentCommand(ms);
		else
		    redirect = new DocumentCommand(metadata);
                redirect.addPart(new Redirect(new FreenetURI("CHK@")));
                metadata = new Metadata(ms);
                metadata.addCommand(redirect);
            } catch (java.net.MalformedURLException e) {
                //            e.printStackTrace();
                throw new CLIException("Exception creating redirect: " +
                                       e);
            } catch (InvalidPartException e) {
                throw new CLIException("Invalid part when creating redirect: "
                                       + e);
            }
        }
	
        // Insert as a SplitFile.
        if ((p.getParam("dontSplit") == null) && (data.size() > 1024 * 1024)) {
            try {
                DocumentCommand splitFile = (metadata == null) ?
		    new DocumentCommand(ms) : new DocumentCommand(metadata);
                splitFile.addPart(new SplitFile(/* URI??? */));
                metadata = new Metadata(ms);
                metadata.addCommand(splitFile);

                // Set parameters needed to run a SplitFileInsertProcess
                int splitFileHtl = (p.getParam("blockHtl") != null) ? p.getInt("blockHtl") : htl;
                ms.setBlockHtl(splitFileHtl);
                ms.setSplitFileRetryHtlIncrement(0); // Doesn't make sense for inserts.
                ms.setSplitFileRetries(p.getInt("retries"));
                ms.setSplitFileThreads(p.getInt("threads"));
                // REDFLAG: fix so null works
                String algoName = (p.getParam("algoName") != null) ? p.getParam("algoName") : 
                    "OnionFEC_a_1_2"; 
                ms.setSplitFileAlgoName(algoName);
                ms.setClientFactory(factory);

            } catch (InvalidPartException e) {
                throw new CLIException("Croaked when adding SplitFile: " +
                                       e);
            }
        }

        if (p.getParam("dontGuessType") == null) {
            String type = MimeTypeUtils.getExtType(data.getName());
            try {
                if (type != null) {
                    if (metadata == null)
                        metadata = new Metadata(ms);
                    DocumentCommand mdc = metadata.getDefaultDocument();
                    if (mdc == null) {
			mdc = new DocumentCommand(ms); // prevent NPE
                        //mdc = new DocumentCommand((Metadata) null);
                        metadata.addDocument(mdc);
                    }
                    mdc.addPart(new InfoPart("file", type));
                }
            } catch (InvalidPartException e) {
                throw new CLIException("Croaked when adding Content-type: " +
                                       e);
            }
        }

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
	
	boolean skipDS = (null != p.getParam("skipDS"));
	
        return new PutRequestProcess(uri, htl, p.getString("cipher"),
                                     metadata, ms,
                                     data, new TempBucketFactory(tempDir), 
                                     0, !noredirect, skipDS);
    }
    
    public boolean takesData() {
        return true;
    }

    public boolean givesData() {
        return false;
    }

}





