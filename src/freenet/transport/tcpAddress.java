package freenet.transport;
import java.net.InetAddress;
import java.net.UnknownHostException;

import freenet.Address;
import freenet.BadAddressException;
import freenet.ConnectFailedException;
import freenet.Connection;
import freenet.Core;
import freenet.ListeningAddress;
import freenet.support.Logger;

/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
 */

/**
 * A class representing the address of an Adaptive Network client in the
 * network which uses the TCP/IP protocol.
 * @author <A HREF="mailto:blanu@uts.cc.utexas.edu">Brandon Wiley</A>
 */

public final class tcpAddress extends Address {

    private InetAddress host;
    private String hostName = null;
    private int port;
    private int hashCode; // has to be invariant with lookups etc
    public static boolean throttleAll = false;

    private String valname = "";

    private final tcpTransport transport;
    
    public int hashCode() {
	return hashCode;
    }
    
    public boolean equals(tcpAddress tcp) {
	if(tcp == null) return false;
	if(tcp.port != port) return false;
	if(tcpAddress.throttleAll != throttleAll) return false;
	if(host == null)
	    return tcp.hostName.equals(hostName);
	else 
	    return tcp.host.equals(host);
    }
    
    public boolean equals(Object o) {
	if(o instanceof tcpAddress)
	    return equals((tcpAddress)o);
	else return false;
    }
    
    private tcpAddress(tcpTransport transport) {
        super(transport);
        this.transport = transport;
    }
    
    /**
     * Creates an address from an InetAddress object and port number.
     */
    public tcpAddress(tcpTransport transport, InetAddress host, int port)
	throws BadAddressException {
        this(transport);
        setPort(port);
        this.host = host;
	if(host!=null) {
	    valname = host.getHostAddress();
        
	    hashCode = port ^ valname.hashCode();//host.hashCode() ^ valname.hashCode();
	} else {
	    hashCode = port;
	    valname = "";
	}
    }
    
    /**
     * Creates an address from a host name or IP string and port number.
     */
    tcpAddress(tcpTransport transport, String hostname, int port)
                                throws BadAddressException {
        this(transport);
        setPort(port);
	hostName = hostname;
	host = null;
	//doDeferredLookup();
        valname = hostname;
	hashCode = port ^ valname.hashCode();
    }

    /** 
     * Creates an address from a string in the format "a.b.c.d:p"
     * where a,b,c,d are the (decimal) octets of the IPv4 address
     * and p is the (decimal) port number.
     */
    tcpAddress(tcpTransport transport, String str) throws BadAddressException {
        this(transport);
        int colon = str.indexOf(':');
        if (colon == -1) {
            throw new BadAddressException("both the IP and port number must be given");
        }
        valname = str.substring(0, colon);
        try {
            host = InetAddress.getByName(valname);
        }
        catch (UnknownHostException e) {
            throw new BadAddressException(""+e);
        }
        setPort(Integer.parseInt(str.substring(colon + 1)));
	hashCode = port ^ valname.hashCode();
    }
    
    public final void doDeferredLookup() throws UnknownHostException {
	if(host == null && hostName != null) {
	    long startTime = System.currentTimeMillis();
	    host = InetAddress.getByName(hostName);
	    if(Core.logger.shouldLog(Logger.DEBUG,this)) {
	    Core.logger.log(this, "getHostAddress() took "+
			    (System.currentTimeMillis()-startTime)+" ms", Logger.DEBUG);
			    }
	}
    }

    public final Connection connect(boolean dontThrottle) 
	throws ConnectFailedException {
	try {
	    doDeferredLookup();
	} catch (UnknownHostException e) {
	    throw new ConnectFailedException(this, "DNS lookup failed!");
	}
        return tcpConnection.connect(transport, this, dontThrottle, throttleAll);
    }
    
    public final String getValString() {
        return valname + ":" + port;
    }
    
    public final String getValName() {
	return valname;
    }
    
    public final ListeningAddress listenPart(boolean dontThrottle) {
        return new tcpListeningAddress(transport, null, port, dontThrottle);
    }
    
    final void setPort(int port) throws BadAddressException {
        if (port < 0 || port > 65535) {
            throw new BadAddressException("port number "+port+" out of range");
        }
        this.port = port;
    }
    
    public final InetAddress getHost() throws UnknownHostException {
	doDeferredLookup();
	return host;
    }
    
    public final int getPort() {
        return port;
    }

    public String toString() {
        return getValString();
    }
}


