package freenet.node.states.data;

import java.io.EOFException;
import java.io.IOException;

import freenet.Core;
import freenet.MessageObject;
import freenet.Presentation;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.Logger;
import freenet.support.io.DataNotValidIOException;
import freenet.support.io.VerifyingInputStream;

public class EatData extends DataState {

    private VerifyingInputStream suck;
    private long length;

    private volatile int result = -1;

    public EatData(long id, VerifyingInputStream suck, long length) {
        super(id, 0);  // fake parent chain
        this.suck   = suck;
        this.length = length;
    }

    public String getName() {
        return "Eating Data";
    }

    public final int result() {
        return result;
    }

    /** If the node is this overworked, let's not be embarassed to show it ;p
      */
    public final void lost(Node n) {
        try { suck.close(); }
        catch (IOException e) {}
    }

    // FIXME .. we might want to provide a way to stop eating data to
    //          mitigate certain DOS efforts .. not that you can ..
    
    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof DataStateInitiator))
            throw new BadStateException("expecting DataStateInitiator");

        try {
            byte[] buffer = new byte[Core.blockSize];
            while (length > 0) {
                int m = suck.read(buffer, 0, (int) Math.min(length, buffer.length));
                if (m == -1) throw new EOFException();
                length -= m;
            }
            result = Presentation.CB_OK;
        }
        catch (DataNotValidIOException e) {
            result = e.getCode();
        }
        catch (IOException e) {
        	Core.logger.log(this,"Connection died while eating data, suck="+suck,Logger.NORMAL);
            result = Presentation.CB_RECV_CONN_DIED;
        }
        finally {
            try {
                if (result == Presentation.CB_RESTARTED
                           || result == Presentation.CB_ABORTED)
                    suck.discontinue();
                else
                    suck.close();
            }
            catch (IOException e) {}
        }
        
        return null;
    }
}


