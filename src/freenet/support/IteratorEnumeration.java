package freenet.support;

import java.util.*;

public class IteratorEnumeration implements Enumeration {
    public Iterator i;
    
    public IteratorEnumeration(Iterator i) {
	this.i = i;
    }
    
    public boolean hasMoreElements() {
	return i.hasNext();
    }
    
    public Object nextElement() {
	return i.next();
    }
}
