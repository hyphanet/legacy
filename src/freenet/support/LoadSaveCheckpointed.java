package freenet.support;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import freenet.Core;
import freenet.support.Checkpointed;

/**
 * @author amphibian Created on Aug 6, 2004
 */
public abstract class LoadSaveCheckpointed implements Checkpointed {

    File[] globalFiles;

    int nextGlobalEstimatorFile = 0;

    private boolean logDEBUG;

    public LoadSaveCheckpointed(File dir, String[] globalFileNames) {
        globalFiles = new File[globalFileNames.length];
        for (int i = 0; i < globalFileNames.length; i++)
            globalFiles[i] = new File(dir, globalFileNames[i]);
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
    }

    public long nextCheckpoint() {
        return System.currentTimeMillis() + checkpointPeriod();
    }

    protected abstract int checkpointPeriod();

    public void checkpoint() {
        File f = globalFiles[nextGlobalEstimatorFile];
        try {
            if (f.exists()) f.delete();
            if (f.exists())
                    throw new IOException(
                            "Failed to delete old global estimator file");
            FileOutputStream fo = new FileOutputStream(f);
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fo));
            writeData(dos);
            dos.close();
            fo.close();
            nextGlobalEstimatorFile++;
            //When we safely has written to one of the files defined.. use
            // next one next
            if (nextGlobalEstimatorFile > globalFiles.length - 1)
            //and if we have used all files.. then start from the
                    // beginning again
                    nextGlobalEstimatorFile = 0;
        } catch (IOException e) {
            Core.logger.log(this, "Couldn't write global estimator out!: " + e,
                    e, Logger.ERROR);
        }

    }

    public abstract void writeData(DataOutputStream dos) throws IOException;

    public void load() {
        preload();
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        for (int i = 0; i < globalFiles.length; i++) {
            if (logDEBUG)
                    Core.logger.log(this, "Reading " + globalFiles[i],
                            Logger.DEBUG);

            FileInputStream fi = null;
            DataInputStream dis = null;
            try {
                fi = new FileInputStream(globalFiles[i]);
                dis = new DataInputStream(fi);
                readFrom(dis);
            } catch (IOException e) {
                if (Core.logger.shouldLog(Logger.MINOR, this))
                        Core.logger.log(this, globalFiles[i]
                                + " corrupt or nonexistant: " + e, e,
                                Logger.NORMAL);
                continue;
            } finally {
                try {
                    if (dis != null) dis.close();
                    if (fi != null) fi.close();
                } catch (IOException e1) {
                    // Ignore
                }
            }
            break; // no exception => we're done
        }
        fillInBlanks();
    }

    protected abstract void fillInBlanks();

    protected abstract void readFrom(DataInputStream dis) throws IOException;

    protected abstract void preload();
}
