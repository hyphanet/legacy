package freenet.node.states.request;


/**
 * Indicates that a request was not routed because it timed out (in the
 * sense of HTL reaching 0).
 */

class EndOfRouteException extends Exception {

    EndOfRouteException() {
        super();
    }

    EndOfRouteException(String s) {
        super(s);
    }
}

