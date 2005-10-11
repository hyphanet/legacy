package freenet.diagnostics;

public interface DiagnosticsFormat {

    String formatStart(DiagnosticsCategory dc);

    String formatEnd(DiagnosticsCategory dc);

    String format(RandomVar rv);

}
