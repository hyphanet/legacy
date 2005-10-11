package freenet.message.client.FEC;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.message.client.ClientMessage;
import freenet.node.State;
import freenet.node.states.FCP.NewFECEncodeSegment;
import freenet.node.states.FCP.NewIllegal;
import freenet.support.Fields;

/**
 * Message to FEC Encode a single segment.
 */
public class FECEncodeSegment extends ClientMessage {

	public static final String messageName = "FECEncodeSegment";

	private int requestedList[] = null;

	// REDFLAG: factor out somewhere.
	protected final static int[] readIntList(String commaDelimitedInts) {
		if (commaDelimitedInts == null) {
			return null;
		}

		String[] values = Fields.commaList(commaDelimitedInts);
		int ret[] = new int[values.length];
		for (int i = 0; i < ret.length; i++) {
			ret[i] = Integer.parseInt(values[i], 16);
		}
		return ret;
	}

	// From wire
	public FECEncodeSegment(BaseConnectionHandler source, RawMessage raw) {
		super(source, raw, true);

		if (!formatError) {
			formatError = true;
			try {
				String requestListAsString = otherFields.getString("RequestedList");
				otherFields.remove("RequestedList");
				requestedList = readIntList(requestListAsString);
				formatError = false;
			} catch (Exception e) {
				e.printStackTrace();
				formatError = true;
			}
		}
		close = false;
	}

	public State getInitialState() {
		return formatError
			? (State) new NewIllegal(id,
				source.getPeerHandler(),
				"Error parsing FECEncodeSegment message.")
			: (State) new NewFECEncodeSegment(id, source.getPeerHandler());
	}

	public String getMessageName() {
		return messageName;
	}

	// Can return an null, which means encode all.
	public int[] requestedList() {
		if (requestedList == null) {
			return null;
		}
		int[] ret = new int[requestedList.length];
		System.arraycopy(requestedList, 0, ret, 0, requestedList.length);
		return ret;
	}
}
