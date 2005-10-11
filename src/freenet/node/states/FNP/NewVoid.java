package freenet.node.states.FNP;
import freenet.Core;
import freenet.node.*;
import freenet.support.Logger;
import freenet.message.VoidMessage;
import java.io.InputStream;
import java.io.IOException;
/**
 * Handels void messages as eats data.
 */

public class NewVoid extends State {
    
    public NewVoid(long id) {
        super(id);
    }
    
    public String getName() {
        return "New Void message";
    }
    
    public State receivedMessage(Node n, VoidMessage v) {
        InputStream in = v.getDataStream();
        if (in != null) {
            if (v.length() > Core.maxPadding) {
                Core.logger.log(this, "Received Void with too much padding: " +
                             v.length(), Logger.MINOR);
                v.drop(n);
            } else {
                byte[] b = new byte[Core.blockSize];
                try {
                    while (in.read(b) != -1); 
                } catch (IOException e) {
                    Core.logger.log(this, "IO problem when reading Void padding.",
                                 e, Logger.DEBUG);
                }
            }
        }
        return null;
    }
    
    public void lost(Node n) {
        // watch me get all worked up about this...
    }
}
