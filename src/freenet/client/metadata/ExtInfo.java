package freenet.client.metadata;

import java.io.InputStream;

import freenet.FieldSet;
import freenet.client.FreenetURI;
import freenet.support.Bucket;

public class ExtInfo extends MetadataPart implements TrailingFieldPart {

    public static final String name = "ExInfo";

    private FreenetURI uri;
    private Bucket uridata;
    private InputStream trailing;
    private boolean hasTrailing;

    public ExtInfo(FreenetURI uri, Bucket uridata) {
        hasTrailing = false;
        this.uri = uri;
        this.uridata = uridata;
    }

    public ExtInfo(InputStream trailing) {
        hasTrailing = true;
        this.trailing = trailing;
    }

    public ExtInfo(FieldSet fs,
                   MetadataSettings ms) throws InvalidPartException {
        String uris = fs.getString("URI");
        if (uris != null)
            try {
                uri = new FreenetURI(uris);
            } catch (java.net.MalformedURLException e) {
                throw new InvalidPartException(name() + ": invalid URI: " 
                                               + uris);
            }
        hasTrailing = "yes".equals( fs.getString("Trailing") );
    }

    /**
     * Sets the trailing field external info it was expected. If the 
     * "Trailing" field was not set in the fieldset or this was constructed
     * by the constructor with only a URI, this will 
     * throw "InvalidPartException".
     */
    public void setTrailing(InputStream in) throws InvalidPartException {
        if (hasTrailing)
            trailing = in;
        else
            throw new InvalidPartException(name()+ ": not expecting trailing");

    }

    public InputStream getTrailing() {
        return trailing;
    }

    public boolean hasTrailing() {
        return hasTrailing;
    }

    public String name() {
        return name;
    }

    public boolean isControlPart() {
        return false;
    }

    public void addTo(FieldSet fs) {
        FieldSet me = new FieldSet();
        if (hasTrailing)
            me.put("Trailing","yes");
        if (uri != null)
            me.put("URI", uri.toString());
    }

    /*
     * Really we need to implement the process for putting and getting
     * the URI field, but I haven't yet thought about trying to collect
     * document metadata (not control stuff) from several sources and
     * agregating it (though of course we'll have to, because of 
     * redirect metadata).
     */
}


