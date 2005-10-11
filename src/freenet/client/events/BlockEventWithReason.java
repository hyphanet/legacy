package freenet.client.events;

/**
 * @author giannij
 */

import freenet.client.ClientEvent;
import freenet.message.client.FEC.SegmentHeader;

// Base class for SplitFile status events that hold a ref 
// to a primative event.    
public abstract class BlockEventWithReason extends BlockEvent  {
    private ClientEvent reason;

    public BlockEventWithReason(SegmentHeader header, boolean downloading,
                                int index, boolean isData, int htl,
                                ClientEvent reason) {
        super(header, downloading, index, isData, htl);
        this.reason = reason;
    }

    public final ClientEvent reason() { return reason; }
}



