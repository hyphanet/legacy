package freenet.session;

import freenet.Authentity;
import freenet.Connection;
import freenet.Identity;

/**
 * This is a linkmanager that essentially does nothing, for interfaces
 * (like FCP or HTTP) that don't do encryption.
 *
 * All the key values can be null.
 *
 * @author oskar
 */

public final class PlainLinkManager implements LinkManager {
    
    public static final int DESIGNATOR = 0;

    public final Link acceptIncoming(Authentity param1, Identity param2,
                                     Connection conn) {
        return new PlainLink(this, conn);
    }

    public final Link createOutgoing(Authentity param1, Identity param2,
                                     Identity param3, Connection conn) {
        return new PlainLink(this, conn);
    }

    public final int designatorNum() {
        return DESIGNATOR;
    }

    public final void cleanupLinks() {}
}

