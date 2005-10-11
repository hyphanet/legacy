package freenet.config;


/**
 * Because we are doomed to reimplement everything from JFCs sooner
 * or later.
 * 
 * @author oskar
 */
public interface PropertySet {

    public long getLong(String name);

    public int getInt(String name);

    public short getShort(String name);

    public boolean getBoolean(String name);

    public String getString(String name);

}
