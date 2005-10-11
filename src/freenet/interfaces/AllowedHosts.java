/*
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.interfaces;

import java.net.InetAddress;
import java.net.UnknownHostException;

import freenet.Address;
import freenet.Core;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.transport.tcpAddress;

/**
 * Container class that holds a list of allowed IP address (with or without
 * subnet masks) and/or host names and that can match an incoming address
 * against those allowed hosts.
 * 
 * @author David Roden &lt;dr@todesbaum.dyndns.org&gt;, original code most
 *              probably by Matthew 'toad' Toseland &lt;toad@amphibian.dyndns.org&gt;
 * @version $Id: AllowedHosts.java,v 1.1 2005/01/08 17:50:06 droden Exp $
 * @since 2005-01-07
 */
public class AllowedHosts {

	/** The address parts */
	private int[][] addresses;

	/**
	 * Creates a new AllowedHosts object that allows the hosts listed in
	 * <code>allowedHosts</code>.
	 * 
	 * @param allowedHosts
	 *                  A comma-separated list of hosts that are allowed
	 */
	public AllowedHosts(String allowedHosts) {
		this(allowedHosts, "127.0.0.1/8");
	}

	/**
	 * Creates a new AllowedHosts object that allows the hosts listed in
	 * <code>allowedHosts</code>. If <code>allowedHosts</code> is an empty
	 * string or <code>null</code> the hosts in <code>emptyDefault</code>
	 * will be allowed.
	 * 
	 * @param allowedHosts
	 *                  A comma-separated list of hosts that are allowed
	 * @param emptyDefault
	 *                  The hosts to allow when <code>allowedHosts</code> is an
	 *                  empty string or <code>null</code>.
	 */
	public AllowedHosts(String allowedHosts, String emptyDefault) {
		if (allowedHosts == null) {
			allowedHosts = emptyDefault;
		}

		allowedHosts = allowedHosts.trim();

		if (allowedHosts.length() == 0) {
			allowedHosts = emptyDefault.trim();
		}

		if (allowedHosts.equals("*")) {
			allowedHosts = "0.0.0.0/0";
		}

		String[] hosts = Fields.commaList(allowedHosts);
		addresses = new int[hosts.length][2];

		for (int hostIndex = 0, hostSize = hosts.length; hostIndex < hostSize; hostIndex++) {
			HostAndSubnetPair pair = parseHostOrNetString(hosts[hostIndex]);
			addresses[hostIndex][0] = pair.getHost();
			addresses[hostIndex][1] = pair.getSubnet();
		}
	}

	/**
	 * Creates a new AllowedHosts object with the given hosts.
	 * 
	 * @param allowedHosts
	 *                  The hosts to allow in the internal representation of this
	 *                  class (which is subject to change without notice!)
	 * @deprecated Use one of {@link #AllowedHosts(String)}or
	 *                     {@link #AllowedHosts(String, String)}
	 */
	public AllowedHosts(int[][] allowedHosts) {
		/* if the array is wrong this will break everything! */
		addresses = allowedHosts;
	}

	/**
	 * Parses a string into an IP address and an (optional) subnet-mask. A
	 * missing subnet-mask is interpreted as "/32", meaning "this host only".
	 * 
	 * @param hostOrNetString
	 *                  The string containing the host/network specification
	 * @return a pair of host and subnet
	 */
	private HostAndSubnetPair parseHostOrNetString(String hostOrNetString) {
		int host;
		int subnet = 32;
		int div = hostOrNetString.indexOf('/');
		if (div == -1) {
			/*
			 * Consider the absence of a subnetmask as 255.255.255.255 (=only
			 * the exact host specified)
			 */
			host = intAddress(hostOrNetString);
		} else {
			subnet = Integer.parseInt(hostOrNetString.substring(div + 1));
			host = intAddress(hostOrNetString.substring(0, div));
		}
		return new HostAndSubnetPair(host, subnet);
	}

	/**
	 * Converts the IP address or host name in <code>addr</code> to an
	 * <code>int</code> value. Naturally, this will only work with IPv4.
	 * 
	 * @param addr
	 *                  The address or host name to convert
	 * @return the IP address as a 32-bit integer
	 */
	private int intAddress(String addr) {
		try {
			return intAddress(InetAddress.getByName(addr));
		} catch (java.net.UnknownHostException e) {
			return 0;
		}
	}

	/**
	 * Converts the InetAddress to an <code>int</code> value. Naturally, this
	 * will only work with IPv4.
	 * 
	 * @param addr
	 *                  The InetAddress to convert
	 * @return the IP address as a 32-bit integer
	 */
	private int intAddress(InetAddress addr) {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, AllowedHosts.class);
		if (logDEBUG) {
			Core.logger.log(AllowedHosts.class, "intAddress(" + addr.toString() + ")", Logger.DEBUG);
		}
		byte[] b = addr.getAddress();
		if (logDEBUG) {
			Core.logger.log(AllowedHosts.class, "Address: " + (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + "." + (b[3] & 0xff) + " (" + b.length + ")", Logger.DEBUG);
		}
		long x = ((((long) b[0]) & 0xff) << 24) + ((b[1] & 0xff) << 16) + ((b[2] & 0xff) << 8) + (b[3] & 0xff);
		if (logDEBUG) {
			Core.logger.log(AllowedHosts.class, "Returning " + Long.toHexString(x), Logger.DEBUG);
		}
		return (int) x;
	}

	/**
	 * Applies the subnet mask to the IP address, setting the lower bits to
	 * <code>0</code>.
	 * 
	 * @param addr
	 *                  The IP address to mask
	 * @param maskbits
	 *                  The number of bits from the lower end to set to <code>0</code>
	 * @return the masked IP address
	 */
	private int mask(int addr, int maskbits) {
		if (maskbits == 0) {
			return 0;
		}
		return addr & (-1 << (32 - maskbits));
	}

	/**
	 * Checks whether <code>address</code> matches any of the allowed hosts in
	 * this object.
	 * 
	 * @param address
	 *                  The address to check
	 * @return <code>true</code> if <code>address</code> is considered to be
	 *             an allowed host, <code>false</code> otherwise
	 * @throws RejectedConnectionException
	 *                  when <code>address</code> is not a TCP address
	 */
	public boolean match(Address address) throws RejectedConnectionException {
		try {
			return match(((tcpAddress) address).getHost());
		} catch (UnknownHostException uhe1) {
			throw new RejectedConnectionException("unknown host on incoming connection!");
		}
	}

	/**
	 * Checks whether <code>address</code> matches any of the allowed hosts in
	 * this object.
	 * 
	 * @param address
	 *                  The address to check
	 * @return <code>true</code> if <code>address</code> is considered to be
	 *             an allowed host, <code>false</code> if it is not or if
	 *             <code>address</code> contains a hostname or FQDN that can not
	 *             be resolved.
	 */
	public boolean match(String address) {
		try {
			return match(InetAddress.getByName(address));
		} catch (UnknownHostException uhe1) {
		}
		return false;
	}

	/**
	 * Checks whether <code>address</code> matches any of the allowed hosts in
	 * this object.
	 * 
	 * @param address
	 *                  The address to check
	 * @return <code>true</code> if <code>address</code> is considered to be
	 *             an allowed host, <code>false</code> otherwise
	 */
	public boolean match(InetAddress inetAddress) {
		int address = intAddress(inetAddress);
		for (int addressIndex = 0, addressSize = addresses.length; addressIndex < addressSize; addressIndex++) {
			int subnet = addresses[addressIndex][1];
			if (mask(address, subnet) == mask(addresses[addressIndex][0], subnet)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the number of allowed hosts in this object.
	 * 
	 * @return The number of allowed hosts in this object
	 */
	public int size() {
		return addresses.length;
	}

	/**
	 * Returns a textual representation of this object. The output is in a form
	 * that could be used to construct another <code>AllowedHosts</code>
	 * object.
	 * 
	 * @return All allowed hosts and/or networks in this object
	 */
	public String toString() {
		StringBuffer result = new StringBuffer();
		for (int addressIndex = 0, addressSize = addresses.length; addressIndex < addressSize; addressIndex++) {
			int address = addresses[addressIndex][0];
			if (result.length() > 0) {
				result.append(", ");
			}
			result.append((address >> 24) & 0xff).append(".");
			result.append((address >> 16) & 0xff).append(".");
			result.append((address >> 8) & 0xff).append(".");
			result.append(address & 0xff);
			if (addresses[addressIndex][1] != 32) {
				result.append("/").append(addresses[addressIndex][1]);
			}
		}
		return result.toString();
	}

	/**
	 * A container class for a pair consisting of an IP address and a subnet
	 * specification.
	 * 
	 * @author David Roden &lt;dr@todesbaum.dyndns.org&gt;, original code most
	 *              probably by Matthew 'toad' Toseland
	 *              &lt;toad@amphibian.dyndns.org&gt;
	 * @version $Id: AllowedHosts.java,v 1.1 2005/01/08 17:50:06 droden Exp $
	 */
	private static class HostAndSubnetPair {

		/** The IP address */
		private int host;

		/** The subnet mask */
		private int subnet;

		/**
		 * Constructs a new pair of host and subnet mask.
		 * 
		 * @param host
		 *                  The host of the pair
		 * @param subnet
		 *                  The subnet mask of the pair
		 */
		public HostAndSubnetPair(int host, int subnet) {
			this.host = host;
			this.subnet = subnet;
		}

		/**
		 * Returns the host.
		 * 
		 * @return the host
		 */
		public int getHost() {
			return host;
		}

		/**
		 * Returns the subnet.
		 * 
		 * @return the subnet
		 */
		public int getSubnet() {
			return subnet;
		}

	}

}
