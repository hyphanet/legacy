package freenet.transport;
import java.net.InetAddress;

import freenet.ListenException;
import freenet.Listener;
import freenet.ListeningAddress;
import freenet.NIOListener;
/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.

 */

public final class tcpListeningAddress extends ListeningAddress {

    private int port;
    private InetAddress bindAddr;
    private boolean dontThrottle;

    private final tcpTransport t;

    tcpListeningAddress(tcpTransport t, InetAddress bindAddr, 
                        int portnum, boolean dontThrottle) {
        super(t);
        this.t = t;
        port = portnum;
	this.dontThrottle = dontThrottle;
        this.bindAddr = bindAddr;
    }

    public final Listener getListener() throws ListenException {
        return new tcpListener(t, this, bindAddr,dontThrottle);
    }
    
    public final NIOListener getNIOListener() throws ListenException {
    	return new tcpNIOListener(this);
    }
    
    public final String getValString() {
        return (bindAddr == null ? "*" : bindAddr.toString()) +
        	":"+Integer.toString(port);
    }

    final int getPort() {
        return port;
    }

    public InetAddress getBindAddress() {
        return bindAddr;
    }

    public boolean getDontThrottle() {
        return dontThrottle;
    }
}


