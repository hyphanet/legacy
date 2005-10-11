package freenet.client.events;

/**
 * @author <a href="mailto: rrkapitz@stud.informatik.uni-erlangen.de">Ruediger Kapitza</a>
 * @version
 */

import freenet.client.ClientEvent;

public class DataNotFoundEvent implements ClientEvent{
    
    public static final int code = 0x08;

    public String getDescription() {
        return "Data Not found ";
    }

    public int getCode() {
        return code;
    }

}// DataNotFoundEvent
