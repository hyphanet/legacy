package freenet.client;

/** Represents any single client to node request.
  * @author tavin
  */
public abstract class Request extends SimpleEventProducer {

    public Request() {
        super();
    }

    private int state = INIT;

    /** Request states */
    public static final int
        INIT         = 0,
        PREPARED     = 1,
        REQUESTING   = 2,
        DONE         = 3,
        CANCELLED    = -1,
        FAILED       = -2;

    /** @return  the current state of the request */
    public final int state() {
        return state;
    }

    final void state(int state) throws IllegalArgumentException {
        if (state >= Request.FAILED && state <= Request.DONE)
            this.state = state;
        else
            throw new IllegalArgumentException("state out of range");
    }

    /** @return  the current state as a name */
    public final String stateName() {
        return nameOf(state);
    }

    /** @return  a string describing a state */
    public static String nameOf(int state) {
        switch (state) {
            case INIT:          return "INIT";
            case PREPARED:      return "PREPARED";
            case REQUESTING:    return "REQUESTING";
            case DONE:          return "DONE";
            case CANCELLED:     return "CANCELLED";
            case FAILED:
            default:            return "FAILED";
        }
    }
}


