package freenet.node.states.FCP;

import freenet.*;
import freenet.node.*;
import freenet.message.client.*;
import freenet.support.*;
import java.io.*;

public abstract class NewClientRequest extends State {

    protected PeerHandler source;
    
    protected NewClientRequest(long id, PeerHandler source) {
        super(id);
        this.source = source;
    }
    
    public void lost(Node n) {
        sendMessage(new Failed(id, "Node states overflowed."));
    }


    // the rest is just some common code used in the FCP states

    protected void sendMessage(ClientMessage cm) {
        try {
            source.sendMessage(cm, 600*1000);
        }
        catch (SendFailedException e) {
            Core.logger.log(this, "Failed to send back FCP message: "+cm,
                            e, Logger.MINOR);
            //source.terminate();   .. C.H. takes care of this
        }
    }
    
    protected void copyFully(InputStream in, OutputStream out, long length)
                                                        throws IOException {
        DataInputStream din = new DataInputStream(in);
        byte[] buf = new byte[Core.blockSize];
        while (length > 0) {
            int n = (int) (length < buf.length ? length : buf.length);
            din.readFully(buf, 0, n);
            out.write(buf, 0, n);
            length -= n;
        }
    }
}



