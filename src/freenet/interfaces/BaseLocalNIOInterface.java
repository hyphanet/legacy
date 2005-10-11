package freenet.interfaces;

import java.net.InetAddress;

import freenet.Address;
import freenet.Connection;
import freenet.Core;
import freenet.ListenException;
import freenet.ListeningAddress;
import freenet.NIOListener;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.transport.tcpAddress;
import freenet.transport.tcpListeningAddress;

/**
 * A BaseLocalNIOInterface is connected to by clients
 * and can do allowed hosts checking.
 *
 */
public abstract class BaseLocalNIOInterface extends NIOInterface {

    private int[][] allowedHosts;
    private String allowedHostsString;//TODO: ugly hack.. this string should be generated on the fly
    boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
    
    private static final int intAddress(String addr) { 
		try {
	    	return intAddress(InetAddress.getByName(addr));
		} catch (java.net.UnknownHostException e) {
	    	return 0;
		}
    }

	private static final int intAddress(InetAddress addr) {
		boolean logDEBUG =
			Core.logger.shouldLog(Logger.DEBUG, BaseLocalNIOInterface.class);
		if (logDEBUG)
			Core.logger.log(
				LocalNIOInterface.class,
				"intAddress(" + addr.toString() + ")",
				Logger.DEBUG);
		byte[] b = addr.getAddress();
		if (logDEBUG)
			Core.logger.log(
				LocalNIOInterface.class,
				"Address: "
					+ (b[0] & 0xff)
					+ "."
					+ (b[1] & 0xff)
					+ "."
					+ (b[2] & 0xff)
					+ "."
					+ (b[3] & 0xff)
					+ " ("
					+ b.length
					+ ")",
				Logger.DEBUG);
		long x =
			((((long) b[0]) & 0xff) << 24)
				+ ((b[1] & 0xff) << 16)
				+ ((b[2] & 0xff) << 8)
				+ (b[3] & 0xff);
		if (logDEBUG)
			Core.logger.log(
				LocalNIOInterface.class,
				"Returning " + Long.toHexString(x),
				Logger.DEBUG);
		return (int) x;
	}
    
    private static final int mask(int addr, int maskbits) {
		int mbits = 32 - maskbits;
		int power;
		if(mbits < 32)
	    	power = 1 << mbits;
		else
	    	power = 0; // 1 << 32 = 1 !! - check before you "fix" this
		int ones = power - 1;
		int mask = ~ones;
		//int mask = ~((1 << (32-maskbits)) -1);
		int out = addr & mask;
		return out;
    }
    
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
        this.allowedHosts = allowedHosts;
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
    public String getAllowedHosts(){
		return allowedHostsString; //TODO: ugly hack.. this string should be generated on the fly	
    }
	
	/**
	* @param  allowedHosts set of Addresses to do an equalsHost() check with;
	*                      null means to allow all hosts
	*/
    public void setAllowedHosts(String allowedHosts){
		int[][] allowedHostsAddr = null;

		if (allowedHosts == null || allowedHosts.trim().length()==0) {
			allowedHosts = "127.0.0.0/8";
		}

		if (allowedHosts.trim().equals("*")) {
			allowedHosts = "0.0.0.0/0";
		}
	
		if(logDEBUG)
			Core.logger.log(this, "New BaseLocalNIOInterface: "+listenAddr+
					","+allowedHosts, Logger.DEBUG);
	
		String[] hosts = Fields.commaList(allowedHosts);

		allowedHostsAddr = new int [hosts.length][2];
	
		for (int i = 0; i < hosts.length; ++i) {
			hostAndSubnetPair h = parseHostOrNetString(hosts[i]);
			allowedHostsAddr[i][0] = mask(h.host, h.subnet);
			allowedHostsAddr[i][1] = h.subnet;
		}
		this.allowedHosts = allowedHostsAddr;
		allowedHostsString = allowedHosts;
    }
    private class hostAndSubnetPair{
    	public int host,subnet;
		hostAndSubnetPair(int host, int subnet){
			this.host = host;
			this.subnet = subnet;
		}
    }
    private hostAndSubnetPair parseHostOrNetString(String hostOrNetString){
		int host, subnet, div = hostOrNetString.indexOf('/');
		if (div == -1) { //Consider the absence of a subnetmask as 255.255.255.255 (=only the exact host specified)
			subnet = 32;
			host = intAddress(hostOrNetString);
		} else {
			subnet = Integer.parseInt(hostOrNetString.substring(div+1));
			host = intAddress(hostOrNetString.substring(0,div));
		}
		return new hostAndSubnetPair(host,subnet);
    }
    
	private boolean hostAllowed(Address addr)
		throws RejectedConnectionException {
		boolean allow = false;
		//insert some code to make sure ha is a tcpAddress and
		//handle things correctly when it's not. --thelema
		int inta;
		try {
			inta = intAddress(((tcpAddress) addr).getHost());
		} catch (java.net.UnknownHostException e) {
			Core.logger.log(
				this,
				"Unknown Host on incoming connection!!",
				Logger.ERROR);
			throw new RejectedConnectionException("unknown host on incoming connection!");
		}

		for (int i = 0; !allow && i < allowedHosts.length; i++) {
			int subnet = allowedHosts[i][0];
			int maskbits = allowedHosts[i][1];
			allow |= (mask(inta, maskbits) == subnet);
			if (logDEBUG)
				Core.logger.log(
					this,
					"Trying "
						+ Integer.toHexString(subnet)
						+ ":"
						+ Integer.toHexString(maskbits)
						+ " for "
						+ Integer.toHexString(inta),
					Logger.DEBUG);
		}
		return allow;
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



