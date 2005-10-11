package freenet.support.sort;

/**
 * Reverses the sign of compare-values passing through this interface,
 * so that the result set of targets gets sorted in the opposite order
 * they normally would.
 */
public final class ReverseSorter implements Sortable {

    private final Sortable sortable;
    
    public ReverseSorter(Sortable sortable) {
        this.sortable = sortable;
    }

    public final int compare(int i1, int i2) {
        return -1 * sortable.compare(i1, i2);
    }

    public final void swap(int i1, int i2) {
        sortable.swap(i1, i2);
    }

    public final int size() {
        return sortable.size();
    }
}


