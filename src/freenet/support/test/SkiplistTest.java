package freenet.support.test;

import java.util.Random;

import junit.framework.TestCase;
import freenet.support.Skiplist;
import freenet.support.Walk;
import freenet.support.Skiplist.SkipNode;

/**
 * A test class for the Skiplist.
 *
 */

public class SkiplistTest extends TestCase {

    public static final void main(String[] args) {
        SimpleTestRunner.main(new String[] { SkiplistTest.class.getName() } );
    }

    Random rand = new Random();

    Skiplist sl;

    public SkiplistTest(String name) {
        super(name);
    }

    public void setUp() {
        rand = new Random();
        sl = new Skiplist(32, rand);

        for (int i = 0 ; i < 1000 ; i++) {
            IntElement h = new IntElement(rand.nextInt());
            sl.treeInsert(h, false);
        }
    }

    public void tearDown() {
        rand = null; sl = null;
    }

    
    public void testOrder() {


        Comparable last = null; Comparable current = null;

        for (Walk w = sl.treeWalk(false) ; 
             ((current = (Comparable) w.getNext()) != null) ; ) {

            if (last != null)
                assertTrue("Asserting that " + last + " > " + current,
                       last.compareTo(current) >= 0);
            else
                assertSame("Max is first", current, sl.treeMax());
            //System.out.print(e.nextElement());
            //System.out.print(' ');
            last = current;
        }

        last = null; current = null;

        for (Walk w = sl.treeWalk(true) ; 
             ((current = (Comparable) w.getNext()) != null) ; ) {
            if (last != null)
                assertTrue("Asserting that " + last + " < " + current,
                       last.compareTo(current) <= 0);
            else 
                assertSame("Min is first", current, sl.treeMin());
            //System.out.print(e.nextElement());
            //System.out.print(' ');
            last = current;
        }
        
        IntElement zero = new IntElement(0);
        sl.treeInsert(zero, true);
        last = null; current = null;
        for (Walk w = sl.treeWalk(new IntElement(0), true, false) ; 
             ((current = (Comparable) w.getNext()) != null) ; ) {
            if (last != null)
                assertTrue("Asserting that " + last + " > " + current,
                       last.compareTo(current) >= 0);
            else
                assertTrue("Inclusivity works", zero == current);
            //System.out.print(e.nextElement());
            //System.out.print(' ');
            last = current;
        }

        last = null; current = null;
        for (Walk w = sl.treeWalk(new IntElement(0), false, true) ; 
             ((current = (Comparable) w.getNext()) != null) ; ) {
            if (last != null)
                assertTrue("Asserting that " + last + " < " + current,
                       last.compareTo(current) <= 0);
            else
                assertTrue("Exclusivity works", zero != current);
            //System.out.print(e.nextElement());
            //System.out.print(' ');
            last = current;
        }
    }

    public void testEmpty() {
        sl = new Skiplist(32, rand);

        IntElement zero = new IntElement(0);

        assertNull("Testing min.", sl.treeMin());
        assertNull("Testing minConstrained (inc).", 
                   sl.treeMinConstrained(zero, true));
        assertNull("Testing minConstrained (exc).", 
                   sl.treeMinConstrained(zero, false));

        assertNull("Testing max o.", sl.treeMax());
        assertNull("Testing maxConstrained (inc).", 
                   sl.treeMaxConstrained(zero, true));
        assertNull("Testing maxConstrained (exc).", 
                   sl.treeMaxConstrained(zero, false));

        // Run the order test as well
        testOrder();
    }

    public void testSingle() {
         sl = new Skiplist(32, rand);
         IntElement zero = new IntElement(0);
         sl.treeInsert(zero, true);

         IntElement zero2 = new IntElement(0);
         assertNotNull("Testing min.", sl.treeMin());
         assertNotNull("Testing minConstrained (inc).", 
                    sl.treeMinConstrained(zero2, true));
         assertNull("Testing minConstrained (exc).", 
                    sl.treeMinConstrained(zero2, false));

         assertNotNull("Testing max.", sl.treeMax());
         assertNotNull("Testing maxConstrained (inc).", 
                       sl.treeMaxConstrained(zero2, true));
         assertNull("Testing maxConstrained (exc).", 
                    sl.treeMaxConstrained(zero2, false));

         // Run the order test as well
         testOrder();
    }

    public void testReplace() {
        IntElement zero1 = new IntElement(0);
        IntElement zero2 = new IntElement(0);
        sl.treeInsert(zero1, true);
        assertSame("Insert element exists.", sl.treeSearch(zero1), zero1);

        IntElement res = (IntElement) sl.treeInsert(zero2, false);
        assertSame("Not replace worked.", sl.treeSearch(zero1), zero1);
        assertSame("Correct collision", res, zero1);

        sl.treeInsert(zero2, true);
        assertSame("Replace worked.", sl.treeSearch(zero1), zero2);
        
        // Run the order test as well
        testOrder();
    }

    public void testRemove() {
        
        for (int i = 0 ; i < 100 ; i++) {
            IntElement h = new IntElement(rand.nextInt());
            sl.treeInsert(h, true);
            assertSame("Inserted element exists.", sl.treeSearch(h), h);
            assertSame("Removed element returned.", 
                          sl.treeRemove((Comparable) h), h);
            assertNull("Remove works.", sl.treeSearch(h));
            assertNull("Remove again.", sl.treeRemove((Comparable) h));
        }

        for (int i = 0 ; i < 100 ; i++) {
            IntElement h = new IntElement(rand.nextInt());
            sl.treeInsert(h, true);
            assertSame("Inserted element exists.", sl.treeSearch(h), h);
            assertTrue("Removed returns true.", sl.treeRemove((SkipNode) h));
            assertNull("Remove works.", sl.treeSearch(h));
            assertNull("Remove again.", sl.treeRemove((Comparable) h));
        }

    }

    private class IntElement extends SkipNode implements Comparable {
        private int i;
        public IntElement(int i) { IntElement.this.i = i; }
        public int compareTo(Object o) {
            int j = ((IntElement) o).i;
            return i == j ? 0 : (i > j ? 1 : -1); }
        public String toString() {
            return Integer.toString(i);
        }

        public Comparable getObject() {
            return this;
        }
    }

}

