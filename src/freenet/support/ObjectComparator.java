package freenet.support;

public class ObjectComparator implements Comparator {

    public static final ObjectComparator instance = new ObjectComparator();
    
    public final int compare(Object o1, Object o2) {
        return compare((Comparable) o1, (Comparable) o2);
    }
    
    public static final int compare(Comparable c1, Comparable c2) {
        return c1.compareTo(c2);
    }
}


