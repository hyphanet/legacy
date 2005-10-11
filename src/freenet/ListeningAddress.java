package freenet;

/**
 * Listening Addresses represent our abstraction of the port in tcp.
 * I guess if you had a telephone based transport you could use the local
 * connection as the listeningaddress or something, for a satellite network
 * it could be the orientation of a dish.
 *
 * @author oskar (mostly)
 */
public abstract class ListeningAddress extends AddressBase {

    protected ListeningAddress(Transport t) {
        super(t);
    }

    public abstract Listener getListener() throws ListenException;
}
