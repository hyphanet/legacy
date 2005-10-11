/*
  This code is part of fproxy, an HTTP proxy server for Freenet.
  It is distributed under the GNU Public Licence (GPL) version 2.  See
  http://www.gnu.org/ for further details of the GPL.
*/

package freenet.client.http;

import freenet.Core;
import java.util.*;

public class ContextManager {
    private static Hashtable table = new Hashtable();

    private final static String makeID() {
        String candidate = null;
        do {
            candidate = Long.toHexString(Math.abs(Core.getRandSource().nextInt()));
        }
        while (table.get(candidate) != null);
        
        return candidate;
    }

    public final synchronized String add(Object obj) {
        String id = makeID();
        table.put(id, obj);
        return id;
    }
    
    public final synchronized void remove(String id) {
        table.remove(id);
    }

    public final synchronized Object lookup(String id) {
        if (id == null) {
            return null;
        }
        return table.get(id);
    }
    
    public final synchronized Enumeration ids() {
	return table.keys();
    }
}

