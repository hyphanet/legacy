package freenet.node;

import freenet.config.Params;

/**
 * Defines a class that can recieve announcements about configuration
 * options being specified along a specific path.  These listeners are
 * registered with the NodeConfigUpdater and recieve notification whenever
 * the overall configuration changes, providing them with the path that they
 * registered with as well as the values currently stored for that property.
 * 
 * Note that when a ConfigUpdateListener initially registers with the 
 * NodeConfigUpdater, the updater fires off a configPropertyUpdated to the 
 * listener with the value of the property requested as it stands at the
 * time of registration (therefore updating the listener from an unknown state
 * to one with the NodeConfigUpdater's latest data)
 *
 */
public interface ConfigUpdateListener {
    /**
     * Notify the listener that the property specified by path has been changed
     * to the value given.
     *  
     * @param path defines what configuration property was updated 
     *             (allows a.b.c notation)
     * @param val  value the configuration system has for the property
     */
    public void configPropertyUpdated(String path, String val);
    
    /**
     * Notify the listener that the property specified by path has been changed
     * to the field set given.
     *  
     * @param path defines what configuration property was updated 
     *             (allows a.b.c notation)
     * @param fs   value the configuration system has for the field set (if
     *             the path is a.b.c and the configuration system has properties
     *             a.b.c.d and a.b.c.e, fs will contain d and e)
     */
    public void configPropertyUpdated(String path, Params fs);
}
