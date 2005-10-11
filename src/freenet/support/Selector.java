package freenet.support;
import java.util.Enumeration;
import java.util.NoSuchElementException;
/**
 * @author oskar
 */

public class Selector {

    private static class LElement {
    	public LElement next;
    	public int pref;
    	public Object o;
    	public LElement(Object o, int pref) {
    		this.pref = pref;
    		this.o = o;
    	}
    }

    private LElement list = new LElement(null, 0);
    private int size = 0;

    /**
     * Registers an available Selection. 
     *
     * @param o      The selection value.
     * @param pref   The preference value of this selection.
     */
	public void register(Object o, int pref) {
		if (o == null)
			throw new NullPointerException();
		synchronized (list) {
			LElement l = new LElement(o, pref);
			if (list.o == null)
				list = l;
			else {
				LElement l1 = null;
				LElement l2 = list;
				while (l2 != null && pref < l2.pref) {
					l1 = l2;
					l2 = l2.next;
				}
				if (l1 != null)
					l1.next = l;
				else
					list = l;
				l.next = l2;
			}
			++size;
		}
	}

    /** @return  the number of registered selections
      */
    public int size() {
        return size;
    }
    
    /**
     * Returns the element with the highest preference.
     */
    public Object getSelection() {
        return list == null ? null : list.o;
    }


    /**
     * Returns an Enumeration of the selection in order of preference, the
     * greatest value first.
     */
    public Enumeration getSelections() {
    	return new SEnumeration();
    }

    /**
     * Returns the preference of a selection. Warning, O(n) method.
     */
    public int preference(Object o) throws NoSuchElementException {
        synchronized(list) {
            LElement s = list;
            while (s != null) {
                if (s.o.equals(o))
                    return s.pref;
            }
            throw new NoSuchElementException("Cannot find: " + o.toString());
        }
    }


	private class SEnumeration implements Enumeration {
		private LElement s;
		public SEnumeration() {
			s = list;
		}
		public boolean hasMoreElements() {
			synchronized (list) {
				return s != null;
			}
		}
		public Object nextElement() {
			synchronized (list) {
				LElement r = s;
				s = s.next;
				return r.o;
			}
		}
	}
}
