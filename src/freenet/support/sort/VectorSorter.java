package freenet.support.sort;

import freenet.support.Comparator;
import java.util.Vector;

/** An object that can be passed to the HeapSorter, or other sorters
  * that take a Sortable object.  It sorts the underlying vector passed
  * in to the constructor.
  *
  * Don't try to add or remove elements and then reuse the same
  * VectorSorter;  get a new one.
  * 
  * @author tavin
  */
public final class VectorSorter implements Sortable {

    private final Vector target;
    private final Comparator comp;

    private final int offset, size;

    public VectorSorter(Vector target) {
        this(target, null, 0, target.size());
    }

    public VectorSorter(Vector target, int offset, int size) {
        this(target, null, offset, size);
    }

    public VectorSorter(Vector target, Comparator comp) {
        this(target, comp, 0, target.size());
    }

    public VectorSorter(Vector target, Comparator comp, int offset, int size) {
        this.target = target;
        this.comp   = comp;
        this.offset = offset;
        this.size   = size;
    }

    public final int compare(int index1, int index2) {
        return comp == null
            ? ((Comparable) target.elementAt(offset+index1)).compareTo(target.elementAt(offset+index2))
            : comp.compare(target.elementAt(offset+index1), target.elementAt(offset+index2))
            ;
    }

    public final void swap(int index1, int index2) {
        Object t = target.elementAt(offset+index1);
        target.setElementAt(target.elementAt(offset+index2), offset+index1);
        target.setElementAt(t, offset+index2);
    }

    public final int size() {
        return size;
    }
}


