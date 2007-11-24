/*
 * This code is part of the Java Adaptive Network Client by Ian Clarke. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

package freenet.crypt;

import java.math.BigInteger;
import java.util.Random;
import java.util.Stack;

import freenet.Core;
import freenet.support.Logger;

public class DiffieHellman {
	
	/**
	 * When the number of precalculations falls below this threshold generation
	 * starts up to make more.
	 */
	private static final int PRECALC_RESUME = 15;

	/** Maximum number of precalculations to create. */
	private static final int PRECALC_MAX = 30;

	/**
	 * How often to wake up and make sure the precalculation buffer is full
	 * regardless of how many are left, in milliseconds. This helps keep the
	 * buffer ready for usage spikes when it is being empties slowly.
	 */
	private static final int PRECALC_TIMEOUT = 193 * 1000;

	private static Random r = Core.getRandSource();
	private static DHGroup group = Global.DHgroupA;
	private static Stack precalcBuffer = new Stack();
	private static Object precalcerWaitObj = new Object();

	private static Thread precalcThread;
	
	public static final BigInteger MIN_EXPONENTIAL_VALUE = new BigInteger("2").pow(24);
	public static final BigInteger MAX_EXPONENTIAL_VALUE = group.getP().subtract(MIN_EXPONENTIAL_VALUE);

	static {
		precalcThread = new PrecalcBufferFill();
		precalcThread.start();
	}

	private static class PrecalcBufferFill extends Thread {

		public PrecalcBufferFill() {
			setName("Diffie-Helman-Precalc");
			setDaemon(true);
		}

		public void run() {
			while (true) {
				while (precalcBuffer.size() < PRECALC_MAX) {
					precalcBuffer.push(genParams());
					synchronized (precalcBuffer) {
						// Notify a waiting thread, that new data is available
						precalcBuffer.notify();
					}
				}

				// Reset the thread priority to normal because it may have been
				// set to MAX if the buffer was emptied.
				precalcThread.setPriority(Thread.NORM_PRIORITY);

				synchronized (precalcerWaitObj) {
						try {
						// Do not set the thread priority here because the
						// thread may have been stopped while holding the
						// precalcerWaitObj lock. The stop causes the thread
						// group to be cleared and setPriority to throw a NPE.
						precalcerWaitObj.wait(PRECALC_TIMEOUT);
						// TODO: this timeout might very well be unneccsary
						} catch (InterruptedException ie) {
							// Ignored.
						}
					}
				}
			}
		}

	/**
	 * This method does not do anything, but calling it causes the
	 * PrecalcBufferFill thread to be started by the static block at the class
	 * scope.
	 */
	public static void init() {
		// Intentionally empty.
	}

	/** Will ask the precalc thread to refill the buffer if neccessary */
	private static void askRefill() {
		// If the buffer size is below the threshold then wake the precalc
		// thread
		if (precalcBuffer.size() < PRECALC_RESUME) {
			if (precalcBuffer.isEmpty()) {
				// If it is all empty, try to fill it up even faster
				precalcThread.setPriority(Thread.MAX_PRIORITY);
			}
			synchronized (precalcerWaitObj) {
				precalcerWaitObj.notify();
			}
		}
			}

	public static BigInteger[] getParams() {
		synchronized (precalcBuffer) {
			//Ensure that we will have something to pop (at least pretty soon)
			askRefill(); 

			//Wait until we actually have something to pop
			while (precalcBuffer.isEmpty()) {
				try {
					precalcBuffer.wait();
				} catch (InterruptedException e) {
					// Ignored.
				}
			}

			BigInteger[] result = (BigInteger[]) precalcBuffer.pop();

			//Hint the precalcer that it might have something to do now
			askRefill();

			//Release possible other precalc value waiters
			precalcBuffer.notify();

			return result;
		}
	}

	private static BigInteger[] genParams() {
		BigInteger params[] = new BigInteger[2];
		// Don't need NativeBigInteger?
		do {
			params[0] = new BigInteger(256, r);
			params[1] = group.getG().modPow(params[0], group.getP());
		} while(!DiffieHellman.checkDHExponentialValidity(DiffieHellman.class, params[1]));
		
		return params;
	}

	/**
	 * Check the validity of a DH exponential
	 *
	 * @param a BigInteger: The exponential to test
	 * @return a boolean: whether the DH exponential provided is acceptable or not
	 *
	 * @see http://securitytracker.com/alerts/2005/Aug/1014739.html
	 * @see http://www.it.iitb.ac.in/~praj/acads/netsec/FinalReport.pdf
	 */
	public static boolean checkDHExponentialValidity(Class caller, BigInteger exponential) {
		int onesCount=0, zerosCount=0;
	
		// Ensure that we have at least 16 bits of each gender
		for(int i=0; i < exponential.bitLength(); i++)
			if(exponential.testBit(i))
				onesCount++;
			else
				zerosCount++;
		if((onesCount<16) || (zerosCount<16)) {
			Core.logger.log(caller, "The provided exponential contains "+zerosCount+" zeros and "+onesCount+" ones wich is unacceptable!", Logger.ERROR);
			return false;
		}
		
		// Ensure that g^x > 2^24
		if(MIN_EXPONENTIAL_VALUE.compareTo(exponential) > -1) {
			Core.logger.log(caller, "The provided exponential is smaller than 2^24 which is unacceptable!", Logger.ERROR);
			return false;
		}
		// Ensure that g^x < (p-2^24)
		if(MAX_EXPONENTIAL_VALUE.compareTo(exponential) < 1) {
			Core.logger.log(caller, "The provided exponential is bigger than (p - 2^24) which is unacceptable!", Logger.ERROR);
			return false;
		}
		
		return true;
	}
	
	public static DHGroup getGroup() {
		return group;
	}
	
	
	
}
