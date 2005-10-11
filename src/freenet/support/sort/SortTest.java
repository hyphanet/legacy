package freenet.support.sort;
import junit.framework.*;

/**
 * A class for testing the various sorting mechanisms.
 */

public class SortTest extends TestCase {
    
    public static final void main(String[] args) {
        freenet.support.test.SimpleTestRunner.main(
            new String[] { SortTest.class.getName() }
        );
    }
    

    private NArraySorter odd;
    private NArraySorter even;
    private NArraySorter forwardList;
    private NArraySorter backwardList;
    private NArraySorter uniformList;

    public SortTest(String name) {
        super(name);
    }

    public static void assertSorted(Sortable a) {
        assertSorted("Testing that " + a + " is sorted", a);
    }

    public static void assertSorted(String m, Sortable a) {
        for (int i = 1 ; i < a.size() ; i++) {
            assertTrue(m, a.compare(i-1,i) <= 0);
        }
    }

    public void setUp() {
        int[] oints = { 4, -37, 78, -20, -48, 89, 90, 74, 27, -4, -4, -38, -33,
                       36, 74, -90, 57, -80, -70, 39, 4, 35, -49, 26, 63, -84,
                        -94, 0, -22, -69, 62, 50, 80, 74, 44, 58, -36 };
        odd = new NArraySorter(oints);
        
        int[] eints = { -64, 4, -92, 11, -35, -42, -29, -84, 83, 35, 90, -91, 
                        -41, 13, -50, -9, 11, -62, 22, 90, -19, -18, -9, -66, 
                        86, -60, -28, 99, 80, -7, 28, 62 };

        even = new NArraySorter(eints);

        int[] fints = new int[32];
        int[] bints = new int[32];
        int[] uints = new int[32];
        for (int i = 0 ; i < 32 ; i++) {
            fints[i] = i;
            bints[i] = 31 - i;
            uints[i] = 1;
        }
        forwardList = new NArraySorter(fints);
        backwardList = new NArraySorter(bints);
        uniformList = new NArraySorter(uints);
    }

    public void tearDown() {
        odd = null;
        even = null;
        forwardList = null;
        backwardList = null;
        uniformList = null;
    }


    private class NArraySorter implements Sortable {
        int[] n;

        public NArraySorter(int[] n) {
            this.n = n;
        }

        public int size() {
            return n.length;
        }

        public int compare(int i, int j) {
            return n[i] == n[j] ? 0 : n[i] > n[j] ? 1 : -1;
        }

        public void swap(int i, int j) {
            int t = n[i];
            n[i] = n[j];
            n[j] = t;
        }

        public String toString() {
            StringBuffer sb = new StringBuffer("{ ");
            for (int i = 0 ; i < n.length ; i++) {
                sb.append(n[i] % 100 + 
                          (i == n.length ? " }" : ", "));
            }
            return sb.toString();
        }

    }

    //////////////////////// Tests /////////////////////////

    public void testSortTest() {
        assertSorted("Testing that sorted list passes",
                     forwardList);
        assertSorted("Testing that uniform list passes",
                     uniformList);
        try {
            assertSorted(backwardList);
            fail("Passed backwards list");
        } catch (AssertionFailedError e) {
        }

        try {
            assertSorted(odd);
            fail("Passed random list of 37 elements");
        } catch (AssertionFailedError e) {
        }

        try {
            assertSorted(even);
            fail("Passed random list of 32 elements");
        } catch (AssertionFailedError e) {
        }

    }

    public void testSortAlgorithm(SortAlgorithm sa) {
        sa.sort(odd);
        assertSorted("Testing that " + sa.name()+" sorted list of 37 elements",
                     odd);
        sa.sort(even);
        assertSorted("Testing that " + sa.name()+" sorted list of 32 elements"
                     + ". Note that 37 element list passed.",
                     even);
        sa.sort(forwardList);
        assertSorted("Testing that " + sa.name()+" left already sorted list",
                     forwardList);
        sa.sort(backwardList);
        assertSorted("Testing that " + sa.name()+" sorted backwards list",
                     backwardList);
        sa.sort(uniformList);
        assertSorted("Testing that "+ sa.name() + " left uniform list",
                     uniformList);
    }

    public void testQuickSort() {
        testSortAlgorithm(new QuickSorter());
    }

    public void testHeapSort() {
        testSortAlgorithm(new HeapSorter());
    }

}





