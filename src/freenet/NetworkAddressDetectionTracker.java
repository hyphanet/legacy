package freenet;

import java.net.Inet4Address;
import java.util.Enumeration;
import java.util.Hashtable;

import freenet.support.Logger;
import freenet.transport.tcpTransport;

/**
 * Class to track detected IP addresses sent back by peer nodes to us.
 */
public class NetworkAddressDetectionTracker {

    public NetworkAddressDetectionTracker() {    }

    private final Hashtable addresses = new Hashtable();
    
    class AddressItem {
        int counter;
        AddressItem() {
            counter = 1;
        }
    }
    
    /**
     * Notify that an address has been detected.
     * @param ip4addr address that was detected.
     */
    public synchronized void detected(Inet4Address ip4addr) {
        Core.logger.log(this, "Detected "+ip4addr, Logger.MINOR);
        Object o = addresses.get(ip4addr);
        if(o == null) {
            // New item
            addresses.put(ip4addr, new AddressItem());
        } else {
            ((AddressItem)o).counter++;
        }
    }

    /**
     * Notify that an address is no longer detected.
     * @param ip4addr the address.
     */
    public synchronized void undetected(Inet4Address ip4addr) {
        Core.logger.log(this, "Undetected: "+ip4addr, Logger.MINOR);
        Object o = addresses.get(ip4addr);
        if(o == null) {
            Core.logger.log(this, "Undetected address that wasn't detect in the first place: "+
                    ip4addr, new Exception("debug"), Logger.ERROR);
        } else {
            AddressItem i = (AddressItem)o;
            i.counter--;
            if(i.counter == 0)
                addresses.remove(ip4addr);
        }
    }

    public synchronized String toHTMLString() {
        if(addresses.size() == 0) return "";
        StringBuffer sb = new StringBuffer(200);
        sb.append("<table border=\"0\" width=\"100%\">\n");
        for(Enumeration e = addresses.keys(); e.hasMoreElements();) {
            Inet4Address addr = (Inet4Address)e.nextElement();
            sb.append("<tr><td align=\"left\">IPv4: ");
            sb.append(addr.getHostAddress());
            sb.append("</td><td align=\"right\">");
            sb.append(((AddressItem)addresses.get(addr)).counter);
            sb.append("</td></tr>");
        }
        sb.append("</table>");
        return new String(sb);
    }

    /**
     * @param valid if true, only consider address that tcpTransport
     * considers to be valid for external node addresses. 
     * @return the most popular detected address, or null if 
     * there are no detected addresses.
     */
    public Inet4Address topAddress(boolean valid) {
        int maxCount = 0;
        Inet4Address bestAddr = null;
        synchronized(this) {
            for(Enumeration e = addresses.keys(); e.hasMoreElements();) {
                Inet4Address addr = (Inet4Address) e.nextElement();
                if(valid) {
                    // Skip any invalid addresses
                    if(!tcpTransport.checkAddress(addr)) continue;
                }
                AddressItem item = (AddressItem) addresses.get(addr);
                int count = item.counter;
                if(count > maxCount) {
                    maxCount = count;
                    bestAddr = addr;
                }
            }
        }
        return bestAddr;
    }
}
