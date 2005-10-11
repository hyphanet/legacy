/*
 * Created on Mar 30, 2004
 */
package freenet.node.rt;

import freenet.Core;
import freenet.support.Logger;

/**
 * @author Iakin
 */
class ForgettingEstimateList {
    
	private Estimate[]list;
	private int length;
	private int at = 0;
	
	ForgettingEstimateList(Estimate[]list, int length){
		this.list = list;
		this.length = Math.min(length, list.length);
		if(Core.logger.shouldLog(Logger.NORMAL, this))
		    checkList();
	}
	
	/**
     * Check the list for nulls. It shouldn't have any.
     */
    private void checkList() {
        for(int i=0;i<list.length;i++) {
            if(list[i] == null)
                Core.logger.log(this, "list["+i+"] = null on "+this,
                        Logger.ERROR);
        }
    }
    
    public synchronized Estimate nextEstimate(){
	    Estimate retval;
	    do {
	        if (list == null || at >= length)
	            return null;
	        retval = list[at];
	        if(at-1 >= 0) list[at-1] = null; //Forget estimates as soon as they have been used in order to conserve memory
	        at++;
	        if(retval == null) {
	            Core.logger.log(this, "Skipping null estimate "+(at-1)+" on "+this,
	                    Logger.ERROR);
	        }
	    } while (retval == null);
		return retval;
	}
    
	public synchronized String toString(){
		return ForgettingEstimateList.class.getName()+": length="+length+", at="+at;
	}
	
	public synchronized void clear(){
		list = null;
	}
}
