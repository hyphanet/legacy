/*
 * Created on Jan 28, 2004
 *  
 */
package freenet.support;

import java.util.Hashtable;

/**
 * LesserLockContentionHashTable implements a subset of the Map interface (get(), put() and remove() only). Since LesserLockContentionHashTable distributing objects between different sub-HashTables lesser lock contention will result. Useful in a situation where many threads will access a single
 * HashTable (and where the size(), clear() etc. methods isn't required)
 */

public class LesserLockContentionHashTable {
	private Hashtable[] pool;

	public LesserLockContentionHashTable(int poolSize, int expectedMapElements) {
		if (poolSize>expectedMapElements)
			throw new IllegalArgumentException("Number of pool items cannot be less than expected number of elements");
			pool = new Hashtable[poolSize];
		for (int i = 0; i < poolSize; i++)
			pool[i] = new Hashtable(expectedMapElements / poolSize);
	}

	private final int preHash(Object key) {
		int hash = key.hashCode() % pool.length;
		if (hash < 0)
			hash *= -1;
		return hash;
	}

	public Object put(Object key, Object value) {
		return pool[preHash(key)].put(key, value);
	}

	public Object get(Object key) {
		return pool[preHash(key)].get(key);
	}

	public Object remove(Object key) {
		return pool[preHash(key)].remove(key);
	}
	
	//If there is a need to externally synchronize access to a
	//part of the map that handles a specific key the needed
	//lockObject can be retrieved using this method
	public Object getLockFor(Object key) {
		return pool[preHash(key)];
	}
	
	//Returns the number of elements in the Table.
	//NOTE: the count is not produced in an atomic fashion
	public int size(){
		int retval = 0;
		for (int i = 0; i < pool.length; i++)
			retval += pool[i].size();
		return retval;
	}
	
	//Returns a snapshot of all elements present in the table
	//NOTE: the snapshot is not produced in an atomic fashion
	public Hashtable snapshot(){
		Hashtable retval = new Hashtable();
		for (int i = 0; i < pool.length; i++)
			retval.putAll((Hashtable)pool[i].clone());
		return retval;
	}
}
