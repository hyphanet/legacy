package freenet.diagnostics;
import java.util.Enumeration;
/**
 * The fields I need in the objects I list events. 
 *
 * @author oskar
 */

interface EventDequeue {

    /**
     * Called before reading and writing starts to an eventdequeue.
     * @param  rv  The RandomVar that contains this dequeue.
     */
    public void open(RandomVar rv);

    /**
     * Called after reading and writing to an eventdequeue is done.
     */
    public void close();

    /**
     * A String from which this dequeue can be restored after the node has
     * restarted.
     */
    public String restoreString();
	
    public Head getHead();
	public Tail getTail();
    
	//Returns an interface for operating on the Head part of the dequeue
    public interface Head{
		/**
		 * Pushes a VarEvent on the head of the dequeue.
		 */
		public void add(VarEvent ve);
    }
    
	//Returns an interface for operating on the Tail part of the dequeue
	//DONT! store the object.. refetch it when it is needed again
	public interface Tail{
		/**
		 * Returns the head VarEvent in the dequeue.
		 */
		public VarEvent get();
		/**
		 * Removes the head element in the dequeue.
		 * @return The element 
		 */
		public VarEvent remove();
		/**
		 * Enumerates the elements from first to last.
		 */
		public Enumeration elements();
		/**
		 * Purges any events older than tMaxAge from the dequeue
		 * @param oldestTimestampToAllow The oldest timestamp allowed to remain in the dequeue
		 */ 
		public void purgeOldEvents(long oldestTimestampToAllow);
	}
}



