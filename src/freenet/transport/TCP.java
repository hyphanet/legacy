package freenet.transport;

import java.io.IOException;
import java.net.*;
import java.nio.channels.*;
import freenet.*;
import freenet.support.Logger;

/**
 * Transport object for TCP.
 *
 * @author oskar
 */
public class TCP extends tcpTransport {

    public TCP(int preference, boolean strict) {
        super(preference, strict);
    }

    public TCP(InetAddress bindAddr, int preference, boolean strict) {
        super(bindAddr, preference, strict);
    }

    public final String getName() {
        return "tcp";
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

    /** proxy the calls to ordinary Socket constructors */
   /* private static final class privSocketFactory extends tcpSocketFactory {
        Socket createSocket(InetAddress host, int port) throws IOException {
            return new Socket(host, port);
        }
        Socket createSocket(InetAddress address, int port,
                            InetAddress clientAddress, int clientPort) throws IOException {
            return new Socket(address, port, clientAddress, clientPort);
        }
        Socket createSocket(String host, int port) throws IOException, UnknownHostException {
            return new Socket(host, port);
        }
        Socket createSocket(String host, int port,
                            InetAddress clientHost, int clientPort) throws IOException,
                                                                           UnknownHostException {
            return new Socket(host, port, clientHost, clientPort);
        }
    }*/

    private static final class privSocketFactory extends tcpSocketFactory {
        Socket createSocket(InetAddress host, int port) throws IOException {
            SocketChannel sc = SocketChannel.open();
	    sc.connect(new InetSocketAddress(host,port));
	    sc.finishConnect();
	    return sc.socket();
        }
        Socket createSocket(InetAddress address, int port,
                            InetAddress clientAddress, int clientPort) throws IOException {
		Core.logger.log(this, " call to unsupported socket constructor ",Logger.NORMAL);
            return createSocket(address,port);
        }
        Socket createSocket(String host, int port) throws IOException, UnknownHostException {
            SocketChannel sc = SocketChannel.open();
	    sc.connect(new InetSocketAddress(host,port));
	    sc.finishConnect();
	    return sc.socket();
        }
        Socket createSocket(String host, int port,
                            InetAddress clientHost, int clientPort) throws IOException,
                                                                           UnknownHostException {
            Core.logger.log(this, " call to unsupported socket constructor ",Logger.NORMAL);
	    return createSocket(host,port);
        }
    }

    /** proxy the calls to ordinary ServerSocket constructors */
    /*
    private static final class privServerSocketFactory extends tcpServerSocketFactory {
        ServerSocket createServerSocket(int port) throws IOException {
            return new ServerSocket(port);
        }
        ServerSocket createServerSocket(int port, int backlog) throws IOException {
            return new ServerSocket(port, backlog);
        }
        ServerSocket createServerSocket(int port, int backlog,
                                        InetAddress ifAddress) throws IOException {
            return new ServerSocket(port, backlog, ifAddress);
        }
    }*/
    
    private static final class privServerSocketFactory extends tcpServerSocketFactory {
        	ServerSocket createServerSocket(int port) throws IOException {
		ServerSocketChannel chan = ServerSocketChannel.open();
		chan.configureBlocking(false);
		ServerSocket sock = chan.socket();
		sock.bind(new InetSocketAddress("127.0.0.1",port));
            return sock;
        }

        ServerSocket createServerSocket(int port, int backlog) throws IOException {
		ServerSocketChannel chan = ServerSocketChannel.open();
		chan.configureBlocking(false);
		ServerSocket sock = chan.socket();
		sock.bind(new InetSocketAddress("127.0.0.1",port),backlog);
            return sock;
        }

        ServerSocket createServerSocket(int port, int backlog,
                                        InetAddress ifAddress) throws IOException {
            ServerSocketChannel chan = ServerSocketChannel.open();
		ServerSocket sock = chan.socket();
		chan.configureBlocking(false);
		sock.setReuseAddress(true);
		sock.bind(new InetSocketAddress(ifAddress,port),backlog);
            return sock;
        }
    }
}


