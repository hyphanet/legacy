package freenet.node.rt;

import freenet.Key;
import freenet.node.IdRefPair;
import freenet.support.PropertyArray;
import freenet.support.StringMap;

class SimpleRTDiagSnapshot implements RTDiagSnapshot {

    private StringMap table = null;
    private PropertyArray refs = null;
    private IdRefPair[] nodes = null;
    private Key[] keys = null;
    private RecentRequestHistory.RequestHistoryItem[] history = null;

    SimpleRTDiagSnapshot(StringMap table,
                         PropertyArray refs,
                         Key[] keys,
			 IdRefPair[] realRefs,
			 RecentRequestHistory.RequestHistoryItem[] history) {
        this.table = table;
        this.refs = refs;
        this.keys = keys != null ? keys : new Key[0];
        nodes = realRefs;
        this.history = history;
    }
    
    public final StringMap tableData() { 
        // const interface, don't need to copy.
        return table; 
    }

    public final PropertyArray refData() { 
	return refs;
    }

    public final Key[] keys() {
        Key[] ret = new Key[keys.length];
        System.arraycopy(keys, 0, ret, 0, ret.length);
        return ret;
    }

    public IdRefPair[] getIdRefPairs() {
        return nodes;
    }

    public RecentRequestHistory.RequestHistoryItem[] recentRequests() {
        return history;
    }
}

