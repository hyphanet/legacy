package freenet.session;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.math.BigInteger;

import net.i2p.util.NativeBigInteger;

import freenet.Address;
import freenet.AuthenticationFailedException;
import freenet.CommunicationException;
import freenet.ConnectFailedException;
import freenet.Connection;
import freenet.Core;
import freenet.DSAAuthentity;
import freenet.DSAIdentity;
import freenet.Identity;
import freenet.ListeningAddress;
import freenet.NegotiationFailedException;
import freenet.crypt.BlockCipher;
import freenet.crypt.CipherInputStream;
import freenet.crypt.CipherOutputStream;
import freenet.crypt.DLES;
import freenet.crypt.DSA;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSASignature;
import freenet.crypt.DecryptionFailedException;
import freenet.crypt.DiffieHellman;
import freenet.crypt.Digest;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA1;
import freenet.crypt.Util;
import freenet.crypt.ciphers.Rijndael;
import freenet.support.Logger;

public final class FnpLink implements LinkConstants, Link {

    public static int AUTH_LAYER_VERSION = 0x01;

	protected static final int VER_BIT_LENGTH = 5,
		VER_BIT_MASK = 0x1f,
		NEGOTIATION_MODE_MASK = 0x03,
		RESTART = 0x01,
		AUTHENTICATE = 0x00,
		SILENT_BOB_BYTE = 0xfb,
		SILENT_BOB_HANGUP = 0xfc;

    protected CipherInputStream in;

    protected CipherOutputStream out;

    protected AdapterOutputStream innerOut;

    protected AdapterInputStream innerIn;

    protected Connection conn;

    protected boolean ready = false;

    protected FnpLinkToken linkInfo;

    protected FnpLinkManager linkManager;

    protected DLES asymCipher = new DLES();

    protected boolean logDEBUG;

    // Due to ridiculously long observed authorizeTime's, this file is heavily
    // augmented
    // with debug logging and timings

    //profiling
    //WARNING:remove before release
    public static volatile int instances = 0;

    private static final Object profLock = new Object();

    protected FnpLink(FnpLinkManager flm, Connection c) {
        linkManager = flm;
        conn = c;
        logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
        //profiling
        //WARNING:remove before release
        synchronized (profLock) {
            instances++;
        }
    }

    //     protected FnpLink(FnpLinkManager flm, FnpLinkToken linkInfo,
    //                       CipherInputStream in, CipherOutputStream out,
    //                       Connection conn) {
    //         this(flm, conn);
    //         this.linkInfo = linkInfo;
    //         setInputStream(in);
    //         setOutputStream(out);
    //         ready = true;
    //     }

	protected void accept(
		DSAPrivateKey privMe,
		DSAIdentity pubMe,
		int paravect)
            throws CommunicationException {
        synchronized (conn) {
            try {
                int oldTimeout = conn.getSoTimeout();
                try {
                    conn.setSoTimeout(Core.authTimeout);

                    InputStream rawIn = conn.getIn();
                    int connType = rawIn.read();

					if (((connType >> (8 - VER_BIT_LENGTH)) & VER_BIT_MASK)
						!= AUTH_LAYER_VERSION)
						throw new NegotiationFailedException(conn.getPeerAddress(), "Wrong auth protocol version");

                    int negmode = connType & NEGOTIATION_MODE_MASK;

                    if (negmode == RESTART) {
                        if (logDEBUG)
							Core.logger.log(this, "Accepting restart.", Logger.DEBUG);
						boolean worked =
							receiveRestartRequest(privMe, pubMe, paravect);
                        if (worked) {
							Core.diagnostics.occurrenceBinomial("inboundRestartRatio", 1, 1);
                            return;
                        } else {
                            // reread
                            connType = rawIn.read();
                            negmode = connType & NEGOTIATION_MODE_MASK;
                        }
                    }

                    if (negmode == AUTHENTICATE) {
                        if (logDEBUG)
							Core.logger.log(this, "Accepting full negotiation", Logger.DEBUG);
                        negotiateInbound(privMe, pubMe, paravect);
						Core.diagnostics.occurrenceBinomial("inboundRestartRatio", 1, 0);
                    } else {
						throw new NegotiationFailedException(conn.getPeerAddress(), "Invalid authentication mode");
                    }
                } finally {
                    conn.setSoTimeout(oldTimeout);
                }
            } catch (InterruptedIOException e) {
				throw new ConnectFailedException(conn.getPeerAddress(), "authentication timed out");
            } catch (IOException e) {
                String s = "I/O error during inbound auth: " + e;
                if(Core.logger.shouldLog(Logger.MINOR, this))
                    Core.logger.log(this, s, e, Logger.MINOR);
                throw new ConnectFailedException(conn.getPeerAddress(), s);
            }
        }
    }

	protected void solicit(
		DSAAuthentity privMe,
		DSAIdentity pubMe,
		DSAIdentity bob,
		boolean safe)
		throws CommunicationException {
        long startTime = System.currentTimeMillis();
        if (logDEBUG)
			Core.logger.log(this, "solicit() at " + startTime, Logger.DEBUG);
        synchronized (conn) {
            try {
                int oldTimeout = conn.getSoTimeout();
                conn.setSoTimeout(Core.authTimeout);
                linkInfo = linkManager.searchOutboundLinks(bob);
                if (linkInfo != null) {
                    long solicitRestartTime = System.currentTimeMillis();
                    if (logDEBUG)
						Core.logger.log(this, "Soliciting restart at " + (solicitRestartTime - startTime), Logger.DEBUG);
                    try {
						boolean worked =
							negotiateRestart(bob, linkInfo.getKeyHash(), linkInfo.getKey(), safe);
                        long negotiatedRestartTime = System.currentTimeMillis();
						long negrestartlen =
							negotiatedRestartTime - solicitRestartTime;
                        if (logDEBUG || negrestartlen > 500)
							Core.logger.log(this, "negotiateRestart took " + negrestartlen + " at "
									+ negotiatedRestartTime, negrestartlen > 500 ? Logger.MINOR : Logger.DEBUG);
                        if (worked) {
							Core.diagnostics.occurrenceBinomial("outboundRestartRatio", 1, 1);
                            return;
                        } else
                            linkManager.removeLink(linkInfo);
                    } catch (AuthenticationFailedException e) {
                        linkManager.removeLink(linkInfo);
                        throw (AuthenticationFailedException) e
                                .fillInStackTrace();
                    }
                }
                long solicitFullTime = System.currentTimeMillis();
                if (logDEBUG)
					Core.logger.log(this, "Soliciting full negotiation at " + solicitFullTime, Logger.DEBUG);
                negotiateOutbound(privMe, pubMe, bob);
                long negotiatedTime = System.currentTimeMillis();
                long neglength = negotiatedTime - solicitFullTime;
                if (logDEBUG || neglength > 500)
					Core.logger.log(this, "negotiateOutbound took " + neglength + " at " + negotiatedTime,
                                neglength > 500 ? Logger.MINOR : Logger.DEBUG);
				Core.diagnostics.occurrenceBinomial("outboundRestartRatio", 1, 0);
                boolean open = conn.isInClosed();
                try {
                    conn.setSoTimeout(oldTimeout);
                } catch (IOException e) {
                    if (open || Core.logger.shouldLog(Logger.DEBUG, this))
						Core.logger.log(this, "Caught " + e + " setting timeout on " + conn, open
								? Logger.NORMAL
								: Logger.DEBUG);
                }
            } catch (InterruptedIOException e) {
				throw new ConnectFailedException(conn.getPeerAddress(), "authentication timed out");
            } catch (IOException e) {
                String s = "I/O error during outbound auth: " + e;
                if(Core.logger.shouldLog(Logger.MINOR, this))
                    Core.logger.log(this, s, e, Logger.MINOR);
				ConnectFailedException ex =
					new ConnectFailedException(conn.getPeerAddress(), s);
                ex.initCause(e);
                throw ex; 
            }
        }
    }

	private boolean negotiateRestart(
		DSAIdentity bob,
		BigInteger hk,
		byte[] k,
		boolean safe)
		throws CommunicationException, IOException {

        long startTime = System.currentTimeMillis();
        if (logDEBUG)
			Core.logger.log(this, "negotiateRestart at " + startTime, Logger.DEBUG);

        OutputStream rawOut = innerOut = new AdapterOutputStream(conn.getOut());
        InputStream rawIn = innerIn = new AdapterInputStream(conn.getIn());

        long gotStreamsTime = System.currentTimeMillis();
        long gslen = gotStreamsTime - startTime;
        if (logDEBUG || gslen > 500)
			Core.logger.log(this, "Got streams in " + gslen + " at " + gotStreamsTime, gslen > 500
					? Logger.MINOR
					: Logger.DEBUG);

        // Send restart challenge
        BigInteger M = hk.shiftLeft(8);
		if (safe)
			M = M.setBit(0);

        long shiftedSetTime = System.currentTimeMillis();
        long sstlen = shiftedSetTime - gotStreamsTime;
        if (logDEBUG || sstlen > 500)
			Core.logger.log(this, "Shifted and set in " + sstlen + " at " + shiftedSetTime, sstlen > 500
					? Logger.MINOR
					: Logger.DEBUG);

        BigInteger[] C = asymCipher.encrypt(bob, M, Core.getRandSource());

        long madeChallengeTime = System.currentTimeMillis();
        long mclen = madeChallengeTime - shiftedSetTime;
        if (logDEBUG || mclen > 500)
			Core.logger.log(this, "Generated challenge in " + mclen + " at " + madeChallengeTime, mclen > 500
					? Logger.MINOR
					: Logger.DEBUG);

        rawOut.write((AUTH_LAYER_VERSION << (8 - VER_BIT_LENGTH)) + RESTART);
        Util.writeMPI(C[0], rawOut);
        Util.writeMPI(C[1], rawOut);
        Util.writeMPI(C[2], rawOut);

        long sentChallengeTime = System.currentTimeMillis();
        long sclen = sentChallengeTime - madeChallengeTime;
        if (logDEBUG || sclen > 500)
			Core.logger.log(this, "Sent challenge in " + sclen + " at " + sentChallengeTime, sclen > 500
					? Logger.MINOR
					: Logger.DEBUG);

        //rawOut.flush(); Flushing is a pass on some level...
        // Set up outbound link level encryption
        BlockCipher c = new Rijndael();
        c.initialize(k);
        PCFBMode ctx = new PCFBMode(c);

        long initCipherTime = System.currentTimeMillis();
        long icl = initCipherTime - sentChallengeTime;
        if (logDEBUG || icl > 500)
			Core.logger.log(this, "Initialized cipher in " + icl + " at " + initCipherTime, icl > 500
					? Logger.MINOR
					: Logger.DEBUG);

        // Set up inbound link level encryption
        if (safe) {
            rawOut.flush();
            long flushedInSafeTime = System.currentTimeMillis();
            long flushlen = flushedInSafeTime - initCipherTime;
            if (logDEBUG || flushlen > 500)
				Core.logger.log(this, "Flushed rawOut in safe branch in " + flushlen + " at " + flushedInSafeTime,
                            flushlen > 500 ? Logger.MINOR : Logger.DEBUG);

            int cb = rawIn.read();
            long readByteTime = System.currentTimeMillis();
            long rbl = readByteTime - flushedInSafeTime;
            if (logDEBUG || rbl > 500)
				Core.logger.log(this, "Read byte in " + rbl + " at " + readByteTime, rbl > 500
						? Logger.MINOR
						: Logger.DEBUG);

            if (cb == SILENT_BOB_BYTE) {
                ctx.writeIV(Core.getRandSource(), rawOut);
                long writtenIVTime = System.currentTimeMillis();
                long ivt = writtenIVTime - readByteTime;
                if (logDEBUG || ivt > 500)
					Core.logger.log(this, "Written silent bob IV in " + ivt + " at " + writtenIVTime, ivt > 500
							? Logger.MINOR
							: Logger.DEBUG);

                // Must flush here: NIO
                rawOut.flush();
                
                setOutputStream(ctx, rawOut);
                if (logDEBUG)
					Core.logger.log(this, "Set output stream", Logger.DEBUG);
                setInputStream(c, rawIn);
                if (logDEBUG)
                        Core.logger.log(this, "Set input stream", Logger.DEBUG);
                
                if(in.needIV()) {
                    if(logDEBUG)
                        Core.logger.log(this, "Reading IV", Logger.DEBUG);
                    in.getCipher().readIV(rawIn);
                    if(logDEBUG)
                        Core.logger.log(this, "Read IV", Logger.DEBUG);
                }
                
                conn.notifyAll();
                if (logDEBUG)
                        Core.logger.log(this, "Notified all", Logger.DEBUG);
                ready = true;
                return true;
            } else if (cb == SILENT_BOB_HANGUP) {
                if (logDEBUG)
					Core.logger.log(this, "Silent bob hangup at " + System.currentTimeMillis(), Logger.DEBUG);
                return false;
            } else {
				throw new NegotiationFailedException(conn.getPeerAddress(), cb == -1 ? "Peer hung up" : "Bad OK byte");
            }
        } else {
            ctx.writeIV(Core.getRandSource(), rawOut);
            long writtenIVTime = System.currentTimeMillis();
            long wiv = writtenIVTime - initCipherTime;
            if (logDEBUG || wiv > 500)
				Core.logger.log(this, "Written IV (unsafe) in " + wiv + " at " + writtenIVTime, wiv > 500
						? Logger.MINOR
						: Logger.DEBUG);

            setOutputStream(ctx, rawOut);
            setSilentBobCheckingInputStream(c, rawIn);
            ready = true;
            conn.notifyAll();
            return true;
        }
    }

	private boolean receiveRestartRequest(
		DSAPrivateKey privMe,
		DSAIdentity pubMe,
		int paravect)
		throws CommunicationException, IOException {

        long startTime = System.currentTimeMillis();
        if (logDEBUG)
			Core.logger.log(this, "Receiving restart request at " + startTime, Logger.DEBUG);

        OutputStream rawOut = innerOut = new AdapterOutputStream(conn.getOut());
        InputStream rawIn = innerIn = new AdapterInputStream(conn.getIn());

        BigInteger[] C = new BigInteger[3];
        C[0] = Util.readMPI(rawIn);
        C[1] = Util.readMPI(rawIn);
        C[2] = Util.readMPI(rawIn);

        long readMPIsTime = System.currentTimeMillis();
        long rlen = readMPIsTime - startTime;

        if (logDEBUG || rlen > 500)
			Core.logger.log(this, "got streams and read MPIs in " + rlen, rlen > 500 ? Logger.MINOR : Logger.DEBUG);

        BigInteger P = null;
        try {
            P = asymCipher.decrypt(pubMe.getGroup(), privMe, C);
        } catch (DecryptionFailedException dfe) {
			throw new AuthenticationFailedException(conn.getPeerAddress(),
                    "Invalid restart message (MAC verify must have failed)");
        }

        long decryptedTime = System.currentTimeMillis();
        long dlen = decryptedTime - readMPIsTime;
        if (logDEBUG || dlen > 500)
			Core.logger.log(this, "decrypted in " + dlen, dlen > 500 ? Logger.MINOR : Logger.DEBUG);

        boolean safe;
		if (P.byteValue() == 0)
			safe = false;
		else if (P.byteValue() == 1)
			safe = true;
        else {
            String error = "Invalid restart message (low 8 bits not 0 or 1)";
			throw new AuthenticationFailedException(conn.getPeerAddress(), error);
        }

        P = P.shiftRight(8);
        linkInfo = linkManager.searchInboundLinks(P);

        if (linkInfo == null) {
            rawOut.write(SILENT_BOB_HANGUP);
            rawOut.flush();
			if (safe)
				return false;
            else
				throw new AuthenticationFailedException(conn.getPeerAddress(),
                        "Unknown Link trying to restart, and unable to do fallback.");
        }

        // oh glorious 0xfb
        rawOut.write(SILENT_BOB_BYTE);
        rawOut.flush();

        long bobTime = System.currentTimeMillis();
        long boblen = bobTime - decryptedTime;
        if (logDEBUG || boblen > 500)
			Core.logger.log(this, "Got link info, written silent bob and flushed in " + boblen, boblen > 500
					? Logger.MINOR
					: Logger.DEBUG);

        // Set up outbound link level encryption
        BlockCipher c = new Rijndael();
        c.initialize(linkInfo.getKey());
        PCFBMode ctx = new PCFBMode(c);

        long cipherTime = System.currentTimeMillis();
        long clen = cipherTime - bobTime;
        if (logDEBUG || clen > 500)
			Core.logger.log(this, "Set up cipher in " + clen, clen > 500 ? Logger.MINOR : Logger.DEBUG);

        ctx.writeIV(Core.getRandSource(), rawOut);
        long IVTime = System.currentTimeMillis();
        long ivlen = IVTime - cipherTime;
        if (logDEBUG || ivlen > 500)
			Core.logger.log(this, "Written IV in " + ivlen, ivlen > 500 ? Logger.MINOR : Logger.DEBUG);

        // we wait with flushing the IV until the first message. It is
        // just as well since it is read with the first message now. /oskar
        //rawOut.flush();

        setOutputStream(ctx, rawOut);

        // Set up inbound link level encryption
        setInputStream(c, rawIn);

        ready = true;
        conn.notifyAll();

        return true;
    }

	private void negotiateInbound(
		DSAPrivateKey privMe,
		DSAIdentity pubMe,
		int paravect)
		throws CommunicationException, IOException {
        boolean cbSent = false;
		BigInteger[] dhParams = DiffieHellman.getParams(),
			DLESCa = new BigInteger[3];

        DSAIdentity Ya;

        BigInteger Ca, Cb = dhParams[1], Z, R = dhParams[0];

        BlockCipher c = new Rijndael();
        byte[] k = new byte[c.getKeySize() >> 3];

        OutputStream rawOut = innerOut = new AdapterOutputStream(conn.getOut());
        InputStream rawIn = innerIn = new AdapterInputStream(conn.getIn());

        if ((paravect & SILENT_BOB) == 0) {
            rawOut.write(SILENT_BOB_BYTE);
            Util.writeMPI(Cb, rawOut);
            cbSent = true;
            rawOut.flush();
        }
        Ca = Util.readMPI(rawIn);

        Z = Ca.modPow(R, DiffieHellman.getGroup().getP());
        byte[] kent = Util.MPIbytes(Z);
        Util.makeKey(kent, k, 0, k.length);
        c.initialize(k);

        DLESCa[0] = Util.readMPI(rawIn);
        DLESCa[1] = Util.readMPI(rawIn);
        DLESCa[2] = Util.readMPI(rawIn);

        if ((paravect & VERIFY_BOBKNOWN) != 0) {
            BigInteger Cav = null;
            try {
                Cav = asymCipher.decrypt(pubMe.getGroup(), privMe, DLESCa);
            } catch (DecryptionFailedException dfe) {
                String err = "Remote sent bogus DLES encrypted data";
				throw new AuthenticationFailedException(conn.getPeerAddress(), err);
            }
            if (!Cav.equals(Ca)) {
                String err = "Remote does not know my identity";
				throw new AuthenticationFailedException(conn.getPeerAddress(), err);
            }
        }

        if (!cbSent) {
            rawOut.write(SILENT_BOB_BYTE);
            Util.writeMPI(Cb, rawOut);
            cbSent = true;
            rawOut.flush();
        }

        PCFBMode pcfb = new PCFBMode(c);
        pcfb.writeIV(Core.getRandSource(), rawOut);
        setOutputStream(pcfb, rawOut);
        //setInputStream(c, rawIn);

        Digest ctx = SHA1.getInstance();
        byte[] Cabytes = Util.MPIbytes(Ca);
        byte[] Cbbytes = Util.MPIbytes(Cb);
        ctx.update(Cabytes, 0, Cabytes.length);
        ctx.update(Cbbytes, 0, Cbbytes.length);
		BigInteger M = new NativeBigInteger(1, ctx.digest());
		DSASignature sigCaCb = DSA.sign(pubMe.getGroup(), privMe, M, Core.getRandSource());

        sigCaCb.write(out);
        out.flush();

        setInputStream(c, rawIn);
        Ya = (DSAIdentity) DSAIdentity.read(in);
		if(logDEBUG)
		    Core.logger.log(this, "Read Ya: "+Ya.fingerprintToString()+": y="+
		            Ya.getYAsHexString()+", group: "+Ya.getGroup(), Logger.DEBUG);
        byte[] Yabytes = Ya.asBytes();
        //System.err.println(freenet.support.HexUtil.bytesToHex(Yabytes));
        //System.err.println(Ya.toString());
        ctx.update(Yabytes, 0, Yabytes.length);
        ctx.update(Cabytes, 0, Cabytes.length);
        ctx.update(Cbbytes, 0, Cbbytes.length);
		M = new NativeBigInteger(1, ctx.digest());
        DSASignature sigYaCaCb = DSASignature.read(in);
		if(logDEBUG)
		    Core.logger.log(this, "Read signature: "+sigYaCaCb+", M should be: "+M+
		            ", Ya="+Ya.fingerprintToString(), Logger.DEBUG);
        if (!DSA.verify(Ya, sigYaCaCb, M)) {
			String err = "Remote does not posess the private key to the public key it offered";
            throw new AuthenticationFailedException(conn.getPeerAddress(), err);
        }

		linkInfo = linkManager.addLink(Ya, pubMe, k);

        ready = true;
        conn.notifyAll();
    }

	private void negotiateOutbound(
		DSAPrivateKey privMe,
		DSAIdentity pubMe,
		DSAIdentity bob)
		throws CommunicationException, IOException {
        // FIXME: Most of this stuff should probably be diagnostics rather than
        // logging

        long startTime = System.currentTimeMillis();
        if (logDEBUG)
			Core.logger.log(this, "negotiateOutbound at " + startTime, Logger.DEBUG);

        BigInteger[] DLESCa, dhParams = DiffieHellman.getParams();
        BigInteger Ca = dhParams[1], Cb, Z, R = dhParams[0], M;

        BlockCipher c = new Rijndael();
        byte[] k = new byte[c.getKeySize() >> 3];

        long setupTime = System.currentTimeMillis();
        long setuplen = setupTime - startTime;
        if (logDEBUG || setuplen > 500)
			Core.logger.log(this, "Setup in " + setuplen, setuplen > 500 ? Logger.MINOR : Logger.DEBUG);

        OutputStream rawOut = innerOut = new AdapterOutputStream(conn.getOut());
        InputStream rawIn = innerIn = new AdapterInputStream(conn.getIn());

		rawOut.write(
			(AUTH_LAYER_VERSION << (8 - VER_BIT_LENGTH)) + AUTHENTICATE);

        long writtenByteTime = System.currentTimeMillis();
        long wbtlen = writtenByteTime - setupTime;
        if (logDEBUG || wbtlen > 500)
			Core.logger.log(this, "Written byte in " + wbtlen, wbtlen > 500 ? Logger.MINOR : Logger.DEBUG);

        ByteArrayOutputStream buff = new ByteArrayOutputStream(10000);

        DLESCa = asymCipher.encrypt(bob, Ca, Core.getRandSource());
        long encryptedTime = System.currentTimeMillis();
        long elen = encryptedTime - writtenByteTime;
        if (logDEBUG || elen > 500)
			Core.logger.log(this, "Encrypted in " + elen, elen > 500 ? Logger.MINOR : Logger.DEBUG);

        Util.writeMPI(Ca, buff);

        Util.writeMPI(DLESCa[0], buff);
        Util.writeMPI(DLESCa[1], buff);
        Util.writeMPI(DLESCa[2], buff);

        long writtenToBufferTime = System.currentTimeMillis();
        long wblen = writtenToBufferTime - encryptedTime;
        if (logDEBUG || wblen > 500)
			Core.logger.log(this, "Written to buffer in " + wblen, wblen > 500 ? Logger.MINOR : Logger.DEBUG);

        buff.writeTo(rawOut);
        rawOut.flush();

        long flushedTime = System.currentTimeMillis();
        long flushlen = flushedTime - writtenToBufferTime;
        if (logDEBUG || flushlen > 500)
			Core.logger.log(this, "Written to raw stream and flushed in " + flushlen, flushlen > 500
					? Logger.MINOR
					: Logger.DEBUG);

        int sbb = rawIn.read();
        long readByteTime = System.currentTimeMillis();
        long readlen = readByteTime - flushedTime;
        if (logDEBUG || readlen > 500)
			Core.logger.log(this, "Read byte in " + readlen, readlen > 500 ? Logger.MINOR : Logger.DEBUG);

        if (SILENT_BOB_BYTE != sbb) {
			String err = sbb == -1 ? "Peer hung up." : "Bob was not silent in the way that we like";
            throw new NegotiationFailedException(conn.getPeerAddress(), err);
        }

        Cb = Util.readMPI(rawIn);
        long readMPITime = System.currentTimeMillis();
        long readmpilen = readMPITime - readByteTime;
        if (logDEBUG || readmpilen > 500)
			Core.logger.log(this, "Read first MPI from peer in " + readmpilen, readmpilen > 500
					? Logger.MINOR
					: Logger.DEBUG);

        Z = Cb.modPow(R, DiffieHellman.getGroup().getP());
        byte[] kent = Util.MPIbytes(Z);
        Util.makeKey(kent, k, 0, k.length);
        c.initialize(k);

        PCFBMode pcfb = new PCFBMode(c);
        long inittedBitsTime = System.currentTimeMillis();
        long ilen = inittedBitsTime - readMPITime;
        if (logDEBUG || ilen > 500)
			Core.logger.log(this, "Initialized more cipher stuff in " + ilen, ilen > 500 ? Logger.MINOR : Logger.DEBUG);

        pcfb.writeIV(Core.getRandSource(), rawOut);
        long writtenIVTime = System.currentTimeMillis();
        long wivlen = writtenIVTime - inittedBitsTime;
        if (logDEBUG || wivlen > 500)
			Core.logger.log(this, "Written IV in " + wivlen, wivlen > 500 ? Logger.MINOR : Logger.DEBUG);

        setOutputStream(pcfb, rawOut);
        //setInputStream(c, rawIn);
        //System.err.println("LALA " + pubMe.toString());
        pubMe.writeForWire(out);
		if(logDEBUG)
		    Core.logger.log(this, "Written pubMe: "+pubMe.fingerprintToString()+
		            ": y="+pubMe.getYAsHexString()+", "+pubMe.getGroup().toString(),
		            Logger.DEBUG);
        long writtenPKTime = System.currentTimeMillis();
        long wplen = writtenPKTime - writtenIVTime;
        if (logDEBUG || wplen > 500)
			Core.logger.log(this, "Set output stream, written PK in " + wplen, wplen > 500
					? Logger.MINOR
					: Logger.DEBUG);

        Digest ctx = SHA1.getInstance();
        byte[] Cabytes = Util.MPIbytes(Ca);
        byte[] Cbbytes = Util.MPIbytes(Cb);
        byte[] Yabytes = pubMe.asBytes();
        ctx.update(Yabytes, 0, Yabytes.length);
        ctx.update(Cabytes, 0, Cabytes.length);
        ctx.update(Cbbytes, 0, Cbbytes.length);
		M = new NativeBigInteger(1, ctx.digest());

		DSASignature sigYaCaCb =
			DSA.sign(pubMe.getGroup(), privMe, M, Core.getRandSource());
        long signedTime = System.currentTimeMillis();
        long siglen = signedTime - writtenPKTime;
        if (logDEBUG || siglen > 500)
			Core.logger.log(this, "Signed in " + siglen, siglen > 500 ? Logger.MINOR : Logger.DEBUG);

        sigYaCaCb.write(out);
        long writtenSigTime = System.currentTimeMillis();
        long wsiglen = writtenSigTime - signedTime;
        if (logDEBUG || wsiglen > 500)
			Core.logger.log(this, "Written sig in " + wsiglen, wsiglen > 500 ? Logger.MINOR : Logger.DEBUG);
		if(logDEBUG)
		    Core.logger.log(this, "Sig: "+sigYaCaCb+", M: "+M+" for "+pubMe.fingerprintToString(), Logger.DEBUG);

        out.flush();
        long flushedAgainTime = System.currentTimeMillis();
        long flushAgainLen = flushedAgainTime - writtenSigTime;
        if (logDEBUG || flushAgainLen > 500)
			Core.logger.log(this, "Flushed again in " + flushAgainLen, flushAgainLen > 500
					? Logger.MINOR
					: Logger.DEBUG);

        setInputStream(c, rawIn);
        DSASignature sigCaCb = DSASignature.read(in);
        long readSigTime = System.currentTimeMillis();
        long rslen = readSigTime - flushedAgainTime;
        if (logDEBUG || rslen > 500)
			Core.logger.log(this, "Set input stream, read signature in " + rslen, rslen > 500
					? Logger.MINOR
					: Logger.DEBUG);

        ctx.update(Cabytes, 0, Cabytes.length);
        ctx.update(Cbbytes, 0, Cbbytes.length);
		M = new NativeBigInteger(1, ctx.digest());
        if (!DSA.verify(bob, sigCaCb, M)) {
			String err =
				"Remote is not who she claims to be, or did not "
                    + "receive the correct DH parameters";
            throw new AuthenticationFailedException(conn.getPeerAddress(), err);
        }

        long verifiedSigTime = System.currentTimeMillis();
        long vstlen = verifiedSigTime - readSigTime;
        if (logDEBUG || vstlen > 500)
			Core.logger.log(this, "Verified signature in " + vstlen, vstlen > 500 ? Logger.MINOR : Logger.DEBUG);

		linkInfo = linkManager.addLink(bob, pubMe, k);
        ready = true;
        conn.notifyAll();
    }

    public final LinkManager getManager() {
        return linkManager;
    }

    public final InputStream getInputStream() {
        waitReady();
        return in;
    }

    public final OutputStream getOutputStream() {
        waitReady();
        return out;
    }

    InputStream tis = null;

    private final void setInputStream(CipherInputStream in) {
        // 	tis = ThrottledInputStream.throttledStream(in);
        // 	if(tis instanceof ThrottledInputStream)
        // 	    ((ThrottledInputStream)tis).setDisabled(true);
		if (in == null)
			throw new IllegalArgumentException("null stream");
        this.in = in;
    }

    private final void setInputStream(BlockCipher c, InputStream raw)
            throws IOException {
		if (raw == null)
			throw new IllegalArgumentException("null stream");
        this.in = new CipherInputStream(c, raw, true, true);
    }

	private final void setSilentBobCheckingInputStream(
		BlockCipher c,
		InputStream raw)
		throws IOException {
		if (raw == null)
			throw new IllegalArgumentException("null stream");
        setInputStream(c, new SilentBobCheckingInputStream(raw));
    }

    /**
     * This lets us check the 0xfb that should precede Bob's encrypted messages
     * after a resume, without adding an extra pass.
     */
	private final class SilentBobCheckingInputStream
		extends FilterInputStream {
        private boolean checked = false;

        public SilentBobCheckingInputStream(InputStream in) {
            super(in);
        }

        public final int read() throws IOException {
			if (!checked)
				check();
            return this.in.read();
        }

		public final int read(byte[] buf, int off, int len)
			throws IOException {
			if (!checked)
				check();
            return this.in.read(buf, off, len);
        }

        private void check() throws IOException {
            checked = true;
            if (this.in.read() != SILENT_BOB_BYTE) {
				Core.logger.log(FnpLink.this, "Bob didn't send the magic byte after a resume; discarding "
						+ "cached session key", Logger.MINOR);
                FnpLink.this.close();
                linkManager.removeLink(linkInfo);
				throw new IOException("Bob didn't send the magic byte after a resume");
            }
        }
    }

    public void encryptBytes(byte[] buf, int offset, int length)
            throws IOException {
        CipherOutputStream os = out;
		if (os == null)
			throw new IOException("already closed!");
        PCFBMode ctx = os.getCipher();
		if (ctx != null)
			ctx.blockEncipher(buf, offset, length);
    }

    // FIXME: neither of these should be inner classes

    protected class AdapterOutputStream extends FilterOutputStream {

        AdapterOutputStream() {
            super(null);
        }

        AdapterOutputStream(OutputStream os) {
            super(os);
        }

        public synchronized void write(byte[] b) throws IOException {
			if (this.out == null)
				throw new IOException("already closed!");
            this.out.write(b);
        }

        public synchronized void write(int i) throws IOException {
			if (this.out == null)
				throw new IOException("already closed!");
            this.out.write(i);
        }

        public synchronized void write(byte[] b, int off, int len)
                throws IOException {
			if (this.out == null)
				throw new IOException("already closed!");
            this.out.write(b, off, len);
        }

        public void flush() throws IOException {
            //Avoid doing a nested
            // FNPLink$AdapterOutputStream/ConnectionHandler$CHOutputStream
            // lock since other code is doing it in reverse (potential deadlock
            // situation)
            OutputStream o = this.out;
			if (o == null)
				throw new IOException("already closed!");
            o.flush();
        }

        OutputStream getOut() {
            return this.out;
        }

        synchronized void setOut(OutputStream out) {
            this.out = out;
        }
    }

    protected class AdapterInputStream extends FilterInputStream {

        AdapterInputStream() {
            super(null);
        }

        AdapterInputStream(InputStream is) {
            super(is);
        }

        InputStream getIn() {
            return this.in;
        }

        void setIn(InputStream is) {
            this.in = is;
        }
    }

    private final void setOutputStream(CipherOutputStream out) {
        this.out = out; // tcpConn buffers us
    }

	private final void setOutputStream(PCFBMode c, OutputStream raw) {
        this.out = new CipherOutputStream(c, raw);
    }

    public InputStream makeInputStream(InputStream is) {
		if (innerIn != null)
			innerIn.setIn(is);
        else
            return null;
        return getInputStream();
    }

    public OutputStream makeOutputStream(OutputStream os) {
        innerOut.setOut(os);
        return getOutputStream();
    }

    //     protected boolean wrapped = false;

    public final void wrapThrottle() {
		freenet.transport.tcpConnection tcpConn =
			(freenet.transport.tcpConnection) conn;
        tcpConn.enableThrottle();
    }

    // 	if(!wrapped) {
    // 	    out = ThrottledOutputStream.throttledStream(out);
    // 	    if(tis != null && tis instanceof ThrottledInputStream) {
    // 		((ThrottledInputStream)tis).setDisabled(false);
    // 	    } else {
    // 		tis = in = ThrottledInputStream.throttledStream(in);
    // 	    }
    // 	    wrapped = true;
    // 	}
    //     }

    public void close() {
        if (logDEBUG)
			Core.logger.log(this, "Closing FnpLink: " + this, new Exception("debug"), Logger.DEBUG);
        synchronized (conn) {
            try {
				java.net.Socket sock =
					((freenet.transport.tcpConnection) conn).getSocket();
				// throws only if already closed, so
                                      // ignore it
                if (sock.getChannel().isBlocking()) {
                    out.flush(); // ignore this too
                }
                conn.close(); // and this
            } catch (IOException e) {
                if (logDEBUG)
					Core.logger.log(this, "close() got an IOException: " + e + " for " + this + " on " + conn,
                                Logger.DEBUG);
                // Purely for event tracking - no real significance
            }
            conn.notifyAll();
            ready = true;
        }

        // Set streams to null, so the buffer
        // can be gc'ed (using the default configuration this frees
        // up 128k)
        //this.in = null; - this.in is not buffered - FIXME
        // other reason: we may still have some process()ing to do

        this.in = null;
        this.out = null;
        AdapterOutputStream aos = this.innerOut;
        AdapterInputStream ais = this.innerIn;
        this.innerOut = null;
        this.innerIn = null;
        aos.setOut(null);
        ais.setIn(null);
        asymCipher = null;
        // 	conn = null; - needed for getPeerAddress etc
    }

    public final void setTimeout(int timeout) throws IOException {
        conn.setSoTimeout(timeout);
    }

    public final int getTimeout() throws IOException {
        return conn.getSoTimeout();
    }

    public final Identity getMyIdentity() {
        return linkInfo.getMyIdentity();
    }

	public final Address getMyAddress(ListeningAddress lstaddr) {
        return conn.getMyAddress(lstaddr);
    }

    public final Address getMyAddress() {
        return conn.getMyAddress();
    }

	public final Address getPeerAddress(ListeningAddress lstaddr) {
        waitReady();
        return conn.getPeerAddress(lstaddr);
    }

    public final Address getPeerAddress() {
        waitReady();
        return conn.getPeerAddress();
    }

    public final Identity getPeerIdentity() {
        waitReady();
        return linkInfo.getPeerIdentity();
    }

    public final Connection getConnection() {
        return conn;
    }

    //public boolean sending() {
    //    return true; //FIXME
    //}

    public int headerBytes() {
        // REDFLAG
        return new Rijndael().getKeySize() / 8;
    }

    private void waitReady() {
        if (!ready) {
            synchronized (conn) {
                while (!ready) {
                    try {
                        conn.wait(200);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
    }

    //profiling
    //WARNING:remove before release
    protected void finalize() {
        synchronized (profLock) {
            instances--;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see freenet.session.Link#decryptBytes(byte[], int, int)
     */
    public void decryptBytes(byte[] decryptBuffer, int offset, int len)
            throws IOException {
        CipherInputStream is = in;
		if (is == null)
			throw new IOException("already closed");
        PCFBMode ctx = is.getCipher();
		if (ctx != null)
			ctx.blockDecipher(decryptBuffer, offset, len);
    }

}
