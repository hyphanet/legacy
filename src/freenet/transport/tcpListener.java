package freenet.transport;
import freenet.*;
import java.io.*;
import java.net.*;
//import freenet.support.Logger;
import java.nio.channels.*;

public final class tcpListener implements Listener {

    private int port;
    private ServerSocket sock;
    private tcpListeningAddress address;
    private boolean dontThrottle;
    //private Selector sel=null;
    private SelectionKey skey;
    public static boolean throttleAll = false;

    private final tcpTransport t;

    tcpListener(tcpTransport t, tcpListeningAddress addr,
		boolean dontThrottle) throws ListenException {
        this(t, addr, null, dontThrottle);
    }

    tcpListener(tcpTransport t, tcpListeningAddress addr,
                InetAddress bindAddr, boolean dontThrottle)
	throws ListenException {
        this.t = t;
	this.dontThrottle = dontThrottle;
        try {
            port = addr.getPort();
	    //sel = Selector.open();
            sock = t.getServerSocketFactory().createServerSocket(addr.getPort(), 50, bindAddr);
            // if addr.port == 0, then the port is set randomly.
            // find out which one we got
            if (port == 0) port = sock.getLocalPort();
            address = new tcpListeningAddress(t, bindAddr, port,
					      dontThrottle);

	//Core.logger.log(this, "registered a channel",Logger.NORMAL);
        } catch (IOException e) {
            throw new ListenException(this+": "+e.getMessage());
        }
    }

    public final ListeningAddress getAddress() {
        return address;
    }

    public final Connection accept() {

        //Socket a= null;
	//sel.wakeup();
	/*Selector sel=Selector.open();
	skey = sock.getChannel().register(sel, SelectionKey.OP_ACCEPT); //TODO_NIO: move all to 1 selector
	sel.select();
	if (sel.selectedKeys().contains(skey))
		a = ((ServerSocketChannel)skey.channel()).accept().socket();
	sel.close();
	if (a==null) Core.logger.log(this, "warning: returning null socket",Logger.NORMAL);
       // Socket a = sock.accept();
        //    a.setSoLinger(true, 0);
        return new tcpConnection(t, a, designator, dontThrottle, throttleAll);*/
	return null;
    }

    public final void setTimeout(int n) throws IOException {
        sock.setSoTimeout(n);
    }

    public final String toString() {
        return t.getName()+'/'+address.getValString();
    }

    public final void close() {
        try { sock.close();skey.cancel();}
        catch(IOException e) {}
        sock=null;
    }
}


