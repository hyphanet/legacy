package freenet.node.states.FCP;

import freenet.*;
import freenet.node.*;
import freenet.message.client.*;
import freenet.message.client.FEC.*;

public class NewFECSegmentFile extends NewClientRequest {

    public NewFECSegmentFile(long id, PeerHandler source) {
        super(id, source);
    }
    
    public final String getName() {
        return "New FEC Segment File";
    }

    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof FECSegmentFile))
            throw new BadStateException("expecting FECSegmentFile");

        try {
            FECSegmentFile msg = (FECSegmentFile)mo;
            SegmentHeader[] headers = Node.fecTools.segmentFile(id, msg.getAlgoName(), msg.getFileLength()) ;

            // Drop the connection after the final header is sent.
            headers[headers.length - 1].setClose(true);

            for (int i = 0; i < headers.length; i++) {
                sendMessage(headers[i]);
            }
        }
        catch (Exception e) {
            e.printStackTrace(); // REDFLAG: remove
            sendMessage(new Failed( id, e.getMessage()));
        }

        return null;
    }
}

