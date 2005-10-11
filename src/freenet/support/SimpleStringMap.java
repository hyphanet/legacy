package freenet.support;

/**
 * An implementation of StringMap good for small lists.
 * <p>
 * Note that value() does a linear search.
**/
public class SimpleStringMap implements StringMap {
    protected String[] keys = null;
    protected Object[] objs = null;

    public SimpleStringMap(String[] keys, Object[] objs) {
        if (keys.length != objs.length) {
            throw new IllegalArgumentException("keys.length != objs.length");
        }
        this.keys = keys;
        this.objs = objs;
    }

    public String[] keys() { 
        String[] ret = new String[keys.length];
        System.arraycopy(keys, 0, ret, 0, ret.length);
        return ret;
    }

    public Object[] values() {
        Object[] ret = new Object[objs.length];
        System.arraycopy(objs, 0, ret, 0, ret.length);
        return ret;
    }

    public final Object value(String key) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) {
                return objs[i];
            }
        }
        return null;
    }
} 

