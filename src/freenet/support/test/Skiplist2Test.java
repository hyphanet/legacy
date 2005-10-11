package freenet.support.test;

import freenet.support.BinaryTree;
import freenet.support.BinaryTree.Node;
import freenet.support.Skiplist;
import freenet.support.Skiplist.SkipNodeImpl;

public class Skiplist2Test extends TreeTester {

    public static final void main(String[] args) {
        if (args.length > 0) {
            int[] seq = new int[args.length];
            for (int i=0; i<args.length; ++i) {
                System.out.println(args[i]);
                seq[i] = Integer.parseInt(args[i]);
            }
            sequences.put("__ARGS", seq);
        }

        /*
        junit.framework.TestResult tr = new junit.framework.TestResult();
        tr.addListener(new SimpleTestRunner.SimpleTestListener());
        (new Skiplist2Test("testWalking")).run(tr);
        */
                
        SimpleTestRunner.main(
             new String[] { Skiplist2Test.class.getName() }
        );
        
    }

    
    public Skiplist2Test(String name) {
        super(name);
    }

    /**
     * Constructs a red-black tree filled with values from
     * the named sequence.
     */
    public BinaryTree makeTree() {
        return new Skiplist(32, rand);
    } 

    public Node makeNode(Comparable comp) {
        return new SkipNodeImpl(comp);
    }

    public void integrityCheck(String seqStr, Node node) {
    }


    public void testConstruction() {
        super.testConstruction();
    }


    public void testRemoval() {
        super.testRemoval();
    }

    public void testReplacing() {
        super.testReplacing();
    }

    public void testMatching() {
        super.testMatching();
    }

    public void testOrdering() {
        super.testOrdering();
    }

    public void testWalking() {
        super.testWalking();
    }

    public void testWalkingModificationAsc() {
        super.testWalkingModificationAsc();
    }

    public void testWalkingModificationDesc() {
        super.testWalkingModificationDesc();
    }
}
