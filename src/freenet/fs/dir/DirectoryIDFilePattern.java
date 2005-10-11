package freenet.fs.dir;

public class DirectoryIDFilePattern implements FilePattern {

    protected final int[] dirID;
    protected final boolean ascending, sparse;

    
    public DirectoryIDFilePattern(int dirID, boolean ascending, boolean sparse) {
        this(new int[] {dirID}, ascending, sparse);
    }
    
    public DirectoryIDFilePattern(int[] dirID, boolean ascending, boolean sparse) {
        this.dirID = dirID;
        this.ascending = ascending;
        this.sparse = sparse;
    }
    
    public final boolean ascending() {
        return ascending;
    }
    
    public final FileNumber key() {
        return sparse
               ? new FileNumber(ascending ? 0 : 0xffff)
               : new FileNumber(ascending ? dirID[0] : 1+dirID[dirID.length-1]);
    }

    public final boolean matches(FileNumber fn) {
        for (int i=0; i<dirID.length; ++i) {
	    //Core.logger.log(this,"Searching for dirID = " + dirID[i] + " to match " + fn.dirID,Logger.DEBUG);
            if (fn.dirID == dirID[i]) {
		//Core.logger.log(this, "Matches", Logger.DEBUG);
		return true;
	    }
        }
	//Core.logger.log(this,"Does not match",Logger.DEBUG);
        return false;
    }

    public final boolean isLimitedBy(FileNumber fn) {
        return !sparse && !matches(fn);
    }
}


