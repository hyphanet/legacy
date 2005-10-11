package freenet.node.simulator;

import java.util.LinkedHashMap;
import java.util.Map;

public class ThreadedLRUQueue extends LinkedHashMap {
	int LRUQueueSize;

	public ThreadedLRUQueue (int size)
	{
		super(size+1, .75F, true);
		LRUQueueSize = size;
	}

	// This method is called just after a new entry has been added
	public boolean removeEldestEntry(Map.Entry eldest) {
		return size() > LRUQueueSize - 1;
	}
}
