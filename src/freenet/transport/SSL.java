package freenet.transport;

import java.io.IOException;
import java.net.*;
import javax.net.*;
import javax.net.ssl.*;

/** Transport object for SSL.  This requires the javax.net libs to work
  * (which are available from Sun as the "JSSE" - Java Secure Sockets
  *  Extension, and are apparently standard in Java 1.4).
  *  
  * We need to look at distributing a free implementation of them.
  * Cryptix and/or PureTLS are probably the most promising sources of
  * free code to adapt for this.
  *
  * Otoh I bet Scott could crack it together in a weekend ;p
  *
  * @author tavin
  */
public class SSL extends tcpTransport {

    public SSL(int preference, boolean strict) {
        super(preference, strict);
    }

    public SSL(InetAddress bindAddr, int preference, boolean strict) {
        super(bindAddr, preference, strict);
    }

    public final String getName() {
        return "ssl";
    }

    public final tcpSocketFactory getSocketFactory() {
        return socketFactory;
    }

    public final tcpServerSocketFactory getServerSocketFactory() {
        return serverSocketFactory;
    }


    
    // these should be anonymous classes but whatever
    
    private static final tcpSocketFactory socketFactory =
        new privSocketFactory();
    
    private static final tcpServerSocketFactory serverSocketFactory =
        new privServerSocketFactory();

    /** proxy the calls to javax.net.SSLSocketFactory */
    private static final class privSocketFactory extends tcpSocketFactory {
        private final SocketFactory s = SSLSocketFactory.getDefault();
        Socket createSocket(InetAddress host, int port) throws IOException {
            return s.createSocket(host, port);
        }
        Socket createSocket(InetAddress address, int port,
                            InetAddress clientAddress, int clientPort) throws IOException {
            return s.createSocket(address, port, clientAddress, clientPort);
        }
        Socket createSocket(String host, int port) throws IOException, UnknownHostException {
            return s.createSocket(host, port);
        }
        Socket createSocket(String host, int port,
                            InetAddress clientHost, int clientPort) throws IOException,
                                                                           UnknownHostException {
            return s.createSocket(host, port, clientHost, clientPort);
        }
    }

    /** proxy the calls to javax.net.SSLServerSocketFactory */
    private static final class privServerSocketFactory extends tcpServerSocketFactory {
        private final ServerSocketFactory s = SSLServerSocketFactory.getDefault();
        ServerSocket createServerSocket(int port) throws IOException {
            return s.createServerSocket(port);
        }
        ServerSocket createServerSocket(int port, int backlog) throws IOException {
            return s.createServerSocket(port, backlog);
        }
        ServerSocket createServerSocket(int port, int backlog,
                                        InetAddress ifAddress) throws IOException {
            return s.createServerSocket(port, backlog, ifAddress);
        }
    }
}








