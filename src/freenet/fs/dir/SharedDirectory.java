package freenet.fs.dir;

import java.util.Enumeration;

/**
 * Effectively creates an unlimited set of independent key namespaces
 * on top of a Directory implementation.
 * @author tavin
 */
public class SharedDirectory implements Directory {

    protected final Directory dir;
    protected final int dirID;
    
    public SharedDirectory(int dirID, Directory dir) {
        this.dirID = dirID;
        this.dir = dir;
    }

    public final Object semaphore() {
        return dir.semaphore();
    }

    public final long available() {
        return dir.available();
    }

    public final long used() {
        return dir.used();
    }

    //public final long largest() {
    //    return dir.largest();
    //}

    public final Enumeration keys(boolean ascending) {
        return dir.keys(new DirectoryIDFilePattern(dirID, ascending, false));
    }

    public final Enumeration keys(FilePattern pat) {
        return dir.keys(new SharedFilePattern(pat));
    }

    public final Enumeration lruKeys(boolean ascending) {
        return FileNumber.filter(new DirectoryIDFilePattern(dirID, true, true),
                                 dir.lruKeys(ascending));
    }

    
    public final boolean delete(FileNumber fn, boolean keepIfUsed) {
        return dir.delete(new FileNumber(dirID, fn), keepIfUsed);
    }
    
    public final boolean demote(FileNumber fn) {
        return dir.demote(new FileNumber(dirID, fn));
    }
    
    public final boolean contains(FileNumber fn) {
        return dir.contains(new FileNumber(dirID, fn));
    }
    
    public final Buffer fetch(FileNumber fn) {
        return dir.fetch(new FileNumber(dirID, fn));
    }

    public final Buffer store(long size, FileNumber fn) {
        return dir.store(size, new FileNumber(dirID, fn));
    }

    // FIXME: these return histograms for the whole underlying directory
    // If we ever use SharedDirectory as it was intended again, this needs
    // to be fixed. Also applies to countKeys().

    public final freenet.support.KeyHistogram getHistogram() {
        return dir.getHistogram();
    }

    public final freenet.support.KeySizeHistogram getSizeHistogram() {
        return dir.getSizeHistogram();
    }
    
    public final long countKeys() {
        return dir.countKeys();
    }

    protected class SharedFilePattern implements FilePattern {

        protected final FilePattern pat;

        SharedFilePattern(FilePattern pat) {
            this.pat = pat;
        }

        public final boolean ascending() {
            return pat.ascending();
        }
        
        public final FileNumber key() {
            return new FileNumber(dirID, pat.key());
        }

        public final boolean matches(FileNumber fn) {
            return dirID == fn.dirID && pat.matches(fn);
        }

        public final boolean isLimitedBy(FileNumber fn) {
            return dirID != fn.dirID || pat.isLimitedBy(fn);
        }
    }
}





