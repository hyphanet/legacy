package freenet.support;

/**
 * An irreversible may be changed once from the state in which it began, 
 * but never again.
 *
 * @author oskar
 */

public class Irreversible {

    private volatile boolean changed = false;
    private volatile boolean state;

    public Irreversible(boolean initialState) {
        this.state = initialState;
    }

    private synchronized final boolean priv_change(boolean newState){
        if (changed)
            return false;
        changed = true;
        state   = newState;
        return true;
    }

    /**
     * Toggles the state.
     * @throws IrreversibleException  if the state already changed once
     */
    public final void change() throws IrreversibleException {
        if(!priv_change(!state))
        	throw new IrreversibleException();
    }
    
    /**
     * Toggles the state if possible.
     * @return wheter or not it was possible to change the state
     * (wheter or not it was already changed in the past)
     */
    public final boolean tryChange(){
        return priv_change(!state);
    }

    /**
     * @param newState new boolean value for the state
     * @throws IrreversibleException  only if newState != current state, and
     *                                already change once.
     */
    public synchronized final void change(boolean newState) throws IrreversibleException {
        if (state != newState)
            priv_change(newState);
    }

    public synchronized final boolean state() {
        return state;
    }
}

