package freenet.node.rt;

import freenet.Key;
import freenet.Identity;
import freenet.NullIdentity;
import freenet.support.Measurable;


public final class Reference implements Measurable {

    final Key key;
    final Identity ident;
    long timestamp;
    
    public String toString() {
	return key.toString()+":"+ident.fingerprintToString()
	    +":"+timestamp;
    }
    
    Reference(Key key) {
        this(key, NullIdentity.instance, -1);
    }
    
    Reference(Key key, Identity ident, long timestamp) {
        this.key = key;
        this.ident = ident;
        this.timestamp = timestamp;
    }

    
    public final Key getKey() {
        return key;
    }

    public final Identity getIdentity() {
        return ident;
    }


    public final int hashCode() {
        return key.hashCode() ^ ident.hashCode();
    }
    

    public final boolean equals(Object o) {
        return o instanceof Reference && equals((Reference) o);
    }

    public final boolean equals(Reference r) {
        return key.equals(r.key) && ident.equals(r.ident);
    }


    public final int compareTo(Object o) {
        return compareTo((Reference) o);
    }

    public final int compareTo(Reference r) {
        // here, we have to make degenerate keys non-colliding
        // so they can coexist in the binary tree
        int cmp = key.compareTo(r.key);
        return cmp == 0 ? ident.compareTo(r.ident) : cmp;
    }
    
    
    public final int compareTo(Object A, Object B) {
        return compareTo((Reference) A, (Reference) B);
    }

    public final int compareTo(Reference A, Reference B) {
        // this is only used in the metric walks,
        // so degenerate keys may collide
        return key.compareTo(A.key, B.key);
    }


    public final int compareToSorted(Object A, Object B) {
        return compareToSorted((Reference) A, (Reference) B);
    }

    public final int compareToSorted(Reference A, Reference B) {
        // this is only used in the metric walks,
        // so degenerate keys may collide
        return key.compareToSorted(A.key, B.key);
    }
}


