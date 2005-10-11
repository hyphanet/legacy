/*
 * Created on Mar 25, 2004
 */
package freenet.node.rt;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import freenet.Core;
import freenet.Identity;
import freenet.support.Logger;

/**
 * @author Iakin
 *
 * A combination of a HashTable and array
 * Caches the array in a copy-on-read fashion
 */

class NodeEstimatorStore {
	final Hashtable estimators = new Hashtable();
	private NodeEstimator[] cachedEstimatorArray;

	//Adds the supplied estimator to the store
	public void put(NodeEstimator est) {
		if(Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(this, "put("+est+") on "+this,
					Logger.DEBUG);
		synchronized(estimators){
			estimators.put(est.id, est);
			cachedEstimatorArray = null;
		}
	}
	
	//Returns the indicated estimator from the store
	public NodeEstimator get(Identity id) {
		return (NodeEstimator) estimators.get(id);
	}
	
	//Removes the indicated estimator from the store
	public void remove(Identity id) {
		synchronized(estimators){
			estimators.remove(id);
			cachedEstimatorArray = null;
		}
	}
	
	//Returns the size of the store
	public int size(){
		return estimators.size();
	}

	//Returns an array of all estimators in the store
	//do _not_ modify the shallow contents of the array since
	//it might be used by other code concurrently
	//When java supports the const keyword properly
	//One wouldn't have to write comments like this
	public /*const*/ NodeEstimator[] toArray() {
		synchronized(estimators){
			if(cachedEstimatorArray == null)
				generateArray();
			return cachedEstimatorArray;
		}
		
	}
	//Regenerates the cached array
	private void generateArray() {
		NodeEstimator[] ea;
		Object[] v2;
		synchronized (estimators) {
			Vector v = new Vector(estimators.size());
			Enumeration e = estimators.elements();
			while (e.hasMoreElements()) {
				v.add(e.nextElement());
			}
			v2 = v.toArray();
		}
		ea = new NodeEstimator[v2.length];
		System.arraycopy(v2, 0, ea, 0, v2.length);
		cachedEstimatorArray = ea;
	}
}