package freenet.message.client;

import java.io.InputStream;
import java.io.IOException;
import java.util.Enumeration;

import freenet.FieldSet;
import freenet.support.Fields;
import freenet.client.FreenetURI;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.InvalidPartException;
import freenet.client.metadata.MetadataPart;
import freenet.client.metadata.Redirect;
import freenet.client.metadata.DateRedirect;
import freenet.client.metadata.SplitFile;
import freenet.client.metadata.InfoPart;
import freenet.client.metadata.MetadataSettings;

/**
 * FCP message containing hints about what to do
 * with the metadata returned by a ClienGet
 * request.
 *
 * @author giannij
 */
public class MetadataHint extends ClientMessage {

    public static final String messageName = "MetadataHint";
    
    // Request was data
    public final static int DATA = 1;
    // Request was a redirect, NextURI contains
    // the next URI to request.
    public final static int REDIRECT = 2;
    // Request was a date redirect, NextURI contains
    // the next URI to request.
    public final static int DATEREDIRECT = 3;
    // Request was a SplitFile
    public final static int SPLITFILE = 4;
    // Request was something we can't parse
    // the metadata for.
    public final static int TOODUMB = 5;
    // There was an error parsing or reading
    // the requests metadata.
    public final static int ERROR = 6; 

    private long timeSec = -1;
    private int kind = -1;
    private String mimeType; 
    private String nextURI; 
    private boolean isMapFile;
    private int increment = 0;
    private long offset = 0;

    // mdStream can be null.
    // To wire.
    public MetadataHint(long id, InputStream mdStream, 
                        FreenetURI uri, boolean hasData, long timeSec) {
        
        super(id, new FieldSet());

        this.close = true;

        if (timeSec == -1) {
            // Set the time if the caller didn't.
            timeSec = System.currentTimeMillis() / 1000; 
        }
        this.timeSec = timeSec;

        // Parse metadata

        MetadataSettings ms = new MetadataSettings();
        Metadata md = null;
        boolean parseError = true;
        try {
            if (mdStream != null) {
                md = new Metadata(mdStream, ms);
                mdStream.close();
            }
            else {
                md = new Metadata(ms);
            }
            parseError = false;
        }
        catch (InvalidPartException ipe) {
            //ipe.printStackTrace();
        }
        catch (IOException ioe) {
            //ioe.printStackTrace();
        }

        if (parseError) {
            kind = ERROR;
        } 
        else if (hasData) {
            kind = DATA;
            mimeType = getMimeType(md.getDefaultDocument());
        }
        else {
            // REDIRECT, DATEREDIRECT, SPLITFILE, 
            // or something too complicated.
            DocumentCommand d = 
                (uri.getMetaString() == null ? 
                 null : 
                 md.getDocument(uri.getMetaString()));

            FreenetURI nuri;
            if (d != null) {
                nuri = uri.popMetaString();
            } else {
                d = md.getDefaultDocument();
                nuri = uri;
            }

            if (d != null) {
                // Extract mimetype info from InfoPart(s)
                // in non-control parts *even for redirects* 
                // The Metadata spec allows mime type info 
                // anywhere along the redirect chain.  Let 
                // the client decide in the case of conflicts.
                mimeType = getMimeType(d);

                // Check to see if the metadata has multiple
                // Redirects.
                isMapFile = isMap(md);

                MetadataPart mp = d.getControlPart();
                if (mp == null) {
                    kind = TOODUMB; // hmmm... 
                }
                else if (mp instanceof DateRedirect) {
                    kind = DATEREDIRECT;
                    DateRedirect dbr = (DateRedirect)mp;
                    nextURI = dbr.getTargetForTime(nuri, timeSec).toString();
                    increment = dbr.getIncrement();
                    offset = dbr.getOffset();
                }
                // Redirect is the superclass of DateRedirect
                // so order is important.
                else if (mp instanceof Redirect) {
                    kind = REDIRECT; 
                    nextURI = (((Redirect)mp).getRequestTarget(nuri)).toString();
                }
                else if (mp instanceof SplitFile) {
                    kind = SPLITFILE;
                }
                else {
                    kind = TOODUMB;
                }
            }
            else {
                kind = TOODUMB; // How do we get here?
            }
        }

        // Set Fieldset values
        otherFields.put("TimeSec", Long.toString(timeSec, 16));
        otherFields.put("Kind", Integer.toString(kind, 16));
        otherFields.put("IsMapFile", Fields.boolToString(isMapFile));
        if (mimeType != null) {
            otherFields.put("MimeType", mimeType);
        }
        if (nextURI != null) {
            otherFields.put("NextURI", nextURI);
        }
        otherFields.put("Increment", Integer.toString(increment, 16));
        otherFields.put("Offset", Long.toString(offset, 16));
    }

    private static String getMimeType(DocumentCommand d) {
        if (d == null) {
            return null;
        }

        String ret = null;
        MetadataPart[] parts = d.getNonControlParts();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] instanceof InfoPart) {
                ret = ((InfoPart)parts[i]).format();
                if (ret != null) {
                    break;
                }
            }
        }
        return ret;
    }

    // Map files don't formally exist anymore,
    // but we call any metadata that has more than
    // 1 redirect a map file.
    private static boolean isMap(Metadata md) {
        if (md == null) {
            return false;
        }

        int redirectCount = 0;
        for (Enumeration e = md.getDocumentNames() ; e.hasMoreElements();) {
            DocumentCommand document = md.getDocument((String)e.nextElement());
            MetadataPart part = document.getControlPart();
            if (part instanceof Redirect) {
                redirectCount++;
                if (redirectCount > 1) {
                    return true;
                }
            }
        }

        return false;
    }


    public String getMessageName() {
        return messageName;
    }
}



