package freenet.support;

import java.util.*;

public interface ReversibleSortedMap extends SortedMap {
    /** 
     * Return the key set, possibly reversed
     * @param backwards whether to reverse the order of the 
     * iterators for the returned set
     */
    Set keySet(boolean backwards);
}

