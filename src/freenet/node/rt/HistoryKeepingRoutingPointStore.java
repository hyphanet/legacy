/*
 * Created on Nov 3, 2003
 *  
 */
package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.LinkedList;

import freenet.FieldSet;
import freenet.Key;
import freenet.support.Unit;

/**
 * @author Iakin
 *  
 */
public class HistoryKeepingRoutingPointStore extends RoutingPointStore {

	LinkedList history = new LinkedList();
	boolean initialized = false;
	final int MAX_HISTORY_LENGTH = 20;

	public Object clone() {
	    return new HistoryKeepingRoutingPointStore(this);
	}
	
	public HistoryKeepingRoutingPointStore(
		FieldSet set, Unit type, int accuracy)
		throws EstimatorFormatException {
		super(set, type, accuracy);
	}

	public HistoryKeepingRoutingPointStore(
		DataInputStream i,
		double maxAllowedTime,
		double minAllowedTime)
		throws IOException {
		super(i, maxAllowedTime, minAllowedTime);
		initialized = true;
	}
	public HistoryKeepingRoutingPointStore(int accuracy, double initTime) {
		super(accuracy, initTime);
		initialized = true;
	}
	public HistoryKeepingRoutingPointStore(
		Key k,
		double high,
		double low,
		int accuracy) {
		super(k, high, low, accuracy);
		initialized = true;
	}
    public HistoryKeepingRoutingPointStore(HistoryKeepingRoutingPointStore store) {
        super(store);
        this.history = (LinkedList) store.history.clone();
        this.initialized = store.initialized;
    }

	//Returns the length of the history
	public int historySize() {
		return history.size();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see freenet.node.rt.RoutingPointStore#notifyModified(int,
	 *      freenet.node.rt.RoutingPointStore.RoutingPoint)
	 */
	protected void notifyPrePointModified(int index) {
		if (initialized)
			snapshot();
		super.notifyPrePointModified(index);
	}
	//Takes a snapshot of the current 'points' and returns the actual
	// resulting historic data
	private synchronized RoutingPoint[] snapshot() {
		if (history.size() > MAX_HISTORY_LENGTH)
			history.removeFirst();
		RoutingPoint[] c = new RoutingPoint[points.length];
		history.addLast(c);
		for (int i = 0; i < c.length; i++) { //Detach from original
			c[i] = (RoutingPoint) points[i].clone();
		}
		return c;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see freenet.node.rt.RoutingPointStore#notifyStructureModified()
	 */
	protected void notifyStructureModified() {
		if (initialized)
			snapshot();
		super.notifyStructureModified();
	}

	synchronized NeighbourRoutingPointPair findKeyBeforeAndAfter(
		BigInteger n,
		int age) {
		if (age == 0)
			return super.findKeyBeforeAndAfter(n);
		RoutingPoint[] p = (RoutingPoint[]) history.get(history.size() - age);
		return RoutingPointStore.findKeyBeforeAndAfter(n, p);
	}

}
