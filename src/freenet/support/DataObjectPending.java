package freenet.support;

import java.io.DataInputStream;
import java.io.IOException;


/**
 * Used to mediate the on-demand deserialization of a DataObject.
 * The DataObject should be constructed from the input stream
 * and then passed into the resolve() method.
 * 
 * @see DataObject
 * @see DataObjectUnloadedException
 */
public interface DataObjectPending {

    int getDataLength();

    DataInputStream getDataInputStream() throws IOException;
    
    void resolve(DataObject o);
}


