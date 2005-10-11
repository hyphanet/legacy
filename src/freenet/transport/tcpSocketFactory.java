package freenet.transport;

import java.net.*;
import java.io.IOException;

/** The javax.net.* classes are not standard everywhere,
  * so we need to imitate their socket factory APIs.
  * @author tavin
  */
abstract class tcpSocketFactory {

    abstract Socket createSocket(InetAddress host, int port) throws IOException;
          
    abstract Socket createSocket(InetAddress address, int port, InetAddress clientAddress,
                                 int clientPort) throws IOException;
          
    abstract Socket createSocket(String host, int port) throws IOException,
                                                               UnknownHostException;
          
    abstract Socket createSocket(String host, int port, InetAddress clientHost,
                                 int clientPort) throws IOException, UnknownHostException;
}


