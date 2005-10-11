package freenet.interfaces;

import freenet.config.*;

/** The simplest abstraction of a service, servlet, plug-in, etc.
  * @author tavin
  */
public interface Service extends ConnectionRunner {

    /** @return  a unique identifying name, such as
      *          a class name or registry name
      */
    String name();

    /** @return  a Config object describing the Service's initialization
      *          parameters, or null if it has none.
      */
    Config getConfig();
    
    /** Called only once, after the Service is first constructed.
      * @param p  a Params object generated from the Options in getConfig()
      *           and initialized through readArgs/readParams
      * @param serviceName name of the service so that it can register config
      *                    event update listeners
      * @throws ServiceException  if the Service cannot run with the given
      *                           initialization parameters
      */
    void init(Params p, String serviceName) throws ServiceException;

    /** Set whether to pre-initialize
     */
    void setInitFirst(boolean b);

    /** Late initialization, with a running node
     */
    void starting();
}


