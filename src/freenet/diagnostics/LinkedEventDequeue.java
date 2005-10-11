package freenet.diagnostics;
import freenet.Core;
import freenet.support.*;
import java.util.Enumeration;

/**
 * A class for storing diagnostics events 
 *
 * @author oskar (initial version)
 * @author Iakin (remade the list construct into a dequeue construct to allow for async add/remove) 
 */
class LinkedEventDequeue implements EventDequeue {

    protected DoublyLinkedList ll; //The current appending-list (head)
	protected DoublyLinkedList tailList; //The always-sorted removallist (tail)
	
	//If nested synchronization is needed the convention it to
	//synch on tail before head..
    private Head headInterface = new HeadImpl(); //head access interface as well as head-lock
	private Tail tailInterface = new TailImpl(); //tail access interface as well as tail-lock

	private String sName;
	private long lastFlip =0;
    public void open(RandomVar rv) {
    	sName = rv.getName();
    }

    public void close() {
    }

    public LinkedEventDequeue() {
        ll = new DoublyLinkedListImpl();
    }

    public LinkedEventDequeue(String s) {
        this();
    }

    public String restoreString() {
        return "null";
    }

    //Transfers any current events in the headlist over to the tail-list
	//This method is the only allowed rendevouz-point between head and tail
    //Should be called in order to make Tail up-to-date...
 
	protected void flip() {
		synchronized (tailInterface) {
			lastFlip = System.currentTimeMillis();
			if (tailList == null)
				tailList = new DoublyLinkedListImpl();
			DoublyLinkedList newEvents;

			synchronized (headInterface) {
				if (ll.size() == 0) //No need to do anything
					return;
				newEvents = ll;
				ll = new DoublyLinkedListImpl();
			}
			//tailList.push(ll); If it only had been this easy... unfortunately we need to ensure some sorting too
			
			while (newEvents.size() > 0) {
				VarEvent ve = (VarEvent) newEvents.shift();
				if (tailList.size() == 0) {
					tailList.push(ve);
				} else {
					if (((VarEvent) tailList.tail()).time() <= ve.time()) { //Is 've' timestamped after the tail-most item in the tail-list
						tailList.push(ve); //If so, just stick the new event at the end of the tailList.. (optimization)
					} else { //Else, figure out where in the tailList the new event belongs
						// iterate backwards in time while future (!) events have occured.
						// this is 0(n). Assuming that the previous list is sorted this
						// shouldn't cause any performance issues. Testing shows that
						// we hardly ever get here, even on a very busy node.
						VarEvent ive;
						long etime = ve.time();
						Enumeration e = tailList.reverseElements();
						while (e.hasMoreElements()) {
							ive = (VarEvent) e.nextElement();
							if (ive.time() <= etime) {
								tailList.insertNext(ive, ve);
								break;
							}
						}
					}
				}
			}
		}
	}

	
	private static class EventTimeSorter implements java.util.Comparator
	{
		public int compare(Object o1, Object o2) {
			int i = new Long(((VarEvent)o1).time).compareTo(new Long(((VarEvent)o2).time));
			return i;
		}
		
	}

	public Head getHead() {
		return headInterface;
	}

	public Tail getTail() {
		flip(); //Transfer the latest events from head to tail.. 
		return tailInterface;
	}
	private class HeadImpl implements Head{
		public synchronized void add(VarEvent ve) {
			ll.push(ve);
			if(ll.size()%150000==0){
				if(lastFlip ==0)
					Core.logger.log(this,"Ouch, reached "+ll.size()/1000+"k events in list '"+sName+"', list has never been flipped. Might be a bad situation, please report to devl@freenetproject.org",new Exception("debug"),Logger.ERROR);
				else
					Core.logger.log(this,"Ouch, reached "+ll.size()/1000+"k events in list '"+sName+"', last flip was "+(System.currentTimeMillis()-lastFlip)/1000+" seconds ago. Might be a bad situation, please report to devl@freenetproject.org",new Exception("debug"),Logger.ERROR);
			}
		}
	}

	private class TailImpl implements Tail{

		public synchronized VarEvent get() {
			return (VarEvent) tailList.head();
		}

		public synchronized VarEvent remove() {
			return (VarEvent) tailList.shift();
		}

		public synchronized Enumeration elements() {
			return tailList.elements();
		}

		public synchronized void purgeOldEvents(long oldestTimestampToAllow) {
			while(get() != null && 
					get().time() <= oldestTimestampToAllow) {
					remove();
			}
		}
		
	}
}
