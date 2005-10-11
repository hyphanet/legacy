package freenet.support.test;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Random;

import junit.framework.TestCase;
import freenet.support.BinaryTree;
import freenet.support.Walk;
import freenet.support.BinaryTree.Node;
import freenet.support.sort.ArraySorter;
import freenet.support.sort.HeapSorter;
import freenet.support.sort.ReverseSorter;
import freenet.support.sort.Sortable;

/**
 * I abstracted Tavin's test for RedBlackTree so I could run it 
 * against my skiplist as well. (oskar)
 *
 * This is a unit test for freenet.support.RedBlackTree
 * Sequences of values to try are contained in a statically
 * initialized hashtable.  The test may be run by invoking
 * the main method of this class, in which case an additional
 * sequence of values to try may be passed in as the
 * command-line args.
 *
 * NOTE: it is assumed that there are no duplicate values
 *       in the test sequences, that each contains at least
 *       ten elements, and that the values are spaced
 *       at least two units apart!
 * 
 * @author tavin
 */
public abstract class TreeTester extends TestCase {

    // Note-  these sequences were derived from throwing random
    //        numbers at the main() method and finding sets that
    //        caused the tests to fail, then fixing the problems,
    //        then going back to more random numbers..

    // These sequences cannot contain any duplicate values,
    // they must have at least ten elements,
    // and they must be spaced at least two units apart!

    public static final Random rand = new Random();
    public static final Hashtable sequences = new Hashtable();
    static {

        // 10 values in sorted order
        sequences.put("A", new int[] {
            -1733613400,
            -1244370435,
            -605742803,
            -266162803,
            -64144351,
            283085925,
            369506131,
            678732349,
            1464774969,
            1704765035
        });

        // 10 values in unsorted order
        sequences.put("B", new int[] {
            1981487242,
            956341488,
            1914939777,
            -1611670742,
            -1702499067,
            1850449253,
            -1092490445,
            -742486421,
            762893437,
            285552681
        });

        // 1000 random values

        sequences.put("R", new Integer(1000));
    }
    

    
    /**
     * A simple Comparable-wrapper around an integer value,
     * used as the dummy value inside the tree's Node objects.
     */
    private static final class TestVal implements Comparable {

        final int val;

        TestVal(int val) {
            this.val = val;
        }

        public final String toString() {
            return Integer.toString(val);
        }

        public final int hashCode() {
            return val * 1000000007;
        }

        public final boolean equals(Object o) {
            return o instanceof TestVal && equals((TestVal) o);
        }

        public final boolean equals(TestVal t) {
            return val == t.val;
        }

        public final int compareTo(Object o) {
            return compareTo((TestVal) o);
        }

        public final int compareTo(TestVal t) {
            return val == t.val ? 0 : (val > t.val ? 29 : -37);
        }
    }


    
    public TreeTester(String name) {
        super(name);
    }


    private int[] getSequence(String name) {
        Object o = sequences.get(name);
        if (o instanceof int[]) {
            return (int[]) o;
        } else {
            int size = ((Integer) o).intValue();
            Hashtable ht = new Hashtable(size);
            int[] ret = new int[size];
            for (int i = 0 ; i < size ; i++) {
                Integer r;
                do {
                    ret[i] = rand.nextInt();
                    r = new Integer(ret[i]);
                } while (ht.contains(r));
                ht.put(r, r);
            }
            return ret;
        }
    }

    /**
     * Constructs an array of TestVal instances corresponding
     * to the named sequence of integers.
     */
    private TestVal[] getTestValSequence(String name) {
        int[] seq = getSequence(name);
        TestVal[] ret = new TestVal[seq.length];
        for (int i=0; i<seq.length; ++i)
            ret[i] = new TestVal(seq[i]);
        return ret;
    }

    /**
     * The same, but returned in sorted order.
     * @param ascending  true for ascending order
     */
    private TestVal[] getTestValSequence(String name, boolean ascending) {
        TestVal[] ret = getTestValSequence(name);
        Sortable so = new ArraySorter(ret);
        if (!ascending)
            so = new ReverseSorter(so);
        HeapSorter.heapSort(so);
        return ret;
    }
    

    /**
     * Return a tree of the sort to test.
     */
    public abstract BinaryTree makeTree();

    /**
     * Return a Node corresponding to the tree.
     */
    public abstract Node makeNode(Comparable t);

    /**
     * Tests the (implementation specific) integrity of some part of the tree.
     */
    public abstract void integrityCheck(String sequence, Node node);


    private BinaryTree makeTree(TestVal[] seq) {
        BinaryTree tree = makeTree();
        for (int i=0; i<seq.length; ++i) {
            if (null != tree.treeInsert(makeNode(seq[i]), false))
                fail("Key collision in sequence!");
        }
        return tree;
    }

 

    /**
     * Tests the basic functioning of the tree-  sane insertion,
     * search, and removal behavior.
     */
    public void testConstruction() {

        Enumeration seqs = sequences.keys();
        while (seqs.hasMoreElements()) {
            
            String seqStr = (String) seqs.nextElement();
            int[] seq = getSequence(seqStr);
            //sequences.get(seqStr);
        
            BinaryTree tree = makeTree();

            // test population
            for (int i=0; i<seq.length; ++i) {
            
                TestVal t = new TestVal(seq[i]);
                
                Node na = makeNode(t);
                assertSame("NodeImpl is sane ("+seqStr+")", t, na.getObject());
            
                assertNull("No spurious collisions ("+seqStr+")",
                           tree.treeInsert(na, false));
                
                Node nb = tree.treeSearch(new TestVal(seq[i]));
                assertSame("What we got out is what we put in ("+seqStr+")", na, nb);
            }

            Node[] nodes = new Node[seq.length];
            
            // test tree search
            for (int i=0; i<seq.length; ++i) {
                nodes[i] = tree.treeSearch(new TestVal(seq[i]));
                assertEquals("What we got out is still what we put in ("+seqStr+")",
                             new TestVal(seq[i]), nodes[i].getObject());
            }

            // test removal by node
            for (int i=0; i<nodes.length; ++i) {
                
                assertTrue("Removal returned success ("+seqStr+")",
                           tree.treeRemove(nodes[i]));
                assertNull("Node really gone after removal ("+seqStr+")",
                           tree.treeSearch(new TestVal(seq[i])));
                
                assertTrue("Node properly detached ("+seqStr+")",
                           !nodes[i].hasParent());
                assertTrue("Node properly detached ("+seqStr+")",
                           !nodes[i].hasLeftChild());
                assertTrue("Node properly detached ("+seqStr+")",
                           !nodes[i].hasRightChild());
                assertNull("Node properly detached ("+seqStr+")",
                           nodes[i].getParent());
                assertNull("Node properly detached ("+seqStr+")",
                           nodes[i].getLeftChild());
                assertNull("Node properly detached ("+seqStr+")",
                           nodes[i].getRightChild());
            }
            
            for (int i=0; i<nodes.length; ++i) {
                tree.treeInsert(nodes[i], false);
            }
            
            // test removal by key
            for (int i=0; i<seq.length; ++i) {
                assertSame("Key-removal works ("+seqStr+")",
                           nodes[i], tree.treeRemove(new TestVal(seq[i])));
            }
        }
    }

    
    /**
     * A more rigorous test of the integrity of the tree under various
     * patterns of node removal (including checking that the tree is
     * correctly balanced).
     */
    public void testRemoval() {
        Enumeration seqe = sequences.keys();
        while (seqe.hasMoreElements()) {
            String seqStr = (String) seqe.nextElement();
            BinaryTree tree;
            Node n;
            
            TestVal[] seq = getTestValSequence(seqStr);
            // remove always the tree-min
            tree = makeTree(seq);
            while (null != (n = tree.treeMin())) {
                Node nn = n;
                while (nn.hasParent()) {
                    nn = nn.getParent();
                }
                integrityCheck(seqStr, nn);

                assertTrue("Sane tree-min removal ("+seqStr+")",
                           tree.treeRemove(n));
            }
            assertNull("Tree really empty ("+seqStr+")", tree.treeMin());
            assertNull("Tree really empty ("+seqStr+")", tree.treeMax());
            
            // remove always the tree-max
            tree = makeTree(seq);
            while (null != (n = tree.treeMax())) {
                Node nn = n;
                while (nn.hasParent()) {
                    nn = nn.getParent();
                }
                integrityCheck(seqStr, nn);
                assertTrue("Sane tree-max removal ("+seqStr+")",
                           tree.treeRemove(n));
            }
            assertNull("Tree really empty ("+seqStr+")", tree.treeMin());
            assertNull("Tree really empty ("+seqStr+")", tree.treeMax());
            
            // remove always the tree-root
            tree = makeTree(seq);
            while (null != (n = tree.treeMin())) {
                while (n.hasParent()) {
                    n = n.getParent();
                }
                integrityCheck(seqStr, n);
                assertTrue("Sane tree-root removal ("+seqStr+")",
                           tree.treeRemove(n));
            }
            assertNull("Tree really empty ("+seqStr+")", tree.treeMin());
            assertNull("Tree really empty ("+seqStr+")", tree.treeMax());
        }
    }
    

    /**
     * Test that the option to replace a node on value-collision works.
     */
    public void testReplacing() {
        Enumeration seqe = sequences.keys();
        while (seqe.hasMoreElements()) {
            String seqStr = (String) seqe.nextElement();

            TestVal[] seq = getTestValSequence(seqStr);
            BinaryTree tree = makeTree(seq);

            for (int i=0; i<seq.length; ++i) {
                
                Node n = makeNode(seq[i]);
                Node old = tree.treeInsert(n, true);
                assertEquals("Replaced equal-value node ("+seqStr+")",
                             n.getObject(), old.getObject());
                
                Node nt = tree.treeSearch(seq[i]);
                assertSame("Replacement node really in tree ("+seqStr+")", nt, n);
                
                assertTrue("Node properly detached ("+seqStr+")",
                           !old.hasParent());
                assertTrue("Node properly detached ("+seqStr+")",
                           !old.hasLeftChild());
                assertTrue("Node properly detached ("+seqStr+")",
                           !old.hasRightChild());
                assertNull("Node properly detached ("+seqStr+")",
                           old.getParent());
                assertNull("Node properly detached ("+seqStr+")",
                           old.getLeftChild());
                assertNull("Node properly detached ("+seqStr+")",
                           old.getRightChild());
            }
        }
    }
            
    
    /**
     * Test that the treeMatch() method behaves.
     */
    public void testMatching() {
        Enumeration seqe = sequences.keys();
        while (seqe.hasMoreElements()) {
            String seqStr = (String) seqe.nextElement();

            TestVal[] seq = getTestValSequence(seqStr, true);
            BinaryTree tree = makeTree(seq);
            TestVal t;
            Node n;
            
            // test boundary conditions
            
            t = new TestVal(Integer.MIN_VALUE);
            n = tree.treeMatch(t, 0);
            assertEquals("Lower boundary tree-matching works",
                         n.getObject(), seq[0]);
            n = tree.treeMatch(t, 10);
            assertEquals("Lower boundary tree-matching works",
                         n.getObject(), seq[0]);
            n = tree.treeMatch(t, -10);
            assertEquals("Lower boundary tree-matching works",
                         n.getObject(), seq[0]);
            
            t = new TestVal(Integer.MAX_VALUE);
            n = tree.treeMatch(t, 0);
            assertEquals("Upper boundary tree-matching works",
                         n.getObject(), seq[seq.length-1]);
            n = tree.treeMatch(t, 10);
            assertEquals("Upper boundary tree-matching works",
                         n.getObject(), seq[seq.length-1]);
            n = tree.treeMatch(t, -10);
            assertEquals("Upper boundary tree-matching works",
                         n.getObject(), seq[seq.length-1]);
            
            // test using an exactly matching search key
            
            int m = seq.length >> 1;
            
            n = tree.treeMatch(seq[m], 0);
            assertEquals("Exact tree-match works ("+seqStr+")",
                         seq[m], n.getObject());
            n = tree.treeMatch(seq[m], 100);
            assertEquals("Exact tree-match works ("+seqStr+")",
                         seq[m], n.getObject());
            n = tree.treeMatch(seq[m], -100);
            assertEquals("Exact tree-match works ("+seqStr+")",
                         seq[m], n.getObject());

            // test matching with a key that has no exact match
            
            t = new TestVal(seq[m].val - 1);
            n = tree.treeMatch(t, 0);
            assertTrue("Zero-sense tree-match works ("+seqStr+")",
                       seq[m].equals(n.getObject())
                       || seq[m-1].equals(n.getObject()));
            n = tree.treeMatch(t, 1);
            assertEquals("Positive-sense tree-match works ("+seqStr+")",
                         seq[m], n.getObject());
            n = tree.treeMatch(t, -1);
            assertEquals("Negative-sense tree-match works ("+seqStr+")",
                         seq[m-1], n.getObject());
        }
    }


    /**
     * Test that the ordering within the tree as evidenced by the
     * various min/max and successor/predecessor methods is consistent
     * with the correct ordering of the TestVal objects.
     */
    public void testOrdering() {
        Enumeration seqe = sequences.keys();
        while (seqe.hasMoreElements()) {
            String seqStr = (String) seqe.nextElement();

            TestVal[] seq = getTestValSequence(seqStr, true);
            BinaryTree tree = makeTree(seq);
            Node n;

            // test tree min/max

            n = tree.treeMin();
            assertEquals("Correct tree-min ("+seqStr+")",
                         n.getObject(), seq[0]);
            
            n = tree.treeMax();
            assertEquals("Correct tree-max ("+seqStr+")",
                         n.getObject(), seq[seq.length-1]);

            // test constrained min/max
            
            int m = seq.length >> 1;
            
            n = tree.treeMinConstrained(seq[m], false);
            assertEquals("Constrained, non-inclusive tree-min works ("+seqStr+")",
                         n.getObject(), seq[m+1]);
            n = tree.treeMinConstrained(seq[m], true);
            assertEquals("Constrained, inclusive tree-min works ("+seqStr+")",
                         n.getObject(), seq[m]);
            
            n = tree.treeMaxConstrained(seq[m], false);
            assertEquals("Constrained, non-inclusive tree-max works ("+seqStr+")",
                         n.getObject(), seq[m-1]);
            n = tree.treeMaxConstrained(seq[m], true);
            assertEquals("Constrained, inclusive tree-max works ("+seqStr+")",
                         n.getObject(), seq[m]);
        
            // test successor/predecessor

            n = tree.treeMin();
            for (int i=0; i<seq.length; ++i) {
                assertEquals("Successorship in correct order ("+seqStr+")",
                             seq[i], n.getObject());
                n = tree.treeSuccessor(n);
            }
            
            n = tree.treeMax();
            for (int i=seq.length-1; i>=0; --i) {
                assertEquals("Predecessorship in correct order ("+seqStr+")",
                             seq[i], n.getObject());
                n = tree.treePredecessor(n);
            }
        }
    }
    

    /**
     * Test that the tree-walking methods are sane.
     */
    public void testWalking() {
        Enumeration seqe = sequences.keys();
        while (seqe.hasMoreElements()) {
            String seqStr = (String) seqe.nextElement();

            TestVal[] seq = getTestValSequence(seqStr, true);
            BinaryTree tree = makeTree(seq);
            Walk w;
            
            // basic ascending and descending

            w = tree.treeWalk(true);
            for (int i=0; i<seq.length; ++i) {
                assertEquals("Ascending walk in correct order ("+seqStr+")",
                             seq[i], ((Node) w.getNext()).getObject());
            }
            assertNull("Ascending walk ended on time ("+seqStr+")",
                       w.getNext());
            
            w = tree.treeWalk(false);
            for (int i=seq.length-1; i>=0; --i) {
                assertEquals("Descending walk in correct order ("+seqStr+")",
                             seq[i], ((Node) w.getNext()).getObject());
            }
            assertNull("Descending walk ended on time ("+seqStr+")",
                       w.getNext());

            // walk from a starting value

            int m = seq.length >> 1;

            w = tree.treeWalk(seq[m], false, true);
            for (int i=m+1; i<seq.length; ++i) {
                assertEquals("Ascending walk from starting key, non-inclusive ("+seqStr+")",
                             seq[i], ((Node) w.getNext()).getObject());
            }
            
            w = tree.treeWalk(seq[m], true, true);
            for (int i=m; i<seq.length; ++i) {
                assertEquals("Ascending walk from starting key, inclusive ("+seqStr+")",
                             seq[i], ((Node) w.getNext()).getObject());
            }
            
            w = tree.treeWalk(seq[m], false, false);
            for (int i=m-1; i>=0; --i) {
                assertEquals("Descending walk from starting key, non-inclusive ("+seqStr+")",
                             seq[i], ((Node) w.getNext()).getObject());
            }
            
            w = tree.treeWalk(seq[m], true, false);
            for (int i=m; i>=0; --i) {
                assertEquals("Descending walk from starting key, inclusive ("+seqStr+")",
                             seq[i], ((Node) w.getNext()).getObject());
            }

            // walk from a starting Node

            Node n = tree.treeSearch(seq[m]);

            w = tree.treeWalk(n, true);
            assertSame("Ascending walk from starting node returns the successor ("+seqStr+")",
                       tree.treeSuccessor(n), w.getNext());
            w = tree.treeWalk(n, false);
            assertSame("Descending walk from starting node returns the predecessor ("+seqStr+")",
                       tree.treePredecessor(n), w.getNext());
        }
    }


    /**
     * Test that the tree Walk instances can cope with concurrent
     * modification of the tree.
     */
    public void testWalkingModificationAsc() {
        Enumeration seqe = sequences.keys();
        while (seqe.hasMoreElements()) {
            String seqStr = (String) seqe.nextElement();

            TestVal[] seq = getTestValSequence(seqStr, true);
            BinaryTree tree = makeTree(seq);
        
            Walk w;
            Node n;
            TestVal t;
            
            // start with an ascending walk
            w = tree.treeWalk(true);
            n = (Node) w.getNext();
            assertEquals("Ascending walk started correctly ("+seqStr+")",
                         n.getObject(), seq[0]);

            // remove just-returned node, shouldn't affect the walk
            tree.treeRemove(n);
            n = (Node) w.getNext();
            assertEquals("Ascending walk handles last-removal ("+seqStr+")",
                         n.getObject(), seq[1]);

            // remove next node in sequence, should get skipped by walk
            tree.treeRemove(seq[2]);
            n = (Node) w.getNext();
            assertEquals("Ascending walk handles next-removal ("+seqStr+")",
                         n.getObject(), seq[3]);

            // insert a new node prior to the next one
            t = new TestVal(seq[4].val - 1);
            tree.treeInsert(makeNode(t), false);
            
            n = (Node) w.getNext();
            assertEquals("Ascending walk catches insertion ("+seqStr+")",
                         n.getObject(), t);
            n = (Node) w.getNext();
            assertEquals("Ascending walk continues correctly ("+seqStr+")",
                         n.getObject(), seq[4]);

            // delete the one just returned, then
            // insert one with the same value (should be skipped)
            tree.treeRemove(seq[4]);
            tree.treeInsert(makeNode(seq[4]), false);
            n = (Node) w.getNext();
            assertEquals("Ascending walk cannot be tricked ("+seqStr+")",
                         n.getObject(), seq[5]);
            
            // delete the one just returned, then
            // insert one with the same value + 1 (should be used)
            tree.treeRemove(seq[5]);
            t = new TestVal(seq[5].val + 1);
            tree.treeInsert(makeNode(t), false);

            n = (Node) w.getNext();
            assertEquals("Ascending walk catches insertion after removal ("+seqStr+")",
                         n.getObject(), t);
        }
    }

    
    /**
     * Test that the tree Walk instances can cope with concurrent
     * modification of the tree.
     */
    public void testWalkingModificationDesc() {
        Enumeration seqe = sequences.keys();
        while (seqe.hasMoreElements()) {
            String seqStr = (String) seqe.nextElement();

            TestVal[] seq = getTestValSequence(seqStr, false);
            BinaryTree tree = makeTree(seq);
      
            Walk w;
            Node n;
            TestVal t;
            
            // start with a descending walk
            w = tree.treeWalk(false);
            n = (Node) w.getNext();
            assertEquals("Descending walk started correctly ("+seqStr+")",
                         n.getObject(), seq[0]);

            // remove just-returned node, shouldn't affect the walk
            tree.treeRemove(n);
            n = (Node) w.getNext();
            assertEquals("Descending walk handles last-removal ("+seqStr+")",
                         n.getObject(), seq[1]);

            // remove next node in sequence, should get skipped by walk
            tree.treeRemove(seq[2]);
            n = (Node) w.getNext();
            assertEquals("Descending walk handles next-removal ("+seqStr+")",
                         n.getObject(), seq[3]);

            // insert a new node prior to the next one
            t = new TestVal(seq[4].val + 1);
            tree.treeInsert(makeNode(t), false);
            
            n = (Node) w.getNext();
            assertEquals("Descending walk catches insertion ("+seqStr+")",
                         n.getObject(), t);
            n = (Node) w.getNext();
            assertEquals("Descending walk continues correctly ("+seqStr+")",
                         n.getObject(), seq[4]);

            // delete the one just returned, then
            // insert one with the same value (should be skipped)
            tree.treeRemove(seq[4]);
            tree.treeInsert(makeNode(seq[4]), false);
            n = (Node) w.getNext();
            assertEquals("Descending walk cannot be tricked ("+seqStr+")",
                         n.getObject(), seq[5]);
            
            // delete the one just returned, then
            // insert one with the same value - 1 (should be used)
            tree.treeRemove(seq[5]);
            t = new TestVal(seq[5].val - 1);
            tree.treeInsert(makeNode(t), false);

            n = (Node) w.getNext();
            assertEquals("Descending walk catches insertion after removal ("+seqStr+")",
                         n.getObject(), t);
        }
    }
}



