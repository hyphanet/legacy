/*
 * Created on Mar 19, 2004
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet.node.rt;

import freenet.Identity;
import freenet.PeerHandler;

/**
 * @author amphibian
 */
public class NodeSortingFilterRoutingTable extends FilterRoutingTable implements NodeSortingRoutingTable  {

    public NodeSortingFilterRoutingTable(RoutingTable rt, Identity blockID) {
        super(rt, blockID);
    }

    public void order(PeerHandler[] ph, boolean ignoreNewbies) {
        ((NodeSortingRoutingTable)rt).order(ph, ignoreNewbies);
    }

    public void order(Identity[] id) {
        ((NodeSortingRoutingTable)rt).order(id);
    }

    public boolean isNewbie(Identity identity, boolean isConnected) {
        return ((NodeSortingRoutingTable)rt).isNewbie(identity, isConnected);
    }

	public double maxPSuccess() {
		return ((NodeSortingRoutingTable)rt).maxPSuccess();
	}

    public long timeTillCanSendRequest(Identity identity, long now) {
        return ((NodeSortingRoutingTable)rt).timeTillCanSendRequest(identity, now);
    }
}
