package freenet.transport;
import freenet.Address;
import freenet.ListeningAddress;
import freenet.Transport;

/**
 * Void is the transport that is not.
 *
 * @author oskar
 */
public class VoidTransport implements Transport {

    /**
     * Returns 0.
     */
    public int preference() {
        return 0;
    }

    public String getName() {
	return "void";
    }

    /**
     * @return true
     */
    public boolean checkAddress(String s) {
        return true;
    }

    /**
     * Constructs a new address for this transport from string s
     */
    public Address getAddress(String s) {
	return new VoidAddress(this);
    }

    public ListeningAddress getListeningAddress(String s, 
						boolean dontThrottle) {
	return new VoidListeningAddress(this);
    }
    
    public final boolean equals(Object o) {
	return o instanceof VoidTransport;
    }

    public final int hashCode() {
	return VoidTransport.class.hashCode();
    }

}


