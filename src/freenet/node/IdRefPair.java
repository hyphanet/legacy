package freenet.node;

import freenet.Identity;


/**
 * A NodeReference and an Identity.
 * Sorts by the identity.
 */
public class IdRefPair {
    public final NodeReference ref;
    public final Identity id;
    public IdRefPair(Identity id, NodeReference ref) {
        if(id == null)
            this.id = ref.getIdentity();
        else
            this.id = id;
        this.ref = ref;
    }
    public int compareTo(Object o) {
        return id.compareTo(o);
    }
    public boolean equals(Object o) {
        return id.equals(o);
    }
    public int hashCode() {
        return id.hashCode();
    }
}
