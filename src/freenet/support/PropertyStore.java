package freenet.support;

public interface PropertyStore {

    DataObject getProperty(String name)
        throws DataObjectUnloadedException;

    void setProperty(String name, DataObject o);
}


