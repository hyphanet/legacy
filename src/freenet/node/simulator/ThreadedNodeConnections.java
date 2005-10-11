package freenet.node.simulator;

public class ThreadedNodeConnections {
	int conections;
	int maxConnections;

	public ThreadedNodeConnections (int limit)
	{
		maxConnections = limit;
		conections = 0;
	}
}
