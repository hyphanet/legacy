package freenet.node;

/** Just a tag really.
  */
public abstract class StateException extends Exception {
    StateException() {
        super();
    }
    StateException(String s) {
        super(s);
    }
}
