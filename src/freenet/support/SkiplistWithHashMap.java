package freenet.support;

import freenet.support.Skiplist;
import freenet.support.Skiplist.SkipNodeImpl;
import java.util.*;

/* A Map implemented using both a Skiplist and a Hashtable
 * Keys MUST be AKO freenet.support.Comparable
 * Does not allow null keys
 */

// FIXME: make the hash map to the nodes.. or use a HashSet of the nodes, for minimal memory use?

public class SkiplistWithHashMap implements ReversibleSortedMap {
    private Hashtable hash;
    private Skiplist sk = new Skiplist(32, new Random());
    
    private final static int ENTRIES = 0;
    private final static int KEYS = 1;
    private final static int VALUES = 2;
    
    public SkiplistWithHashMap(int hashsize) {
    	hash = new Hashtable(hashsize);
    }
    
    public void clear() {
    	//hash.clear();
    	throw new UnsupportedOperationException("skiplist has no clear!");
    }
    
    public boolean containsKey(Object key) {
    	return hash.containsKey(key);
    }
    
    public boolean containsValue(Object val) {
    	return hash.containsValue(val);
    }
    
    public synchronized Set entrySet() {
// 	return hash.entrySet();
	/* We cannot return hash.entrySet because
	 * a) Map.Entry.setValue !
	 * b) Sort order
	 */
	return new MySet(ENTRIES, false);
    }
    
    public synchronized boolean equals(Object o) {
	if(o instanceof SkiplistWithHashMap) {
	    SkiplistWithHashMap m = (SkiplistWithHashMap)o;
	    return m.hash.equals(hash);
	} else {
	    if(o instanceof Map) {
		return entrySet().equals(((Map)o).entrySet());
	    } else return false;
	}
    }
    
    public Object get(Object o) {
    	return hash.get(o);
    }
    
    public int hashCode() {
    	return hash.hashCode();
    }
    
    public boolean isEmpty() {
    	return hash.isEmpty();
    }
    
    public Set keySet() {
	// FIXME! - this is the most important bit!
	return new MySet(KEYS, false);
    }
    
    public Set keySet(boolean backwards) {
	return new MySet(KEYS, backwards);
    }
    
    public synchronized Object put(Object key, Object value) {
	if(key == null) throw new NullPointerException();
	// We do not allow null keys
	Comparable c = (Comparable)key;
	Object out = hash.put(c, value);
	sk.treeInsert(new MySkipNodeImpl(c, value), true);
	return out;
    }
    
    public void putAll(Map t) {
    	throw new UnsupportedOperationException();
    }
    
    public synchronized Object remove(Object key) {
	Comparable c = (Comparable)key;
	Object out = hash.remove(c);
	sk.treeRemove(c);
	return out;
    }
    
    public int size() {
    	return hash.size();
    }
    
    public Collection values() {
    	return hash.values();
    }
    
    public Hashtable cloneHash() {
    	return (Hashtable)(hash.clone());
    }
    
    public java.util.Comparator comparator() {
	return null;
    }
    
    public synchronized Object firstKey() {
	return ((MySkipNodeImpl)sk.treeMin()).comp;
    }
    
    public synchronized Object lastKey() {
	return ((MySkipNodeImpl)sk.treeMax()).comp;
    }
    
    public SortedMap headMap(Object o) {
	if(!(o instanceof Comparable))
	    throw new ClassCastException();
	return new MyRestrictedMap(null, (Comparable)o);
    }
    
    public SortedMap tailMap(Object o) {
	if(!(o instanceof Comparable))
	    throw new ClassCastException();
	return new MyRestrictedMap((Comparable)o, null);
    }
    
    public SortedMap subMap(Object from, Object to) {
	return new MyRestrictedMap((Comparable)from, (Comparable)to);
    }
    
    protected class MySkipNodeImpl extends SkipNodeImpl
	implements Map.Entry {
	public Object value;
	public MySkipNodeImpl(Comparable key, Object value) {
	    super(key);
	    this.value = value;
	}
	
	public String toString() {
	    return comp.toString() + ":" + value.toString();
	}
	
	public boolean equals(Object o) {
	    if(!(o instanceof MySkipNodeImpl)) return false;
	    MySkipNodeImpl m = (MySkipNodeImpl)o;
	    return (m.comp.equals(comp) && m.value.equals(value));
	}
	
	public Object getKey() {
	    return comp;
	}
	
	public Object getValue() {
	    return value;
	}
	
	public int hashCode() {
	    return comp.hashCode();
	}
	
	public Object setValue(Object newval) {
	    Object old = value;
	    value = newval;
	    return old;
	}
    }
    
    protected class MyRestrictedMap implements ReversibleSortedMap {
	Comparable min; // contained
	Comparable max; // not contained
	MyRestrictedMap(Comparable from, Comparable to) {
	    min = from;
	    max = to;
	}
	
	public void clear() {
	    throw new UnsupportedOperationException("skiplist.MyRestrictedMap has no clear!");
	}
	
	public boolean containsKey(Object key) {
	    Comparable c = (Comparable)key;
	    if(min != null && c.compareTo(min) == -1)
		return false;
	    if(max != null && c.compareTo(max) >= 0)
		return false;
	    return SkiplistWithHashMap.this.containsKey(key);
	}
	
	public boolean containsValue(Object value) {
	    if(SkiplistWithHashMap.this.containsValue(value)) {
		Set keys = keySet();
		synchronized(SkiplistWithHashMap.this) {
		    Iterator i = keys.iterator();
		    while(i.hasNext()) {
			Comparable comp = (Comparable)i.next();
			Object val = SkiplistWithHashMap.this.get(comp);
			if(value == null && val == null) return true;
			if(value.equals(val)) return true;
		    }
		    return false;
		}
	    } else return false;
	}
	
	public Set entrySet() {
	    return new MySet(ENTRIES, false, min, max);
	}
	
	protected final SkiplistWithHashMap parent() {
	    return SkiplistWithHashMap.this;
	} // FIXME!
	
	public boolean equals(Object o) {
	    if(o instanceof MyRestrictedMap) {
		MyRestrictedMap m = (MyRestrictedMap)o;
		if(m.parent() == SkiplistWithHashMap.this) {
		    if((m.min == min) && (m.max == max)) return true;
		}
		return entrySet().equals(m.entrySet());
	    } else {
		if(o instanceof Map) {
		    Map m = (Map)o;
		    return entrySet().equals(m.entrySet());
		} else return false;
	    }
	}
	
	public Object get(Object key) {
	    Comparable c = (Comparable)key;
	    if(min != null && c.compareTo(min) == -1)
		return null;
	    if(max != null && c.compareTo(max) >= 0)
		return null;
	    return SkiplistWithHashMap.this.get(c);
	}
	
	public int hashCode() {
	    // FIXME: this is slow!
	    // Cannot cache it though, unless no changes are made in parent
	    Iterator i = keySet().iterator();
	    int hc = 0;
	    while(i.hasNext()) {
		hc += i.next().hashCode();
	    }
	    return hc;
	}
	
	public boolean isEmpty() {
	    // Any more efficient way of doing this?
	    Object o = firstKey();
	    return (o == null);
	}
	
	public Set keySet() {
	    return new MySet(KEYS, false, min, max);
	}
	
	public Set keySet(boolean reversed) {
	    return new MySet(KEYS, reversed, min, max);
	}
	
	public Object put(Object key, Object value) {
	    Comparable c = (Comparable)key;
	    if(min != null && c.compareTo(min) == -1)
		throw new IllegalArgumentException("too low");
	    if(max != null && c.compareTo(max) >= 0)
		throw new IllegalArgumentException("too high");
	    return SkiplistWithHashMap.this.put(key, value);
	}
	
	public void putAll(Map t) {
	    throw new UnsupportedOperationException();
	}
	
	public Object remove(Object key) {
	    Comparable c = (Comparable)key;
	    if(min != null && c.compareTo(min) == -1)
		return null;
	    if(max != null && c.compareTo(max) >= 0)
		return null;
	    return SkiplistWithHashMap.this.remove(c);
	}
	
	public int size() {
	    // Hrrm
	    // FIXME: any way to do this more efficiently?
	    return keySet().size();
	}
	
	public Collection values() {
	    // Don't you just love java's TMTOWTDI interfaces?
	    return new MySet(VALUES, false, min, max);
	}
	
	public java.util.Comparator comparator() {
	    return null;
	}
	
	public Object firstKey() {
	    if(min == null) return SkiplistWithHashMap.this.firstKey();
	    synchronized(SkiplistWithHashMap.this) {
		MySkipNodeImpl m = (MySkipNodeImpl)(sk.treeMinConstrained(min, true));
		if(m == null) return null;
		Comparable key = m.comp;
		if(max != null && key.compareTo(max) == 1)
		    return null;
		return key;
	    }
	}
	
	public Object lastKey() {
	    if(max == null) return SkiplistWithHashMap.this.lastKey();
	    synchronized(SkiplistWithHashMap.this) {
		MySkipNodeImpl m = (MySkipNodeImpl)(sk.treeMaxConstrained(min, true));
		if(m == null) return null;
		Comparable key = m.comp;
		if(max != null && key.compareTo(max) == 1)
		    return null;
		return key;
	    }
	}
	
	public SortedMap headMap(Object o) {
	    Comparable c = (Comparable)o;
	    if(c.compareTo(max) == 1) c = max;
	    return new MyRestrictedMap(null, c);
	}
	
	public SortedMap tailMap(Object o) {
	    Comparable c = (Comparable)o;
	    if(c.compareTo(min) == -1) c = min;
	    return new MyRestrictedMap(c, null);
	}
	
	public SortedMap subMap(Object from, Object to) {
	    Comparable fromkey = (Comparable)from;
	    Comparable tokey = (Comparable)to;
	    if(tokey.compareTo(max) == 1) tokey = max;
	    if(fromkey.compareTo(min) == 1) fromkey = min;
	    return new MyRestrictedMap(fromkey, tokey);
	}
    }
    
    protected class MySet implements Set {
	Comparable from;
	Comparable to;
	int mode;
	boolean backwards;
	
	MySet(int mode, boolean backwards) {
	    this.mode = mode;
	    from = null;
	    to = null;
	    this.backwards = backwards;
	}
	
	MySet(int mode, boolean backwards, Comparable from, Comparable to) {
	    this.mode = mode;
	    this.to = to;
	    this.from = from;
	    this.backwards = backwards;
	}
	
	public boolean add(Object o) {
	    throw new UnsupportedOperationException();
	}
	
	public boolean addAll(Collection c) {
	    throw new UnsupportedOperationException();
	}
	
	public void clear() {
	    throw new UnsupportedOperationException();
	    // FIXME
	}
	
	public boolean contains(Object o) {
	    if(mode == ENTRIES) {
		MySkipNodeImpl m = (MySkipNodeImpl)o;
		Comparable key = m.comp;
		if(from != null && key.compareTo(from) == -1)
		    return false;
		if(to != null && key.compareTo(to) >= 0)
		    return false;
		Object got = SkiplistWithHashMap.this.get(key);
		if(got == null) return false;
		return got.equals(m.value);
	    } else if(mode == KEYS) {
		Comparable key = (Comparable)o;
		if(from != null && key.compareTo(from) == -1)
		    return false;
		if(to != null && key.compareTo(to) >= 0)
		    return false;
		return SkiplistWithHashMap.this.containsKey(key);
	    } else if(mode == VALUES) {
		if(from == null && to == null) {
		    return SkiplistWithHashMap.this.containsValue(o);
		} else {
		    // FIXME
		    throw new UnsupportedOperationException();
		}
	    } else {
		throw new IllegalStateException();
	    }
	}
	
	public boolean containsAll(Collection c) {
	    throw new UnsupportedOperationException();
	    // FIXME
	}
	
	protected final SkiplistWithHashMap parent() {
	    return SkiplistWithHashMap.this;
	} // FIXME!
	
	public boolean equals(Object o) {
	    if(o instanceof MySet) {
		MySet m = (MySet)o;
		if(m.parent() == SkiplistWithHashMap.this) {
		    if(m.mode == mode && m.from == from && m.to == to) 
			return true;
		}
	    } else if(!(o instanceof Set)) {
		return false;
	    }
	    Set s = (Set)o;
	    Iterator i1 = iterator();
	    Iterator i2 = s.iterator();
	    while(i1.hasNext() && i2.hasNext()) {
		Object o1 = i1.next();
		Object o2 = i2.next();
		if(!(o1.equals(o2))) return false;
	    }
	    if(i1.hasNext() ^ i2.hasNext())
		return false;
	    return true;
	}
	
	public int hashCode() {
	    // FIXME: slow
	    // But can't cache it unless invalidate on any change
	    Iterator i = iterator();
	    int hc = 0;
	    while(i.hasNext()) {
		hc += i.next().hashCode();
	    }
	    return hc;
	}
	
	public boolean isEmpty() {
	    if(from == null && to == null)
		return SkiplistWithHashMap.this.isEmpty();
	    synchronized(SkiplistWithHashMap.this) {
		MySkipNodeImpl m = getFirst();
		if(m == null) return true;
		if(to != null) {
		    if(m.comp.compareTo(to) >= 0) return true;
		}
		return false;
	    }
	}
	
	protected MySkipNodeImpl getFirst() {
	    synchronized(SkiplistWithHashMap.this) {
		MySkipNodeImpl m;
		if(((!backwards) && (from != null)) || (backwards && (to != null))) {
		    if(!backwards) {
			m = (MySkipNodeImpl)(sk.treeMinConstrained(from, false));
			if(to != null && m.comp.compareTo(to) >= 1) m = null;
		    } else {
			m = (MySkipNodeImpl)(sk.treeMaxConstrained(to, false));
			if(from != null && m.comp.compareTo(from) == -1) m = null;
		    }
		    // Inclusive at start, exclusive at end, as per docs
		} else {
		    if(!backwards) {
			m = (MySkipNodeImpl)(sk.treeMin());
			if(to != null && m.comp.compareTo(to) >= 1) m = null;
		    } else {
			m = (MySkipNodeImpl)(sk.treeMax());
			if(from != null && m.comp.compareTo(from) == -1) m = null;
		    }
		}
		return m;
	    }
	}
	
	protected MySkipNodeImpl getFirstAfter(MySkipNodeImpl m) {
	    synchronized(SkiplistWithHashMap.this) {
		if(!backwards) {
		    m = (MySkipNodeImpl)(sk.treeMinConstrained(m.comp, false));
		    if(to != null && m.comp.compareTo(to) == 1) m = null;
		} else {
		    m = (MySkipNodeImpl)(sk.treeMaxConstrained(m.comp, false));
		    if(from != null && m.comp.compareTo(from) == -1) m = null;
		}
	    }
	    return m;
	}
	
	protected MySkipNodeImpl getSkipNode(Object o) {
	    if(mode == KEYS) {
		Comparable c = (Comparable)o;
		// FIXME - put entries in the hashmap, use that
		synchronized(SkiplistWithHashMap.this) {
		    return (MySkipNodeImpl)(sk.treeSearch(c));
		}
	    } else if(mode == ENTRIES) {
		Map.Entry e = (Map.Entry)o;
		return getSkipNode(e.getKey());
	    } else if(mode == VALUES) {
		MySkipNodeImpl m = getFirst();
		while(m != null) {
		    if(m.value == o) return m;
		    m = getFirstAfter(m);
		}
		return null;
	    } else {
		throw new IllegalStateException();
	    }
	}
	
	public Iterator iterator() {
	    return new MyIterator();
	}
	
	public boolean remove(Object o) {
	    MySkipNodeImpl m = getSkipNode(o);
	    if(m == null) return false;
	    return SkiplistWithHashMap.this.remove(m.comp) != null;
	}
	
	public boolean removeAll(Collection c) {
	    throw new UnsupportedOperationException();
	}
	
	public boolean retainAll(Collection c) {
	    throw new UnsupportedOperationException();
	}
	
	public int size() {
	    if(from == null && to == null)
		return SkiplistWithHashMap.this.size();
	    // FIXME: slow
	    Iterator i = iterator();
	    int count = 0;
	    while(i.hasNext()) {
		i.next();
		count++;
	    }
	    return count;
	}
	
	public Object[] toArray() {
	    // FIXME: slow
	    synchronized(SkiplistWithHashMap.this) {
		int sz = size();
		Object[] out = new Object[sz];
		Iterator it = iterator();
		for(int i=0;i<sz;i++) {
		    out[i] = it.next();
		}
		return out;
	    }
	}
	
	public Object[] toArray(Object[] o) {
	    synchronized(SkiplistWithHashMap.this) {
		int sz = size();
		int osz = o.length;
		if(osz < sz) {
		    throw new UnsupportedOperationException();
		    // FIXME: implement necessary reflection voodoo
		}
		Iterator it = iterator();
		for(int i=0;i<sz;i++) {
		    o[i] = it.next();
		}
		return o;
	    }
	}
	    
	protected class MyIterator implements Iterator {
	    Comparable curKey;
	    Comparable limit;
	    MySkipNodeImpl next;
	    
	    public MyIterator() {
		limit = to;
		next = getFirst();
		if(next != null) curKey = next.comp;
	    }
	    
	    public boolean hasNext() {
		return next != null;
	    }
	    
	    public Object next() {
		if(next == null) throw new NoSuchElementException();
		Object retval;
		if(mode == KEYS) {
		    retval = next.comp;
		} else if(mode == VALUES) {
		    retval = next.value;
		} else if(mode == ENTRIES) {
		    retval = next;
		} else {
		    throw new IllegalStateException("unknown mode");
		}
		// Now get the next one
		next = getFirstAfter(next);
		return retval;
	    }
	    
	    public void remove() {
		throw new UnsupportedOperationException();
		// FIXME
	    }
	}
    }
}

