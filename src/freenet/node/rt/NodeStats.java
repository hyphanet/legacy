package freenet.node.rt;

/**
 * Interface for the stats that are passed when creating a new node.
 * Used by NodeEstimatorFactory implementations to configure initial values.
 * @author amphibian
 */
public interface NodeStats {
	// Reset to default values (usually completely insane values - don't use it until you've added some values)
	void reset();
	
	void register(NodeEstimator ne); 

	// Returns the minimum probability of a DNF
	double minPDNF();

	/**
	 * Report some stats to Core.diagnostics vars
	 */
	void reportStatsToDiagnostics();
}
