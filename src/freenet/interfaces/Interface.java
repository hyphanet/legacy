package freenet.interfaces;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Enumeration;
import java.util.Vector;

import freenet.Connection;
import freenet.Core;
import freenet.ListenException;
import freenet.Listener;
import freenet.ListeningAddress;
import freenet.Transport;
import freenet.support.Logger;

/**
 * An interface receives incoming connections from a Freenet peer
 * (or a client).  It contains a Transport layer ListeningAddress
 * to listen for connections, and a ConnectionRunner to dispatch
 * the accepted connections.
 *
 * <p>
 * If an exception throw occurs in the socket accept, a failure
 * counter is incremented and the thread sleeps for 2500ms before
 * trying again.  After 10 successive failures the interface
 * stops listening until explicitly reactivated with listen().
 * Restarting the interface resets the failure count to 0.
 * </p>
 *
 * @author oskar
 */
public abstract class Interface implements Runnable {

    /** address this interface listens on */
    private final ListeningAddress listenAddr;

    /** the thread that is accepting connections */
    private Thread executor;
    
    /** to decommission the interface completely */
    private boolean terminated = false;

    /** toggled to bring the interface up and down */
    private boolean listening = true;
    
    
    /** collects exception throws */
    private final Vector errors = new Vector();
    

    /**
     * @param listenAddr  The listening address this Interface should
     *                    operate on.
     */
    public Interface(ListeningAddress listenAddr) {
        this.listenAddr = listenAddr;
    }

    /**
     * @return "Interface # <port>"
     */
    public String toString() {
        return "Interface # "+listenAddr;
    }


    /**
     * @return  the Transport of the listening address
     */
    public final Transport transport() {
        return listenAddr.getTransport();
    }

    /**
     * @return  the ListeningAddress this was constructed with
     */
    public final ListeningAddress listeningAddress() {
        return listenAddr;
    }
    

    /**
     * @return  the number of successive socket accept failures
     */
    public final int getExceptionCount() {
        return errors.size();
    }
    
    /**
     * @return  an enumeration of the collected exception throws
     */
    public final Enumeration getExceptions() {
        return errors.elements();
    }
    
    
    /**
     * @return  true, if the interface is accepting connections
     */
    public final boolean isListening() {
        return listening;
    }

    /**
     * @return  true, if the interface has been stopped
     */
    public final boolean isTerminated() {
        return terminated;
    }

    boolean wasStopped = false;
    
    /**
     * Bring the interface up or down.  This merely pauses the thread.
     * @param listen  true for up, false for down
     */
    public synchronized final void listen(boolean listen) {
        if (listen) {
	    if(Core.logger.shouldLog(Logger.DEBUG,this)) 
		Core.logger.log(this, "Starting listening "+this,
				new Exception("debug"), 
				wasStopped ? Logger.NORMAL : Logger.DEBUG);
            listening = true;
            errors.removeAllElements();
            this.notify();
        } else {
	    Exception e = new Exception("debug");
	    Core.logger.log(this, "Stopping listening "+this,
			    Logger.NORMAL);
	    System.err.println("Stopping listening "+this);
	    wasStopped = true;
	    e.printStackTrace(System.err);
            listening = false;
            if (executor != null)
                executor.interrupt();
        }
    }
    
    /**
     * Decommission the interface entirely.  The run() method will return.
     */
    public synchronized final void terminate() {
        terminated = true;
        this.notify();
        if (executor != null)
            executor.interrupt();
    }


    Listener listener = null;

    /**
     * Accepts connections and feeds them through the layers of this Interface.
     */
    public void run() {
        Core.logger.log(this, "Starting interface: "+this, Logger.MINOR);
	starting();
        try {
            executor = Thread.currentThread();
            while (!terminated) {
                if (!listening) {
		    boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
                    synchronized (this) {
                        while (!listening && !terminated) {
                            try {
				if(logDEBUG) 
				    Core.logger.log(this, "Waiting for notification to reopen "+this,
						    Logger.DEBUG);
                                this.wait(200);
				if(logDEBUG) Core.logger.log(this, "Got notification to reopen "+this,
							     Logger.DEBUG);
                            } catch (InterruptedException e) {}
                        }
                    }
                    continue;
                }
                try {
                    acceptConnections();
                }
                catch (Throwable e) {
                    Core.logger.log(this,
                                    "Unhandled throw accepting connections: "+this,
                                    e, Logger.ERROR);
		    System.err.println("Unhandled throw accepting connections: "+this+": "+e);
		    e.printStackTrace(System.err);
                    errors.addElement(e);
                    listening = false;
                }
            }
        } finally {
            try {
                if (listener != null)
                    listener.close();
            }
            catch (IOException e) {
                errors.addElement(e);
            }
            Core.logger.log(this, "Interface terminating: "+
                            this, Logger.NORMAL);
        }
    }
    
    
    /**
     * Called at start of run() to initialize anything that needs to be
     * initialized after the node is started
     */
    protected abstract void starting();

    private void acceptConnections() {
	if(Core.logger.shouldLog(Logger.DEBUG,this))
	    Core.logger.log(this, "Accepting connections", Logger.DEBUG);
	while (listening && !terminated) {
	    try {
		if (listener == null) {
		    listener = listenAddr.getListener();
		}
		try {
		    listener.setTimeout(2500);
		}
		catch (IOException e) {}

		Connection conn = listener.accept();

		try {
		    dispatch(conn);
		    Core.logger.log(this, "Accepted connection: " + conn,
				    Logger.MINOR);
		}
		catch (RejectedConnectionException e) {
		    Core.logger.log(this, "Rejected connection: " +
				    e.getMessage(),
				    Logger.MINOR);
		    conn.close();
		}
		catch (Throwable e) {
		    Core.logger.log(this,
				    "Unhandled throwable when dispatching connection",
				    e, Logger.NORMAL);
			e.printStackTrace();
		    conn.close();
		}

		errors.removeAllElements();
	    }
	    catch (InterruptedIOException e) {
		// nop
	    }
	    catch (IOException e) {
		Core.logger.log(this,
				"I/O error accepting connections on "+this
				+": "+e, Logger.MINOR);
	    }
	    catch (ListenException e) {
		Core.logger.log(this,
				"Cannot open listener: "+this,
				e, Logger.ERROR);
		errors.addElement(e);
		try {
		    Thread.sleep(5000);
		}
		catch (InterruptedException ie) {}
	    }
	    catch (Throwable e) {
		Core.logger.log(this,
				"Unhandled throw accepting connections: "+this,
				e, Logger.ERROR);
		errors.addElement(e);
		try {
		    Thread.sleep(5000);
		}
		catch (InterruptedException ie) {}
	    }
	    finally {
		if (errors.size() >= 6) {
		    Core.logger.log(this,
				    "Stopping interface due to errors: "+this,
				    Logger.ERROR);
		    System.err.println("Stopping interface due to errors: "+this);
		    listening = false;
		}
	    }
	}
    }
        
    /**
     * Should decide whether to accept the connection and start
     * a thread to handle it, then return quickly.
     */
    protected abstract void dispatch(Connection conn)
                    throws RejectedConnectionException;
}
