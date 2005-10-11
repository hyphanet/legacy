package freenet.node.states.request;

import freenet.Core;
import freenet.support.Logger;

/**
 * Message scheduled to indicate that the expected DataInsert has not been
 * received.
 * 
 * @author oskar
 */
class NoInsert extends RequestObject {
    
    NoInsert(long id) {
        super(id, true);
        if(Core.logger.shouldLog(Logger.DEBUG, this))
            Core.logger.log(this, "Created "+this, new Exception("debug"), 
                    Logger.DEBUG);
    }

    NoInsert(RequestState rs) {
        super(rs.id(), true);
        if(Core.logger.shouldLog(Logger.DEBUG, this))
            Core.logger.log(this, "Created "+this, new Exception("debug"), 
                    Logger.DEBUG);
    }
}
