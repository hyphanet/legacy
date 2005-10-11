package freenet.support;

public class ObjectMetric extends ObjectComparator implements Metric {

    public static final ObjectMetric instance = new ObjectMetric();
    
    public final int compare(Object A, Object B, Object C) {
        return compare((Measurable) A, (Measurable) B, (Measurable) C);
    }
    
    public static final int compare(Measurable A, Measurable B, Measurable C) {
        return A.compareTo(B, C);
    }

    public final int compareSorted(Object A, Object B, Object C) {
        return compareSorted((Measurable) A, (Measurable) B, (Measurable) C);
    }

    public static final int compareSorted(Measurable A, Measurable B, Measurable C) {
        return A.compareToSorted(B, C);
    }
}


