package freenet.node.states.FCP;

import freenet.Core;
import freenet.FieldSet;
import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.Version;
import freenet.message.client.ClientHello;
import freenet.message.client.NodeHello;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.ds.FSDataStore;
import freenet.support.Logger;

public class NewHello extends NewClientRequest {

	private static boolean logDEBUG=true;

    public NewHello(long id, PeerHandler source) {
        super(id, source);
		logDEBUG=Core.logger.shouldLog(Logger.DEBUG,this);
    }
    
    public final String getName() {
        return "New Client Hello";
    }

    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof ClientHello))
            throw new BadStateException("expecting ClientHello");
	if(logDEBUG)
	    Core.logger.log(this, "Running ClientHello: "+mo+" on "+this,
			    Logger.DEBUG);
        FieldSet fs = new FieldSet();
        fs.put("Node", Version.getVersionString());
        fs.put("Protocol", "1.2");
	fs.put("MaxFileSize", Long.toHexString(((FSDataStore)(n.ds)).maxDataSize));
	if (n.getHighestSeenBuild() > Version.buildNumber) {
            fs.put("HighestSeenBuild", ""+Version.highestSeenBuild);
	}
        sendMessage(new NodeHello(id, fs));
	if(logDEBUG) Core.logger.log(this, "Finished running ClientHello "+
				     mo+" on "+this, Logger.DEBUG);
        return null;
    }
}

