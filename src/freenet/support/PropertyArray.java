package freenet.support;
import java.util.Vector;
import freenet.support.Comparator;
import freenet.support.sort.HeapSorter;
import freenet.support.sort.VectorSorter;

/**
 * A tabular data structure with string headers for the columns.
 * <p>
 * This class was added to replace using StringMap for  
 * displaying runtime diagnostics which
 * are not nescessarily known at the time
 * the display client (e.g. NodeStatusServlet)
 * is compiled.
**/
public class PropertyArray {
    protected String[] keys = null;
    protected Vector objectArrays = null;
    protected Object[] builder = null;
    protected Comparator compare = null;

    /**
     * @param keyNames An array of string labels for the objects in each row
     * @param sortby a comparator for the Object arrays in each row
     **/
    public PropertyArray (String[] keyNames, Comparator sortby) {
    	if (keyNames == null || keyNames.length < 1) 
    			throw new IllegalArgumentException("cannot construct with null or empty keyNames");
    	keys = keyNames;
    	objectArrays = new Vector();
    	builder = new Object[keyNames.length];
    	compare = sortby;
    }


    /**
     * @param data One row of data.  Must have the same length as keys
     * @deprecated
     **/
    public void add (Object[] data) {
	if (data.length != keys.length) {
            throw new IllegalArgumentException("keys.length != objs.length");
	}
	objectArrays.addElement(data);
    }

    /**
     * returns a copy of keys (so that the original can't be modified)
     * 
     * @return An ordered array of unique String keys.
     *
     **/
    public String[] keys() {
	String[] ret = new String[keys.length];
	System.arraycopy(keys,0,ret,0,keys.length);
	return ret;
    }

    /**
     * sorts objectArrays and returns the result.
     * ** This modifies objectArrays **
     * @return An vector of Object[]s with values corresponding 
     *         to keys().
     **/
    public Vector values() {
	if (compare != null) {
	    HeapSorter.heapSort(new VectorSorter(objectArrays, compare));
	}
	return objectArrays;
    }

    public int getPos(String key) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) {
                return i;
            }
        }
	return -1;
    }

    /**
     * @return The Objects mapped to a key. null if there
     *         isn't one.
     **/
    public Vector value(String key) {
	int p = getPos(key);
	if (p==-1) return null;

	Vector ret = new Vector();
	for (int i=0; i<objectArrays.size(); i++) {
	    Object[] row = (Object[]) objectArrays.elementAt(i);
	    ret.addElement(row[p]);
	}
	return ret;
    }

    /**
     * allows construction of an appropriate object[] by name (as opposed to position)
     *
     * @param var A String in keynames that decides the position
     * @param val The object to store in that position
     **/
    public void addToBuilder(String var, Object val) {
	int i = getPos(var);
	if (i==-1) throw new IllegalArgumentException("Column <"+var+"> not defined");
	builder[i] = val;
    }

    /**
     * adds builder to objectarrays and resets its contents
     **/
    public void commitBuilder() { 
	objectArrays.addElement(builder);
	builder = new Object[keys.length]; 
    }

} 

