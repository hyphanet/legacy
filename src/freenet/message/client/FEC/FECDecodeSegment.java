package freenet.message.client.FEC;

import freenet.BaseConnectionHandler;
import freenet.RawMessage;
import freenet.message.client.ClientMessage;
import freenet.node.State;
import freenet.node.states.FCP.NewFECDecodeSegment;
import freenet.node.states.FCP.NewIllegal;
import freenet.support.Fields;

/**
 * Message to FEC decode a single segment.
 */
public class FECDecodeSegment extends ClientMessage {

	public static final String messageName = "FECDecodeSegment";

	private int blockList[] = null;
	private int checkList[] = null;
	private int requestedList[] = null;

	// REDFLAG: factor this out somewhere
	protected final static int[] readIntList(String commaDelimitedInts) {
		if (commaDelimitedInts == null) {
			return new int[0];
		}

		String[] values = Fields.commaList(commaDelimitedInts);
		int ret[] = new int[values.length];
		for (int i = 0; i < ret.length; i++) {
			ret[i] = Integer.parseInt(values[i], 16);
		}
		return ret;
	}

	// From wire
	public FECDecodeSegment(BaseConnectionHandler source, RawMessage raw) {
		super(source, raw, true);

		if (!formatError) {
			formatError = true;
			try {
				String blockListAsString = otherFields.getString("BlockList");
				otherFields.remove("BlockList");
				blockList = readIntList(blockListAsString);

				String checkListAsString = otherFields.getString("CheckList");
				otherFields.remove("CheckList");
				checkList = readIntList(checkListAsString);

				String requestListAsString = otherFields.getString("RequestedList");
				otherFields.remove("RequestedList");
				requestedList = readIntList(requestListAsString);

				formatError = false;
			} catch (Exception e) {
				formatError = true;
			}
			close = false;
		}
	}

	public State getInitialState() {
		return formatError
			? (State) new NewIllegal(id,
				source.getPeerHandler(),
				"Error parsing FECDecodeSegment message.")
			: (State) new NewFECDecodeSegment(id, source.getPeerHandler());
	}

	public String getMessageName() {
		return messageName;
	}

	public int[] blockList() {
		int[] ret = new int[blockList.length];
		System.arraycopy(blockList, 0, ret, 0, blockList.length);
		return ret;
	}

	public int[] checkList() {
		int[] ret = new int[checkList.length];
		System.arraycopy(checkList, 0, ret, 0, checkList.length);
		return ret;
	}

	public int[] requestedList() {
		int[] ret = new int[requestedList.length];
		System.arraycopy(requestedList, 0, ret, 0, requestedList.length);
		return ret;
	}
}
