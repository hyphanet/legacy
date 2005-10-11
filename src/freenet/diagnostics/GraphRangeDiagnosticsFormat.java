package freenet.diagnostics;

import freenet.diagnostics.EventDequeue.Tail;

public class GraphRangeDiagnosticsFormat implements DiagnosticsFormat {

	private final int period;
	private final int type;
	private GraphRange r;

	public GraphRangeDiagnosticsFormat(int period, int type) {
		this.period = period;
		this.type = type;

		r = null;
	}

	public String formatStart(DiagnosticsCategory dc) {
		return "";
	}

	public String formatEnd(DiagnosticsCategory dc) {
		return "";
	}

	public String format(RandomVar rv) {
		EventDequeue el = rv.getEvents(period);
		if (el == null) {
			r = new GraphRange(null, Diagnostics.NUMBER_OF_EVENTS);
		} else {
			el.open(rv);
			Tail reader = el.getTail();
			r = new GraphRange(reader.elements(), type);

			el.close();
		}
		return "";
	}

	public GraphRange getRange() {
		return r;
	}
}
