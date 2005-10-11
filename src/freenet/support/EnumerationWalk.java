package freenet.support;

import java.util.Enumeration;

/**
 * Wraps an Enumeration with a Walk interface.
 */
public class EnumerationWalk implements Walk {
    
    private final Enumeration enu;

    public EnumerationWalk(Enumeration enu) {
        this.enu = enu;
    }

    public final Object getNext() {
        return enu.hasMoreElements() ? enu.nextElement() : null;
    }
}


