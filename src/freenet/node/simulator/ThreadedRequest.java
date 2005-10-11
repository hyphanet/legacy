package freenet.node.simulator;
import java.util.Stack;

import freenet.Key;
import freenet.node.rt.KeyspaceEstimator;

public class ThreadedRequest {
	int requestId;
	Key key;
	int sourceNode;
	int dataSourceNode;
	int dataSourceNodeDistance;
	int nextHop;
	int htl;
	boolean success;
	Stack route;
	KeyspaceEstimator sourceE;
	KeyspaceEstimator sourceEpDNF;
	KeyspaceEstimator sourceEtSuccess;
	KeyspaceEstimator sourceEtFailure;
	KeyspaceEstimator dataSourceE;
	KeyspaceEstimator dataSourceEpDNF;
	KeyspaceEstimator dataSourceEtSuccess;
	KeyspaceEstimator dataSourceEtFailure;

	public ThreadedRequest(int id, int source, Key requestKey, int requestHtl)
	{
		requestId = id;
		sourceNode = source;
		nextHop = source;
		key = requestKey;
		htl = requestHtl;

		route = new Stack();
		route.push(new Hop(source, requestHtl));

		sourceE = null;
		sourceEpDNF = null;
		sourceEtSuccess = null;
		sourceEtFailure = null;
		dataSourceE = null;
		dataSourceEpDNF = null;
		dataSourceEtSuccess = null;
		dataSourceEtFailure = null;
	}

	public boolean isRequest()
	{
		return !success;
	}

	public Hop getHopData()
	{
		return (Hop) route.pop();
	}

	public boolean NewHop(int id, int htl)
	{
		route.push(new Hop(id, htl));
		return true;
	}

	public int getNextHop()
	{
		return nextHop;
	}
}

class Hop
{
	public int nodeId;
	public int htl;

	public Hop (int hopNode, int hopHtl)
	{
		nodeId = hopNode;
		htl = hopHtl;
	}
}
