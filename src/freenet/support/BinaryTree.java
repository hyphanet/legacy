package freenet.support;

/**
 * A BinaryTree implementation embodies the algorithms for dealing
 * with an ordered set of objects but does not have to deal with
 * mapping keys to values, unlike what you find in 1.2+ java.util.
 *
 * The node object itself is defined by an interface and must be
 * provided by the application.  Tree implementations should provide
 * an optional rudimentary implementation of the node object.
 * 
 * BinaryTrees do not tolerate degenerate objects.  Objects that
 * compare equal will collide in the tree.  If degenerate objects
 * must be stored in the tree, the compareTo() method of the nodes
 * must be redefined to eliminate the degeneracy.
 * 
 * @author tavin
 */
public interface BinaryTree {

    /**
     * Inserts a node into the tree.  If there is a collision,
     * it can either abort or replace the previous node.
     * 
     * @param n  the new node to insert in the tree
     * @param replace  true to replace a comparatively equal node
     * @return  null if there was no collision,
     *          otherwise the node that it collided with
     */
    Node treeInsert(Node n, boolean replace);

    /**
     * Removes a node from the tree, if it is really in a tree.
     * Note that it is probably not possible for the implementation
     * instance to distinguish its own nodes from those belonging
     * to another tree that is also an instance of the same
     * implementation.
     * @return  true, unless the node was not in a tree
     *          (and therefore not removed)
     */
    boolean treeRemove(Node n);

    /**
     * First attempts to find a node matching the search key.  If
     * found, removes it and returns it.
     * @param searchKey  the Comparable value to remove
     * @return  the deleted node, or null if nothing was deleted
     */
    Node treeRemove(Comparable searchKey);

    /**
     * Attempts to find a node matching the search key.
     * @param searchKey  the Comparable value to find
     * @return  the node if found, or null if there was no match
     */
    Node treeSearch(Comparable searchKey);

    /**
     * Similar to treeSearch() in that it looks for a node matching
     * the given search key.  However if an exact match is not found,
     * it will settle for the nearest match on one side.  The side
     * may be specified or left to chance.
     * @param searchKey  the Comparable value to find
     * @param cmpSense   a number describing the preferred relationship
     *                   of the nearest match.  the value should be the
     *                   same sign as the desired value of the comparator
     *                   between the nearest match and the search key.
     *                   in other words if the nearest match should be
     *                   on the large side, a number greater than zero.
     *                   zero means no preference.
     * @return  the best match as described above,
     *          or null if the tree is empty
     */
    Node treeMatch(Comparable searchKey, int cmpSense);
    

    /**
     * @return  the smallest-valued node in the tree,
     *          or null if the tree is empty
     */
    Node treeMin();

    /**
     * @param searchKey  the bounding value
     * @param inclusive  whether to close the interval by
     *                   allowing an exact match of the search key
     * @return  the smallest-valued node in the tree
     *          that is in the interval defined by
     *          the search key and positive infinite,
     *          closed if inclusive == true
     */
    Node treeMinConstrained(Comparable searchKey, boolean inclusive);
    

    /**
     * @return  the largest-valued node in the tree,
     *          or null if the tree is empty
     */
    Node treeMax();

    /**
     * @param searchKey  the bounding value
     * @param inclusive  whether to close the interval by
     *                   allowing an exact match of the search key
     * @return  the largest-valued node in the tree
     *          that is in the interval defined by
     *          the search key and negative infinite,
     *          closed if inclusive == true
     */
    Node treeMaxConstrained(Comparable searchKey, boolean inclusive);


    /**
     * @return  the node with the next ascending value,
     *          or null if there isn't one
     */
    Node treeSuccessor(Node n);

    /**
     * @return  the node with the next descending value,
     *          or null if there isn't one
     */
    Node treePredecessor(Node n);

    
    /**
     * @param ascending  true to go in ascending order
     * @return  a Walk instance that returns all Node objects
     *          in the tree, in ascending or descending order
     */
    Walk treeWalk(boolean ascending);

    /**
     * @param node  the starting point for the Walk
     * @param ascending  true to go in ascending order
     * @return  a Walk instance that returns all Node objects
     *          in the tree that succeed/precede the given
     *          Node, but not including the given Node itself
     */
    Walk treeWalk(Node node, boolean ascending);
    
    /**
     * @param searchKey  the starting point for the Walk
     * @param inclusive  whether to allow including the search key
     * @param ascending  true to go in ascending order
     * @return  a Walk instance that returns all Node objects
     *          in the tree that succeed/precede the Node matching
     *          the search key, possibly including that node itself
     */
    Walk treeWalk(Comparable searchKey, boolean inclusive, boolean ascending);
    


    /**
     * Must be provided by the application.
     * The methods in this interface should be self-explanatory.
     *
     * Implementations of BinaryTree may require the nodes
     * to implement an interface that extends BinaryTree.Node.
     */
    public interface Node {

        /**
         * @return  a Comparable instance to be used as the
         *          value of this node
         */
        Comparable getObject();

        boolean hasParent();
        Node getParent();
        void setParent(Node n);

        boolean hasLeftChild();
        Node getLeftChild();
        void setLeftChild(Node n);
        
        boolean hasRightChild();
        Node getRightChild();
        void setRightChild(Node n);
    }
}


