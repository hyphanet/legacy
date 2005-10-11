package freenet.support.servlet.http;

/**
 * Interface to keep HttpSessionsImpl's.  Note: This interface is specific to
 * HttpSessionImpl's and will not hold HttpSession's.  This is because
 * we seem to be using some methods which are not in the HttpSession interface.
 *
 * @author oskar
 */
public interface SessionHolder {
    public HttpSessionImpl getSession(String id);
    public void putSession(HttpSessionImpl s);
}
