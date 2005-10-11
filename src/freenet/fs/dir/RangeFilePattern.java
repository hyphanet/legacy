package freenet.fs.dir;


public class RangeFilePattern implements FilePattern {

    protected final FileNumber lowerBound, upperBound;
    protected final boolean lowerClosed, upperClosed, ascending;


    public RangeFilePattern(FileNumber bound, boolean closed, boolean ascending) {
        if (ascending) {
            this.lowerBound = bound;
            this.lowerClosed = closed;
            this.upperBound = null;
            this.upperClosed = false;
        }
        else {
            this.lowerBound = null;
            this.lowerClosed = false;
            this.upperBound = bound;
            this.upperClosed = closed;
        }
        this.ascending = ascending;
    }

    public RangeFilePattern(FileNumber lowerBound, boolean lowerClosed,
                            FileNumber upperBound, boolean upperClosed,
                            boolean ascending) {
        this.lowerBound = lowerBound;
        this.lowerClosed = lowerClosed;
        this.upperBound = upperBound;
        this.upperClosed = upperClosed;
        this.ascending = ascending;
    }
    
    public final boolean ascending() {
        return ascending;
    }
    
    public final FileNumber key() {
        return ascending ? lowerBound : upperBound;
    }

    public final boolean matches(FileNumber fn) {
        return ascending ? (upperBound == null
                            || upperClosed && fn.compareTo(upperBound) <= 0
                            || fn.compareTo(upperBound) < 0)
                         : (lowerBound == null
                            || lowerClosed && fn.compareTo(lowerBound) >= 0
                            || fn.compareTo(lowerBound) > 0);
    }
        
    public final boolean isLimitedBy(FileNumber fn) {
        return !matches(fn);
    }
}


