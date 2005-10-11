package freenet;
import java.util.Enumeration;
import java.util.Hashtable;

import freenet.support.Selector;

public class PresentationHandler {

    private final Hashtable presentations = new Hashtable();
    private final Selector select=new Selector();

    public void register(Presentation p, int pref) {
        presentations.put(new Integer(p.designatorNum()),p);
        select.register(p, pref);
    }

    public Enumeration getPresentations() {
        return select.getSelections();
    }

    public Presentation get(int num) {
        return (Presentation) presentations.get(new Integer(num));
    }
    public Presentation getDefault(){
    	return (Presentation)select.getSelection();
    }

    public int size() {
        return select.size();
    }
}
