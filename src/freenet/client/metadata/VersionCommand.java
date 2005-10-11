package freenet.client.metadata;
import freenet.support.Fields;
import freenet.FieldSet;
/**
 * The metadata "header"
 *
 * @author oskar
 */

public class VersionCommand {

    private Metadata md;
    private int revision;

    public VersionCommand(Metadata md) {
        this.md = md;
        this.revision = Metadata.revision();
    }

    public VersionCommand(Metadata md, FieldSet fs) 
        throws InvalidPartException {

        this.md = md;
        String v = fs.getString("Revision");
        if (v == null)
            throw new InvalidPartException("Version: No revision given");
        else try {
            revision = (int) Fields.hexToLong(v);
        } catch (NumberFormatException e) {
            throw new InvalidPartException("Version: Bad revision number: " +
                                           v);
        }
        if (revision != Metadata.revision())
            throw new InvalidPartException("I support version " + 
                                           Long.toHexString(Metadata.revision()) +
                                           " but " + fs.get("Revision") +
                                           " required.");
    }

    public FieldSet toFieldSet() {
        FieldSet fs = new FieldSet();
        fs.put("Revision",Long.toHexString(revision));
        return fs;
    }

    public int revision() {
        return revision;
    }
}
