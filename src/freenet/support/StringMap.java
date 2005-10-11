package freenet.support;

/**
 * A one to one String -> Object mapping with a fixed
 * order.
 * <p>
 * This interface was added to support 
 * displaying runtime diagnostics which
 * are not nescessarily known at the time
 * the display client (e.g. NodeStatusServlet)
 * is compiled.
**/
public interface StringMap {
    /**
     * @return An ordered array of unique String keys.
     **/
    String[] keys();

    /**
     * @return An array of Object values corresponding 
     *         to keys().
     **/
    Object[] values();

    /**
     * @return The Object mapped to the key. null if there
     *         isn't one.
     **/
    Object value(String key);
} 

