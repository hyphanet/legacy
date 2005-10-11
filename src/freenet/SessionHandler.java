package freenet;

import freenet.session.*;
import freenet.support.*;
import java.util.Hashtable;
import java.util.Enumeration;

public class SessionHandler implements Checkpointed {

    private final Hashtable sessions = new Hashtable();
    private final Selector select = new Selector();


    public final void register(LinkManager m, int pref) {
        select.register(m, pref);
        sessions.put(new Integer(m.designatorNum()),m);
    }

    public final LinkManager get(int num) {
        return (LinkManager) sessions.get(new Integer(num));
    }

    public final Enumeration getLinkManagers() {
        return select.getSelections();
    }

    public final int size() {
        return select.size();
    }


    public final String getCheckpointName() {
        return "Discarding expired session keys.";
    }

    public final long nextCheckpoint() {
        return System.currentTimeMillis() + 1000 * 60 * 5;  // 5 minutes from now
    }
    
    /**
     * Calls cleanupLinks() on all registered LinkManagers.
     */
    public void checkpoint() {
        Enumeration e = getLinkManagers();
        while (e.hasMoreElements())
            ((LinkManager) e.nextElement()).cleanupLinks();
    }
}


