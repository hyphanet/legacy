package freenet.node.rt;

import freenet.FieldSet;
import freenet.node.NodeReference;

/**
 * Tiny class for NodeReference:FieldSet pairs 
 * @author amphibian
 */
public class NodeReferenceEstimatorPair {
	public final NodeReference ref;
	final FieldSet estimator;
	public NodeReferenceEstimatorPair(NodeReference ref, FieldSet estimator) {
		this.ref = ref;
		this.estimator = estimator;
	}
}
