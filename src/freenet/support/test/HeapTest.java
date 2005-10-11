package freenet.support.test;

import java.util.Random;

import junit.framework.TestCase;
import freenet.support.Heap;
import freenet.support.Heap.Element;

/**
 * A test class for the Heap.
 *
 */

public class HeapTest extends TestCase {

    public static final void main(String[] args) {
        SimpleTestRunner.main(
            new String[] { HeapTest.class.getName() }
        );
    }
    

    public HeapTest(String name) {
        super(name);
    }

    public void testHeap() {
        Heap hp = new Heap();
        int[] ints = { -6, -50, -45, 92, 36, 95, 9, -63, -52, 24, -76, 6, 71, 
                       43, 48, 71, -95, -73, -38, -80, -20, -21, 4, 34, -45, 
                       39, -19, 70, -17, 22, -46, 36 };
        for (int i = 0 ; i < ints.length ; i++) {
            hp.put(new IntElement(ints[i]));
            assertTrue("Heap integrity after insert of " + ints[i],
                       hp.checkHeap());
        }
        assertEquals("Testing the heap size",hp.size(), 32);
        //        System.out.println(hp.toString());
        Comparable last = hp.pop();
        for (int i = 1 ; i < ints.length ; i++) {
            IntElement el = (IntElement) hp.pop();
            assertTrue("Testing heap order",last.compareTo(el) > -1);

            last = el;
            assertTrue("Heap integrity after removing of " + ints[i],
                   hp.checkHeap());
        }
    }

    public void testSimulation() {
        Heap hp = new Heap();
        Random r = new Random();
        for (int i = 0 ; i < 10000 ; i++) {
            int c = r.nextInt(6);
            String action = "nothing.";
            if (c <= 2) {
                int ival = r.nextInt(1000);
                hp.put(new IntElement(ival));
                action = "putting value: " + ival;
            } else if (c <= 4 && hp.size() > 0) {
                Comparable res = hp.pop();
                action = "popping out: " + res;
            } else if (hp.size() > 0) {
                Element[] es = hp.elementArray();
                int p = r.nextInt(es.length);
                es[p].remove();
                action = "removing: " + es[p] + " from pos: " + p; 
            }
            assertTrue("Heap integrity after " + action + " in iteration " + i,
                   hp.checkHeap());
        }

    }

    private class IntElement extends Heap.Element {
        private int i;
        public IntElement(int i) { IntElement.this.i = i; }
        public int compareTo(Object o) {
            int j = ((IntElement) o).i;
            return i == j ? 0 : (i > j ? 1 : -1); }
        public String toString() {
            return Integer.toString(i);
        }
    }




}
