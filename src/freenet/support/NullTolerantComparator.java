package freenet.support;

/**
 * Comparator that goes by the native order, except that null's are
 * all equal and all less than any other object.
 * @author amphibian
 */
public class NullTolerantComparator implements java.util.Comparator {

    public int compare(Object o1, Object o2) {
        if(o1 == null && o2 == null) return 0;
        if(o1 == null && o2 != null) return -1; // o1<o2
        if(o1 != null && o2 == null) return 1;  // o1>o2
        return ((Comparable)o1).compareTo(o2);
    }
}
