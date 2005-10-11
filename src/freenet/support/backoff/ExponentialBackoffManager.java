/*
 * Created on Mar 31, 2004
 *
 */
package freenet.support.backoff;

import freenet.Core;
import freenet.support.Logger;


public class ExponentialBackoffManager implements ResettableBackoffManager{
    private int currentDelay = 0;
    private int baseBackoffDelay = 0;
    private final int startBackoffDelay;
    private int lastBackoffDelay = 0; // last backoff before recent backoff stop
    private int lastBaseBackoffDelay =0; //last basebackoff before recent backoff stop
    private long lastBackedOffAt = -1;
	public ExponentialBackoffManager(int startBackoffDelay){
		this.startBackoffDelay = startBackoffDelay;
		
	}

	public synchronized void resetBackoff() {
		lastBackoffDelay = currentDelay;
        currentDelay = 0;
        lastBaseBackoffDelay = baseBackoffDelay;
        baseBackoffDelay = 0;
	}

	public synchronized void revokeReset(){
		currentDelay = lastBackoffDelay;
        baseBackoffDelay = lastBaseBackoffDelay;
        backoff();
	}
	public synchronized void backoff(){
		if (baseBackoffDelay <= 0)
            baseBackoffDelay = startBackoffDelay;

        lastBackedOffAt = System.currentTimeMillis();
        
        currentDelay =
            baseBackoffDelay + Core.getRandSource().nextInt(baseBackoffDelay);

        baseBackoffDelay <<= 1;

        if (Core.logger.shouldLog(Logger.DEBUG,this))
            Core.logger.log(
                    this,
                    "Backing of " + this +" for " + currentDelay + "ms",
                    Logger.DEBUG);
	}

	public synchronized long backoffRemaining() {
		return lastBackedOffAt + currentDelay - System.currentTimeMillis();
	}

	public boolean isBackedOff() {
		return backoffRemaining() > 0;
	}
}