package freenet.support;

import java.util.Vector;
import java.util.Enumeration;

/**
 * Yet another ADT. I moved it out of Ticker where it has been for a while, so
 * it should work.
 * 
 * @author oskar
 */
public class Heap {

	private static class WrapperElement extends Element {

		private Comparable c;

		WrapperElement(Comparable c) {
			this.c = c;
		}

		public Comparable content() {
			return c;
		}

		public final int compareTo(Object e) {
			return c.compareTo(((WrapperElement) e).c);
		}

		public final String toString() {
			return c.toString();
		}
	}

	public static abstract class Element implements Comparable {

		private int pos = 0;
		private Heap heap = null;

		/**
		 * By default this returns the Element itself, but if the Element wraps
		 * another Comparable, it is returned.
		 */
		public Comparable content() {
			return this;
		}

		abstract public int compareTo(Object e);

		/**
		 * Removes the element from the list.
		 * 
		 * @return true if the elemnt was in the list.
		 */
		public boolean remove() {
			//if (executed || pos == 0) {
			if (pos == 0) {
				return false;
			} else {
				heap.remove(pos);
				pos = 0;
				heap = null;
				return true;
			}
		}
	}

	private final Vector events;

	private final int maxFreeSpaceSlack;

	public Heap() {
		this(10);
	}

	public Heap(int initialCapacity) {
		this(initialCapacity, 500);
	}
	public Heap(int initialCapacity, int maxSpaceslack)
	{
		events = new Vector(initialCapacity);
		events.addElement(new Object());
		this.maxFreeSpaceSlack = maxSpaceslack;
	}

	/**
	 * Puts an object in the Heap by wrapping an Element around it.
	 */
	public Element put(Comparable c) {
			Element evt = new WrapperElement(c);
			put(evt);
			return evt;
		}

	/**
	 * Puts an object that subclasses Element directly in this list.
	 */
	public void put(Element evt) throws PromiscuousItemException {
		if (evt.pos != 0)
			throw new PromiscuousItemException(evt);

		synchronized (events) {
			events.addElement(evt); // just to increase the size
			int i = events.size() - 1;
			put(evt, i);
		}
	}

	private void put(Element evt, int i) {
		Element tmp;
		while (i > 1
			&& evt.compareTo((tmp = (Element) events.elementAt(i >> 1))) > 0) {
			tmp.pos = i;
			events.setElementAt(tmp, i);
			i = i >> 1;
		}
		evt.pos = i;
		evt.heap = this;
		events.setElementAt(evt, i);
	}

	private void remove(int i) {
		synchronized (events) {
			Element tmp = (Element) events.lastElement();
			((Element) events.elementAt(i)).pos = 0;

			events.removeElementAt(events.size() - 1);

			// The JVM will not automatically trim the list. Take up the slack
			// only when the remainder isn't a trivial size.
			if (events.capacity() - events.size() > maxFreeSpaceSlack ) {
				events.trimToSize();
			}

			if (events.size() <= i) {
				return; // we were removing the very last element
			}

			int child;
			Element childe;
			while (true) {
				int shifti = i << 1;
				if (shifti > (events.size() - 1)) {
					break;
				} else if (shifti > (events.size() - 2)) {
					child = shifti;
				} else {
					child =
						(((Element) events.elementAt(shifti))
							.compareTo(events.elementAt(shifti + 1))
							> 0
							? shifti
							: shifti + 1);
				}
				if (((Element) events.elementAt(child)).compareTo(tmp) < 0) {
					break;
				} else {
					childe = (Element) events.elementAt(child);
					childe.pos = i;
					events.setElementAt(childe, i);
					i = child;
				}
			}
			put(tmp, i);
		}
	}

	public Comparable top() {
		synchronized (events) {
			if (events.size() == 1)
				return null;
			Element r = (Element) events.elementAt(1);
			return r instanceof WrapperElement ? ((WrapperElement) r).c : r;
		}
	}

	public Comparable pop() {
		synchronized (events) {
			if (events.size() == 1)
				return null;
			Element r = (Element) events.elementAt(1);
			remove(1);
			r.pos = 0;
			r.heap = null;
			return r instanceof WrapperElement ? ((WrapperElement) r).c : r;
		}
	}

	/**
	 * A debugging method that checks that this object retains the heap
	 * property.
	 */
	public boolean checkHeap() {
		for (int i = 1; i < events.size(); i++) {
			Element r = (Element) events.elementAt(i);
			if (i << 1 < events.size()) {
				Element c1 = (Element) events.elementAt(i << 1);
				if (r.compareTo(c1) < 0) {
					System.err.println(
						"Heap integrity failed at pos "
							+ i
							+ " because "
							+ c1
							+ " is child to "
							+ r);
					System.err.println(toString());
					return false;
				}
			}
			if (i << 1 + 1 < events.size()) {
				Element c2 = (Element) events.elementAt(i << 1 + 1);
				if (r.compareTo(c2) < 0) {
					System.err.println(
						"Heap integrity failed at pos "
							+ i
							+ " because "
							+ c2
							+ " is child to "
							+ r);
					System.err.println(toString());
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Returns an enumeration of the elements in the heap order.
	 */
	public Enumeration elements() {
		Enumeration e = events.elements();
		e.nextElement(); // kill dummy
		return e;
	}

	public Element[] elementArray() {
		Object[] os = new Object[events.size()];
		events.copyInto(os);
		Element[] el = new Element[events.size() - 1];
		System.arraycopy(os, 1, el, 0, el.length);
		return el;
	}

	public final int size() {
		return events.size() - 1;
	}

	public String toString() {
		StringBuffer sb = new StringBuffer("{ ");
		synchronized (events) {
			int i = 1;
			for (Enumeration e = elements(); e.hasMoreElements(); i++) {
				sb.append('(').append(i).append(") ");
				sb.append(e.nextElement());
				if (e.hasMoreElements())
					sb.append(", ");
			}
		}
		sb.append('}');
		return sb.toString();
	}

}
