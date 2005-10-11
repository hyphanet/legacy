/*
 * Created on May 27, 2004
 *
 */
package freenet.support;

import java.util.Hashtable;

/**
 * @author Iakin
 * A version of the LesserLockContentionHashTable that provides improved performance for
 * the snapshot() and toArray() methods.
 */
public class SnapshotCachingLesserLockContentionHashTable extends LesserLockContentionHashTable {
	private final Object lock = new Object();
	private Object[] arraySnapshot;
	private Hashtable snapshot;
	
	public SnapshotCachingLesserLockContentionHashTable(int poolSize, int expectedMapElements) {
		super(poolSize, expectedMapElements);
	}
	
	public Hashtable snapshot() {
		synchronized(lock)
		{
			if(snapshot != null)
				return snapshot;
			// Use h to avoid needing to lock in dirtied()
			// This means snapshot() return value may not
			// be entirely up to date, just like it's parent
			Hashtable h = super.snapshot();
			snapshot = h;
			return h;
		}
	}
	public Object[] toArray() {
		synchronized(lock)
		{
			if(arraySnapshot != null)
				return arraySnapshot;
			Hashtable h = snapshot();
			Object[] a = h.values().toArray();
			arraySnapshot = a;
			return a;
		}
	}
	private void dirtied()
	{
		// No synchronization to avoid deadlocks
		snapshot = null;
		arraySnapshot = null;
	}
	public Object put(Object key, Object value) {
		Object o = super.put(key, value);
		dirtied();
		return o;
	}
	public Object remove(Object key) {
		Object o = super.remove(key); 
		dirtied();
		return o;
	}
}
