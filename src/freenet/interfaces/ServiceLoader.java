package freenet.interfaces;

import freenet.node.Node;
import java.lang.reflect.*;

/**
 * Instantiates a Service by searching for a compatible constructor.
 */
public abstract class ServiceLoader {

    /**
     * @param cls   a Class implementing Service
     * @param node  used for constructors that take a Node or Core
     */
    public static Service load(Class cls, Node node, boolean initFirst)
	throws ServiceException { 
        
        if (!Service.class.isAssignableFrom(cls))
            throw new ServiceException("Class does not implement Service");
        
        // find a compatible constructor
        Constructor[] constructors = cls.getConstructors();
        Object[] args = null;
        int i;
        for (i=0; i<constructors.length; ++i) {
            Class[] types = constructors[i].getParameterTypes();
            if (types.length == 0) {
                args = new Object[0];
                break;
            }
            if (types.length == 1 && types[0].isAssignableFrom(Node.class)) {
                args = new Object[] {node};
                break;
            }
        }
        
        if (args == null)
            throw new ServiceException("No constructor found");
        
        try {
            Service s = (Service) constructors[i].newInstance(args);
	    s.setInitFirst(initFirst);
	    return s;
        }
        catch (Exception e) {
            throw new ServiceException(""+e);
        }
    }
}


