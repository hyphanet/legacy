package freenet.node.simulator;

import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

public class ThreadedSimulator {

	static SimThread simThreads[] = new SimThread[ 4 ];

	public static void main( String args[] )
	{
		for(int i = 0; i < simThreads.length; i++)
		{
			simThreads[i] = new SimThread((100 * i), (100 * (i + 1)) - 1);
		}
	}

	public static SimThread getThread(Integer val)
	{
		for(int i = 0; i < simThreads.length; i++)
		{
			if(simThreads[i].isLocalNode(val))
			{
				return simThreads[i];
			}
		}
		return null;
	}

}

class SimThread extends Thread 
{
	private boolean running;
	private Map nodes;
	private TreeMap waitingRequests;
	private LinkedList waitingConnections;
	private int lowestNode;
	private int highestNode;

	public HoldObjectSynchronized communicationQueue;

	public SimThread(int min_in, int max_in)
	{
		lowestNode = min_in;
		highestNode = max_in;
		running = false;
		waitingRequests = new TreeMap();
		waitingConnections = new LinkedList();
	}

	public void run()
	{
		running = true;
		Object communication;
		boolean newCommunication = false;
		try
		{
			while(running)
			{
				if(newCommunication || interrupted())
				{
					newCommunication = false;
					communication = communicationQueue.recieve();
					while (communication != null)
					{
						if(communication instanceof ThreadedRequest)
						{
							Object o = waitingRequests.put(new Integer(((ThreadedRequest)communication).getNextHop()), communication);
							if ( o != null )
							{
								throw (new Exception("Request ID already on stack"));
							}
						}
						else if (communication instanceof ThreadedConnectionRequest)
						{
							if(!waitingConnections.add(communication))
							{
								throw (new Exception("Unknown Error Queuing Connection Request"));
							}
						}
						else
						{
							throw(new Exception("Unknown Communication"));
						}
						communication = communicationQueue.recieve();
					};
				}
				if(!waitingConnections.isEmpty())
				{
					processWaitingConnections();
				}
				else if(!waitingRequests.isEmpty())
				{
					processWaitingRequest();
				}
				else
				{
					try {
						wait();
					}
					catch ( InterruptedException e )
					{
						newCommunication = true;
					}
				}
			}
		}
		catch (Exception e)
		{
			System.err.println("thread " + lowestNode + ", " + highestNode + " Failed");
			running = false;
		}
	}
	
	private boolean processWaitingRequest() throws Exception 
	{
		ThreadedRequest r = (ThreadedRequest) waitingRequests.remove((Integer)waitingRequests.firstKey());
		ThreadedNode n = (ThreadedNode) nodes.get(new Integer(r.getNextHop()));
		if(n.process(r))
		{
			Integer destination = new Integer(r.getNextHop());
			if(isLocalNode(destination))
			{
				Object o = waitingRequests.put(destination, r);
				if ( o != null )
				{
					throw (new Exception("Request ID already on stack"));
				}
			}
			else
			{
				if(!sendToThread(destination, r))
					throw (new Exception("Error routing request"));
			}
		}
		else
		{
			// something goes here, can't remember what....
		}
		return false;
	}
	
	private boolean processWaitingConnections() throws Exception
	{
		Object o = waitingConnections.removeFirst();
		while((o != null) && (o instanceof ThreadedConnectionRequest) )
		{
			ThreadedConnectionRequest c = (ThreadedConnectionRequest) o;
			Integer to = new Integer(c.sentTo());
			if(isLocalNode(to))
			{
				ThreadedNode n = (ThreadedNode) nodes.get(to);
				n.connectTo(c);
			}
			else
			{
				if(!sendToThread(to, c))
					throw (new Exception("Error routing request"));
			}
			o = waitingConnections.removeFirst();
		}
		return false;
	}
	
	public boolean isLocalNode(Integer val)
	{
		return (val.intValue() >= lowestNode && val.intValue() <= highestNode);
	}
	
	public boolean sendToThread(Integer n, Object r)
	{
		ThreadedSimulator.getThread(n).communicationQueue.send(r);
		return true;
	}
	
}