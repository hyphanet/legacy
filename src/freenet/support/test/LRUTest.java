package freenet.support.test;
import junit.framework.*;
import freenet.support.LRUQueue;

import java.util.Enumeration;

/**
 * A test class for the LRU Queue (just want to make sure I didn't break it).
 *
 */

public class LRUTest extends TestCase {

    public static final void main(String[] args) {
        SimpleTestRunner.main(
            new String[] { LRUTest.class.getName() }
        );
    }
    

    public LRUTest(String name) {
        super(name);
    }

    

    public void testLRU() {
        Integer[] ints = new Integer[15];
        LRUQueue lq = new LRUQueue();

        for (int i = 0 ; i < 15 ; i++) {
            ints[i] = new Integer(i);
        }

        for (int i = 0 ; i < 5 ; i++) {
            lq.push(ints[i]);
        }

        for (int i = 0 ; i < 10 ; i++) {
            lq.push(ints[i + 5]);
            assertSame(lq.pop(), ints[i]);
        }

        Enumeration e = lq.elements();
        for (int i = 10 ; i < 15 ; i++) {
            assertSame(e.nextElement(), ints[i]);
        }

        lq.push(ints[13]);
        lq.push(ints[10]);

        assertSame(lq.pop(), ints[11]);
        assertSame(lq.pop(), ints[12]);
        assertSame(lq.pop(), ints[14]);
        assertSame(lq.pop(), ints[13]);
        assertSame(lq.pop(), ints[10]);
        
        assertNull(lq.pop());
        
    }
}
