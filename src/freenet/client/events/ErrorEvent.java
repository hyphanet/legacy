package freenet.client.events;
import freenet.Core;
import freenet.client.*;
import freenet.support.Logger;

/**
 * This event represents an error condition within the client library that 
 * causes. If this occurs when using the client, then I fucked up.
 *
 * @author oskar
 */

public class ErrorEvent implements ClientEvent {
    public static final int code = 0x10;
    private String comment;

    public ErrorEvent(String comment) {
        this.comment = comment;
        Core.logger.log(this, getDescription(), Logger.ERROR);
    }

    public String getDescription() {
        return "An error condition occured in the client: " + comment;
    }

    public int getCode() {
        return code;
    }
}
