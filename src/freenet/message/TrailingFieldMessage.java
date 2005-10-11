package freenet.message;
import freenet.*;
import java.io.InputStream;

/**
 * Interface for messages with a trailing field.
 * @author oskar
 */

public interface TrailingFieldMessage extends MessageObject {

    public InputStream getDataStream();

    //public Storables getStorables();

    public long trailerLength();

    public void setTrailerLength(long length);

}
