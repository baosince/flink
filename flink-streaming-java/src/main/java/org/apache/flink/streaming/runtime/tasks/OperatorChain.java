/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.io.network.api.CancelCheckpointMarker;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.api.writer.RecordWriter;
import org.apache.flink.runtime.metrics.MetricNames;
import org.apache.flink.runtime.metrics.groups.OperatorIOMetricGroup;
import org.apache.flink.runtime.metrics.groups.OperatorMetricGroup;
import org.apache.flink.runtime.plugable.SerializationDelegate;
import org.apache.flink.streaming.api.collector.selector.CopyingDirectedOutput;
import org.apache.flink.streaming.api.collector.selector.DirectedOutput;
import org.apache.flink.streaming.api.collector.selector.OutputSelector;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.graph.StreamEdge;
import org.apache.flink.streaming.api.operators.BoundedMultiInput;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.InputSelection;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorFactory;
import org.apache.flink.streaming.api.operators.TwoInputStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.io.RecordWriterOutput;
import org.apache.flink.streaming.runtime.metrics.WatermarkGauge;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatus;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusMaintainer;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusProvider;
import org.apache.flink.util.OutputTag;
import org.apache.flink.util.XORShiftRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * The {@code OperatorChain} contains all operators that are executed as one chain within a single
 * {@link StreamTask}.
 *
 * @param <OUT> The type of elements accepted by the chain, i.e., the input type of the chain's
 *              head operator.
 */
@Internal
public class OperatorChain<OUT, OP extends StreamOperator<OUT>> implements StreamStatusMaintainer {

	private static final Logger LOG = LoggerFactory.getLogger(OperatorChain.class);

	/**
	 * Stores all operators on this chain in reverse order.
	 */
	private final StreamOperator<?>[] allOperators;

	private final RecordWriterOutput<?>[] streamOutputs;

	private final WatermarkGaugeExposingOutput<StreamRecord<OUT>> chainEntryPoint;

	private final OP headOperator;

	/**
	 * Current status of the input stream of the operator chain.
	 * Watermarks explicitly generated by operators in the chain (i.e. timestamp
	 * assigner / watermark extractors), will be blocked and not forwarded if
	 * this value is {@link StreamStatus#IDLE}.
	 */
	private StreamStatus streamStatus = StreamStatus.ACTIVE;

	/** The flag that tracks finished inputs. */
	private InputSelection finishedInputs = new InputSelection.Builder().build();

	public OperatorChain(
			StreamTask<OUT, OP> containingTask,
			List<RecordWriter<SerializationDelegate<StreamRecord<OUT>>>> recordWriters) {

		final ClassLoader userCodeClassloader = containingTask.getUserCodeClassLoader();
		final StreamConfig configuration = containingTask.getConfiguration();

		StreamOperatorFactory<OUT> operatorFactory = configuration.getStreamOperatorFactory(userCodeClassloader);

		// we read the chained configs, and the order of record writer registrations by output name
		Map<Integer, StreamConfig> chainedConfigs = configuration.getTransitiveChainedTaskConfigsWithSelf(userCodeClassloader);

		// create the final output stream writers
		// we iterate through all the out edges from this job vertex and create a stream output
		List<StreamEdge> outEdgesInOrder = configuration.getOutEdgesInOrder(userCodeClassloader);
		Map<StreamEdge, RecordWriterOutput<?>> streamOutputMap = new HashMap<>(outEdgesInOrder.size());
		this.streamOutputs = new RecordWriterOutput<?>[outEdgesInOrder.size()];

		// from here on, we need to make sure that the output writers are shut down again on failure
		boolean success = false;
		try {
			for (int i = 0; i < outEdgesInOrder.size(); i++) {
				StreamEdge outEdge = outEdgesInOrder.get(i);

				RecordWriterOutput<?> streamOutput = createStreamOutput(
					recordWriters.get(i),
					outEdge,
					chainedConfigs.get(outEdge.getSourceId()),
					containingTask.getEnvironment());

				this.streamOutputs[i] = streamOutput;
				streamOutputMap.put(outEdge, streamOutput);
			}

			// we create the chain of operators and grab the collector that leads into the chain
			List<StreamOperator<?>> allOps = new ArrayList<>(chainedConfigs.size());
			this.chainEntryPoint = createOutputCollector(
				containingTask,
				configuration,
				chainedConfigs,
				userCodeClassloader,
				streamOutputMap,
				allOps);

			if (operatorFactory != null) {
				WatermarkGaugeExposingOutput<StreamRecord<OUT>> output = getChainEntryPoint();

				headOperator = operatorFactory.createStreamOperator(containingTask, configuration, output);

				headOperator.getMetricGroup().gauge(MetricNames.IO_CURRENT_OUTPUT_WATERMARK, output.getWatermarkGauge());
			} else {
				headOperator = null;
			}

			// add head operator to end of chain
			allOps.add(headOperator);

			this.allOperators = allOps.toArray(new StreamOperator<?>[allOps.size()]);

			success = true;
		}
		finally {
			// make sure we clean up after ourselves in case of a failure after acquiring
			// the first resources
			if (!success) {
				for (RecordWriterOutput<?> output : this.streamOutputs) {
					if (output != null) {
						output.close();
					}
				}
			}
		}
	}

	@VisibleForTesting
	OperatorChain(
			StreamOperator<?>[] allOperators,
			RecordWriterOutput<?>[] streamOutputs,
			WatermarkGaugeExposingOutput<StreamRecord<OUT>> chainEntryPoint,
			OP headOperator) {

		this.allOperators = checkNotNull(allOperators);
		this.streamOutputs = checkNotNull(streamOutputs);
		this.chainEntryPoint = checkNotNull(chainEntryPoint);
		this.headOperator = checkNotNull(headOperator);
	}

	@Override
	public StreamStatus getStreamStatus() {
		return streamStatus;
	}

	@Override
	public void toggleStreamStatus(StreamStatus status) {
		if (!status.equals(this.streamStatus)) {
			this.streamStatus = status;

			// try and forward the stream status change to all outgoing connections
			for (RecordWriterOutput<?> streamOutput : streamOutputs) {
				streamOutput.emitStreamStatus(status);
			}
		}
	}

	public void broadcastCheckpointBarrier(long id, long timestamp, CheckpointOptions checkpointOptions) throws IOException {
		CheckpointBarrier barrier = new CheckpointBarrier(id, timestamp, checkpointOptions);
		for (RecordWriterOutput<?> streamOutput : streamOutputs) {
			streamOutput.broadcastEvent(barrier);
		}
	}

	public void broadcastCheckpointCancelMarker(long id) throws IOException {
		CancelCheckpointMarker barrier = new CancelCheckpointMarker(id);
		for (RecordWriterOutput<?> streamOutput : streamOutputs) {
			streamOutput.broadcastEvent(barrier);
		}
	}

	public void prepareSnapshotPreBarrier(long checkpointId) throws Exception {
		// go forward through the operator chain and tell each operator
		// to prepare the checkpoint
		final StreamOperator<?>[] operators = this.allOperators;
		for (int i = operators.length - 1; i >= 0; --i) {
			final StreamOperator<?> op = operators[i];
			if (op != null) {
				op.prepareSnapshotPreBarrier(checkpointId);
			}
		}
	}

	/**
	 * Ends an input (specified by {@code inputId}) of the {@link StreamTask}. The {@code inputId}
	 * is numbered starting from 1, and `1` indicates the first input.
	 *
	 * @param inputId The ID of the input.
	 * @throws Exception if some exception happens in the endInput function of an operator.
	 */
	public void endInput(int inputId) throws Exception {
		if (finishedInputs.areAllInputsSelected()) {
			return;
		}

		if (headOperator instanceof TwoInputStreamOperator) {
			if (finishedInputs.isInputSelected(inputId)) {
				return;
			}

			if (headOperator instanceof BoundedMultiInput) {
				((BoundedMultiInput) headOperator).endInput(inputId);
			}

			finishedInputs = InputSelection.Builder
				.from(finishedInputs)
				.select(finishedInputs.getInputMask() == 0 ? inputId : -1)
				.build();
		} else {
			// here, the head operator is a stream source or an one-input stream operator,
			// so all inputs are finished
			finishedInputs = new InputSelection.Builder()
				.select(-1)
				.build();
		}

		if (finishedInputs.areAllInputsSelected()) {
			// executing #endInput() happens from head to tail operator in the chain
			for (int i = allOperators.length - 1; i >= 0; i--) {
				StreamOperator<?> operator = allOperators[i];
				if (operator instanceof BoundedOneInput) {
					((BoundedOneInput) operator).endInput();
				}
			}
		}
	}

	public RecordWriterOutput<?>[] getStreamOutputs() {
		return streamOutputs;
	}

	public StreamOperator<?>[] getAllOperators() {
		return allOperators;
	}

	public WatermarkGaugeExposingOutput<StreamRecord<OUT>> getChainEntryPoint() {
		return chainEntryPoint;
	}

	/**
	 * This method should be called before finishing the record emission, to make sure any data
	 * that is still buffered will be sent. It also ensures that all data sending related
	 * exceptions are recognized.
	 *
	 * @throws IOException Thrown, if the buffered data cannot be pushed into the output streams.
	 */
	public void flushOutputs() throws IOException {
		for (RecordWriterOutput<?> streamOutput : getStreamOutputs()) {
			streamOutput.flush();
		}
	}

	/**
	 * This method releases all resources of the record writer output. It stops the output
	 * flushing thread (if there is one) and releases all buffers currently held by the output
	 * serializers.
	 *
	 * <p>This method should never fail.
	 */
	public void releaseOutputs() {
		for (RecordWriterOutput<?> streamOutput : streamOutputs) {
			streamOutput.close();
		}
	}

	public OP getHeadOperator() {
		return headOperator;
	}

	public int getChainLength() {
		return allOperators == null ? 0 : allOperators.length;
	}

	// ------------------------------------------------------------------------
	//  initialization utilities
	// ------------------------------------------------------------------------

	private <T> WatermarkGaugeExposingOutput<StreamRecord<T>> createOutputCollector(
			StreamTask<?, ?> containingTask,
			StreamConfig operatorConfig,
			Map<Integer, StreamConfig> chainedConfigs,
			ClassLoader userCodeClassloader,
			Map<StreamEdge, RecordWriterOutput<?>> streamOutputs,
			List<StreamOperator<?>> allOperators) {
		List<Tuple2<WatermarkGaugeExposingOutput<StreamRecord<T>>, StreamEdge>> allOutputs = new ArrayList<>(4);

		// create collectors for the network outputs
		for (StreamEdge outputEdge : operatorConfig.getNonChainedOutputs(userCodeClassloader)) {
			@SuppressWarnings("unchecked")
			RecordWriterOutput<T> output = (RecordWriterOutput<T>) streamOutputs.get(outputEdge);

			allOutputs.add(new Tuple2<>(output, outputEdge));
		}

		// Create collectors for the chained outputs
		for (StreamEdge outputEdge : operatorConfig.getChainedOutputs(userCodeClassloader)) {
			int outputId = outputEdge.getTargetId();
			StreamConfig chainedOpConfig = chainedConfigs.get(outputId);

			WatermarkGaugeExposingOutput<StreamRecord<T>> output = createChainedOperator(
				containingTask,
				chainedOpConfig,
				chainedConfigs,
				userCodeClassloader,
				streamOutputs,
				allOperators,
				outputEdge.getOutputTag());
			allOutputs.add(new Tuple2<>(output, outputEdge));
		}

		// if there are multiple outputs, or the outputs are directed, we need to
		// wrap them as one output

		List<OutputSelector<T>> selectors = operatorConfig.getOutputSelectors(userCodeClassloader);

		if (selectors == null || selectors.isEmpty()) {
			// simple path, no selector necessary
			if (allOutputs.size() == 1) {
				return allOutputs.get(0).f0;
			}
			else {
				// send to N outputs. Note that this includes the special case
				// of sending to zero outputs
				@SuppressWarnings({"unchecked", "rawtypes"})
				Output<StreamRecord<T>>[] asArray = new Output[allOutputs.size()];
				for (int i = 0; i < allOutputs.size(); i++) {
					asArray[i] = allOutputs.get(i).f0;
				}

				// This is the inverse of creating the normal ChainingOutput.
				// If the chaining output does not copy we need to copy in the broadcast output,
				// otherwise multi-chaining would not work correctly.
				if (containingTask.getExecutionConfig().isObjectReuseEnabled()) {
					return new CopyingBroadcastingOutputCollector<>(asArray, this);
				} else  {
					return new BroadcastingOutputCollector<>(asArray, this);
				}
			}
		}
		else {
			// selector present, more complex routing necessary

			// This is the inverse of creating the normal ChainingOutput.
			// If the chaining output does not copy we need to copy in the broadcast output,
			// otherwise multi-chaining would not work correctly.
			if (containingTask.getExecutionConfig().isObjectReuseEnabled()) {
				return new CopyingDirectedOutput<>(selectors, allOutputs);
			} else {
				return new DirectedOutput<>(selectors, allOutputs);
			}

		}
	}

	private <IN, OUT> WatermarkGaugeExposingOutput<StreamRecord<IN>> createChainedOperator(
			StreamTask<?, ?> containingTask,
			StreamConfig operatorConfig,
			Map<Integer, StreamConfig> chainedConfigs,
			ClassLoader userCodeClassloader,
			Map<StreamEdge, RecordWriterOutput<?>> streamOutputs,
			List<StreamOperator<?>> allOperators,
			OutputTag<IN> outputTag) {
		// create the output that the operator writes to first. this may recursively create more operators
		WatermarkGaugeExposingOutput<StreamRecord<OUT>> chainedOperatorOutput = createOutputCollector(
			containingTask,
			operatorConfig,
			chainedConfigs,
			userCodeClassloader,
			streamOutputs,
			allOperators);

		// now create the operator and give it the output collector to write its output to
		StreamOperatorFactory<OUT> chainedOperatorFactory = operatorConfig.getStreamOperatorFactory(userCodeClassloader);
		OneInputStreamOperator<IN, OUT> chainedOperator = chainedOperatorFactory.createStreamOperator(
				containingTask, operatorConfig, chainedOperatorOutput);

		allOperators.add(chainedOperator);

		WatermarkGaugeExposingOutput<StreamRecord<IN>> currentOperatorOutput;
		if (containingTask.getExecutionConfig().isObjectReuseEnabled()) {
			currentOperatorOutput = new ChainingOutput<>(chainedOperator, this, outputTag);
		}
		else {
			TypeSerializer<IN> inSerializer = operatorConfig.getTypeSerializerIn1(userCodeClassloader);
			currentOperatorOutput = new CopyingChainingOutput<>(chainedOperator, inSerializer, outputTag, this);
		}

		// wrap watermark gauges since registered metrics must be unique
		chainedOperator.getMetricGroup().gauge(MetricNames.IO_CURRENT_INPUT_WATERMARK, currentOperatorOutput.getWatermarkGauge()::getValue);
		chainedOperator.getMetricGroup().gauge(MetricNames.IO_CURRENT_OUTPUT_WATERMARK, chainedOperatorOutput.getWatermarkGauge()::getValue);

		return currentOperatorOutput;
	}

	private RecordWriterOutput<OUT> createStreamOutput(
			RecordWriter<SerializationDelegate<StreamRecord<OUT>>> recordWriter,
			StreamEdge edge,
			StreamConfig upStreamConfig,
			Environment taskEnvironment) {
		OutputTag sideOutputTag = edge.getOutputTag(); // OutputTag, return null if not sideOutput

		TypeSerializer outSerializer = null;

		if (edge.getOutputTag() != null) {
			// side output
			outSerializer = upStreamConfig.getTypeSerializerSideOut(
					edge.getOutputTag(), taskEnvironment.getUserClassLoader());
		} else {
			// main output
			outSerializer = upStreamConfig.getTypeSerializerOut(taskEnvironment.getUserClassLoader());
		}

		return new RecordWriterOutput<>(recordWriter, outSerializer, sideOutputTag, this);
	}

	// ------------------------------------------------------------------------
	//  Collectors for output chaining
	// ------------------------------------------------------------------------

	/**
	 * An {@link Output} that measures the last emitted watermark with a {@link WatermarkGauge}.
	 *
	 * @param <T> The type of the elements that can be emitted.
	 */
	public interface WatermarkGaugeExposingOutput<T> extends Output<T> {
		Gauge<Long> getWatermarkGauge();
	}

	static class ChainingOutput<T> implements WatermarkGaugeExposingOutput<StreamRecord<T>> {

		protected final OneInputStreamOperator<T, ?> operator;
		protected final Counter numRecordsIn;
		protected final WatermarkGauge watermarkGauge = new WatermarkGauge();

		protected final StreamStatusProvider streamStatusProvider;

		@Nullable
		protected final OutputTag<T> outputTag;

		public ChainingOutput(
				OneInputStreamOperator<T, ?> operator,
				StreamStatusProvider streamStatusProvider,
				@Nullable OutputTag<T> outputTag) {
			this.operator = operator;

			{
				Counter tmpNumRecordsIn;
				try {
					OperatorIOMetricGroup ioMetricGroup = ((OperatorMetricGroup) operator.getMetricGroup()).getIOMetricGroup();
					tmpNumRecordsIn = ioMetricGroup.getNumRecordsInCounter();
				} catch (Exception e) {
					LOG.warn("An exception occurred during the metrics setup.", e);
					tmpNumRecordsIn = new SimpleCounter();
				}
				numRecordsIn = tmpNumRecordsIn;
			}

			this.streamStatusProvider = streamStatusProvider;
			this.outputTag = outputTag;
		}

		@Override
		public void collect(StreamRecord<T> record) {
			if (this.outputTag != null) {
				// we are only responsible for emitting to the main input
				return;
			}

			pushToOperator(record);
		}

		@Override
		public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
			if (this.outputTag == null || !this.outputTag.equals(outputTag)) {
				// we are only responsible for emitting to the side-output specified by our
				// OutputTag.
				return;
			}

			pushToOperator(record);
		}

		protected <X> void pushToOperator(StreamRecord<X> record) {
			try {
				// we know that the given outputTag matches our OutputTag so the record
				// must be of the type that our operator expects.
				@SuppressWarnings("unchecked")
				StreamRecord<T> castRecord = (StreamRecord<T>) record;

				numRecordsIn.inc();
				operator.setKeyContextElement1(castRecord);
				operator.processElement(castRecord);
			}
			catch (Exception e) {
				throw new ExceptionInChainedOperatorException(e);
			}
		}

		@Override
		public void emitWatermark(Watermark mark) {
			try {
				watermarkGauge.setCurrentWatermark(mark.getTimestamp());
				if (streamStatusProvider.getStreamStatus().isActive()) {
					operator.processWatermark(mark);
				}
			}
			catch (Exception e) {
				throw new ExceptionInChainedOperatorException(e);
			}
		}

		@Override
		public void emitLatencyMarker(LatencyMarker latencyMarker) {
			try {
				operator.processLatencyMarker(latencyMarker);
			}
			catch (Exception e) {
				throw new ExceptionInChainedOperatorException(e);
			}
		}

		@Override
		public void close() {
			try {
				operator.close();
			}
			catch (Exception e) {
				throw new ExceptionInChainedOperatorException(e);
			}
		}

		@Override
		public Gauge<Long> getWatermarkGauge() {
			return watermarkGauge;
		}
	}

	static final class CopyingChainingOutput<T> extends ChainingOutput<T> {

		private final TypeSerializer<T> serializer;

		public CopyingChainingOutput(
				OneInputStreamOperator<T, ?> operator,
				TypeSerializer<T> serializer,
				OutputTag<T> outputTag,
				StreamStatusProvider streamStatusProvider) {
			super(operator, streamStatusProvider, outputTag);
			this.serializer = serializer;
		}

		@Override
		public void collect(StreamRecord<T> record) {
			if (this.outputTag != null) {
				// we are only responsible for emitting to the main input
				return;
			}

			pushToOperator(record);
		}

		@Override
		public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
			if (this.outputTag == null || !this.outputTag.equals(outputTag)) {
				// we are only responsible for emitting to the side-output specified by our
				// OutputTag.
				return;
			}

			pushToOperator(record);
		}

		@Override
		protected <X> void pushToOperator(StreamRecord<X> record) {
			try {
				// we know that the given outputTag matches our OutputTag so the record
				// must be of the type that our operator (and Serializer) expects.
				@SuppressWarnings("unchecked")
				StreamRecord<T> castRecord = (StreamRecord<T>) record;

				numRecordsIn.inc();
				StreamRecord<T> copy = castRecord.copy(serializer.copy(castRecord.getValue()));
				operator.setKeyContextElement1(copy);
				operator.processElement(copy);
			} catch (ClassCastException e) {
				if (outputTag != null) {
					// Enrich error message
					ClassCastException replace = new ClassCastException(
						String.format(
							"%s. Failed to push OutputTag with id '%s' to operator. " +
								"This can occur when multiple OutputTags with different types " +
								"but identical names are being used.",
							e.getMessage(),
							outputTag.getId()));

					throw new ExceptionInChainedOperatorException(replace);
				} else {
					throw new ExceptionInChainedOperatorException(e);
				}
			} catch (Exception e) {
				throw new ExceptionInChainedOperatorException(e);
			}

		}
	}

	static class BroadcastingOutputCollector<T> implements WatermarkGaugeExposingOutput<StreamRecord<T>> {

		protected final Output<StreamRecord<T>>[] outputs;

		private final Random random = new XORShiftRandom();

		private final StreamStatusProvider streamStatusProvider;

		private final WatermarkGauge watermarkGauge = new WatermarkGauge();

		public BroadcastingOutputCollector(
				Output<StreamRecord<T>>[] outputs,
				StreamStatusProvider streamStatusProvider) {
			this.outputs = outputs;
			this.streamStatusProvider = streamStatusProvider;
		}

		@Override
		public void emitWatermark(Watermark mark) {
			watermarkGauge.setCurrentWatermark(mark.getTimestamp());
			if (streamStatusProvider.getStreamStatus().isActive()) {
				for (Output<StreamRecord<T>> output : outputs) {
					output.emitWatermark(mark);
				}
			}
		}

		@Override
		public void emitLatencyMarker(LatencyMarker latencyMarker) {
			if (outputs.length <= 0) {
				// ignore
			} else if (outputs.length == 1) {
				outputs[0].emitLatencyMarker(latencyMarker);
			} else {
				// randomly select an output
				outputs[random.nextInt(outputs.length)].emitLatencyMarker(latencyMarker);
			}
		}

		@Override
		public Gauge<Long> getWatermarkGauge() {
			return watermarkGauge;
		}

		@Override
		public void collect(StreamRecord<T> record) {
			for (Output<StreamRecord<T>> output : outputs) {
				output.collect(record);
			}
		}

		@Override
		public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
			for (Output<StreamRecord<T>> output : outputs) {
				output.collect(outputTag, record);
			}
		}

		@Override
		public void close() {
			for (Output<StreamRecord<T>> output : outputs) {
				output.close();
			}
		}
	}

	/**
	 * Special version of {@link BroadcastingOutputCollector} that performs a shallow copy of the
	 * {@link StreamRecord} to ensure that multi-chaining works correctly.
	 */
	static final class CopyingBroadcastingOutputCollector<T> extends BroadcastingOutputCollector<T> {

		public CopyingBroadcastingOutputCollector(
				Output<StreamRecord<T>>[] outputs,
				StreamStatusProvider streamStatusProvider) {
			super(outputs, streamStatusProvider);
		}

		@Override
		public void collect(StreamRecord<T> record) {

			for (int i = 0; i < outputs.length - 1; i++) {
				Output<StreamRecord<T>> output = outputs[i];
				StreamRecord<T> shallowCopy = record.copy(record.getValue());
				output.collect(shallowCopy);
			}

			if (outputs.length > 0) {
				// don't copy for the last output
				outputs[outputs.length - 1].collect(record);
			}
		}

		@Override
		public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
			for (int i = 0; i < outputs.length - 1; i++) {
				Output<StreamRecord<T>> output = outputs[i];

				StreamRecord<X> shallowCopy = record.copy(record.getValue());
				output.collect(outputTag, shallowCopy);
			}

			if (outputs.length > 0) {
				// don't copy for the last output
				outputs[outputs.length - 1].collect(outputTag, record);
			}
		}
	}
}
