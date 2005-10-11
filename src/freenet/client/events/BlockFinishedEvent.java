package freenet.client.events;

/**
 * @author giannij
 */

import freenet.client.ClientEvent;
import freenet.message.client.FEC.SegmentHeader;

public class BlockFinishedEvent extends BlockEventWithReason  {
    public static final int code = 0x46;

    private int exitCode = -1;

    public BlockFinishedEvent(SegmentHeader header, boolean downloading, int index, boolean isData, int htl,
                              ClientEvent reason, int exitCode) {
        super(header, downloading, index, isData, htl, reason);
        this.exitCode = exitCode;
    }

    public final int exitCode() { return exitCode; }
    public final String getDescription() { 
        String rs = "";
        if ((reason() != null) && (exitCode == FAILED)) {
            switch(reason().getCode()) {
            case RouteNotFoundEvent.code: rs = ":RNF"; break;
            case DataNotFoundEvent.code: rs = ":DNF"; break;
            case ExceptionEvent.code: rs = ":EXCEPTION: "+reason().getDescription(); break;
            case ErrorEvent.code: rs = ":ERROR: "+reason().getDescription(); break;
            }
        }
        String text = isRequesting() ? "requesting" : "inserting";
        return formatMsg("Finished " + text) + ": " + exitCodeToString(exitCode) + rs; 
    }

    public final int getCode() { return code; }
}


