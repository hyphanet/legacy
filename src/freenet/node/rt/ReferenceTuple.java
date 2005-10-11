package freenet.node.rt;

import java.util.Enumeration;
import java.util.NoSuchElementException;

/**
 * This is a linked-list element with some helper methods.
 */
class ReferenceTuple {

    static final class ReferenceEnumeration implements Enumeration {
        private ReferenceTuple rt;
        ReferenceEnumeration(ReferenceTuple rt) {
            this.rt = rt;
        }
        public final boolean hasMoreElements() {
            return rt != null;
        }
        public final Object nextElement() {
            if (rt == null) {
                throw new NoSuchElementException();
            }
            try {
                return rt.ref;
            }
            finally {
                rt = rt.next;
            }
        }
    }
    

    ReferenceTuple next = null;

    final Reference ref;

    ReferenceTuple(Reference ref) {
        this.ref = ref;
    }
}


