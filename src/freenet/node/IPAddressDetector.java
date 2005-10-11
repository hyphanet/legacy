/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.node;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Vector;

import freenet.Core;
import freenet.support.Checkpointed;
import freenet.support.Logger;

/**
 * A class to autodetect our IP address(es)
 */

class IPAddressDetector implements Checkpointed {
	//private String preferedAddressString = null;
	private final int interval;
	public IPAddressDetector(int interval) {
		this.interval = interval;
	}

	/**
	 * @return our name
	 */
	public String getCheckpointName() {
		return "Autodetection of IP addresses";
	}

	/** 
	 * @return next scheduling point
	 */
	public long nextCheckpoint() {
		return System.currentTimeMillis() + interval; // We are pretty cheap
	}

	InetAddress lastInetAddress = null;
	InetAddress[] lastAddressList = null;
	long lastDetectedTime = -1;

	/** 
	 * Fetches the currently detected IP address. If not detected yet a detection is forced
	 * @param preferedAddress An address that for some reason is prefered above others. Might be null
	 * @return Detected ip address
	 */
	public InetAddress getAddress(String preferedAddress) {
		return getAddress(0, preferedAddress);
	}

	public InetAddress getAddress(InetAddress preferredAddress) {
	    checkpoint(preferredAddress);
	    return lastInetAddress;
	}
	
	/** 
	 * Fetches the currently detected IP address. If not detected yet a detection is forced
	 * @return Detected ip address
	 */
	public InetAddress getAddress() {
		return getAddress(0, null);
	}

	/**
	 * Get the IP address
	 * @param preferedAddress An address that for some reason is prefered above others. Might be null
	 * @return Detected ip address
	 */
	public InetAddress getAddress(long recheckTime, String preferedAddress) {
		if (lastInetAddress == null
			|| System.currentTimeMillis() > (lastDetectedTime + recheckTime))
			checkpoint(preferedAddress);
		return lastInetAddress;
	}

	/**
	 * Get the IP address
	 * @return Detected ip address
	 */
	public InetAddress getAddress(long recheckTime) {
		return getAddress(recheckTime, null);
	}

	public void checkpoint() {
		checkpoint((InetAddress)null);
	}

	boolean old = false;

	protected synchronized void checkpoint(String preferredAddress) {
	    InetAddress preferredInetAddress = null;
		try {
			preferredInetAddress = InetAddress.getByName(preferredAddress);
			//It there was something preferred then convert it to a proper class
		} catch (UnknownHostException e) {
		}
		checkpoint(preferredInetAddress);
	}
	
	/**
	 * Execute a checkpoint - detect our internet IP address and log it
	 * @param preferedAddress An address that for some reason is prefered above others. Might be null
	 */
	protected synchronized void checkpoint(InetAddress preferedInetAddress) {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		Vector addrs = new Vector();

		Enumeration interfaces = null;
		try {
			interfaces = java.net.NetworkInterface.getNetworkInterfaces();
		} catch (NoClassDefFoundError e) {
			addrs.add(oldDetect());
			old = true;
		} catch (SocketException e) {
			Core.logger.log(
				this,
				"SocketException trying to detect NetworkInterfaces",
				e,
				Logger.ERROR);
			addrs.add(oldDetect());
			old = true;
		}

		if (!old) {
			while (interfaces.hasMoreElements()) {
				java.net.NetworkInterface iface =
					(java.net.NetworkInterface) (interfaces.nextElement());
				if (logDEBUG)
					Core.logger.log(
						this,
						"Scanning NetworkInterface " + iface.getDisplayName(),
						Logger.DEBUG);
				Enumeration ee = iface.getInetAddresses();
				while (ee.hasMoreElements()) {

					InetAddress addr = (InetAddress) (ee.nextElement());
					addrs.add(addr);
					if (logDEBUG)
						Core.logger.log(
							this,
							"Adding address "
								+ addr
								+ " from "
								+ iface.getDisplayName(),
							Logger.DEBUG);
				}
				if (logDEBUG)
					Core.logger.log(
						this,
						"Finished scanning interface " + iface.getDisplayName(),
						Logger.DEBUG);
			}
			if (logDEBUG)
				Core.logger.log(
					this,
					"Finished scanning interfaces",
					Logger.DEBUG);
		}

		if (preferedInetAddress == null
			&& lastInetAddress != null
				? isInternetAddress(lastInetAddress)
				: true) //If no specific other address is preferred then we prefer to keep our old address
			preferedInetAddress = lastInetAddress;

		InetAddress oldAddress = lastInetAddress;
		onGetAddresses(addrs, preferedInetAddress);
		lastDetectedTime = System.currentTimeMillis();
		if (oldAddress != null && lastInetAddress != null && 
		        !lastInetAddress.equals(oldAddress)) {
			Core.logger.log(
				this,
				"Public IP Address changed from "
					+ oldAddress.getHostAddress()
					+ " to "
					+ lastInetAddress.getHostAddress(),
				Logger.MINOR);
			Main.redetectAddress();
			// We know it changed
		}
	}

	protected InetAddress oldDetect() {
		boolean shouldLog = Core.logger.shouldLog(Logger.DEBUG, this);
		if (shouldLog)
			Core.logger.log(
				this,
				"Running old style detection code",
				Logger.DEBUG);
		DatagramSocket ds = null;
		try {
			try {
				ds = new DatagramSocket();
			} catch (SocketException e) {
				Core.logger.log(this, "SocketException", e, Logger.ERROR);
				return null;
			}

			// This does not transfer any data
			// The ip is a.root-servers.net, 42 is DNS
			try {
				ds.connect(InetAddress.getByName("198.41.0.4"), 42);
			} catch (UnknownHostException ex) {
				Core.logger.log(this, "UnknownHostException", ex, Logger.ERROR);
				return null;
			}
			return ds.getLocalAddress();
		} finally {
			if (ds != null) {
				ds.close();
			}
		}
	}

	/** Do something with the list of detected IP addresses.
	 * @param v Vector of InetAddresses
	 * @param preferedInetAddress An address that for some reason is prefered above others. Might be null
	 */
	protected void onGetAddresses(Vector v, InetAddress preferedInetAddress) {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (logDEBUG)
			Core.logger.log(
				this,
				"onGetAddresses found " + v.size() + " potential addresses)",
				Logger.DEBUG);
		boolean detectedInetAddress = false;
		InetAddress addrDetected = null;
		if (v.size() == 0) {
			Core.logger.log(this, "No addresses found!", Logger.ERROR);
			addrDetected = null;
		} else {
//			InetAddress lastNonValidAddress = null;
			for (int x = 0; x < v.size(); x++) {
				if (v.elementAt(x) != null) {
					InetAddress i = (InetAddress) (v.elementAt(x));
					if (logDEBUG)
						Core.logger.log(
							this,
							"Address " + x + ": " + i,
							Logger.DEBUG);
					if (isInternetAddress(i)) {
						//Do not even consider this address if it isn't globally addressable
						if (logDEBUG)
							Core.logger.log(
								this,
								"Setting default address to "
									+ i.getHostAddress(),
								Logger.DEBUG);

						addrDetected = i;
						//Use the last detected valid IP as 'detected' IP
						detectedInetAddress = true;
						if (preferedInetAddress != null
							&& addrDetected.equals(
								preferedInetAddress)) { //Prefer the specified address if it is still available to us. Do not look for more ones
							if (logDEBUG)
								Core.logger.log(
									this,
									"Detected address is the preferred address, setting final address to "
										+ lastInetAddress.getHostAddress(),
									Logger.DEBUG);
							lastInetAddress = addrDetected;
							return;
						}

					}// else
//						lastNonValidAddress = i;
				}
			}
			//If we are here we didn't manage to find a valid globally addressable IP. Do the best of the situation, return the last valid non-addressable IP
			//This address will be used by the node if the user has configured localIsOK.
//			if (lastInetAddress == null || (!detectedInetAddress))
//				lastInetAddress = lastNonValidAddress;
		}
		lastInetAddress = addrDetected;
		// FIXME: add support for multihoming
	}

	protected boolean isInternetAddress(InetAddress addr) {
		return freenet.transport.tcpTransport.checkAddress(addr);
	}
}
