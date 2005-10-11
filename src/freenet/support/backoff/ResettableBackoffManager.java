/*
 * Created on Mar 31, 2004
 *
 */
package freenet.support.backoff;

public interface ResettableBackoffManager extends BackoffManager{
	public void resetBackoff();
	public void revokeReset();
}