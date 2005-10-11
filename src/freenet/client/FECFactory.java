/*
  This code is distributed under the GNU Public Licence (GPL)
  version 2.  See http://www.gnu.org/ for further details of the GPL.
*/

package freenet.client;

import freenet.Core;
import freenet.support.Logger;

import java.lang.reflect.Constructor;
import java.util.Hashtable;

/**
 * Factory class to dynamically load 
 * FECEncoder and FECDecoder implementations.
 * <p>
 * @author giannij
 **/
public class FECFactory {
    /**
     * Register and encoder implementation class.
     **/
    public boolean registerEncoder(String className) {
        try {
            return register("_encoder", className, FECEncoder.class);
        }
        catch (Throwable t) {
            Core.logger.log(this, "Hard failure registering encoder: " + className + 
                            ". UPDATE freenet-ext.jar!", 
                            Logger.ERROR); 
            throw new RuntimeException("Hard failure registering encoder: " + className);
        }
    }

    /**
     * Register an decoder implementation class.
     **/
    public boolean registerDecoder(String className) {
        try {
            return register("_decoder", className, FECDecoder.class);
        }
        catch (Throwable t) {
            Core.logger.log(this, "Hard failure registering decoder: " + className + 
                            ". UPDATE freenet-ext.jar!", 
                            Logger.ERROR); 

            throw new RuntimeException("Hard failure registering decoder: " + className);
        }
    }
    
    /**
     * Returns true if the specified endcoder or decoder name is
     * registered.
     **/
    public final boolean isRegistered(String decoderName, boolean encoder) {
        String suffix = "_decoder";
        if (encoder) {
            suffix = "_encoder";
        }
        return codecs.get(decoderName + suffix) != null;
    }

    /**
     * Creates a new instance of the requested encoder.
     * Returns null if the requested encoder can't be
     * created. Returns an instance of the first 
     * registered encoder if name == null.
     **/
    public FECEncoder getEncoder(String name) {
        if (name == null) {
            if (defaultEncoder != null) {
                return getEncoder(defaultEncoder);
            }
        }
        return (FECEncoder)makeInstance(name, "_encoder");
    }

    /**
     * Creates a new instance of the requested decoder.
     * Returns null if the requested encoder can't be
     * created. Returns an instance of the first 
     * registered decoder if name == null.
     **/
    public FECDecoder getDecoder(String name) {
        if (name == null) {
            if (defaultEncoder != null) {
                return getDecoder(defaultEncoder);
            }
        }

        return (FECDecoder)makeInstance(name, "_decoder");
    }

    /**
     * Removes all registered implementations.
     **/
    public void flush() {
        codecs.clear();
    }


    /**
     * The default encoder name.  This is just the name of the first encoder
     * that was registered.
     **/
    public final String getDefaultEncoder() { return defaultEncoder; }

    /**
     * The default decoder name.  This is just the name of the first decoder
     * that was registered.
     **/
    public final String getDefaultDecoder() { return defaultEncoder; }

    ////////////////////////////////////////////////////////////

    private boolean register(String suffix, String className, Class mustAssign) {
        Class cls;
        try {
            cls = Class.forName(className.trim());
        }
        catch (ClassNotFoundException e) {
            Core.logger.log(this, "Couldn't load class: " + className + 
                            ".", 
                            Logger.ERROR); 

            return false;
        }
        
        if (!mustAssign.isAssignableFrom(cls)) {
            Core.logger.log(this, "Couldn't load class: " + className + 
                            " doesn't implement " + mustAssign.getName(), 
                            Logger.ERROR); 

            return false;
        }

        // find a default constructor
        Constructor[] constructors = cls.getConstructors();
        boolean found = false;
        int i;
        for (i=0; i<constructors.length; ++i) {
            Class[] types = constructors[i].getParameterTypes();
            if (types.length == 0) {
                found = true;
                break;
            }
        }
        
        if (!found) {
            Core.logger.log(this, "Couldn't load FECDecoder: " + className + 
                            ". No default constructor.", 
                            Logger.MINOR); 
           return false;
        }

        Object inst = null;
        try {
            inst = constructors[i].newInstance(new Object[0]);
        }
        catch (Throwable t) {
            // IMPORTANT: 
            // I want to make sure we don't crash on linkage 
            // errors.  This is especially important, 
            // because some decoder implementations may use JNI.
            // 
            Core.logger.log(this, "Couldn't make FEC codec instance: " +  className +
                            ", " + t.toString(), 
                            Logger.ERROR); 
            return false;
        }

        String name = null;
        if (inst instanceof FECEncoder) {
            name = ((FECEncoder)inst).getName();
        }
        else if (inst instanceof FECDecoder) {
            name = ((FECDecoder)inst).getName();
        }
        
        if (name == null) {
            Core.logger.log(this, "Couldn't read FEC codec instance name: " +  className,
                            Logger.ERROR);
            return false;
        }

        if (codecs.get(name + suffix) != null) {
            Core.logger.log(this, "FEC codec with same name already loaded: " +  className,
                            Logger.ERROR);
            return false;
        }

        codecs.put(name + suffix,constructors[i]);
        
        // Keep first registered encoder and decoder 
        // as defaults.
        if (suffix.equals("_encoder") && (defaultEncoder == null)) {
            defaultEncoder = name;
        }
        if (suffix.equals("_decoder") && (defaultDecoder == null)) {
            defaultDecoder = name;
        }

        return true;
    }

    private final Object makeInstance(String name, String suffix) {
        Constructor cons = (Constructor)codecs.get(name + suffix);
        if (cons == null) {
            return null;
        }

        Object ret = null;
        try {
            ret = cons.newInstance(new Object[0]);
        }
        catch (Throwable t) {
            // IMPORTANT: 
            // I want to make sure we don't crash on linkage 
            // errors.  This is especially important, 
            // because some decoder implementations may use JNI.
            // 
            Core.logger.log(this, "Couldn't make FEC codec instance: " + name + suffix +
                            ", " + t.toString(), 
                            Logger.ERROR); 

        }
        return ret;
    }

    private String defaultEncoder = null;
    private String defaultDecoder = null;

    private Hashtable codecs = new Hashtable();
}










