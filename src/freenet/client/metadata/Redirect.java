package freenet.client.metadata;
import freenet.FieldSet;
import freenet.client.Request;
import freenet.client.RequestProcess;
import freenet.client.GetRequestProcess;
import freenet.client.PutRequestProcess;
import freenet.client.FreenetURI;
import freenet.support.Bucket;
import freenet.support.BucketFactory;

public class Redirect extends MetadataPart {

    public static final String name = "Redirect";
    
    protected FreenetURI target;

    public Redirect(FreenetURI target) {
        this.target = target;
    }

    public Redirect(FieldSet fs, 
                    MetadataSettings ms) throws InvalidPartException {
        try {
            if (fs.get("Target") == null) {
                throw new InvalidPartException(name() + ": Requires target");
            }
            target = new FreenetURI(fs.getString("Target"));
            
        } catch (java.net.MalformedURLException e) {
            throw new InvalidPartException(name() + ": malformed URI: " 
                                           + e.getMessage());
        }
    }

    public void addTo(FieldSet fs) {
        FieldSet me = new FieldSet();
        //System.err.println("LALA Converting myself " + target);
        me.put("Target", getTarget().toString());
        fs.put(name(), me);
    }

    public FreenetURI getTarget() {
        return target;
    }

    public void setTarget(FreenetURI target) {
        this.target = target;
    }

    public String name() {
        return name;
    }

    public boolean isControlPart() {
        return true;
    }

    public RequestProcess getGetProcess(FreenetURI furi, int htl, Bucket data, 
                                        BucketFactory ptBuckets, 
                                        int recursionLevel, 
                                        MetadataSettings ms) {

        return new GetRequestProcess(getRequestTarget(furi), 
                                     htl,
                                     data, ptBuckets, ++recursionLevel, ms);
    }

    public RequestProcess getPutProcess(FreenetURI furi, int htl, 
                                        String cipher, Metadata next,
                                        MetadataSettings ms,
                                        Bucket data, BucketFactory ptBuckets,
                                        int recursionLevel, boolean descend) {

        return new RedirectPutProcess(getTarget(), null, htl, cipher, next, 
                                      ms, data, ptBuckets,
                                      ++recursionLevel, descend);
    }

    // Exposed for MetadataHint.
    public FreenetURI getRequestTarget(FreenetURI furi) {
        return target.addMetaStrings(furi.getAllMetaStrings());
    }

    /**
     * I tunnel the PutRequestProcess through here so I can reset target.
     */
    protected class RedirectPutProcess extends PutRequestProcess {

        // the dateredirect class uses this to fix the docname
        private String docname;

        public RedirectPutProcess(FreenetURI furi, String docname, int htl, 
                                  String cipher, Metadata next, 
                                  MetadataSettings ms,
                                  Bucket data, BucketFactory ptBuckets,
                                  int recursionLevel, boolean descend) {
            super(furi, htl, cipher, next, ms, data, ptBuckets, recursionLevel,
                  descend);
            this.docname = docname;
        }

        public Request getNextRequest() {
            Request r = super.getNextRequest();
            if (r == null && getURI() != null)
                target = getURI();
            if (docname != null)
                target = target.setDocName(docname);
            //System.err.println("TARGET:"  + target + " DOCNAME " + docname);
            //System.err.println("LALA: Got new target " + target);
            return r;
        }
    }

    public String toString() {
        return "Redirect -> " + target;
    }
}

