/*
 * Created on Jan 21, 2004
 *

 */
package freenet.support;

/**
 * @author Iakin
 * A class for containing and threadsafely counting on an 'int' value 
 */
public class AtomicIntCounter {
		//The volatile declaration ensures that any load, modify
		//and store bytecode ops on this int are done atomically by the JVM
		private volatile int val=0; 
    	
		/*Bumps the value one step*/
		public synchronized int inc(){
			//We need to have this method synchronized since '++' is
			//not an atomic op (it actually executes as val=val+1)
			return val++;
		}
		/*decreases the value one step*/
		public synchronized int dec(){
			//We need to have this method synchronized since '--' is
			//not an atomic op (it actually executes as val=val-1)
			return val--;
		}
		/*Returns the contained value*/
		public int get(){
			return val; //Use the fact that the value is declared volatile
		}
}
