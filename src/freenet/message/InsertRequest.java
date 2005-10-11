
package freenet.message;

import freenet.BaseConnectionHandler;
import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.InvalidMessageException;
import freenet.Key;
import freenet.PeerHandler;
import freenet.Presentation;
import freenet.RawMessage;
import freenet.node.State;
import freenet.node.states.FNP.NewInsertRequest;

/**
 * The Request message for Inserting data. 
 *
 * @author oskar
 */

public class InsertRequest extends Request {

	public static final String messageName = "InsertRequest";

	public InsertRequest(long idnum, int htl, Key key, Identity id, FieldSet otherFields) {
		super(idnum, htl, key, id, otherFields);
	}

	public InsertRequest(long idnum, int htl, Key key, Identity id) {
		super(idnum, htl, key, id);
	}

	public InsertRequest(BaseConnectionHandler source, RawMessage raw) throws InvalidMessageException {
		super(source, raw);
	}

	public State getInitialState() {
		stateTime = System.currentTimeMillis();
		if (receivedTime > 1000 * 1000 * 1000) {
			Core.diagnostics.occurrenceContinuous("messageInitialStateTime", stateTime - receivedTime);
		}
		return new NewInsertRequest(id);
	}

	public RawMessage toRawMessage(Presentation t, PeerHandler ph) {
		RawMessage raw = super.toRawMessage(t, ph);
		return raw;
	}

	public String getMessageName() {
		return messageName;
	}

	public int getPriority() {
		return -3; // same as DataRequest
	}

}






