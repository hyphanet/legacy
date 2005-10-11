/*
 * Created on Apr 20, 2004
 *
 */
package freenet;

import java.util.Iterator;

/**
 * @author Iakin
 * An simplistic ArrayList implementation that allows for very effective non-synchronized iteration
 * but at the same time provides horribly slow mutative operations (insert, remove only currently)
 * See the documentation for the JDK 1.5 java.util.concurrent.CopyOnWriteArrayList class for more information
 * TODO: Remove this class and use the above class whenever 1.5-compliant JVM:s becomes more widespread 
  */
public class CopyOnWriteArrayList{
	private Object[] list = new Object[0];
	
	public synchronized void add(Object o){
		Object[] newList = new Object[list.length+1];
		System.arraycopy(list,0,newList,0,list.length);
		newList[newList.length-1] = o;
		list = newList;
	}
	/** Removes the first encountered occurrence of the indicated object from the collection
	 *  Code shamelessly copied from Doug Lea's Concurrent java classes
	 *  @return true iff the operation mutated the container
	 * */
	public synchronized boolean remove(Object element) {
		int len = list.length;
		if (len == 0)
			return false;
		//Copy while searching for element to remove
		//This wins in the normal case of element being present
		int newlen = len - 1;
		Object[] newArray = new Object[newlen];
		for (int i = 0; i < newlen; ++i) {
			if (element == list[i] || (element != null && element.equals(list[i]))) {
				//found one; copy remaining and exit
				for (int k = i + 1; k < len; ++k)
					newArray[k - 1] = list[k];
				list = newArray;
				return true;
			} else
				newArray[i] = list[i];
		}
		//special handling for last cell
		if (element == list[newlen] || (element != null && element.equals(list[newlen]))) {
			list = newArray;
			return true;
		} else
			return false; // throw away copy
	}
	
	public int size(){
		return list.length;
	}
	
	public boolean isEmpty(){
		return list.length == 0;
	}
	
	//Returns the first item in the list of or null if no such item exists  
	public Object first(){
		Object[] l = list;
		if(l.length >0)
			return l[0];
		else
			return null;
	}
	
	Iterator iterator(){
		return new SnapshotIterator(this);
	}
	private static class SnapshotIterator implements Iterator{
		private Object[] list;
		private int next = 0;
		SnapshotIterator(CopyOnWriteArrayList l){
			this.list = l.list;
		}
		public void remove() {
			throw new UnsupportedOperationException("Remove not supported");
		}

		public boolean hasNext() {
			return next < list.length;
		}

		public Object next() {
			return list[next++];
		}
		
	}
}
