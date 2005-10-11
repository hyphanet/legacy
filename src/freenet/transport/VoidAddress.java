package freenet.transport;
import freenet.*;

/**
 * VoidAddress are addresses that cannot be connected to, ie they serve only
 * to mark the absence of a real address.
 *
 * @author oskar
 */

public class VoidAddress extends Address {

    public VoidAddress() {
        super(new VoidTransport());
    }

    public VoidAddress(VoidTransport v) {
        super(v);
    }

    public Connection connect(boolean dontThrottle) throws ConnectFailedException {
        throw new ConnectFailedException(this, "Cannot connect to VoidAddress objects");
    }

    public ListeningAddress listenPart(boolean dontThrottle) {
        return new VoidListeningAddress((VoidTransport) t);
    }

    public final String getValString() {
        return "(void)";
    }
}


