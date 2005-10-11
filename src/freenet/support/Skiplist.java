package freenet.support;

import java.util.Random;

/**
 * This an implementation of the BinaryTree interface backed by a SkipList.
 * Of course, a SkipList is not a binary tree at all, it just happens to
 * meet all the criteria of a balanced tree (and yet is 100 times easier
 * to deal with). 
 *
 * The only issue with the interface is that the Nodes in the the skiplist
 * do not fit at all with the interface for BinaryTree.Node, so nodes 
 * used with implementations MUST subclass SkipNode (below).
 *
 * @author oskar
 */

public class Skiplist implements BinaryTree {

    /**
     * An implementation of BinaryTree.Node. Note that Skiplist can 
     * only use Nodes of this type, not any other implementation of
     * BinaryTree.Node (yeah, I know that is stupid, but it's a forcefit.)
     *
     */
    public static abstract class SkipNode implements BinaryTree.Node {
        protected SkipNode[] skips;
        protected SkipNode prev;
        protected Comparable key;

        protected boolean used = false;

        private void init(int size) {
            if (used)
                throw new RuntimeException("Attempted to insert SkipNode in " +
                                           "two lists");
            else
                used = true;
            key = getObject();
            skips = new SkipNode[size];
        }

        private void removed() {
            used = false;
        }

        /**
         * @return  a Comparable instance to be used as the
         *          value of this node
         */
        public abstract Comparable getObject();

        /**
         * These do nothing.
         */
        public boolean hasParent() { croak(); return false;}
        public BinaryTree.Node getParent() { croak(); return null; } 
        public void setParent(BinaryTree.Node n) { croak(); }

        public boolean hasLeftChild() { croak(); return false; }
        public BinaryTree.Node getLeftChild() { croak(); return null; }
        public void setLeftChild(BinaryTree.Node n) { croak(); }
        
        public boolean hasRightChild() { croak(); return false; }
        public BinaryTree.Node getRightChild() { croak(); return null; }
        public void setRightChild(BinaryTree.Node n) { croak(); }

        private static void croak() {
            throw new RuntimeException("Tried to call one of the tree node " +
                                       "methods on a Skiplist node.");
        }
    }

    /**
     * An implementation where the Comparable is given in the constructor.
     */
    public static class SkipNodeImpl extends SkipNode {
        public Comparable comp;

        public SkipNodeImpl(Comparable comp) {
            this.comp = comp;
        }

        public Comparable getObject() {
            return comp;
        }

        public String toString() {
            return comp.toString();
        }
    }

    private class HeaderNode extends SkipNode {
        public HeaderNode() {
            skips = new SkipNode[1];
            used = true;
        }

        public Comparable getObject() {
            throw new Error("Skiplist broken, called getObject on Header!");
        }
    }

    private Random r;

    private final int maxlevel;

    private SkipNode header;
    private SkipNode[] update;

    public Skiplist(int maxlevel) {
        this(maxlevel, new Random());
    }

    public Skiplist(int maxlevel, Random r) {
        this.maxlevel = maxlevel;
        header = new HeaderNode(); 
        update = new SkipNode[1];
        this.r = r;
    }

    public BinaryTree.Node treeSearch(Comparable key) {
        return find(key);
    }

    public BinaryTree.Node treeInsert(BinaryTree.Node contents, boolean replace) {

        SkipNode newNode = (SkipNode) contents;
        Comparable key = contents.getObject();

        SkipNode found = find(key);
        if (found != null) {
            if (replace) {
                newNode.prev = found.prev;
                newNode.skips = found.skips;
                newNode.key = key;
                for (int i = newNode.skips.length - 1 ; i >= 0 ; i--) {
                    update[i].skips[i] = newNode;
                }
            }
            return found; 
        } else {
            newNode.init(geometricRand());
            
            int oldHeaderSize = header.skips.length;
            if (newNode.skips.length > oldHeaderSize) {
                SkipNode[] newskips = new SkipNode[newNode.skips.length];
                System.arraycopy(header.skips, 0, newskips, 0, 
                                 oldHeaderSize);
                header.skips = newskips;
                update = new SkipNode[newskips.length];
                find(key);
            }

            newNode.prev = update[0];
            if (update[0].skips[0] != null)
                update[0].skips[0].prev = newNode;

            for (int i = newNode.skips.length - 1 ; i >= 0 ; i--) {
                newNode.skips[i] = update[i].skips[i];
                update[i].skips[i] = newNode;
            }

            // if we extended the header, fill now 
            for (int i = oldHeaderSize ; i < header.skips.length ;
                 i++) {
                header.skips[i] = newNode;
            }

//              System.err.println("header: ");
//              for (int i = 0 ; i < header.skips.length ; i++) {
//                  System.err.println("  " + i + "  " + header.skips[i]);
//              }

//              System.err.println("newNode: " + newNode);
//              for (int i = 0 ; i < newNode.skips.length ; i++) {
//                  System.err.println("  " + i + "  " + newNode.skips[i]);
//              }

            return null;
        }
    }

    public BinaryTree.Node treeRemove(Comparable key) {
        SkipNode found = find(key);
        if (found == null)
            return null;
        else {
            doRemove(found);
            return found;
        }
    }

    public boolean treeRemove(BinaryTree.Node contents) {
        //System.err.println("REMOVING: " + contents);
        //errPrint();
        SkipNode found = find(contents.getObject());
        //System.err.println("FF: " + found);
        if (found == null || found != contents) {
            return false;
        } else {
            doRemove(found);
            return true;
        }
    }

    /*
     * Does the actual removing (expects the update array to be correct).
     */
    private void doRemove(SkipNode found) {

        int dec = 0;
        if (found.skips[0] != null)
            found.skips[0].prev = update[0];
        for (int i = found.skips.length - 1 ; i >= 0 ; i--) {
            if (found.skips[i] == null && update[i] == header &&
                dec < (header.skips.length - 1)) // allways keep 1
                dec++;
            else
                update[i].skips[i] = found.skips[i];
        }

        SkipNode[] newSkips = new SkipNode[header.skips.length - dec];
        System.arraycopy(header.skips, 0, newSkips, 0, newSkips.length);
        header.skips = newSkips;

        found.removed();
    }

    public BinaryTree.Node treeMatch(Comparable searchKey, int cmpSense) {
        SkipNode n = find(searchKey);
        if (n != null) {
            return n;
        } else if (cmpSense <= 0) {
            return update[0] == header ? header.skips[0] : update[0];
        } else {
            return (update[0].skips[0] == null ? 
                    (update[0] == header ? null : update[0]) : 
                    update[0].skips[0]);
                    

        }
    }

    public BinaryTree.Node treeMin() {
        return header.skips[0];
    }

    public BinaryTree.Node treeMinConstrained(Comparable key, boolean inclusive) {
        SkipNode found = find(key);
        return (found != null && inclusive ? found : 
                found != null ? found.skips[0] :
                update[0].skips[0]);
    }

    public BinaryTree.Node treeMax() {
        return last();
    }

    public BinaryTree.Node treeMaxConstrained(Comparable key, boolean inclusive) {
        SkipNode found = find(key);
        return (found != null && inclusive ? found :
                update[0] != header ? update[0] : null);
    }

    public BinaryTree.Node treeSuccessor(BinaryTree.Node n) {
        return ((SkipNode) n).skips[0];
    }

    public BinaryTree.Node treePredecessor(BinaryTree.Node n) {
        SkipNode prev = ((SkipNode) n).prev;
        return prev == header ? null : prev;
    }

    public Walk treeWalk(boolean ascending) {
        return (ascending ?
                new NodeWalker(true, true) :
                new NodeWalker(true, false));
    }

    public Walk treeWalk(BinaryTree.Node node, boolean ascending) {
        SkipNode start = (SkipNode) node;
        return new NodeWalker(start, ascending);
    }

    public Walk treeWalk(Comparable key, boolean inclusive, boolean ascending){
        return new NodeWalker(key, inclusive, ascending);
    }

    public void errPrint() {
        for (SkipNode current = header.skips[0] ; current != null ; 
             current = current.skips[0]) {
            System.err.println(current);
        }
    }
    
    static long findCalled = 0;
    public static long findCalled() { return findCalled; }
    /*
     * The update array is set a sideeffect
     */
    private SkipNode find(Object key) {
	findCalled++;
        //System.err.println("Find: " + key);
        SkipNode current = header;
        for (int i = current.skips.length - 1 ; i >= 0 ; i--) {
            while (current.skips[i] != null && 
                   current.skips[i].key.compareTo(key) < 0) {
                current = current.skips[i];
                //System.err.println(i + " " + key + "  " + current.key + 
                //                   "  " + current.skips[i].key);
            }
            update[i] = current;
        }

        //System.err.println("Current: " + current);
        //System.err.println("Current.skips[0]:  " + current.skips[0]);
        //if (current.skips[0] != null)
        //    System.err.println("current.skips[0].key: " + current.skips[0].key);
        //System.err.println("key: " + key);
        return (current.skips[0] != null && 
                current.skips[0].key.compareTo(key) == 0 ? 
                current.skips[0] : null);
    }

    private SkipNode last() {
        SkipNode last = header;
        for (int i = last.skips.length - 1 ; i >= 0 ; i--) {
            while (last.skips[i] != null)
                last = last.skips[i];
        }
        return (last == header ? null : last);
    }

    private int geometricRand() {
        int rb = r.nextInt();
        for (int i = 1 ; i <= maxlevel ; i++) {
            if ((rb & 1) == 1)
                return i;
            rb = rb >> 1;
        }

        return maxlevel;
    }

    private class NodeWalker implements Walk {

        private SkipNode current;
        private Comparable currComp;
        private boolean inclusive;

        private boolean forwards;
        private boolean beginAtEnd;

        public NodeWalker(SkipNode current, boolean forwards) {
            this.current = current;
            this.currComp = current.getObject();
            inclusive = false;
            this.forwards = forwards;
            this.beginAtEnd = false;
        }

        public NodeWalker(Comparable currComp, boolean inclusive, 
                          boolean forwards) {
            this.current = null;
            this.currComp = currComp;
            this.inclusive = inclusive;
            this.forwards = forwards;
            this.beginAtEnd = false;
        }

        public NodeWalker(boolean inclusive, boolean forwards) {
            this.inclusive = inclusive;
            this.forwards = forwards;
            this.beginAtEnd = true;
        }

        public Object getNext() {
            if (!beginAtEnd && currComp == null)
                return null;

            if (current != null && current.used) {
                current = (forwards ?
                           current.skips[0] :
                           current.prev != header ?
                           current.prev : null);
            } else { // removed
                if (beginAtEnd) {
                    current = (forwards ? header.skips[0] : last());
                    beginAtEnd = false;
                } else {
                    SkipNode next = find(currComp);
                    current = (forwards && next != null && !inclusive ?
                               next.skips[0] :       // skipping forward
                               forwards && next != null ? 
                               next :                // inclusive forward
                               forwards ?
                               update[0].skips[0] :  // normal forward
                               next != null && inclusive ?
                               next :                // inclusive backwards
                               update[0] != header ? 
                               update[0] :           // normal backwards
                               null);                // end backwards
                }
            }

            currComp = current == null ? null : current.getObject();
            inclusive = false;
            return current;
        }
    }
}






