/*
 * Created on Mar 31, 2004
 *
 */
package freenet.support.backoff;


public interface BackoffManager{
	public void backoff();
	public long backoffRemaining();
	public boolean isBackedOff();
}