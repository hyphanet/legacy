package freenet.transport;
import freenet.ListenException;
import freenet.Listener;
import freenet.ListeningAddress;
/**
 * VoidListeningAddresses have no reason for life.
 *
 * @author oskar
 */

public class VoidListeningAddress extends ListeningAddress {

    public VoidListeningAddress(VoidTransport v) {
	super(v);
    }

    public Listener getListener() throws ListenException {
	throw new ListenException("VoidListeningAddress cannot listen");
    }

    public String getValString() {
	return "void";
    }
}
