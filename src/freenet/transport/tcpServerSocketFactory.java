package freenet.transport;

import java.net.*;
import java.io.IOException;

/** The javax.net.* classes are not standard everywhere,
  * so we need to imitate their socket factory APIs.
  * @author tavin
  */
abstract class tcpServerSocketFactory {

    abstract ServerSocket createServerSocket(int port) throws IOException;

    abstract ServerSocket createServerSocket(int port, int backlog) throws IOException;
    
    abstract ServerSocket createServerSocket(int port, int backlog,
                                             InetAddress ifAddress) throws IOException;

}


