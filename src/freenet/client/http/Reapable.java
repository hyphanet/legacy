/*
  This code is part of fproxy, an HTTP proxy server for Freenet.
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

package freenet.client.http;

public interface Reapable {
    /**
     * Releases all resources used by the implementing object.
     * <p>
     * The object shouldn't be used after this is called.
     * <p>
     * This may be called more than once.
     * <p>
     * @return true if resources where successfully released, false 
     *         otherwise.
     **/
     boolean reap();
    
    /**
     * Returns true if the object can be reclaimed.
     **/
    boolean isExpired();
}

