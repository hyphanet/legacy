package freenet.node;

import freenet.MessageObject;
import java.util.Vector;
import java.util.Enumeration;

/**
 * Holds a (small) set of StateChains sharing the same unique ID,
 * and multiplexes received MOs to them.
 *
 * Terminates itself when there are no sub-chains left.
 */
public abstract class AggregatingState extends State {

    private final Vector chains;

    /**
     * @param id       the external id of the chains
     * @param prefill  the number of null slots to start with
     *                 (at least this many messages creating
     *                  an initial state must be received
     *                  before the AggregatingState will go away)
     */
    public AggregatingState(long id, int prefill) {
        super(id);
        chains = new Vector(prefill);
        while (prefill-- > 0)
            chains.addElement(new StateChain());
    }

    public State received(Node n, MessageObject mo) throws StateException {
        if (!(mo instanceof NodeMessageObject))
            throw new BadStateException("Cannot multiplex: "+mo);
        NodeMessageObject nmo = (NodeMessageObject) mo;
        Enumeration e = chains.elements();
        StateChain c;
        while (e.hasMoreElements()) {
            c = (StateChain) e.nextElement();
            if (c.receives(mo)) {
                if (!c.received(n, nmo))
                    chains.removeElement(c);  // not live
                return chains.isEmpty() ? null : this;
            }
        }
        c = new StateChain();
        if (c.received(n, nmo))
            chains.addElement(c);  // new live sub-chain
	chains.trimToSize();
        return chains.isEmpty() ? null : this;
    }

    public void lost(Node n) {
        Enumeration e = chains.elements();
        while (e.hasMoreElements()) {
            StateChain c = (StateChain) e.nextElement();
            c.lost(n);
        }
    }

    public int priority() {
        int p = EXPENDABLE;
        for (Enumeration e = chains.elements() ; e.hasMoreElements();) {
            int np = ((StateChain) e.nextElement()).priority();
            if (np > p)
                p = np;
        }
        return p;
    }
}






