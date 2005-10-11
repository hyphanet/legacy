package freenet.support;

import freenet.support.BinaryTree.Node;

/**
 * Returns nodes from the tree in order of increasing distance
 * from a starting point.
 */
public class MetricWalk implements Walk {

    protected final BinaryTree tree;
    protected final Metric metric;

    protected Node nlo = null,
                   nhi = null;
        
    protected Comparable searchKey;
    protected boolean inclusive;
        
    public MetricWalk(BinaryTree tree, Node node) {
        this(tree, node, ObjectMetric.instance);
    }

    public MetricWalk(BinaryTree tree, Node node, Metric metric) {
        this.tree = tree;
        nlo = nhi = node;
        searchKey = node.getObject();
        this.metric = metric;
    }

    public MetricWalk(BinaryTree tree, Comparable searchKey, boolean inclusive) {
        this(tree, searchKey, inclusive, ObjectMetric.instance);
    }
    
    public MetricWalk(BinaryTree tree, Comparable searchKey,
                      boolean inclusive, Metric metric) {
        this.tree = tree;
        this.searchKey = searchKey;
        this.inclusive = inclusive;
        this.metric = metric;
    }

    public MetricWalk(MetricWalk w) {
	this.tree = w.tree;
	this.searchKey = w.searchKey;
	this.inclusive = w.inclusive;
	this.metric = w.metric;
	this.nlo = w.nlo;
	this.nhi = w.nhi;
    }
    
    public Object clone() {
	return new MetricWalk(this);
    }
    
    /**
     * @return  the next closest node in terms of the metric.
     *          if both the low node and the high node are equidistant,
     *          the low node is returned.
     */
    public Object getNext() {
        
        Node nextLo = (nlo == null ? tree.treeMaxConstrained(searchKey, inclusive)
                                   : tree.treePredecessor(nlo));
        Node nextHi = (nhi == null ? tree.treeMinConstrained(searchKey, inclusive)
                                   : tree.treeSuccessor(nhi));

        if (nextLo == null && nextHi == null)
            return null;

        if (nextLo == null)
            return nhi = nextHi;

        if (nextHi == null)
            return nlo = nextLo;

        return metric.compareSorted(searchKey, nextLo.getObject(), nextHi.getObject()) > 0
               ? (nhi = nextHi) : (nlo = nextLo);
    }
    
    public String toString() {
	return "MetricWalk("+searchKey+")";
    }
}


