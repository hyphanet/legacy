package freenet.support;

/**
 * Similar to an Enumeration, but cannot be tested for additional
 * elements without actually trying to fetch one.
 */
public interface Walk {

    /**
     * @return  the next element, or null if there are no more
     */
    Object getNext();
}


