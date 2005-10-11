/*
 * Created on Mar 31, 2004
 */
package freenet.support.backoff;

public class AutoResettingBackoffManager implements BackoffManager{
	private final ResettableBackoffManager underlying;
	private final int resetAfter;
	private long lastBackoff=System.currentTimeMillis();
	public AutoResettingBackoffManager(ResettableBackoffManager underlying, int resetAfter){
		this.underlying = underlying;
		this.resetAfter = resetAfter;
	}

	public synchronized void backoff() {
		handleReset();
		lastBackoff = System.currentTimeMillis(); //Restart the bounds timer
		underlying.backoff();
	}

	public long backoffRemaining() {
		handleReset();
		return underlying.backoffRemaining();
	}

	public boolean isBackedOff() {
		handleReset();
		return underlying.isBackedOff();
	}
	
	private synchronized void handleReset(){
		if((lastBackoff-resetAfter)>System.currentTimeMillis())
			underlying.resetBackoff();
	}
}