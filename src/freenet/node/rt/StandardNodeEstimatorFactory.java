/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.node.rt;

import java.io.IOException;

import freenet.Core;
import freenet.FieldSet;
import freenet.Identity;
import freenet.Key;
import freenet.crypt.RandomSource;
import freenet.node.NodeReference;
import freenet.support.DataObjectPending;
import freenet.support.Logger;

/**
 * Factory interface for StandardNodeEstimators
 */
public class StandardNodeEstimatorFactory implements NodeEstimatorFactory {

	NGRoutingTable ngrt;
	final RunningAverageFactory rafProbability;
	final RunningAverageFactory rafTime;
	final KeyspaceEstimatorFactory kef;
	final RandomSource rand;

	public StandardNodeEstimatorFactory(
		NGRoutingTable ngrt,
		RunningAverageFactory rafProbability,
		RunningAverageFactory rafTime,
		KeyspaceEstimatorFactory rtef,
		RandomSource rand) {
		this.ngrt = ngrt;
		this.rafProbability = rafProbability;
		this.rafTime = rafTime;
		this.kef = rtef;
		this.rand = rand;
	}

	public StandardNodeEstimatorFactory(
		RunningAverageFactory rafProbability,
		RunningAverageFactory rafTime,
		KeyspaceEstimatorFactory rtef,
		RandomSource rand) {
		this.rafProbability = rafProbability;
		this.rafTime = rafTime;
		this.kef = rtef;
		this.rand = rand;
	}

	public void setNGRT(NGRoutingTable ngrt) {
		if (this.ngrt != null)
			throw new IllegalArgumentException();
		this.ngrt = ngrt;
	}

	public NodeEstimator create(
		RoutingMemory mem,
		Identity i,
		NodeReference ref,
		FieldSet e,
		boolean needConnection,
		StandardNodeStats stats) {
		// Create completely new NodeEstimator for the identity
		NodeEstimator ne =
			new StandardNodeEstimator(
				ngrt,
				i,
				ref,
				mem,
				e,
				rafProbability,
				rafTime,
				kef,
				null,
				stats,
				needConnection,
				rand);
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(
				this,
				"Created new NodeEstimator from scratch: " + ne,
				Logger.DEBUG);
		return ne;
	}

	public NodeEstimator create(
		RoutingMemory mem,
		Identity i,
		NodeReference ref,
		DataObjectPending e,
		boolean needConnection)
		throws IOException {
		// Hrrm
		// Serialize in from the DOP
		return new StandardNodeEstimator(
			ngrt,
			i,
			ref,
			mem,
			rafProbability,
			rafTime,
			kef,
			e,
			needConnection,
			rand);
	}

	public NodeEstimator create(
		RoutingMemory mem,
		Identity id,
		NodeReference ref,
		FieldSet e,
		Key k,
		boolean needConnection,
		StandardNodeStats stats) {
		NodeEstimator ne =
			new StandardNodeEstimator(
				ngrt,
				id,
				ref,
				mem,
				e,
				rafProbability,
				rafTime,
				kef,
				k,
				stats,
				needConnection,
				rand);
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(
				this,
				"Created new NodeEstimator from scratch: " + ne,
				Logger.DEBUG);
		return ne;
	}

	public KeyspaceEstimator createGlobalTimeEstimator(String name) {
		return kef.createZeroSmooth(name);
	}

	public KeyspaceEstimator createGlobalRateEstimator(String name) {
		// May as well initialize it to 0 - better that we estimate
		// retries being too expensive than too cheap.
		return kef.createInitTransfer(0.0, name);
	}

	public NodeEstimator create(
		RoutingMemory mem,
		Identity id,
		NodeReference ref,
		FieldSet estimator,
		boolean needConnection,
		NodeStats stats) {
	    if(stats == null)
	        throw new IllegalArgumentException("stats null!");
		if (stats instanceof StandardNodeStats)
			return create(
				mem,
				id,
				ref,
				estimator,
				needConnection,
				(StandardNodeStats) stats);
		else
			throw new IllegalArgumentException("Unrecognized stats type");
	}

	public NodeEstimator create(
		RoutingMemory mem,
		Identity id,
		NodeReference ref,
		FieldSet estimator,
		Key k,
		boolean needConnection,
		NodeStats stats) {
		if (stats == null)
			throw new NullPointerException();
		if (stats instanceof StandardNodeStats)
			return create(
				mem,
				id,
				ref,
				estimator,
				k,
				needConnection,
				(StandardNodeStats) stats);
		else
			throw new IllegalArgumentException("Unrecognized stats type");
	}

	public NodeStats createStats() {
		return new StandardNodeStats();
	}

	public NodeStats defaultStats() {
		return StandardNodeStats.createPessimisticDefault();
	}
}
