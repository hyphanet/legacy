package freenet.interfaces;

import java.net.InetAddress;
import java.net.UnknownHostException;

import freenet.Address;
import freenet.Connection;
import freenet.Core;
import freenet.ListeningAddress;
import freenet.config.Params;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.thread.ThreadFactory;
import freenet.transport.TCP;
import freenet.transport.tcpAddress;

/**
 * A LocalInterface is connected to by clients and can do allowed hosts
 * checking.
 */
public class LocalInterface extends Interface {

	protected ThreadFactory tf;
	protected ConnectionRunner runner;
	protected int[][] allowedHosts;

	private static final int intAddress(String addr) {
		try {
			return intAddress(InetAddress.getByName(addr));
		} catch (UnknownHostException e) {
			return 0;
		}
	}

	private static final int intAddress(InetAddress addr) {
		boolean logDEBUG =
			Core.logger.shouldLog(Logger.DEBUG, LocalNIOInterface.class);
		if (logDEBUG)
			Core.logger.log(
				LocalInterface.class,
				"intAddress(" + addr.toString() + ")",
				Logger.DEBUG);
		byte[] b = addr.getAddress();
		if (logDEBUG)
			Core.logger.log(
				LocalInterface.class,
				"Address: " + addr.getHostAddress() + " (" + b.length + ")",
				Logger.DEBUG);
		long x =
			((((long) b[0]) & 0xff) << 24)
				+ ((b[1] & 0xff) << 16)
				+ ((b[2] & 0xff) << 8)
				+ (b[3] & 0xff);
		if (logDEBUG)
			Core.logger.log(
				LocalInterface.class,
				"Returning " + Long.toHexString(x),
				Logger.DEBUG);
		return (int) x;
	}

	private static final int mask(int addr, int maskbits) {
		int mbits = 32 - maskbits;
		int power;
		if (mbits < 32)
			power = 1 << mbits;
		else
			power = 0; // 1 << 32 = 1 !! - check before you "fix" this
		int ones = power - 1;
		int mask = ~ones;
		//int mask = ~((1 << (32-maskbits)) -1);
		int out = addr & mask;
		return out;
	}

	/**
	 * Builds a LocalInterface from a Params using the entries: port,
	 * allowedHosts, bindAddress
	 * 
	 * @param fs
	 * @param tf
	 * @param runner
	 * @return Description of the Return Value
	 * @throws InterfaceException
	 *             if the Params was unusable
	 * 
	 * TODO - transport independence
	 */
	public static LocalInterface make(
		Params fs,
		ThreadFactory tf,
		ConnectionRunner runner,
		boolean dontThrottle,
		int lowRunningConnections,
		int highRunningConnections)
		throws InterfaceException {

		int port = fs.getInt("port");
		if (port == 0)
			throw new InterfaceException("No port given for interface");

		String allowedHosts = fs.getString("allowedHosts");
		if (allowedHosts != null && allowedHosts.trim().length() == 0) {
			allowedHosts = null;
		}

		String bindAddress = fs.getString("bindAddress");
		if (bindAddress == null || bindAddress.trim().length() == 0) {
			// If no bindAddress was specified, we bind to loopback
			// only unless allowedHosts was specified, in which case
			// we bind to all addresses.
			bindAddress = (allowedHosts == null ? "127.0.0.1" : null);
		}

		TCP tcp;
		try {
			// "*" allows all connections. e.g. for HTTP gateways.
			if (bindAddress == null || bindAddress.trim().equals("*")) {
				tcp = new TCP(1, false);
			} else {
				tcp =
					new TCP(
						InetAddress.getByName(bindAddress.trim()),
						1,
						false);
			}
		} catch (Exception e) {
			InterfaceException ie = new InterfaceException("" + e);
			ie.initCause(e);
			throw ie;
		}

			return new LocalInterface(
				tcp.getListeningAddress(port, dontThrottle),
				tf,
				runner,
				allowedHosts,
				lowRunningConnections,
				highRunningConnections);

	}

	private int runningConnections = 0; // number of connections running
	private int lowRunningConnections; // reenable interface when go below this
	private int highRunningConnections; // disable interface when go above this

	/**
	 * @param runner
	 *            handles the connection thread
	 * @param listenAddr
	 * @param tf
	 */
	public LocalInterface(
		ListeningAddress listenAddr,
		ThreadFactory tf,
		ConnectionRunner runner,
		int lowRunningConnections,
		int highRunningConnections) {
		this(listenAddr, tf, runner, new int[][] { { 0 }, {
				0 }
		}, lowRunningConnections, highRunningConnections);
	}

	/**
	 * @param allowedHosts
	 *            set of Addresses to do an equalsHost() check with; null means
	 *            to allow all hosts
	 * @param listenAddr
	 * @param tf
	 * @param runner
	 */
	public LocalInterface(
		ListeningAddress listenAddr,
		ThreadFactory tf,
		ConnectionRunner runner,
		int[][] allowedHosts,
		int lowRunningConnections,
		int highRunningConnections) {
		super(listenAddr);
		this.tf = tf;
		this.runner = runner;
		this.allowedHosts = allowedHosts;
		this.lowRunningConnections = lowRunningConnections;
		this.highRunningConnections = highRunningConnections;
	}

	/**
	 * @param allowedHosts
	 *            set of Addresses to do an equalsHost() check with; null means
	 *            to allow all hosts
	 * @param listenAddr
	 * @param tf
	 * @param runner
	 */
	public LocalInterface(
		ListeningAddress listenAddr,
		ThreadFactory tf,
		ConnectionRunner runner,
		String allowedHosts,
		int lowRunningConnections,
		int highRunningConnections) {
		super(listenAddr);
		int[][] allowedHostsAddr = null;

		if (allowedHosts == null || allowedHosts.trim().length() == 0 ) {
			allowedHosts = "127.0.0.0/8";
		}

		if (allowedHosts.trim().equals("*")) {
			allowedHosts = "0.0.0.0/0";
		}

		String[] hosts = Fields.commaList(allowedHosts);

		allowedHostsAddr = new int[hosts.length][2];

		for (int i = 0; i < hosts.length; ++i) {
			int host, subnet, div = hosts[i].indexOf('/');
			if (div == -1) {
				subnet = 32;
				host = intAddress(hosts[i]);
			} else {
				subnet = Integer.parseInt(hosts[i].substring(div + 1));
				host = intAddress(hosts[i].substring(0, div));
			}
			allowedHostsAddr[i][0] = mask(host, subnet);
			allowedHostsAddr[i][1] = subnet;
		}
		this.tf = tf;
		this.runner = runner;
		this.allowedHosts = allowedHostsAddr;
		this.lowRunningConnections = lowRunningConnections;
		this.highRunningConnections = highRunningConnections;
	}

	/**
	 * @param conn
	 * @exception RejectedConnectionException
	 */
	protected void dispatch(Connection conn)
		throws RejectedConnectionException {
		boolean allow = false;
		Address ha = conn.getPeerAddress();
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"Dispatching connection on a LocalInterface from "
					+ ha.toString(),
				Logger.DEBUG);
		//insert some code to make sure ha is a tcpAddress and
		//handle things correctly when it's not. --thelema
		int inta;
		try {
			inta = intAddress(((tcpAddress) ha).getHost());
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

		if (allow) {
			Thread t = tf.getThread(new ConnectionShell(conn));
			if (logDEBUG) {
				String tname = "";
				if (t != null)
					tname = t.toString();
				Core.logger.log(
					this,
					"Allocated thread for local connection: " + tname,
					Logger.DEBUG);
			}
		} else {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Rejecting local connection",
					Logger.DEBUG);
			throw new RejectedConnectionException("host not allowed: " + ha);
		}
	}

	protected void starting() {
		runner.starting();
	}

	protected class ConnectionShell implements Runnable {
		protected final Connection conn;

		boolean uppedRC = false;

		protected ConnectionShell(Connection conn) {
			this.conn = conn;
			synchronized (this) {
				uppedRC = true;
				runningConnections++;
			}
			if ((highRunningConnections > 0)
				&& (runningConnections > highRunningConnections)
				&& isListening())
				listen(false);
			if (Core.logger.shouldLog(Logger.DEBUG, this))
				Core.logger.log(
					this,
					"RunningConnections now "
						+ runningConnections
						+ ", listening = "
						+ isListening(),
					Logger.DEBUG);
		}

		protected void finalize() {
			synchronized (this) {
				if (uppedRC == true) {
					runningConnections--;
					uppedRC = false;
				}
			}
		}

		/** Main processing method for the ConnectionShell object */
		public void run() {
			try {
				runner.handle(conn);
			} catch (RuntimeException t) {
				Core.logger.log(
					LocalInterface.this,
					"Unhandled throwable while handling connection",
					t,
					Logger.ERROR);
				conn.close();
				throw t;
			} catch (Error e) {
				conn.close();
				throw e;
			} catch (Exception e) {
				Core.logger.log(
					LocalInterface.this,
					"Unhandled Exception while handling connection",
					e,
					Logger.ERROR);
				e.printStackTrace(Core.logStream);
				conn.close();
			} finally {
				synchronized (this) {
					runningConnections--;
					uppedRC = false;
				}
				if (runningConnections < lowRunningConnections
					&& !isListening()) {
					listen(true);
				}
				if (Core.logger.shouldLog(Logger.DEBUG, this))
					Core.logger.log(
						this,
						"RunningConnections now "
							+ runningConnections
							+ ", listening = "
							+ isListening(),
						Logger.DEBUG);
			}
		}
	}
}
