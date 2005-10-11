package freenet.support.servlet.http;

import java.util.Enumeration;
import java.util.Hashtable;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;

/**
 * @author oskar
 */
public class HttpSessionImpl implements HttpSession {

    private Hashtable attr = new Hashtable();

    private long lastAccessTime;
    private long creationTime;
    private long maxInterval;
    private String id;
    private boolean isNew = true;

    private boolean valid = true;

    public HttpSessionImpl(String id, long maxInterval) {
        this.id = id;
        this.maxInterval = maxInterval;
        this.creationTime = System.currentTimeMillis();
        this.lastAccessTime = creationTime;
    }

    public long getCreationTime() {
        check();
        return creationTime;
    }

    public String getId() {
        check();
        return id;
    }

    public long getLastAccessedTime() {
        check();
        return lastAccessTime;
    }

    public void setMaxInactiveInterval(int interval) {
        check();
        maxInterval = interval * 1000; // i sort of like consistant units...
    }

    public int getMaxInactiveInterval() {
        check();
        return (int) (maxInterval / 1000);
    }
    
    public int getIdleTime(){
    	return (int) (System.currentTimeMillis()-getLastAccessedTime())/1000;
    }

    /**
     * @deprecated
     * @return <code>null</code>
     */
    final public HttpSessionContext getSessionContext() {
        return null;
    }

    public Object getAttribute(String name) {
        check();
        return attr.get(name);
    }

    /** @deprecated */
    final public Object getValue(String name) {
        return getAttribute(name);
    }

    public Enumeration getAttributeNames() {
        check();
        return attr.keys();
    }
    
    /** @deprecated */
    final public String[] getValueNames() {
        check();
        String[] r = new String[attr.size()];
        int i = 0;
        for (Enumeration e = attr.keys() ; e.hasMoreElements() ; i++) {
            r[i] = (String) e.nextElement();
        }
        return r;
    }

    public void setAttribute(String name, Object value) {
        check();
        attr.put(name, value);
    }

    /** @deprecated */
    final public void putValue(String name, Object value) {
        setAttribute(name, value);
    }

    public void removeAttribute(String name) {
        check();
        attr.remove(name);
    }

    /** @deprecated */
    final public void removeValue(String name) {
        removeAttribute(name);
    }

    public void invalidate() {
        valid = false;
        attr.clear();
    }

    public boolean isNew() {
        check();
        return isNew;
    }

    /**
     * Note: This is not in the HttpSession API
     */
    final void wasAccessed() {
        lastAccessTime = System.currentTimeMillis();
    }

    /**
     * Note: This is not in the HttpSession API
     */
    final void notNew() {
        isNew = false;
    }

    final boolean isValid() {
        return valid;
    }

    private final void check() {
        if (!valid)
            throw new IllegalStateException("Method called on invalidated session");
    }
}

