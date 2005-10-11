package freenet.interfaces;

import freenet.Address;
import freenet.Connection;
import freenet.Core;
import freenet.ListenException;
import freenet.ListeningAddress;
import freenet.NIOListener;
import freenet.support.Logger;
import freenet.transport.tcpListeningAddress;

/**
 * A BaseLocalNIOInterface is connected to by clients
 * and can do allowed hosts checking.
 *
 */
public abstract class BaseLocalNIOInterface extends NIOInterface {

    private AllowedHosts allowedHosts;
    boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
    
    int lowRunningConnections; // reenable interface when go below this
    int highRunningConnections; // disable interface when go above this
   
    /**
     * @param  allowedHosts         set of Addresses to do an equalsHost() check with;
     *                      null means to allow all hosts
     * @param  listenAddr           Description of the Parameter
     * @exception  ListenException  Description of the Exception
     */
    public BaseLocalNIOInterface(ListeningAddress listenAddr,
				 int[][] allowedHosts,
				 int lowRunningConnections, 
				 int highRunningConnections,
				 String interfaceName)
        throws ListenException {
        super(listenAddr,interfaceName);
        this.allowedHosts = new AllowedHosts(allowedHosts);
		this.lowRunningConnections = lowRunningConnections;
		this.highRunningConnections = highRunningConnections;
		this.listener = getListener(listenAddr);
    }
    
    private NIOListener getListener(ListeningAddress listenAddr) 
		throws ListenException {
		return ((tcpListeningAddress)listenAddr).getNIOListener();
    }
    
    protected synchronized void reopenListener() throws ListenException {
		if(listening) return;
		Core.logger.log(this, "Restarting listening on "+this,
			Logger.NORMAL);
		this.listener = getListener(listenAddr);
		register(loop);
		listening = true;
    }
    
    /**
     * @param  allowedHosts         set of Addresses to do an equalsHost() check with;
     *                      null means to allow all hosts
     * @param  listenAddr           Description of the Parameter
     * @exception  ListenException  Description of the Exception
     */
    public BaseLocalNIOInterface(ListeningAddress listenAddr,
				 String allowedHosts,
				 int lowRunningConnections, 
				 int highRunningConnections,
				 String interfaceName)
        throws ListenException {
		super(listenAddr,interfaceName);
		setAllowedHosts(allowedHosts);
		this.lowRunningConnections = lowRunningConnections;
		this.highRunningConnections = highRunningConnections;
		this.listener = getListener(listenAddr);
		//Node.registerMBean(this,"Node.Interface","Provider=NIOInterface,ListeningAddress="+listenAddr);
    }

	public String getAllowedHosts() {
		return allowedHosts.toString();	
	}

	/**
	* @param  allowedHosts set of Addresses to do an equalsHost() check with;
	*                      null means to allow all hosts
	*/
    public void setAllowedHosts(String allowedHosts) {
    	this.allowedHosts = new AllowedHosts(allowedHosts);
    }

    private boolean hostAllowed(Address addr)
		throws RejectedConnectionException {
    	return allowedHosts.match(addr);
	}


    /**
     *  Description of the Method
     *
     * @param  conn                             Description of the Parameter
     * @exception  RejectedConnectionException  Thrown when connections from the connecting host isn't allowed
     */
    protected void dispatch(Connection conn) throws RejectedConnectionException {
		logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
		Address ha = conn.getPeerAddress();
		if(ha == null) {
			if(logDEBUG)
				Core.logger.log(this, "Connection had null address, dropping: "+
						conn, Logger.DEBUG);
			return;
		}
		if(logDEBUG)
	    	Core.logger.log(this, 
				    "Dispatching connection on a BaseLocalNIOInterface from " +
			    	ha.toString(), Logger.DEBUG);
		if ( hostAllowed(ha)) {
	    	handleConnection(conn);
		} else {
	    	if(logDEBUG)
				Core.logger.log(this, "Rejecting local connection",	Logger.DEBUG);
	    	throw new RejectedConnectionException("host not allowed: " + ha);
		}
    }
    
    protected abstract void handleConnection(Connection conn);
}



