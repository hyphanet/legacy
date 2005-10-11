package freenet.node.simulator.newsim;

import java.util.Random;

import freenet.Key;
import freenet.node.rt.KeyspaceEstimatorFactory;
import freenet.support.DoublyLinkedListImpl;

/**
 * A single route in the routing table.
 * Also an element on the main connection LRU list.
 */
public class Route extends DoublyLinkedListImpl.Item {

    final Node connectedTo;
    final Node connectedFrom;
    /** Random number used when choosing between two nodes with identical 
     * estimates. If we do not use this, then all nodes randomly choose the same
     * node and overload it, although that will in the short term help success. 
     */
    int tiebreaker;
    /** The ID of the last request to be routed through this route. Used to
     * avoid routing the same request to the same node twice.
     */
    int lastID = -1;
    final RouteEstimator re;
    /** The total number of hits this node has had i.e. the number of times this
     * node has had its routing data changed as the result of a request reaching
     * completion one way or another. */
    int totalHits = 0;
    /** Are we on the "experienced" list? */
    boolean onExperiencedList;
    
    Route(Connections from, Node to, Random r, KeyspaceEstimatorFactory kef, int initTimeVal, int bias) {
        connectedTo = to;
        connectedFrom = from.node;
        tiebreaker = r.nextInt();
        // What kind of RouteEstimator do we want?
        int type = to.sim.myConfig.estimatorClass;
        switch(type) {
        	case SimConfig.CLASS_ALL_THREE:
        	    re = new StandardRouteEstimator(kef, initTimeVal);
        		break;
        	case SimConfig.CLASS_PROBABILITY_ONLY:
        	    re = new ProbabilityOnlyRouteEstimator(kef);
        		break;
        	case SimConfig.CLASS_TSUCCESS_FIXED_PENALTY:
        	    re = new TSuccessFixedPenaltyRouteEstimator(kef, initTimeVal, bias);
        		break;
        	default:
        	    throw new IllegalArgumentException("Unknown estimator class "+type);
        }
    }

    /**
     * @param k
     * @return
     */
    public double estimate(Key k) {
        return re.estimate(k);
    }

    /**
     * Succeeded!
     * @param time The time, in hops, taken to get the data.
     */
    public void succeeded(Key k, long time) {
        re.succeeded(k, time);
        hit();
    }

    /**
     * Failed.
     * @param hops The time, in hops, taken to get the DNF.
     */
    public void failed(Key k, long time) {
        re.failed(k, time);
        hit();
    }

    /**
     * Called every time the routing data changes.
     */
    private void hit() {
        totalHits++;
        // Switch lists if necessary
        connectedFrom.conns.checkList(this);
    }

    public double totalHits() {
        return totalHits;
    }

    /**
     * @return
     */
    public boolean isExperienced() {
        return totalHits >= connectedFrom.sim.myConfig.experienceHits;
    }
}
