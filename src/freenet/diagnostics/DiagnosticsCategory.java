package freenet.diagnostics;

public interface DiagnosticsCategory {

    public String name();

    public String comment();

    public int level();

    public DiagnosticsCategory getParent();

}
