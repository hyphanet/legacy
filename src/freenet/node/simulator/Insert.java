package freenet.node.simulator;

import freenet.Key;

/**
 * A simulated insert request.
 */
public class Insert extends Request {

    public Insert(Key k, int htl) {
        super(k, htl);
    }

}
