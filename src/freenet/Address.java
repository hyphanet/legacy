package freenet;

public abstract class Address extends AddressBase {

    protected Address(Transport t) {
        super(t);
    }

    /**
     * Connects to this Address.
     * @return  The new Connection.
     */
    public abstract Connection connect(boolean dontThrottle) 
	throws ConnectFailedException;

    /**
     * Returns the part of this address that differentiates several nodes
     * on the same entity on the network.
     **/
    public abstract ListeningAddress listenPart(boolean dontThrottle);
}




