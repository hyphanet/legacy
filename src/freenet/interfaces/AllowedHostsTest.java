/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package freenet.interfaces;

import java.net.InetAddress;

import junit.framework.TestCase;


/**
 * @author David Roden <dr@todesbaum.dyndns.org>
 * @version $Id: AllowedHostsTest.java,v 1.1.2.1 2005/01/28 22:24:01 amphibian Exp $
 */
public class AllowedHostsTest extends TestCase {

	public void testAllowedHosts() throws Exception {
		AllowedHosts allowedHosts = new AllowedHosts("172.16.0.0/16, 127.0.0.1/8, 185.218.36.159");
		
		assertNotNull(allowedHosts);
		assertEquals(allowedHosts.size(), 3);
		
		assertEquals(allowedHosts.match(InetAddress.getByName("127.0.0.1")), true);
		
		assertEquals(allowedHosts.match(InetAddress.getByName("172.16.0.1")), true);
		assertEquals(allowedHosts.match(InetAddress.getByName("172.16.42.23")), true);

		assertEquals(allowedHosts.match(InetAddress.getByName("185.218.36.159")), true);
		
		assertEquals(allowedHosts.match(InetAddress.getByName("172.17.42.23")), false);
		assertEquals(allowedHosts.match(InetAddress.getByName("192.16.1.14")), false);
		assertEquals(allowedHosts.match(InetAddress.getByName("212.84.222.3")), false);
		assertEquals(allowedHosts.match(InetAddress.getByName("185.218.36.158")), false);
		assertEquals(allowedHosts.match(InetAddress.getByName("185.218.36.160")), false);
		assertEquals(allowedHosts.match(InetAddress.getByName("57.218.36.159")), false);
		
		assertEquals(allowedHosts.toString(), "172.16.0.0/16, 127.0.0.1/8, 185.218.36.159");
	}
	
	public void testEmptyAllowedHosts() throws Exception {
		AllowedHosts allowedHosts = new AllowedHosts("*");
		assertNotNull(allowedHosts);
		assertEquals(1, allowedHosts.size());
		assertEquals(true, allowedHosts.match(InetAddress.getByName("185.218.36.158")));
		assertEquals(true, allowedHosts.match(InetAddress.getByName("57.218.36.158")));
		assertEquals(true, allowedHosts.match(InetAddress.getByName("127.0.0.1")));
	}

}
