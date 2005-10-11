package freenet.diagnostics;
import java.util.Vector;
import java.util.Enumeration;
import java.io.PrintWriter;

class StdDiagnosticsCategory implements DiagnosticsCategory {

    private StdDiagnosticsCategory parent;
    private String name, comment;

    private Vector categories;
    private Vector fields;

    private int level;

    StdDiagnosticsCategory(String name, String comment, 
                           StdDiagnosticsCategory parent) {
        this.name = name;
        this.comment = comment;
        this.parent = parent;

        level = parent == null ? 1 : parent.level() + 1;
        fields = new Vector();
        categories = new Vector();
        if (parent != null)
            parent.addCategory(this);
    }

    void writeVars(PrintWriter out, DiagnosticsFormat format) {
        out.println(format.formatStart(this));
        for (Enumeration e = categories() ; e.hasMoreElements() ;) {
            ((StdDiagnosticsCategory) e.nextElement()).writeVars(out,
                                                                 format);
        }
        for (Enumeration e = fields() ; e.hasMoreElements() ;) {
            out.println(format.format((RandomVar) e.nextElement()));
        }
        out.println(format.formatEnd(this));
    }

    void addField(RandomVar rv) {
        fields.addElement(rv);
    }

    void addCategory(StdDiagnosticsCategory c) {
        categories.addElement(c);
    }

    Enumeration fields() {
        return fields.elements();
    }

    Enumeration categories() {
        return categories.elements();
    }

    StdDiagnosticsCategory getStdParent() {
        return parent;
    }

    public String name() {
        return name;
    }

    public String comment() {
        return comment;
    }

    public int level() {
        return level;
    }

    public DiagnosticsCategory getParent() {
        return parent;
    }


}
