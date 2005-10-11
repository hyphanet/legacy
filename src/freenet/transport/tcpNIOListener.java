package freenet.transport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import freenet.ListenException;
import freenet.ListeningAddress;
import freenet.NIOListener;
import freenet.SelectorLoop;
import freenet.interfaces.NIOInterface;


/**
 * this is an actual listener on a nio loop
 * most of the code is stolen from the tcpListener.java
 * usage:
 * this thing registers a socket to a nioloop, then provides a callback
 * method.  That callback method creates a Connection object and delegates it
 * to the appropriate interface.  The interface needs to register with this
 * as well.
 */
public final class tcpNIOListener implements NIOListener {
    private ServerSocket sock;
    private tcpListeningAddress address;
    private boolean dontThrottle;
    private NIOInterface dispatcher;
    private InetAddress bindAddr;
    
    //Will contain the # of the local port we are listening on.
    private int port;
    
    //private SelectionKey skey;
    private SelectorLoop loop;
    public static boolean throttleAll = false;

    private final tcpTransport t;

   public tcpNIOListener(tcpTransport t, tcpListeningAddress addr,
		boolean dontThrottle) throws ListenException {
        this(t, addr, null,dontThrottle);
        
    }

    tcpNIOListener(tcpTransport t, tcpListeningAddress addr,
                InetAddress bindAddr, boolean dontThrottle)
	throws ListenException {
        this.t = t;
	this.bindAddr = bindAddr;
	
	//TODO: Why do we not just use the provided 'addr'?
	//It has port#, it has 'dontThrottle' and it can have a 'bindAddr'
	//Do those variables contiain the wrong values for us?
	this.address = new tcpListeningAddress(t, bindAddr,addr.getPort(),
					      dontThrottle);
	this.dontThrottle = dontThrottle;
	port = address.getPort();
	startListener();
    }
    
    /**
     * this method assumes we have passed the necessary arguments
     * at creation time.  Its used for restarting a listener.
     */
    public void startListener() throws ListenException{
    	try {
            
    		//sel = Selector.open();
            sock = t.getServerSocketFactory().createServerSocket(address.getPort(), 50, bindAddr);
            // if addr.port == 0, then the port is set randomly.
            // find out which one we got
            if (port == 0) port = sock.getLocalPort();
            
            
        } catch (IOException e) {
            throw new ListenException(this+": "+e.getMessage());
        }
    }

    public final ListeningAddress getAddress() {
        return address;
    }

    public final void setTimeout(int n) throws IOException {
        sock.setSoTimeout(n);
    }

    public final String toString() {
        return t.getName()+'/'+port;
    }

    /*************** OK HERE WE GO ************/

    public void register(SelectorLoop loop, NIOInterface dispatcher) {
    	this.dispatcher = dispatcher;
    	this.loop = loop;
	loop.register(sock.getChannel(),this);
    }

    public final void close() {
    	try{
		sock.close();
	}catch(IOException e) {}//log something
	loop.unregister(sock);
    }

    public void accept(Socket sock) throws IOException{
    	tcpConnection con = tcpConnection.wrap(t, sock, dontThrottle, throttleAll);
	//call the dispatch method of the interface for this connection.
	dispatcher.acceptConnection(con);
    }


}
