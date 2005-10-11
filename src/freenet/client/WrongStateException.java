package freenet.client;

import freenet.client.Request;

public class WrongStateException extends Exception {
    int stateShouldBe;
    int stateIs;
    public WrongStateException(String s, int stateShouldBe, int stateIs) {
        super("Wrong state: "+Request.nameOf(stateIs) + " should be "+
	      Request.nameOf(stateShouldBe)+": "+s);
    }
}
