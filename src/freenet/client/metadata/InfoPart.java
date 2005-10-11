package freenet.client.metadata;
import freenet.FieldSet;

//import freenet.support.Bucket;
//import java.io.InputStream;

public class InfoPart extends MetadataPart {

    public static final String name = "Info";

    private String format;
    private String description;
    private String checksum;

    public InfoPart(String description, String format, String checksum) {
        this.format = format;
        this.description = description;
        this.checksum = checksum;
    }

    // maybe we should have a mimetype class :-)
    public InfoPart(String description, String format) {
        this.format = format;
        this.description = description;
    }

    public InfoPart(String description) {
        this(description, null);
    }

    public InfoPart(FieldSet fs, 
                    MetadataSettings ms) {
        format = fs.getString("Format");
        description = fs.getString("Description");
        checksum = fs.getString("Checksum");
    }

    public String name() {
        return name;
    }

    public final String format() { return format; }

    // REDFLAG: Need to spec.  I am using SHA1 for now. -- gj
    public final String checksum() { return checksum; }
    final void setChecksum(String value) { checksum = value; }
        
    public boolean isControlPart() {
        return false;
    }

    public void addTo(FieldSet fs) {
        FieldSet me = new FieldSet();
        if (description != null) {
	    me.put("Description", description);
	}

        if (format != null) {
            me.put("Format", format);
	}

        if (checksum != null) {
            me.put("Checksum", checksum );
	}


        fs.put(name(), me);
    }

    public String toString() {
        return "Info: " + description + " (" + (format == null ? "" : format) + ")" +
             " Checksum: " + (checksum == null ? "" : checksum);
    }
}

