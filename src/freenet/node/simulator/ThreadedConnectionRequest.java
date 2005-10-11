package freenet.node.simulator;
import freenet.Key;
import freenet.node.rt.KeyspaceEstimator;

public class ThreadedConnectionRequest {
	int requestId;
	int sourceNode;
	int dataSourceNode;
	boolean accept;
	boolean disconnect;
	Key announceKey;
	boolean success;
	KeyspaceEstimator sourceE;
	KeyspaceEstimator sourceEpDNF;
	KeyspaceEstimator sourceEtSuccess;
	KeyspaceEstimator sourceEtFailure;
	
	public ThreadedConnectionRequest(int id, int source, int destination, Key key, boolean disconnect)
	{
		requestId = id;
		sourceNode = source;
		dataSourceNode = destination;
		this.accept = false;
		this.disconnect = disconnect;
		announceKey = key;
	}

	public void accept()
	{
		accept = true;
	}

	public boolean isRequest()
	{
		return !accept;
	}

	public boolean isDisconnect()
	{
		return disconnect;
	}

	public int sentTo()
	{
		if(accept)
		{
			return sourceNode;
		}
		else
		{
			return dataSourceNode;
		}
	}
}
