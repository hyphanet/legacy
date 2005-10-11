package freenet.support;

/**
 * Extends the two-object comparison of Comparator with a
 * three-object comparison of the relative distance between
 * the objects.
 */
public interface Metric extends Comparator {

    /**
     * @return   0 if A is equidistant from B and C
     *          >0 if the distance from A to B is greater than from A to C
     *          <0 if the distance from A to C is greater than from A to B
     */
    int compare(Object A, Object B, Object C);

    /**
     * The same, but object A is known to fall between B and C
     * in the sort order.
     */
    int compareSorted(Object A, Object B, Object C);
}


