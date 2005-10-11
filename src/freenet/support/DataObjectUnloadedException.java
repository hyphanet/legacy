package freenet.support;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Thrown in a generic context where an object is being retrieved,
 * but only exists in a serialized form.  By throwing this exception,
 * the InputStream from which the object can be read is exposed to
 * the code layer that knows how to deserialize the object.  That
 * code layer must catch the exception, deserialize the object, and
 * then call the resolve() method with the result.
 * @see DataObject
 * @see DataObjectPending
 */
public abstract class DataObjectUnloadedException extends Exception
                                                  implements DataObjectPending{

    public abstract int getDataLength();

    public abstract DataInputStream getDataInputStream() throws IOException;
    
    public abstract void resolve(DataObject o);

}

