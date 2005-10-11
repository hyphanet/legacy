/*
  This code is part of the Java Adaptive Network Client by Ian Clarke. 
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
 */

/**
 * This is the DataRequest message
 *
 * @see Node
 * @see Address
 * @author Brandon Wiley (blanu@uts.cc.utexas.edu)
 * @author Ian Clarke (I.Clarke@strs.co.uk)
 **/

package freenet.message;
import freenet.*;
import freenet.node.*;
import freenet.node.states.FNP.NewDataRequest;
import freenet.support.*;
//import java.net.*;

public class DataRequest extends Request {

	public static final String messageName = "DataRequest";

	public DataRequest(long idnum, int htl, Key key, Identity id) {
		super(idnum, htl, key, id);
	}

	public DataRequest(long idnum, int htl, Key key, Identity id, FieldSet otherFields) {
		super(idnum, htl, key, id, otherFields);
	}

	public DataRequest(BaseConnectionHandler source, RawMessage raw) throws InvalidMessageException {
		super(source, raw);
	}

	public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
		RawMessage raw = super.toRawMessage(t, ph);
		return raw;
	}

	public final boolean hasTrailer() {
		return false;
	}

	public final long trailerLength() {
		return 0;
	}

	public String getMessageName() {
		return messageName;
	}

	public State getInitialState() {
		stateTime = System.currentTimeMillis();
		if (receivedTime > 1000 * 1000 * 1000) {
			long t = stateTime - receivedTime;
			Core.diagnostics.occurrenceContinuous("messageInitialStateTime", t);
			if (t > 3000) // GC :(
				// Most timeouts are well over this - Core.hopTime(1) ~= 18s
				Core.logger.log(this, "Long messageInitialStateTime " + t + " : " + this, Logger.NORMAL);
		}
		return new NewDataRequest(id);
	}

	public int getPriority() {
		return -3; // less important than DataReply but more than Accepted
	}
}


