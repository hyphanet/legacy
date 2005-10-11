package freenet;

/**
 * The common superclass of Self and Peer.
 *
 * @author oskar
 */

public abstract class AddressBase {

    protected final Transport t;

    protected AddressBase(Transport t) {
        this.t = t;
    }

    /**
     * Return the object for the this type of Address.
     */
    public final Transport getTransport() {
        return t;
    }

    /**
     * Returns a string like:
     * "Identity fingerpint: <pk fingerprint> Type: <transport type> 
     *  Val: <getValString()>
     */
    public String toString() {
        //StringBuffer bf = new StringBuffer();
        //bf.append(" Type: ").append(t.getName());
        //bf.append(" Val: ").append(getValString());	    
        //return bf.toString();
        return t.getName() + "/" + getValString();
    }

    /**
     * Returns a FieldSet describing this Address for FNP serialization.
     */
    /*
    public FieldSet toFieldSet() {
		FieldSet fs = new FieldSet();
                //		identity.addTo(fs);
		
		fs.add("physical",t.getName() + "/" + getValString());
		return fs;
    }
    */

    /**
     * Two addresses considered equal if the share the same Transport and 
     * identity.
     */
    /*
    public final boolean equals(Object o) {
        return ((o instanceof Address) &&
                (((Address) o).t == t));
    }
    */

    /**
     * Returns the string representation of the physical part of the address
     * for serialization.
     */
    public abstract String getValString();

}


