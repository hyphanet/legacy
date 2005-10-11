package freenet.support;

import java.util.Enumeration;

/**
 * Wraps a Walk with a (pre-fetching) Enumeration.
 */
public class WalkEnumeration implements Enumeration {

    private final Walk walk;
    private Object next;
    

    public WalkEnumeration(Walk walk) {
        this.walk = walk;
        next = walk.getNext();
    }
    

    public final boolean hasMoreElements() {
        return next != null;
    }

    public final Object nextElement() {
        try {
            return next;
        }
        finally {
            next = walk.getNext();
        }
    }
}


