package freenet.interfaces;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Vector;

import freenet.Connection;
import freenet.Core;
import freenet.ListenException;
import freenet.ListeningAddress;
import freenet.NIOListener;
import freenet.SelectorLoop;
import freenet.Transport;
import freenet.support.Logger;
import freenet.transport.tcpConnection;
import freenet.transport.tcpNIOListener;


/**
 * a NIO interface is the same as the regular interface
 * except that it does not run in its own thread and just 
 * dispatches connections.
 */
 
 public abstract class NIOInterface {
 

     protected NIOListener listener;

    /** the nio loop this interface is running on*/
    protected SelectorLoop loop;

    /** address this interface listens on */
    protected final ListeningAddress listenAddr;

    /** to decommission the interface completely */
    private boolean terminated = false;

    /** toggled to bring the interface up and down */
    protected boolean listening = true;
    
    /** a symbolic name for the interface **/
	protected String symbolicName;
			


    /** collects exception throws */
    private final Vector errors = new Vector();

    /**
     * @param listenAddr  The listening address this Interface should
     *                    operate on.
     */
    public NIOInterface(ListeningAddress listenAddr,String symbolicName) {
        this.listenAddr = listenAddr;
		this.symbolicName = symbolicName;
	//TODO: in the subclass, create the NIOListener
    }

    /******* OK THIS IS IT *********/
    public void register(SelectorLoop loop) {
    	this.loop = loop;
	listener.register(loop,this);
	starting();
    }
    
    protected abstract void starting();
    
    protected final void stopListening() throws IOException{
	listening = false;
        listener.close();
    }

    public String getName(){
    	return symbolicName;
    }
    
    
    public synchronized final void listen(boolean listen) {
     if (!listen){
	   
	    Core.logger.log(this, "Stopping listening "+this,
			    Logger.NORMAL);
	    System.err.println("Stopping listening "+this);
	    //wasStopped = true;
	   try {
            stopListening();
	  }catch (IOException e) {e.printStackTrace();}
     }else{
     	try{
	((tcpNIOListener)listener).startListener(); // I don't want to pollute the interface
	}catch(ListenException e) {
		e.printStackTrace();
		return;
	}
        listener.register(loop,this);
     }
	   
    }
  
     
    public void acceptConnection(Connection conn) {
	if(tcpConnection.getWSL() != null)
	    tcpConnection.getWSL().putBandwidth(80);
	if(tcpConnection.getRSL() != null)
	    tcpConnection.getRSL().putBandwidth(80);
	// FIXME: how much does a TCP handshake really cost?
	
    		try {
		    dispatch(conn);
		    Core.logger.log(this, "Accepted connection: " + conn,
				    Logger.MINOR);
		}
		catch (RejectedConnectionException e) {
		    Core.logger.log(this, "Rejected connection: " +
				    e.getMessage(),
				    Logger.MINOR);
		/**
		 * leak not here -zab
		 */
		conn.close(); 
		
		}catch (Throwable e) {
		    Core.logger.log(this,
				    "Unhandled throwable when dispatching connection",
				    e, Logger.NORMAL);
			e.printStackTrace();
		    conn.close();
		}

	//errors.removeAllElements();
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
    public final ListeningAddress getListeningAddress() {
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
    
    /**
     * Should decide whether to accept the connection and start
     * a thread to handle it, then return quickly.
     */
    protected abstract void dispatch(Connection conn) throws RejectedConnectionException;
}
