package freenet;

/**
 * Instances of this class describe Transport protocols available to 
 * Freenet.
 */

public interface Transport {

    String getName();

    /**
     * The preference weight of this transport. 
     * Transports with higher pref will be prefered
     * over ones with lower.
     */
    int preference();

    /**
     * Checks that an address is correctly formatted for this type.
     * @return  True if the address seems correctly formatted.
     */
    boolean checkAddress(String s);

    /**
     * Constructs a new address for this transport from string s
     */
    Address getAddress(String s) throws BadAddressException;

    /**
     * Constructs a new listening address for this transport from string s
     * @argument dontThrottle whether to subject the connection to bandwidth limiting
     */
    ListeningAddress getListeningAddress(String s, boolean dontThrottle)
	throws BadAddressException;
}
