package freenet.client.cli;
import java.io.IOException;

import freenet.client.ComputeCHKProcess;
import freenet.client.RequestProcess;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.InfoPart;
import freenet.client.metadata.InvalidPartException;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.MimeTypeUtils;
import freenet.config.Params;
import freenet.support.Bucket;

public class ComputeCHKCommand implements ClientCommand {

    public ComputeCHKCommand() {
    }

    public String getName() {
        return "computechk";
    }

    public String getUsage() {
        return "computechk";
    }

    public String[] getDescription() {
        return new String[] {
            "Computes the CHK value that the given data would have if inserted."
        };
    }

    public int argCount() {
        return 0;
    }

    public RequestProcess getProcess(Params p, Bucket mdata, Bucket data)
	throws CLIException {
        Metadata metadata = null;
	
	if (mdata instanceof freenet.support.NullBucket)
	    mdata = null;

	MetadataSettings ms = new MetadataSettings();
        try {
           if (mdata != null)
               metadata = new Metadata(mdata.getInputStream(), ms);
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

        if (p.getParam("dontGuessType") == null) {
            String type = MimeTypeUtils.getExtType(data.getName());
            //System.err.println("CONTENT TYPE: " + type);
            try {
                if (type != null) {
                    if (metadata == null)
                        metadata = new Metadata(ms);
                    DocumentCommand mdc = metadata.getDefaultDocument();
                    if (mdc == null) {
                        mdc = new DocumentCommand(ms);
                        metadata.addDocument(mdc);
                    }
                    mdc.addPart(new InfoPart("file", type));
                }
            } catch (InvalidPartException e) {
                throw new CLIException("Croaked when adding Content-type: " +
                                       e);
            }
        }
	
        return new ComputeCHKProcess(p.getString("cipher"), metadata, ms,
                                     data);
    }

    public boolean takesData() {
        return true;
    }

    public boolean givesData() {
        return false;
    }
}
