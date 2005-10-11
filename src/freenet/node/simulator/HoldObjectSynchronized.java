package freenet.node.simulator;

public class HoldObjectSynchronized {
	private Object requests[];
	private int size;
	private boolean writeable = true;
	private boolean readable = false;
	private int readLoc = 0, writeLoc = 0;
	private Thread owner;

	public HoldObjectSynchronized( int runningThreads, Thread parent )
	{
		size = 4 * runningThreads + 1;
		requests = new Object[ size ];

		this.owner = parent;
	}

	public synchronized void send( Object val )
	{
		while ( !writeable ) {
			try {
				wait();
			}
			catch ( InterruptedException e ) {
				System.err.println( e.toString() );
			}
		}

		requests[ writeLoc ] = val;
		readable = true;

		writeLoc = ( writeLoc + 1 ) % size;

		if ( writeLoc == readLoc ) {
			writeable = false;
		}

		this.owner.interrupt();
	}

	public synchronized Object recieve()
	{
		Object val;

		if ( !readable ) {
			return null;
		}

		writeable = true;
		val = requests[ readLoc ];

		readLoc = ( readLoc + 1 ) % size;

		if ( readLoc == writeLoc ) {
			readable = false;
		}

		notify();
		return val;
	}
	
}

