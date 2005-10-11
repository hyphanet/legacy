package freenet.client.cli;
import freenet.config.Params;
import freenet.support.Bucket;
import freenet.support.Fields;
import freenet.support.FileBucket;
import freenet.support.FileBucketFactory;
import freenet.client.RequestProcess;
import freenet.client.PutSiteProcess;
import freenet.client.FreenetURI;
import freenet.client.metadata.*;
import java.io.IOException;
import java.io.File;
/**
 * Command plugin for inserting whole sites.
 */

public class PutSiteCommand implements ClientCommand {

    public PutSiteCommand() {
    }

    public String getName() {
        return "putsite";
    }

    public String getUsage() {
        return "putsite <URI> [file]...";
    }

    public String[] getDescription() {
        return new String[] {
            "Adds a number of files to Freenet, and then a mapfile containing redirect to",
            "them. If a file is given through --metadata it is used as the map file and",
            "entries for the files given are added/updated."
        };
    }

    public int argCount() {
        return 1;
    }

    public RequestProcess getProcess(Params p, Bucket mdata, Bucket data) 
        throws CLIException {

        FreenetURI uri;
        int htl = p.getInt("htl");

        MetadataSettings ms = new MetadataSettings();
        try {
            if (p.getString("requestTime").length()!=0)
                ms.setCurrentTime(Fields.dateTime(p.getString("requestTime")));
        } catch (NumberFormatException e) {
            throw new CLIException("requestTime could not be parsed: " +
                                   e.getMessage());
        }

        Metadata metadata = null;
        try {
            uri = new FreenetURI(p.getArg(1));
            if (mdata != null)
                metadata = new Metadata(mdata.getInputStream(), ms);
            else
                metadata = new Metadata(ms);
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

        Bucket[] buckets = new Bucket[p.getNumArgs() - 2];
        boolean guess = p.getParam("dontGuessType") == null;
        for (int i = 2 ; i < p.getNumArgs() ; i++) {
            buckets[i-2] = new FileBucket(new File(p.getArg(i)));
            String name = buckets[i-2].getName();
            try {
                if (metadata.getDocument(name) == null) {
                    DocumentCommand dc = new DocumentCommand(ms, name);
                    dc.addPart(new Redirect(new FreenetURI("CHK@")));
                    if (guess) {
                        String type = MimeTypeUtils.getExtType(name);
                        if (type != null)
                            dc.addPart(new InfoPart("file", type));
                    }
                    metadata.addCommand(dc);
                }
            } catch (InvalidPartException e) {
                throw new CLIException("Could not create metadata: " + e);
            } catch (java.net.MalformedURLException e) {
                throw new CLIException("FreenetURI croaked on simple CHK@ URI:"
                                       + e);
            }
        }
        return new PutSiteProcess(uri, htl, p.getString("cipher"),
                                  metadata, buckets, 
                                  new FileBucketFactory());
    }

    public boolean takesData() {
        return false;
    }

    public boolean givesData() {
        return false;
    }

}
