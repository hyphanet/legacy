/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.interfaces;
import java.net.InetAddress;
import java.util.LinkedList;

import freenet.Connection;
import freenet.Core;
import freenet.ListenException;
import freenet.ListeningAddress;
import freenet.config.Params;
import freenet.support.Logger;
import freenet.thread.ThreadFactory;
import freenet.transport.TCP;

public final class LocalNIOInterface extends BaseLocalNIOInterface {
    
    protected final ConnectionRunner runner;
    protected final ThreadFactory tf;
    
    /**
     * @param  allowedHosts         set of Addresses to do an equalsHost() check with;
     *                      null means to allow all hosts
     * @param  listenAddr           Description of the Parameter
     * @param  tf                   Description of the Parameter
     * @param  runner               Description of the Parameter
     * @exception  ListenException  Description of the Exception
     */
    public LocalNIOInterface(ListeningAddress listenAddr, ThreadFactory tf,
			     ConnectionRunner runner, String allowedHosts,
			     int lowRunningConnections, 
			     int highRunningConnections,
			     String interfaceName)
        throws ListenException {
		super(listenAddr, allowedHosts, lowRunningConnections,
	      highRunningConnections,interfaceName);
		this.runner = runner;
		this.tf = tf;

    }
    
    /**
     * @param  runner               handles the connection thread
     * @param  listenAddr           Description of the Parameter
     * @param  tf                   Description of the Parameter
     * @exception  ListenException  Description of the Exception
     */
    public LocalNIOInterface(ListeningAddress listenAddr,
			     ConnectionRunner runner, 
			     ThreadFactory tf, int lowRunningConnections,
			     int highRunningConnections,
				 String interfaceName) 
	throws ListenException {
        super(listenAddr, new int[][]{{0},{0}}, lowRunningConnections, 
	      highRunningConnections,interfaceName);
		this.runner = runner;
		this.tf = tf;

    }
    
    /**
     * @param  allowedHosts         set of Addresses to do an equalsHost() check with;
     *                      null means to allow all hosts
     * @param  listenAddr           Description of the Parameter
     * @param  tf                   Description of the Parameter
     * @param  runner               Description of the Parameter
     * @exception  ListenException  Description of the Exception
     */
    public LocalNIOInterface(ListeningAddress listenAddr, ThreadFactory tf,
               ConnectionRunner runner, int[][] allowedHosts,
			  int lowRunningConnections, 
			  int highRunningConnections,
			  String interfaceName)

        throws ListenException {
		super(listenAddr, allowedHosts, lowRunningConnections,
	      highRunningConnections,interfaceName);
		this.tf = tf;
		this.runner = runner;

    }
    
    /**
     * Builds a LocalNIOInterface from a Params using the entries:
     *      port, allowedHosts, bindAddress
     *
     * @param  fs                   Description of the Parameter
     * @param  tf                   Description of the Parameter
     * @param  runner               Description of the Parameter
     * @return                      Description of the Return Value
     * @throws  InterfaceException  if the Params was unusable
     * @throws  ListenException     if the new Interface couldn't bind
     *                             to its listening address
     *
     * TODO - transport independence
     */
    public static LocalNIOInterface make(Params fs, ThreadFactory tf,

					 ConnectionRunner runner,
					 boolean dontThrottle,
					 int lowRunningConnections,
					 int highRunningConnections,
					 String interfaceName)
        throws InterfaceException, ListenException {
	
        int port;
	
        String bindAddress;
        String allowedHosts = null;
	
		port = fs.getInt("port");
		if(port == 0)
            throw new InterfaceException("No port given for interface");
	
        allowedHosts = fs.getString("allowedHosts");
        if (allowedHosts != null && allowedHosts.trim().length()==0) {
            allowedHosts = null;
        }
	
        bindAddress = fs.getString("bindAddress");
        if (bindAddress == null || bindAddress.trim().length()==0) {
            // if no bindAddress was specified, we bind to loopback
            // only unless allowedHosts was specified, in which case
            // we bind to all addresses
            bindAddress = (allowedHosts == null ? "127.0.0.1" : null);
        }
	
        TCP tcp;
        try {
            // "*" allows all connections.  e.g. for HTTP gateways.
            if (bindAddress == null || bindAddress.trim().equals("*")) {
                tcp = new TCP(1, false);
            } else {
                tcp = new TCP(InetAddress.getByName(bindAddress.trim()), 1,
                              false);
            }
        } catch (Exception e) {
            throw new InterfaceException("" + e);
        }
	
	
        return new LocalNIOInterface(tcp.getListeningAddress(port, dontThrottle),
                tf, runner, allowedHosts, lowRunningConnections,
                highRunningConnections,interfaceName);
	
    }
    
    protected void starting() {
		runner.starting();
    }
    
    protected void handleConnection(Connection conn) {
		if(acceptingConnections) {
			if(logDEBUG)
				Core.logger.log(this, "Accepting connection immediately: "+
								conn, Logger.DEBUG);
			if(conn != null)
				realHandleConnection(conn);
			synchronized(oldConnections) {
				int i=0;
				while(!oldConnections.isEmpty() && acceptingConnections) {
					if(logDEBUG)
						Core.logger.log(this, "Handling old connection #"+i++,
										Logger.DEBUG);
					realHandleConnection((Connection)oldConnections.
										 removeFirst());
				}
			}
		} else {
			if(logDEBUG)
				Core.logger.log(this, "Deferring connection "+conn,
								Logger.DEBUG);
			synchronized(oldConnections) {
				int deleted = 0;
				while(oldConnections.size() >= MAX_QUEUED_CONNECTIONS) {
					((Connection)(oldConnections.removeLast())).close(false);
					deleted++;
				}
				if(deleted > 0)
					Core.logger.log(this, "Dropped "+deleted+" old connections",
									Logger.NORMAL);
				if(conn != null)
					oldConnections.addFirst(conn);
				if(logDEBUG)
					Core.logger.log(this, "Added "+conn+" - "+oldConnections.size()+
									" connections queued", Logger.DEBUG);
			}
		}
    }
    
    protected void realHandleConnection(Connection conn) {
		if(runner.needsThread()) {
		    ConnectionShell cs = new ConnectionShell(conn);
			Thread t = tf.getThread(cs);
			if(logDEBUG) {
				String tname = "";
				if (t != null) tname = t.toString();
				Core.logger.log(this, "Allocated thread for local connection: "+
								cs+":"+t+":"+tname+":"+conn, Logger.DEBUG);
			}
		} else {
			if(logDEBUG)
				Core.logger.log(this, "Running local connection in-thread: "+
								conn, Logger.DEBUG);
            try {
                runner.handle(conn);
            } catch (RuntimeException t) {
                Core.logger.log(LocalNIOInterface.this,
                                "Unhandled throwable while handling "+
								"connection "+conn+": "+t,
                                t, Logger.ERROR);
                conn.close();
                throw t;
            } catch (Error e) {
                conn.close();
				Core.logger.log(LocalNIOInterface.this, "Unhandled Error while handling connection: "+conn, e, Logger.ERROR);
                throw e;
			} catch (Exception e) {
				Core.logger.log(LocalNIOInterface.this, "Unhandled Exception while handling connection", e, Logger.ERROR);
				e.printStackTrace(Core.logStream);
				conn.close();
			}
		}
    }
    
    final int MAX_QUEUED_CONNECTIONS = 128;
    
    LinkedList oldConnections = new LinkedList();
    volatile boolean acceptingConnections = true;
    
    int runningConnections = 0; // number of connections running
    
    protected class ConnectionShell implements Runnable {
        protected final Connection conn;
	
		boolean uppedRC = false;
	
        protected ConnectionShell(Connection conn) {
            this.conn = conn;
			synchronized(LocalNIOInterface.this) {
				uppedRC = true;
				runningConnections++;
			}
            if((highRunningConnections > 0) && (runningConnections >
												highRunningConnections) &&
			   isListening()) {
				Core.logger.log(this, "Stopping processing connections "+
								this, Logger.MINOR);
				acceptingConnections = false;
			}
			if(logDEBUG)
				Core.logger.log(this, "RunningConnections now "+
								runningConnections+", listening = "+
								isListening(), Logger.DEBUG);
        }
	
		protected void finalize() {
			decrementRunningConnections();
			// Successful return from handle() means it will eventually be closed by handler
		}
	
        /**  Main processing method for the ConnectionShell object */
        public void run() {
            logDEBUG = Core.logger.shouldLog(Logger.DEBUG, LocalNIOInterface.class);
            try {
                if(logDEBUG)
                    Core.logger.log(this, "Handling conn "+conn+" on "+this,
                            Logger.DEBUG);
                runner.handle(conn);
                if(logDEBUG)
                    Core.logger.log(this, "Successfully handled conn "+conn+" on "+this,
                            Logger.DEBUG);
            } catch (RuntimeException t) {
                Core.logger.log(LocalNIOInterface.this,
                                "Unhandled throwable while handling connection",
                                t, Logger.ERROR);
                conn.close();
                throw t;
            } catch (Error e) {
                conn.close();
                throw e;
			} catch (Exception e) {
				Core.logger.log(LocalNIOInterface.this, "Unhandled Exception while handling connection", e, Logger.ERROR);
				e.printStackTrace(Core.logStream);
				conn.close();
            } finally {
				decrementRunningConnections();
			}
        }
	
		protected void decrementRunningConnections() {
			synchronized(LocalNIOInterface.this) {
				if(uppedRC == true) {
					runningConnections--;
					uppedRC = false;
					if(logDEBUG)
						Core.logger.log(this, "RunningConnections now "+
										runningConnections+", listening = "+
										isListening(), Logger.DEBUG);
					if(runningConnections < lowRunningConnections 
					   && !acceptingConnections) {
						Core.logger.log(this, "Restarting processing connections "+
										this, Logger.MINOR);
						acceptingConnections = true;
					} else return;
				} else return;
			}
			handleConnection(null);
		}
    }
}
