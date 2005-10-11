package freenet.fs.dir;

import freenet.support.Fields;

public class PrefixFilePattern implements FilePattern {

    protected final FileNumber prefix;
    protected final boolean ascending;

    
    public PrefixFilePattern(FileNumber prefix, boolean ascending) {
        this.prefix = prefix;
        this.ascending = ascending;
    }

    public final boolean ascending() {
        return ascending;
    }
    
    public final FileNumber key() {
        return prefix;
    }

    public final boolean matches(FileNumber fn) {
        return Fields.byteArrayEqual(prefix.key, fn.key,
                                     0, 0, prefix.key.length);
    }

    public final boolean isLimitedBy(FileNumber fn) {
        return !matches(fn);
    }
}


