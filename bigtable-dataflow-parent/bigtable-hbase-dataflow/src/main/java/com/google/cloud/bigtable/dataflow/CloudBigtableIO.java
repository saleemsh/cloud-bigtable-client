/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigtable.dataflow;

import static com.google.bigtable.repackaged.com.google.api.client.repackaged.com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.BufferedMutator.ExceptionListener;
import org.apache.hadoop.hbase.client.BufferedMutatorParams;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.RetriesExhaustedWithDetailsException;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.util.Preconditions;
import com.google.bigtable.repackaged.com.google.bigtable.v2.SampleRowKeysRequest;
import com.google.bigtable.repackaged.com.google.bigtable.v2.SampleRowKeysResponse;
import com.google.bigtable.repackaged.com.google.cloud.bigtable.config.BulkOptions;
import com.google.bigtable.repackaged.com.google.cloud.bigtable.grpc.BigtableDataClient;
import com.google.bigtable.repackaged.com.google.cloud.bigtable.grpc.BigtableInstanceName;
import com.google.bigtable.repackaged.com.google.cloud.bigtable.grpc.BigtableSession;
import com.google.bigtable.repackaged.com.google.cloud.bigtable.grpc.BigtableSessionSharedThreadPools;
import com.google.bigtable.repackaged.com.google.cloud.bigtable.grpc.async.ResourceLimiterStats;
import com.google.bigtable.repackaged.com.google.cloud.bigtable.grpc.scanner.FlatRow;
import com.google.bigtable.repackaged.com.google.cloud.bigtable.grpc.scanner.ResultScanner;
import com.google.cloud.bigtable.batch.common.ByteStringUtil;
import com.google.cloud.bigtable.batch.common.CloudBigtableServiceImpl;
import com.google.cloud.bigtable.dataflow.coders.HBaseMutationCoder;
import com.google.cloud.bigtable.dataflow.coders.HBaseResultArrayCoder;
import com.google.cloud.bigtable.dataflow.coders.HBaseResultCoder;
import com.google.cloud.bigtable.hbase.BigtableOptionsFactory;
import com.google.cloud.bigtable.hbase.adapters.read.FlatRowAdapter;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.coders.AtomicCoder;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderRegistry;
import com.google.cloud.dataflow.sdk.io.BoundedSource;
import com.google.cloud.dataflow.sdk.io.BoundedSource.BoundedReader;
import com.google.cloud.dataflow.sdk.io.range.ByteKey;
import com.google.cloud.dataflow.sdk.io.range.ByteKeyRangeTracker;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.transforms.display.DisplayData;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PDone;
import com.google.common.annotations.VisibleForTesting;

/**
 * <p>
 * Utilities to create {@link com.google.cloud.dataflow.sdk.transforms.PTransform}s for reading and
 * writing <a href="https://cloud.google.com/bigtable/">Google Cloud Bigtable</a> entities in a
 * Cloud Dataflow pipeline.
 * </p>
 * <p>
 * Google Cloud Bigtable offers you a fast, fully managed, massively scalable NoSQL database service
 * that's ideal for web, mobile, and Internet of Things applications requiring terabytes to
 * petabytes of data. Unlike comparable market offerings, Cloud Bigtable doesn't require you to
 * sacrifice speed, scale, or cost efficiency when your applications grow. Cloud Bigtable has been
 * battle-tested at Google for more than 10 years--it's the database driving major applications such
 * as Google Analytics and Gmail.
 * </p>
 * <p>
 * To use {@link CloudBigtableIO}, users must use gcloud to get a credential for Cloud Bigtable:
 *
 * <pre>
 * $ gcloud auth login
 * </pre>
 * <p>
 * To read a {@link PCollection} from a table, with an optional {@link Scan}, use
 * {@link CloudBigtableIO#read(CloudBigtableScanConfiguration)}:
 * </p>
 *
 * <pre>
 * {@code
 * PipelineOptions options = PipelineOptionsFactory.fromArgs(args).create();
 * Pipeline p = Pipeline.create(options);
 * PCollection<Result> = p.apply(
 *   Read.from(CloudBigtableIO.read(
 *      new CloudBigtableScanConfiguration.Builder()
 *          .withProjectId("project-id")
 *          .withInstanceId("instance-id")
 *          .withTableId("table-id")
 *          .build())));
 * }
 * </pre>
 * <p>
 * To write a {@link PCollection} to a table, use
 * {@link CloudBigtableIO#writeToTable(CloudBigtableTableConfiguration)}:
 * </p>
 *
 * <pre>
 * {@code
 * PipelineOptions options =
 *     PipelineOptionsFactory.fromArgs(args).create();
 * Pipeline p = Pipeline.create(options);
 * PCollection<Mutation> mutationCollection = ...;
 * mutationCollection.apply(
 *   CloudBigtableIO.writeToTable(
 *      new CloudBigtableScanConfiguration.Builder()
 *          .withProjectId("project-id")
 *          .withInstanceId("instance-id")
 *          .withTableId("table-id")
 *          .build()));
 * }
 * </pre>
 *
 * @deprecated Please use the Beam version of CloudBigtableIO instead. This project will be removed
 *             in future versions.
 */

@Deprecated
public class CloudBigtableIO {

  enum CoderType {
    RESULT,
    RESULT_ARRAY;
  }

  private static AtomicCoder<Result> RESULT_CODER = new HBaseResultCoder();
  private static AtomicCoder<Result[]> RESULT_ARRAY_CODER = new HBaseResultArrayCoder();
  private static final FlatRowAdapter FLAT_ROW_ADAPTER = new FlatRowAdapter();

  @SuppressWarnings("rawtypes")
  private static AtomicCoder HBASE_MUTATION_CODER = new HBaseMutationCoder();

  @SuppressWarnings("rawtypes")
  public static Coder getCoder(CoderType type) {
    switch (type) {
      case RESULT:
        return RESULT_CODER;

      case RESULT_ARRAY:
        return RESULT_ARRAY_CODER;

      default:
        throw new IllegalArgumentException("Can't get a coder for type: " + type.name());
    }
  }

  /**
   * Performs a {@link ResultScanner#next()} or {@link ResultScanner#next(int)}.  It also checks if
   * the ResultOutputType marks the last value in the {@link ResultScanner}.
   *
   * @param <ResultOutputType> is either a {@link Result} or {@link Result}[];
   */
  @VisibleForTesting
  interface ScanIterator<ResultOutputType> extends Serializable {
    /**
     * Get the next unit of work.
     *
     * @param resultScanner The {@link ResultScanner} on which to operate.
     * @param rangeTracker The {@link ByteKeyRangeTracker} that defines the range in which to get results.
     */
    ResultOutputType next(ResultScanner<FlatRow> resultScanner, ByteKeyRangeTracker rangeTracker) throws IOException;

    /**
     * Is the work complete? Checks for null in the case of {@link Result}, or empty in the case of
     * an array of ResultOutputType.
     */
    boolean isCompletionMarker(ResultOutputType result);

    /**
     * This is used to figure out how many results were read.  This is more useful for {@link Result}[].
     */
    long getRowCount(ResultOutputType result);
  }

  /**
   * Iterates the {@link ResultScanner} via {@link ResultScanner#next()}.
   */
  static final ScanIterator<Result> RESULT_ADVANCER = new ScanIterator<Result>() {
    private static final long serialVersionUID = 1L;

    @Override
    public Result next(ResultScanner<FlatRow> resultScanner, ByteKeyRangeTracker rangeTracker)
        throws IOException {
      FlatRow row = resultScanner.next();
      if (row != null
          && rangeTracker.tryReturnRecordAt(true, ByteStringUtil.toByteKey(row.getRowKey()))) {
        return FLAT_ROW_ADAPTER.adaptResponse(row);
      }
      return null;
    }

    @Override
    public boolean isCompletionMarker(Result result) {
      return result == null;
    }

    @Override
    public long getRowCount(Result result) {
      return result == null ? 0 : 1;
    }
  };

  /**
   * Iterates the {@link ResultScanner} via {@link ResultScanner#next(int)}.
   */
  static final class ResultArrayIterator implements ScanIterator<Result[]> {
    private static final long serialVersionUID = 1L;
    private final int arraySize;

    public ResultArrayIterator(int arraySize) {
      this.arraySize = arraySize;
    }


    @Override
    public Result[] next(ResultScanner<FlatRow> resultScanner, ByteKeyRangeTracker rangeTracker)
        throws IOException {
      List<Result> results = new ArrayList<>();
      for (int i = 0; i < arraySize; i++) {
        FlatRow row = resultScanner.next();
        if (row == null) {
          // The scan completed.
          break;
        }
        ByteKey key = ByteStringUtil.toByteKey(row.getRowKey());
        if (!rangeTracker.tryReturnRecordAt(true, key)) {
          // A split occurred and the split key was before this key.
          break;
        }
        results.add(FLAT_ROW_ADAPTER.adaptResponse(row));
      }
      return results.toArray(new Result[results.size()]);
    }


    @Override
    public boolean isCompletionMarker(Result[] result) {
      return result == null || result.length == 0;
    }

    @Override
    public long getRowCount(Result[] result) {
      return result == null ? 0 : result.length;
    }
  }

  /**
   * A {@link BoundedSource} for a Cloud Bigtable {@link Table}, which is potentially filtered by a
   * {@link Scan}.
   */
  @SuppressWarnings("serial")
  static abstract class AbstractSource<ResultOutputType> extends BoundedSource<ResultOutputType> {
    protected static final Logger SOURCE_LOG = LoggerFactory.getLogger(AbstractSource.class);
    protected static final long SIZED_BASED_MAX_SPLIT_COUNT = 4_000;
    static final long COUNT_MAX_SPLIT_COUNT= 15_360;

    /**
     * Configuration for a Cloud Bigtable connection, a table, and an optional scan.
     */
    private final CloudBigtableScanConfiguration configuration;

    // Ordinals aren't necessarily consistent across VMs, so use the name
    private final String coderTypeName;
    private final ScanIterator<ResultOutputType> scanIterator;

    private transient List<SampleRowKeysResponse> sampleRowKeys;

    AbstractSource(
        CloudBigtableScanConfiguration configuration,
        CoderType coderType,
        ScanIterator<ResultOutputType> scanIterator) {
      this.configuration = configuration;
      this.coderTypeName = coderType.name();
      this.scanIterator = scanIterator;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Coder<ResultOutputType> getDefaultOutputCoder() {
      return getCoder(CoderType.valueOf(coderTypeName));
    }

    // TODO: Move the splitting logic to bigtable-hbase, and separate concerns between dataflow needs
    // and Cloud Bigtable logic.
    protected List<SourceWithKeys<ResultOutputType>> getSplits(long desiredBundleSizeBytes) throws Exception {
      desiredBundleSizeBytes = Math.max(getEstimatedSizeBytes(null) / SIZED_BASED_MAX_SPLIT_COUNT,
        desiredBundleSizeBytes);
      CloudBigtableScanConfiguration conf = getConfiguration();
      byte[] scanStartKey = conf.getZeroCopyStartRow();
      byte[] scanEndKey = conf.getZeroCopyStopRow();
      List<SourceWithKeys<ResultOutputType>> splits = new ArrayList<>();
      byte[] startKey = HConstants.EMPTY_START_ROW;
      long lastOffset = 0;
      for (SampleRowKeysResponse response : getSampleRowKeys()) {
        byte[] endKey = response.getRowKey().toByteArray();
        // Avoid empty regions.
        if (Bytes.equals(startKey, endKey) && startKey.length > 0) {
          continue;
        }

        long offset = response.getOffsetBytes();
        // Get all the start/end key ranges that match the user supplied Scan.  See
        // https://github.com/apache/hbase/blob/master/hbase-server/src/main/java/org/apache/hadoop/hbase/mapreduce/TableInputFormatBase.java#L298
        // for original logic.
        if (isWithinRange(scanStartKey, scanEndKey, startKey, endKey)) {
          byte[] splitStart = null;
          byte[] splitStop = null;
          if (scanStartKey.length == 0 || Bytes.compareTo(startKey, scanStartKey) >= 0) {
            splitStart = startKey;
          } else {
            splitStart = scanStartKey;
          }

          if ((scanEndKey.length == 0 || Bytes.compareTo(endKey, scanEndKey) <= 0)
              && endKey.length > 0) {
            splitStop = endKey;
          } else {
            splitStop = scanEndKey;
          }
          splits.addAll(split(offset - lastOffset, desiredBundleSizeBytes, splitStart, splitStop));
        }
        lastOffset = offset;
        startKey = endKey;
      }
      // Create one last region if the last region doesn't reach the end or there are no regions.
      byte[] endKey = HConstants.EMPTY_END_ROW;
      if (!Bytes.equals(startKey, endKey) && scanEndKey.length == 0) {
        splits.add(createSourceWithKeys(startKey, endKey, 0));
      }
      List<SourceWithKeys<ResultOutputType>> result = reduceSplits(splits);

      // Randomize the list, since the default behavior would lead to multiple workers hitting the
      // same tablet.
      Collections.shuffle(result);
      return result;
    }

    private List<SourceWithKeys<ResultOutputType>>
        reduceSplits(List<SourceWithKeys<ResultOutputType>> splits) {
      if (splits.size() < COUNT_MAX_SPLIT_COUNT) {
        return splits;
      }
      List<SourceWithKeys<ResultOutputType>> reducedSplits = new ArrayList<>();
      SourceWithKeys<ResultOutputType> start = null;
      SourceWithKeys<ResultOutputType> lastSeen = null;
      int numberToCombine = (int) ((splits.size() + COUNT_MAX_SPLIT_COUNT - 1) / COUNT_MAX_SPLIT_COUNT);
      int counter = 0;
      long size = 0;
      for (SourceWithKeys<ResultOutputType> source : splits) {
        if (counter == 0) {
          start = source;
        }
        size += source.getEstimatedSize();
        counter++;
        lastSeen = source;
        if (counter == numberToCombine) {
          reducedSplits.add(createSourceWithKeys(start.getConfiguration().getZeroCopyStartRow(),
            source.getConfiguration().getZeroCopyStopRow(), size));
          counter = 0;
          size = 0;
          start = null;
        }
      }
      if (start != null) {
        reducedSplits.add(createSourceWithKeys(start.getConfiguration().getZeroCopyStartRow(),
          lastSeen.getConfiguration().getZeroCopyStopRow(), size));
      }
      return reducedSplits;
    }

    /**
     * Checks if the range of the region is within the range of the scan.
     */
    protected static boolean isWithinRange(byte[] scanStartKey, byte[] scanEndKey,
        byte[] startKey, byte[] endKey) {
      return (scanStartKey.length == 0 || endKey.length == 0
              || Bytes.compareTo(scanStartKey, endKey) < 0)
          && (scanEndKey.length == 0 || Bytes.compareTo(scanEndKey, startKey) > 0);
    }

    /**
     * Performs a call to get sample row keys from
     * {@link BigtableDataClient#sampleRowKeys(SampleRowKeysRequest)} if they are not yet cached.
     * The sample row keys give information about tablet key boundaries and estimated sizes.
     */
    public synchronized List<SampleRowKeysResponse> getSampleRowKeys() throws IOException {
      if (sampleRowKeys == null) {
        sampleRowKeys = new CloudBigtableServiceImpl().getSampleRowKeys(getConfiguration());
      }
      return sampleRowKeys;
    }

    @VisibleForTesting
    void setSampleRowKeys(List<SampleRowKeysResponse> sampleRowKeys) {
      this.sampleRowKeys = sampleRowKeys;
    }

    /**
     * Validates the existence of the table in the configuration.
     */
    @Override
    public void validate() {
      CloudBigtableIO.validateTableConfig(getConfiguration());
    }

    /**
     * Gets an estimated size based on data returned from {@link #getSampleRowKeys}. The estimate
     * will be high if a {@link Scan} is set on the {@link CloudBigtableScanConfiguration}; in such
     * cases, the estimate will not take the Scan into account, and will return a larger estimate
     * than what the {@link CloudBigtableIO.Reader} will actually read.
     *
     * @param options The pipeline options.
     * @return The estimated size of the data, in bytes.
     * @throws IOException
     */
    @Override
    public long getEstimatedSizeBytes(PipelineOptions options) throws IOException {
      long totalEstimatedSizeBytes = 0;

      byte[] scanStartKey = getConfiguration().getZeroCopyStartRow();
      byte[] scanStopKey = getConfiguration().getZeroCopyStopRow();

      byte[] startKey = HConstants.EMPTY_START_ROW;
      long lastOffset = 0;
      for (SampleRowKeysResponse response : getSampleRowKeys()) {
        byte[] currentEndKey = response.getRowKey().toByteArray();
        // Avoid empty regions.
        if (Bytes.equals(startKey, currentEndKey) && startKey.length != 0) {
          continue;
        }
        long offset = response.getOffsetBytes();
        if (isWithinRange(scanStartKey, scanStopKey, startKey, currentEndKey)) {
          totalEstimatedSizeBytes += (offset - lastOffset);
        }
        lastOffset = offset;
        startKey = currentEndKey;
      }
      SOURCE_LOG.info("Estimated size in bytes: " + totalEstimatedSizeBytes);

      return totalEstimatedSizeBytes;
    }

    /**
     * Checks whether the pipeline produces sorted keys.
     *
     * <p>NOTE: HBase supports reverse scans, but Cloud Bigtable does not.
     *
     * @param options The pipeline options.
     * @return Whether the pipeline produces sorted keys.
     */
    @Override
    public boolean producesSortedKeys(PipelineOptions options) throws Exception {
      return true;
    }

    /**
     * Splits the region based on the start and stop key. Uses
     * {@link Bytes#split(byte[], byte[], int)} under the covers.
     * @throws IOException
     */
    protected List<SourceWithKeys<ResultOutputType>> split(long regionSize, long desiredBundleSizeBytes,
        byte[] startKey, byte[] stopKey) throws IOException {
      Preconditions.checkState(desiredBundleSizeBytes > 0);
      int splitCount = (int) Math.ceil((double) (regionSize) / (double) (desiredBundleSizeBytes));

      if (splitCount < 2 || stopKey.length == 0 || Bytes.compareTo(startKey,stopKey) >= 0) {
        return Collections.singletonList(createSourceWithKeys(startKey, stopKey, regionSize));
      } else {
        if (stopKey.length > 0) {
          Preconditions.checkState(Bytes.compareTo(startKey, stopKey) <= 0,
            "Source keys not in order: [%s, %s]", Bytes.toStringBinary(startKey),
            Bytes.toStringBinary(stopKey));
          Preconditions.checkState(regionSize > 0, "Source size must be positive", regionSize);
        }
        try {
          byte[][] splitKeys = Bytes.split(startKey, stopKey, splitCount - 1);
          Preconditions.checkState(splitCount + 1 == splitKeys.length);
          List<SourceWithKeys<ResultOutputType>> result = new ArrayList<>();
          for (int i = 0; i < splitCount; i++) {
            result.add(createSourceWithKeys(splitKeys[i], splitKeys[i + 1], regionSize));
          }
          return result;
        } catch (Exception e) {
          SOURCE_LOG.warn(String.format("Could not split '%s' and '%s', so using that as a range.",
            Bytes.toString(startKey), Bytes.toString(stopKey)), e);
          return Collections.singletonList(createSourceWithKeys(startKey, stopKey, regionSize));
        }
      }
    }

    @VisibleForTesting
    SourceWithKeys<ResultOutputType> createSourceWithKeys(byte[] startKey, byte[] stopKey,
        long size) {
      CloudBigtableScanConfiguration updatedConfig =
          getConfiguration().toBuilder().withKeys(startKey, stopKey).build();
      return new SourceWithKeys<>(updatedConfig, CoderType.valueOf(coderTypeName), scanIterator,
          size);
    }

    /**
     * Creates a reader that will scan the entire table based on the {@link Scan} in the
     * configuration.
     * @return A reader for the table.
     */
    @Override
    public BoundedSource.BoundedReader<ResultOutputType> createReader(PipelineOptions options) {
      return new CloudBigtableIO.Reader<>(this, scanIterator);
    }

    /**
     * @return the configuration
     */
    protected CloudBigtableScanConfiguration getConfiguration() {
      return configuration;
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      configuration.populateDisplayData(builder);
    }
  }

  /**
   * A {@link BoundedSource} for a Cloud Bigtable {@link Table}, which is potentially filtered by a
   * {@link Scan}.
   */
  public static class Source<ResultOutputType> extends AbstractSource<ResultOutputType> {
    private static final long serialVersionUID = -5580115943635114126L;

    Source(
        CloudBigtableScanConfiguration configuration,
        CoderType coderType,
        ScanIterator<ResultOutputType> scanIterator) {
      super(configuration, coderType, scanIterator);
    }

    // TODO: Add a method on the server side that will be a more precise split based on server-side
    // statistics
    /**
     * Splits the table based on keys that belong to tablets, known as "regions" in the HBase API.
     * The current implementation uses the HBase {@link RegionLocator} interface, which calls
     * {@link BigtableDataClient#sampleRowKeys(SampleRowKeysRequest)} under the covers. A
     * {@link SourceWithKeys} may correspond to a single region or a portion of a region.
     * <p>
     * If a split is smaller than a single region, the split is calculated based on the assumption
     * that the data is distributed evenly between the region's startKey and stopKey. That
     * assumption may not be correct for any specific start/stop key combination.
     * </p>
     * <p>
     * This method is called internally by Cloud Dataflow. Do not call it directly.
     * </p>
     * @param desiredBundleSizeBytes The desired size for each bundle, in bytes.
     * @param options The pipeline options.
     * @return A list of sources split into groups.
     */
    @Override
    public List<? extends BoundedSource<ResultOutputType>> splitIntoBundles(
        long desiredBundleSizeBytes, PipelineOptions options) throws Exception {
      // Update the desiredBundleSizeBytes in order to limit the number of splits to
      // MAX_SPLIT_COUNT.  This is an extremely rough estimate for large scale jobs.  There is
      // currently a hard limit on both the count of Sources as well as the sum of the sizes of
      // serialized Sources.  This solution will not work for large workloads for cases where either
      // the row key sizes are large, or the scan is large.
      //
      // TODO: Work on a more robust algorithm for splitting that works for more cases.
      List<? extends BoundedSource<ResultOutputType>> splits = getSplits(desiredBundleSizeBytes);
      SOURCE_LOG.info("Creating {} splits.", splits.size());
      SOURCE_LOG.debug("Created splits {}.", splits);
      return splits;
    }

    /**
     * Gets an estimated size based on data returned from {@link BigtableDataClient#sampleRowKeys}.
     * The estimate will be high if a {@link Scan} is set on the {@link CloudBigtableScanConfiguration};
     * in such cases, the estimate will not take the Scan into account, and will return a larger estimate
     * than what the {@link CloudBigtableIO.Reader} will actually read.
     *
     * @param options The pipeline options.
     * @return The estimated size of the data, in bytes.
     */
    @Override
    public long getEstimatedSizeBytes(PipelineOptions options) throws IOException {
      long totalEstimatedSizeBytes = 0;

      byte[] scanStartKey = getConfiguration().getZeroCopyStartRow();
      byte[] scanEndKey = getConfiguration().getZeroCopyStopRow();

      byte[] startKey = HConstants.EMPTY_START_ROW;
      long lastOffset = 0;
      for (SampleRowKeysResponse response : getSampleRowKeys()) {
        byte[] currentEndKey = response.getRowKey().toByteArray();
        // Avoid empty regions.
        if (Bytes.equals(startKey, currentEndKey) && startKey.length != 0) {
          continue;
        }
        long offset = response.getOffsetBytes();
        if (isWithinRange(scanStartKey, scanEndKey, startKey, currentEndKey)) {
          totalEstimatedSizeBytes += (offset - lastOffset);
        }
        lastOffset = offset;
        startKey = currentEndKey;
      }
      SOURCE_LOG.info("Estimated size in bytes: " + totalEstimatedSizeBytes);

      return totalEstimatedSizeBytes;
    }
  }

  /**
   * A {@link BoundedSource} for a Cloud Bigtable {@link Table} with a start/stop key range, along
   * with a potential filter via a {@link Scan}.
   */
  protected static class SourceWithKeys<ResultOutputType> extends AbstractSource<ResultOutputType> {
    private static final long serialVersionUID = 1L;
    /**
     * An estimate of the size of the source, in bytes.
     *
     * <p>NOTE: This value is a guesstimate. It could be significantly off, especially if there is
     * a {@link Scan} selected in the configuration. It will also be off if the start and stop
     * keys are calculated via
     * {@link CloudBigtableIO.Source#splitIntoBundles(long, PipelineOptions)}.
     */
    private final long estimatedSize;

    protected SourceWithKeys(
        CloudBigtableScanConfiguration configuration,
        CoderType coderType,
        ScanIterator<ResultOutputType> scanIterator,
        long estimatedSize) {
      super(configuration, coderType, scanIterator);

      byte[] stopRow = configuration.getZeroCopyStopRow();
      if (stopRow.length > 0) {
        byte[] startRow = configuration.getZeroCopyStartRow();
        if (Bytes.compareTo(startRow, stopRow) >= 0) {
          throw new IllegalArgumentException(String.format(
            "Source keys not in order: [%s, %s]", Bytes.toStringBinary(startRow),
            Bytes.toStringBinary(stopRow)));
        }
        Preconditions.checkState(estimatedSize > 0, "Source size must be positive",
            estimatedSize);
      }
      this.estimatedSize = estimatedSize;
      SOURCE_LOG.debug("Source with split: {}.", this);
    }

    /**
     * Gets an estimate of the size of the source.
     *
     * <p>NOTE: This value is a guesstimate. It could be significantly off, especially if there is
     * a{@link Scan} selected in the configuration. It will also be off if the start and stop keys
     * are calculated via {@link Source#splitIntoBundles(long, PipelineOptions)}.
     *
     * @param options The pipeline options.
     * @return The estimated size of the source, in bytes.
     */
    @Override
    public long getEstimatedSizeBytes(PipelineOptions options) {
      return estimatedSize;
    }

    public long getEstimatedSize() {
      return estimatedSize;
    }

    // TODO: Add a method on the server side that will be a more precise split based on server-
    // side statistics
    /**
     * Splits the bundle based on the assumption that the data is distributed evenly between
     * startKey and stopKey. That assumption may not be correct for any specific
     * start/stop key combination.
     *
     * <p>This method is called internally by Cloud Dataflow. Do not call it directly.
     *
     * @param desiredBundleSizeBytes The desired size for each bundle, in bytes.
     * @param options The pipeline options.
     * @return A list of sources split into groups.
     */
    @Override
    public List<? extends BoundedSource<ResultOutputType>> splitIntoBundles(long desiredBundleSizeBytes,
        PipelineOptions options) throws Exception {
      final CloudBigtableScanConfiguration conf = getConfiguration();
      List<? extends BoundedSource<ResultOutputType>> newSplits = split(estimatedSize,
        desiredBundleSizeBytes, conf.getZeroCopyStartRow(), conf.getZeroCopyStopRow());
      SOURCE_LOG.trace("Splitting split {} into {}", this, newSplits);
      return newSplits;
    }

    @Override
    public String toString() {
      return String.format("Split start: '%s', end: '%s', size: %d.",
        Bytes.toStringBinary(getConfiguration().getZeroCopyStartRow()),
        Bytes.toStringBinary(getConfiguration().getZeroCopyStopRow()),
        estimatedSize);
    }
  }
  /**
   * Reads rows for a specific {@link Table}, usually filtered by a {@link Scan}.
   */
  @VisibleForTesting
  static class Reader<ResultOutputType> extends BoundedReader<ResultOutputType> {
    private static final Logger READER_LOG = LoggerFactory.getLogger(Reader.class);

    private CloudBigtableIO.AbstractSource<ResultOutputType> source;
    private final ScanIterator<ResultOutputType> scanIterator;

    private transient BigtableSession session;
    private transient ResultScanner<FlatRow> scanner;
    private transient ResultOutputType current;
    protected long workStart;
    private final AtomicLong rowsRead = new AtomicLong();
    private final ByteKeyRangeTracker rangeTracker;

    @VisibleForTesting
    Reader(CloudBigtableIO.AbstractSource<ResultOutputType> source,
        ScanIterator<ResultOutputType> scanIterator) {
      this.source = source;
      this.scanIterator = scanIterator;
      this.rangeTracker = ByteKeyRangeTracker.of(source.getConfiguration().toByteKeyRange());
    }

    /**
     * Creates a {@link Connection}, {@link Table} and {@link ResultScanner} and advances to the
     * next {@link Result}.
      */
    @Override
    public boolean start() throws IOException {
      initializeScanner();
      workStart = System.currentTimeMillis();
      return advance();
    }

    @VisibleForTesting
    void initializeScanner() throws IOException {
      Configuration config = source.getConfiguration().toHBaseConfig();

      // This will use cached data channels under the covers.
      session = new BigtableSession(BigtableOptionsFactory.fromConfiguration(config).toBuilder());
      scanner = session.getDataClient().readFlatRows(source.getConfiguration().getRequest());
    }

    /**
     * Calls {@link ResultScanner#next()}.
     */
    @Override
    public boolean advance() throws IOException {
      current = scanIterator.next(scanner, rangeTracker);
      if (scanIterator.isCompletionMarker(current)) {
        rangeTracker.markDone();
        return false;
      } else {
        rowsRead.addAndGet(scanIterator.getRowCount(current));
        return true;
      }
    }

    @Override
    public final Double getFractionConsumed() {
      if (rangeTracker.isDone()) {
        return 1.0;
      }
      return rangeTracker.getFractionConsumed();
    }

    /**
     * Attempt to split the work by some percent of the ByteKeyRange based on a lexicographical
     * split (and not statistics about the underlying table, which would be better, but that
     * information does not exist).
     */
    @Override
    public final synchronized BoundedSource<ResultOutputType> splitAtFraction(double fraction) {
      if (fraction < .01 || fraction > .99) {
        return null;
      }
      ByteKey splitKey;
      try {
        splitKey = rangeTracker.getRange().interpolateKey(fraction);
      } catch (IllegalArgumentException e) {
        READER_LOG.info("{}: Failed to interpolate key for fraction {}.", rangeTracker.getRange(), fraction);
        return null;
      }

      READER_LOG.info("Proposing to split {} at fraction {} (key {})", rangeTracker, fraction,
        splitKey);

      long estimatedSizeBytes = -1;
      try {
        estimatedSizeBytes = source.getEstimatedSizeBytes(null);
      } catch (IOException e) {
        READER_LOG.info("{}: Failed to get estimated size for key for fraction {}.", rangeTracker.getRange(), fraction);
        return null;
      }
      SourceWithKeys<ResultOutputType> residual = null;
      SourceWithKeys<ResultOutputType> primary = null;
      try {
        long newPrimarySize = (long) (fraction * estimatedSizeBytes);
        long residualSize = estimatedSizeBytes - newPrimarySize;

        byte[] currentStartKey = rangeTracker.getRange().getStartKey().getBytes();
        byte[] splitKeyBytes = splitKey.getBytes();
        byte[] currentStopKey = rangeTracker.getRange().getEndKey().getBytes();

        if (!rangeTracker.trySplitAtPosition(splitKey)) {
          return null;
        }

        primary = source.createSourceWithKeys(currentStartKey, splitKeyBytes, newPrimarySize);
        residual = source.createSourceWithKeys(splitKeyBytes, currentStopKey, residualSize);

        this.source = primary;
        return residual;
      } catch (Throwable t) {
        try {
          String msg = String.format("%d Failed to get estimated size for key for fraction %f.",
            rangeTracker.getRange(), fraction);
          READER_LOG.warn(msg, t);
        } catch (Throwable t1) {
          // ignore.
        }
        return null;
      }
    }

    @VisibleForTesting
    protected void setSession(BigtableSession session) {
      this.session = session;
    }

    @VisibleForTesting
    protected void setScanner(ResultScanner<FlatRow> scanner) {
      this.scanner = scanner;
    }

    @VisibleForTesting
    public ByteKeyRangeTracker getRangeTracker() {
      return rangeTracker;
    }

    /**
     * Closes the {@link ResultScanner}, {@link Table}, and {@link Connection}.
     */
    @Override
    public void close() throws IOException {
      if (scanner != null) {
        scanner.close();
        scanner = null;
      }
      long totalOps = getRowsReadCount();
      long elapsedTimeMs = System.currentTimeMillis() - workStart;
      long operationsPerSecond = elapsedTimeMs == 0 ? 0 : (totalOps * 1000 / elapsedTimeMs);
      READER_LOG.info(
          "{} Complete: {} operations in {} ms. That's {} operations/sec",
          this,
          totalOps,
          elapsedTimeMs,
          operationsPerSecond);
    }

    @VisibleForTesting
    long getRowsReadCount() {
      return rowsRead.get();
    }

    @Override
    public final ResultOutputType getCurrent() throws NoSuchElementException {
      return current;
    }

    @Override
    public final synchronized BoundedSource<ResultOutputType> getCurrentSource() {
      return source;
    }

    @Override
    public String toString() {
      return String.format("Reader for: ['%s' - '%s']",
        Bytes.toStringBinary(rangeTracker.getStartPosition().getBytes()),
        Bytes.toStringBinary(rangeTracker.getStopPosition().getBytes()));
    }
  }

  ///////////////////// Write Class /////////////////////////////////

  /**
   * Initializes the coders for the Cloud Bigtable Write {@link PTransform}. Sets up {@link Coder}s
   * required to serialize HBase {@link Put}, {@link Delete}, and {@link Mutation} objects. See
   * {@link HBaseMutationCoder} for additional implementation details.
   *
   * @return The {@link Pipeline} for chaining convenience.
   */
  @SuppressWarnings("unchecked")
  public static Pipeline initializeForWrite(Pipeline p) {
    // This enables the serialization of various Mutation types in the pipeline.
    CoderRegistry registry = p.getCoderRegistry();

    // MutationCoder only supports Puts and Deletes. It will throw exceptions for Increment
    // and Append since they are not idempotent. Put is logically idempotent if the column family
    // has a single version(); multiple versions are fine for most cases.  If it's not, add
    // a timestamp to the Put to make it fully idempotent.
    registry.registerCoder(Put.class, HBASE_MUTATION_CODER);
    registry.registerCoder(Delete.class, HBASE_MUTATION_CODER);
    registry.registerCoder(Mutation.class, HBASE_MUTATION_CODER);

    return p;
  }


  private static class MutationStatsExporter {
    private static final Logger STATS_LOG = LoggerFactory.getLogger(AbstractSource.class);
    private static Map<String, MutationStatsExporter> mutationStatsExporters = new HashMap<>();

    static synchronized void initializeMutationStatsExporter(BufferedMutatorDoFn<?> doFn) {
      CloudBigtableConfiguration config = doFn.getConfig();
      BigtableInstanceName instanceName =
          new BigtableInstanceName(config.getProjectId(), config.getInstanceId());
      String key = instanceName.toString();
      MutationStatsExporter mutationStatsExporter = mutationStatsExporters.get(key);
      if (mutationStatsExporter == null) {
        mutationStatsExporter = new MutationStatsExporter(instanceName, doFn);
        mutationStatsExporter.startExport();
        mutationStatsExporters.put(key, mutationStatsExporter);
      }
    }

    private final AggregatorWithState cumulativeThrottlingSeconds;
    private final BigtableInstanceName instanceName;

    MutationStatsExporter(BigtableInstanceName instanceName, BufferedMutatorDoFn<?> doFn) {
      this.instanceName = instanceName;
      cumulativeThrottlingSeconds = new AggregatorWithState(doFn.cumulativeThrottlingSeconds);
    }

    protected void startExport() {
      Runnable r = new Runnable() {
        @Override
        public void run() {
          try {
            cumulativeThrottlingSeconds.set(TimeUnit.NANOSECONDS.toSeconds(
              ResourceLimiterStats.getInstance(instanceName).getCumulativeThrottlingTimeNanos()));
          } catch (Exception e) {
            STATS_LOG.warn("Something bad happened in export stats", e);
          }
        }
      };
      BigtableSessionSharedThreadPools.getInstance().getRetryExecutor().scheduleAtFixedRate(r, 5, 5,
        TimeUnit.MILLISECONDS);
    }
  }

  /**
   * This is a DoFn that relies on {@link BufferedMutator} as the implementation to write data to
   * Cloud Bigtable. The main function of this class is to manage Aggregators relating to mutations.
   * @param <InputType>
   */
  private static abstract class BufferedMutatorDoFn<InputType>
      extends AbstractCloudBigtableTableDoFn<InputType, Void> {

    private static final long serialVersionUID = 1L;

    // Stats
    protected final Aggregator<Long, Long> mutationsCounter;
    protected final Aggregator<Long, Long> exceptionsCounter;
    protected final Aggregator<Long, Long> cumulativeThrottlingSeconds;

    public BufferedMutatorDoFn(CloudBigtableConfiguration config) {
      super(config);
      mutationsCounter = createAggregator("mutations", new Sum.SumLongFn());
      exceptionsCounter = createAggregator("exceptions", new Sum.SumLongFn());
      cumulativeThrottlingSeconds =
          createAggregator("cumulativeThrottlingSeconds", new Sum.SumLongFn());
    }

    protected BufferedMutator createBufferedMutator(Context context, String tableName)
        throws IOException {
      MutationStatsExporter.initializeMutationStatsExporter(this);
      return getConnection()
          .getBufferedMutator(new BufferedMutatorParams(TableName.valueOf(tableName))
              .writeBufferSize(BulkOptions.BIGTABLE_MAX_MEMORY_DEFAULT)
              .listener(createExceptionListener(context)));
    }

    protected ExceptionListener createExceptionListener(final Context context) {
      return new ExceptionListener() {
        @Override
        public void onException(RetriesExhaustedWithDetailsException exception,
            BufferedMutator mutator) throws RetriesExhaustedWithDetailsException {
          logExceptions(context, exception);
          throw exception;
        }
      };
    }
  }

  /**
   * A {@link DoFn} that can write either a bounded or unbounded {@link PCollection} of
   * {@link Mutation}s to a table specified via a {@link CloudBigtableTableConfiguration} using the
   * BufferedMutator.
   */
  public static class CloudBigtableSingleTableBufferedWriteFn
      extends BufferedMutatorDoFn<Mutation> {
    private static final long serialVersionUID = 2L;
    private transient BufferedMutator mutator;
    private final String tableName;

    public CloudBigtableSingleTableBufferedWriteFn(CloudBigtableTableConfiguration config) {
      super(config);
      tableName = config.getTableId();
    }

    private synchronized BufferedMutator getBufferedMutator(Context context)
        throws IOException {
      if (mutator == null) {
        mutator = createBufferedMutator(context, tableName);
      }
      return mutator;
    }

    /**
     * Performs an asynchronous mutation via {@link BufferedMutator#mutate(Mutation)}.
     */
    @Override
    public void processElement(ProcessContext context) throws Exception {
      Mutation mutation = context.element();
      if (DOFN_LOG.isTraceEnabled()) {
        DOFN_LOG.trace("Persisting {}", Bytes.toStringBinary(mutation.getRow()));
      }
      getBufferedMutator(context).mutate(mutation);
      mutationsCounter.addValue(1L);
    }

    /**
     * Closes the {@link BufferedMutator} and {@link Connection}.
     */
    @Override
    public synchronized void finishBundle(Context context) throws Exception {
      try {
        if (mutator != null) {
          mutator.close();
          mutator = null;
        }
      } catch (RetriesExhaustedWithDetailsException exception) {
        exceptionsCounter.addValue((long) exception.getCauses().size());
        logExceptions(context, exception);
        rethrowException(exception);
      } finally {
        // Close the connection to clean up resources.
        super.finishBundle(context);
      }
    }
  }

  /**
   * A {@link DoFn} that can write either a bounded or unbounded {@link PCollection} of
   * {@link Mutation}s to a table specified via a {@link CloudBigtableTableConfiguration} doing a
   * a single mutation per processElement call.
   */
  public static class CloudBigtableSingleTableSerialWriteFn
      extends AbstractCloudBigtableTableDoFn<Mutation, Void> {
    // Enables the use of this class instead of the buffered mutator one.
    public static final String DO_SERIAL_WRITES = "google.bigtable.dataflow.singletable.serial";

    private static final long serialVersionUID = 2L;
    private transient Table table;
    private final String tableId;

    // Stats
    private final Aggregator<Long, Long> mutationsCounter;

    public CloudBigtableSingleTableSerialWriteFn(CloudBigtableTableConfiguration config) {
      super(config);
      tableId = config.getTableId();

      mutationsCounter = createAggregator("mutations", new Sum.SumLongFn());
    }

    private synchronized Table getTable() throws IOException {
      if (table == null) {
        table = getConnection().getTable(TableName.valueOf(tableId));
      }
      return table;
    }

    /**
     * Performs an asynchronous mutation via {@link Table#put(Put)}.
     */
    @Override
    public void processElement(ProcessContext context) throws Exception {
      Mutation mutation = context.element();
      if (DOFN_LOG.isTraceEnabled()) {
        DOFN_LOG.trace("Persisting {}", Bytes.toStringBinary(mutation.getRow()));
      }

      if (mutation instanceof Put) {
        getTable().put((Put) mutation);
      } else if (mutation instanceof Delete) {
        getTable().delete((Delete) mutation);
      } else {
        throw new IllegalArgumentException("Encountered unsupported mutation type: " + mutation.getClass());
      }

      mutationsCounter.addValue(1L);
    }

    /**
     * Closes the {@link Table} and {@link Connection}.
     */
    @Override
    public void finishBundle(Context context) throws Exception {
      try {
        if (table != null) {
          table.close();
        }
      } finally {
        // Close the connection to clean up resources.
        super.finishBundle(context);
      }
    }
  }

  /**
   * A {@link DoFn} that can write either a bounded or unbounded {@link PCollection} of {@link KV}
   * of (String tableName, List of {@link Mutation}s) to the specified table.
   *
   * <p>NOTE: This {@link DoFn} will write {@link Put}s and {@link Delete}s, not {@link
   * org.apache.hadoop.hbase.client.Append}s and {@link org.apache.hadoop.hbase.client.Increment}s.
   * This limitation exists because if the batch fails partway through, Appends/Increments might be
   * re-run, causing the {@link Mutation} to be executed twice, which is never the user's intent.
   * Re-running a Delete will not cause any differences.  Re-running a Put isn't normally a problem,
   * but might cause problems in some cases when the number of versions supported by the column
   * family is greater than one.  In a case where multiple versions could be a problem, it's best to
   * add a timestamp to the {@link Put}.
   */
  public static class CloudBigtableMultiTableWriteFn
      extends BufferedMutatorDoFn<KV<String, Iterable<Mutation>>> {
    private static final long serialVersionUID = 2L;

    // Stats
    private transient Map<String, BufferedMutator> mutators;

    public CloudBigtableMultiTableWriteFn(CloudBigtableConfiguration config) {
      super(config);
    }

    @Override
    public void startBundle(DoFn<KV<String, Iterable<Mutation>>, Void>.Context c) throws Exception {
      super.startBundle(c);
      mutators = new HashMap<>();
    }

    /**
     * Uses the connection to create a new {@link Table} to write the {@link Mutation}s to.
     *
     * <p>NOTE: This method does not create a new table in Cloud Bigtable. The table must already
     * exist.
     *
     * @param context The context for the {@link DoFn}.
     */
    @Override
    public void processElement(ProcessContext context) throws Exception {
      KV<String, Iterable<Mutation>> element = context.element();
      BufferedMutator mutator = getMutator(context, element.getKey());
      try {
        for (Mutation mutation : element.getValue()) {
          mutator.mutate(mutation);
          mutationsCounter.addValue(1l);
        }
      } catch (RetriesExhaustedWithDetailsException exception) {
        logExceptions(context, exception);
        rethrowException(exception);
      }
    }

    private BufferedMutator getMutator(Context context, String tableName) throws IOException {
      BufferedMutator mutator = mutators.get(tableName);
      if (mutator == null) {
        mutator = createBufferedMutator(context, tableName);
        mutators.put(tableName, mutator);
      }
      return mutator;
    }

    @Override
    public void finishBundle(DoFn<KV<String, Iterable<Mutation>>, Void>.Context c)
        throws Exception {
      for (BufferedMutator bufferedMutator : mutators.values()) {
        try {
          bufferedMutator.flush();
        } catch (RetriesExhaustedWithDetailsException exception) {
          logExceptions(c, exception);
          rethrowException(exception);
        }
      }
      mutators.clear();
      super.finishBundle(c);
    }
  }

  /**
   * A {@link PTransform} that wraps around a {@link DoFn} that will write {@link Mutation}s to
   * Cloud Bigtable.
   */
  public static class CloudBigtableWriteTransform<T> extends PTransform<PCollection<T>, PDone> {
    private static final long serialVersionUID = -2888060194257930027L;

    private final DoFn<T, Void> function;

    public CloudBigtableWriteTransform(DoFn<T, Void> function) {
      this.function = function;
    }

    @Override
    public PDone apply(PCollection<T> input) {
      input.apply(ParDo.of(function));
      return PDone.in(input.getPipeline());
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      function.populateDisplayData(builder);
    }
  }

  /**
   * Creates a {@link PTransform} that can write either a bounded or unbounded {@link PCollection}
   * of {@link Mutation}s to a table specified via a {@link CloudBigtableTableConfiguration}.
   *
   * <p>NOTE: This {@link PTransform} will write {@link Put}s and {@link Delete}s, not {@link
   * org.apache.hadoop.hbase.client.Append}s and {@link org.apache.hadoop.hbase.client.Increment}s.
   * This limitation exists because if the batch fails partway through, Appends/Increments might be
   * re-run, causing the {@link Mutation} to be executed twice, which is never the user's intent.
   * Re-running a Delete will not cause any differences.  Re-running a Put isn't normally a problem,
   * but might cause problems in some cases when the number of versions supported by the column
   * family is greater than one.  In a case where multiple versions could be a problem, it's best to
   * add a timestamp to the {@link Put}.
   */
  public static PTransform<PCollection<Mutation>, PDone> writeToTable(
      CloudBigtableTableConfiguration config) {
    validateTableConfig(config);

    DoFn<Mutation, Void> writeFn = null;

    // Provide a way to do serial writes, slower but easier to troubleshoot.
    if (config.getConfiguration().get(
        CloudBigtableSingleTableSerialWriteFn.DO_SERIAL_WRITES) != null) {
      writeFn = new CloudBigtableSingleTableSerialWriteFn(config);
    } else {
      writeFn = new CloudBigtableSingleTableBufferedWriteFn(config);
    }

    return new CloudBigtableWriteTransform<>(writeFn);
  }

   /**
   * Creates a {@link PTransform} that can write either a bounded or unbounded {@link PCollection}
   * of {@link KV} of (String tableName, List of {@link Mutation}s) to the specified table.
   *
   * <p>NOTE: This {@link PTransform} will write {@link Put}s and {@link Delete}s, not {@link
   * org.apache.hadoop.hbase.client.Append}s and {@link org.apache.hadoop.hbase.client.Increment}s.
   * This limitation exists because if the batch fails partway through, Appends/Increments might be
   * re-run, causing the {@link Mutation} to be executed twice, which is never the user's intent.
   * Re-running a Delete will not cause any differences.  Re-running a Put isn't normally a problem,
   * but might cause problems in some cases when the number of versions supported by the column
   * family is greater than one.  In a case where multiple versions could be a problem, it's best to
   * add a timestamp to the {@link Put}.
   */
   public static PTransform<PCollection<KV<String, Iterable<Mutation>>>, PDone>
      writeToMultipleTables(CloudBigtableConfiguration config) {
    validateConfig(config);
    return new CloudBigtableWriteTransform<>(new CloudBigtableMultiTableWriteFn(config));
  }

  /**
   * Creates a {@link BoundedSource} for a Cloud Bigtable {@link Table}, which is potentially
   * filtered by a {@link Scan}.
   */
  public static com.google.cloud.dataflow.sdk.io.BoundedSource<Result>
      read(CloudBigtableScanConfiguration config) {
    return new Source<Result>(config, CoderType.RESULT, RESULT_ADVANCER);
  }

  /**
   * Creates a {@link BoundedSource} for a Cloud Bigtable {@link Table} for multiple
   * {@linkplain Result}s, which is potentially filtered by a {@link Scan}.
   * @param config The CloudBigtableScanConfiguration which defines the connection information,
   *          table and optional scan.
   * @param resultCount The number of results to get per batch
   */
  public static com.google.cloud.dataflow.sdk.io.BoundedSource<Result[]>
      readBulk(CloudBigtableScanConfiguration config, int resultCount) {
    return new Source<>(config, CoderType.RESULT_ARRAY, new ResultArrayIterator(resultCount));
  }

  private static void checkNotNullOrEmpty(String value, String type) {
    checkArgument(
        !isNullOrEmpty(value), "A " + type + " must be set to configure Bigtable properly.");
  }

  private static void validateTableConfig(CloudBigtableTableConfiguration configuration) {
    validateConfig(configuration);
    checkNotNullOrEmpty(configuration.getTableId(), "tableid");
  }

  private static void validateConfig(CloudBigtableConfiguration configuration) {
    checkNotNullOrEmpty(configuration.getProjectId(), "projectId");
    checkNotNullOrEmpty(configuration.getInstanceId(), "instanceId");
  }
}
