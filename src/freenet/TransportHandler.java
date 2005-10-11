package freenet;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.StringTokenizer;

import freenet.support.Selector;
/**
 *
 * This class handles the abstraction of Transport objects, and the support
 * for several transports at the same time.
 *
 * @author Oskar
 */

public class TransportHandler {

    private Hashtable transports;
    private Selector select;


    /**
     * Create a new TransportHandler
     */
    public TransportHandler() {
        transports = new Hashtable();
        select = new Selector();
    }

    /**
     * Registers a transport available from this machine. 
     * @param t       The Transport object to register.
     */
    public void register(Transport t) {
        transports.put(t.getName(),t);
        select.register(t, t.preference());
    }

    
    /**
     * Returns the Tranport with the given name, if possible.
     */
    public Transport get(String s) {
        return (Transport) transports.get(s);
    }

    /**
     * Return the most preferable Transport available, if possible
     */
    public Transport get() {
        return (Transport) select.getSelection();
    }

    /**
     * Checks that an address is correctly formatted for it's type.
     * @return  True if the address seems correctly formatted, or 
     *          if we do not know anything about this transport.
     */
    public boolean checkAddress(String type, String addr) {
        Transport t = get(type);
        return t == null ? true: t.checkAddress(addr);
    }

    /**
     * ShortCut method to return the address described by the fieldset.
     * @param fs  A FieldSet containing a standard format address
     * @return   The address object for the fs.
     * @exception  IllegalArgumentException  this FieldSet did not contain
     *                                       a proper address.
     * @exception  BadAddressException       the address could not be resolved,
     *                                       for example the Transport is not
     *                                       supported.
     */
    public Address readAddress(FieldSet fs) 
	throws BadAddressException, IllegalArgumentException {

	String phys = fs.getString("physical");
	if (phys == null || phys.indexOf('/') == -1) {
	    throw new IllegalArgumentException("No physical address in set"); 
	}
	StringTokenizer st = new StringTokenizer(phys,"/");
	String tname = st.nextToken();
	String val = st.nextToken();

	Transport t = get(tname);
	if (t == null)
	    throw new BadAddressException("Unsupported transport: " + tname);

	return t.getAddress(val);
    }

    /**
     * Returns an Enumeration of the transports in order of weight.
     */
    public Enumeration getTransports() {
	return select.getSelections();
    }

}



