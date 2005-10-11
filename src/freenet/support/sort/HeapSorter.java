package freenet.support.sort;

/** A utility class that operates on Sortable objects.  It treats
  * the integer-indexed array as a left-to-right numbering scheme
  * for a binary tree, and enforces the heap property on that tree.
  * Hence, the largest elements (according to Sortable.compare())
  * are moved to index 0.
  *
  * It can also be used to heap-sort in ascending order (again, according
  * to Sortable.compare()), but this destroys the heap property.
 **/
public class HeapSorter implements SortAlgorithm {

    public void sort(Sortable list) {
        heapSort(list);
    }

    public String name() {
        return "Heapsort";
    }

    public static void heapify(Sortable A, int i, int heapSize) {
        int l = i << 1;   // left(i)
        int r = l + 1;    // right(i)
        int largest = (l < heapSize && A.compare(l, i) > 0 ? l : i);
        if (r < heapSize && A.compare(r, largest) > 0) largest = r;		
        if (largest != i) {
            A.swap(i, largest);
            heapify(A, largest, heapSize);
        }
    }
    
    /** Enforces the heap property.
     * @param A a Sortable object
     **/
    public static void enforceHeap(Sortable A) {
        int size = A.size();
        for (int i=size>>1; i>=0; --i) 
            heapify(A, i, size);
    }
    
    /** Sorts the Sortable.
     * @param A a Sortable object
     **/
    public static void heapSort(Sortable A) {
        enforceHeap(A);
        int heapSize = A.size();
        for (int i=heapSize-1; i>0; --i) {
            A.swap(0, i);
            --heapSize;
            heapify(A, 0, heapSize);
        }
    }
}
