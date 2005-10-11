package freenet.transport;

import java.net.InetAddress;
import java.util.StringTokenizer;

import freenet.Address;
import freenet.BadAddressException;
import freenet.Core;
import freenet.ListeningAddress;
import freenet.Transport;
import freenet.support.Logger;

/** A tcpTransport is any Transport based on Sockets.  Subclasses implementing
  * this provide their own socket factories so that there can be a choice
  * between plain TCP, SSL, etc.
  */
abstract public class tcpTransport implements Transport {

    private final boolean strict;

    private final InetAddress bindAddr;
    private final int preference;

    /** Starts a TCP transport object.
      */
    tcpTransport(int preference, boolean strict) {
        this(null, preference, strict);
    }

    /** Starts a TCP transport object that only listens on one local 
      * interface.
      * @param  strict     Wether to strictly enforce addresses as only
      *                    those common on the general internet.
      */
    tcpTransport(InetAddress bindAddr, /*int designator,*/ int preference,
                 boolean strict) {

        this.bindAddr   = bindAddr;
        this.preference = preference;
        this.strict = strict;
    }

    public final int preference() {
        return preference;
    }

    /**
     * Is this a valid address? Specifically, return false if it
     * is in an RFC3330 reserved space.
     */
    public static boolean checkAddress(int[] i) {
	// ip address (IPV6 is not supported by this transport)
	boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,tcpTransport.class);
	if(logDEBUG)
	    Core.logger.log(tcpTransport.class, "Checking "+i[0]+"."+i[1]+"."+i[2]+
			    "."+i[3], Logger.DEBUG);
	if (i.length != 4)
	    return false;
	
	for (int j = 0 ; j < 4 ; j++)
	    if (i[j] < 0 || i[j] > 255)
		return false;
	
	if (i[0] == 10 || (i[0] == 172 && i[1] >= 16 && i[1] < 31) 
	    || (i[0] == 192 && i[1] == 168)) // local network
	    return false;
	
	if (i[0] == 169 && i[1] == 254) 
		return false; // link local
	
	if (i[0] == 198 && (i[1] == 18 || i[1] == 19))
		return false; // RFC2544
	
	if (i[0] == 192 && i[1] == 0 && i[2] == 2)
		return false; // test-net, see RFC3330
	
	if (i[0] == 127) // loopback
	    return false;
	
	if (i[0] == 0) // "this" net
	    return false;
	
	if (i[0] >= 224 && i[0] < 240)
		return false; // multicast
	
	return true;
    }
    
    public static boolean checkAddress(InetAddress addr) {
    	if(Core.logger.shouldLog(Logger.DEBUG,tcpTransport.class)) Core.logger.log(tcpTransport.class, "Checking "+addr, Logger.DEBUG);
	int[] i = new int[4];
	byte[] bytes = addr.getAddress();
	if(bytes.length != 4) {
	    return false;
	}
	for(int x=0;x<4;x++) {
	    byte b = bytes[x];
	    int ii = b;
	    if(ii < 0) ii += 256;
	    i[x] = ii;
	}
	return checkAddress(i);
    }
    
	public static boolean checkAddress(byte[] b) {
		int[] i = new int[4];
		for(int x=0;x<4;x++) i[x] = b[x] & 0xff;
		return checkAddress(i);
	}

    public boolean checkAddress(String s) {
	return checkAddress(s, false);
    }
    
    public boolean checkAddress(String s, boolean noPort) {
	boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG,this);
	if(logDEBUG)
	    Core.logger.log(this, "Checking "+s, Logger.DEBUG);
	String a = s;
	if(!noPort) {
	    StringTokenizer st = new StringTokenizer(s, ":");
	    if (st.countTokens() != 2) 
		return false;
	    
	    a = st.nextToken();
	    try {
		int p = Integer.parseInt(st.nextToken());
		if (p < 0 || p >= (1 << 16))
		    return false;
	    } catch (NumberFormatException e) {
		return false;
	    }
	}
        if (!strict)
            return true;
	
        // strict check
	if(logDEBUG)
	    Core.logger.log(this, "Strict check", Logger.DEBUG);

        StringTokenizer at = new StringTokenizer(a, ".");
        int n = at.countTokens();

        try {
            int[] i = new int[4];
            for (int j = 0 ; j < 4 ; j++) {
                if (!at.hasMoreTokens()) {
		    if(logDEBUG)
			Core.logger.log(this, "Only "+j+" tokens.",
					Logger.DEBUG);
                    return false;
		}
		String tok = at.nextToken();
		if(logDEBUG)
		    Core.logger.log(this, "Trying to parseInt: "+tok,
				    Logger.DEBUG);
                i[j] = Integer.parseInt(tok);
            }
	    return checkAddress(i);
        } catch (NumberFormatException e) {
            // dns address
            if (n < 2) {
		Core.logger.log(this, a+": Not a DNS address, too short!",
				Logger.MINOR);
                return false;
	    }
	    
	    if(logDEBUG)
		Core.logger.log(this, "Apparently valid DNS address: "+a,
				Logger.DEBUG);
	    return true;
            // maybe we should actually look up the IP address here,
            // but I'm concerned about revealing ourselves.
        }
    }
    
    public Address getAddress(String s) throws BadAddressException {
        StringTokenizer st = new StringTokenizer(s, ":");
        if (st.countTokens() != 2) {
            throw new BadAddressException("Bad tcp address: "+s);
        }
        try {
            return new tcpAddress(this, st.nextToken(),
                                  Integer.parseInt(st.nextToken()));
            
        } catch (NumberFormatException e) {
            throw new BadAddressException("Illegal port value: "+s);
        }
    }

    public ListeningAddress getListeningAddress(String s, 
						boolean dontThrottle) 
	throws BadAddressException {
        try {
            return new tcpListeningAddress(this, bindAddr,
                                           Integer.parseInt(s), dontThrottle);
        } catch (NumberFormatException e) {
            throw new BadAddressException("Illegal port value: "+s);
        }
    }

    public ListeningAddress getListeningAddress(int port, 
						boolean dontThrottle) {
	return new tcpListeningAddress(this, bindAddr,port,
				       dontThrottle);
    }
    
    public final boolean equals(Object o) {
        return o instanceof tcpTransport && equals((tcpTransport) o);
    }

    public final boolean equals(tcpTransport o) {
        return getClass().equals(o.getClass())
            && preference == o.preference
            && (bindAddr == null && o.bindAddr == null
                || bindAddr != null && o.bindAddr != null && bindAddr.equals(o.bindAddr));
    }


    // implementation methods
    
    public abstract String getName();

    abstract tcpSocketFactory getSocketFactory();

    abstract tcpServerSocketFactory getServerSocketFactory();

    /* Keep out of compile...
    public static void main(String[] args) {
        SSL ssl = new SSL(10);
        TCP tcp = new TCP(10);
        System.out.println("TCP equals TCP: "+tcp.equals(tcp));
        System.out.println("TCP equals SSL: "+tcp.equals(ssl));
        System.out.println("SSL equals SSL: "+ssl.equals(ssl));
        System.out.println("SSL equals TCP: "+ssl.equals(tcp));
    }
    */
}


