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
package com.google.cloud.bigtable.hbase.adapters;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.client.Scan;

import com.google.bigtable.v2.MutateRowRequest;
import com.google.bigtable.v2.MutateRowsRequest;
import com.google.bigtable.v2.ReadModifyWriteRowRequest;
import com.google.bigtable.v2.ReadRowsRequest;
import com.google.cloud.bigtable.config.BigtableOptions;
import com.google.cloud.bigtable.grpc.BigtableTableName;
import com.google.cloud.bigtable.hbase.adapters.read.DefaultReadHooks;
import com.google.cloud.bigtable.hbase.adapters.read.ReadHooks;

/**
 * Adapts HBase Deletes, Gets, Scans, Puts, RowMutations, Appends and Increments to Bigtable requests.
 *
 * @author sduskis
 * @version $Id: $Id
 */
public class HBaseRequestAdapter {

  public static class MutationAdapters {
    protected final PutAdapter putAdapter;
    protected final HBaseMutationAdapter hbaseMutationAdapter;
    protected final RowMutationsAdapter rowMutationsAdapter;

    public MutationAdapters(BigtableOptions.Builder options, Configuration config) {
      this(Adapters.createPutAdapter(config, options));
    }

    @VisibleForTesting
    MutationAdapters(PutAdapter putAdapter) {
      this.putAdapter = putAdapter;
      this.hbaseMutationAdapter = Adapters.createMutationsAdapter(putAdapter);
      this.rowMutationsAdapter = new RowMutationsAdapter(hbaseMutationAdapter);
    }

    public MutationAdapters withServerSideTimestamps() {
      return new MutationAdapters(putAdapter.withServerSideTimestamps());
    }
  }

  protected final MutationAdapters mutationAdapters;
  protected final TableName tableName;
  protected final BigtableTableName bigtableTableName;

  /**
   * <p>Constructor for HBaseRequestAdapter.</p>
   *
   * @param options a {@link com.google.cloud.bigtable.config.BigtableOptions} object.
   * @param tableName a {@link org.apache.hadoop.hbase.TableName} object.
   * @param config a {@link org.apache.hadoop.conf.Configuration} object.
   */
  public HBaseRequestAdapter(BigtableOptions.Builder options, TableName tableName, Configuration config) {
    this(options, tableName, new MutationAdapters(options, config));
  }

  /**
   * <p>Constructor for HBaseRequestAdapter.</p>
   *
   * @param options a {@link BigtableOptions} object.
   * @param tableName a {@link TableName} object.
   * @param mutationAdapters a {@link MutationAdapters} object.
   */
  public HBaseRequestAdapter(BigtableOptions.Builder options,
                             TableName tableName,
                             MutationAdapters mutationAdapters) {
    this(tableName,
        options.build().instanceName().toTableName(tableName.getQualifierAsString()),
        mutationAdapters);
  }


  /**
   * <p>Constructor for HBaseRequestAdapter.</p>
   *
   * @param tableName a {@link TableName} object.
   * @param bigtableTableName a {@link BigtableTableName} object.
   * @param mutationAdapters a {@link MutationAdapters} object.
   */
  @VisibleForTesting
  HBaseRequestAdapter(TableName tableName,
                              BigtableTableName bigtableTableName,
                              MutationAdapters mutationAdapters) {
    this.tableName = tableName;
    this.bigtableTableName = bigtableTableName;
    this.mutationAdapters = mutationAdapters;
  }

  public HBaseRequestAdapter withServerSideTimestamps(){
    return new HBaseRequestAdapter(tableName, bigtableTableName, mutationAdapters.withServerSideTimestamps());
  }

  /**
   * <p>adapt.</p>
   *
   * @param delete a {@link org.apache.hadoop.hbase.client.Delete} object.
   * @return a {@link com.google.bigtable.v2.MutateRowRequest} object.
   */
  public MutateRowRequest adapt(Delete delete) {
    MutateRowRequest.Builder requestBuilder = Adapters.DELETE_ADAPTER.adapt(delete);
    requestBuilder.setTableName(getTableNameString());
    return requestBuilder.build();
  }

  /**
   * <p>adapt.</p>
   *
   * @param delete a {@link org.apache.hadoop.hbase.client.Delete} object.
   * @return a {@link com.google.bigtable.v2.MutateRowsRequest.Entry} object.
   */
  public MutateRowsRequest.Entry adaptEntry(Delete delete) {
    return Adapters.DELETE_ADAPTER.toEntry(delete);
  }

  /**
   * <p>adapt.</p>
   *
   * @param get a {@link org.apache.hadoop.hbase.client.Get} object.
   * @return a {@link com.google.bigtable.v2.ReadRowsRequest} object.
   */
  public ReadRowsRequest adapt(Get get) {
    ReadHooks readHooks = new DefaultReadHooks();
    ReadRowsRequest.Builder builder = Adapters.GET_ADAPTER.adapt(get, readHooks);
    builder.setTableName(getTableNameString());
    return readHooks.applyPreSendHook(builder.build());
  }

  /**
   * <p>adapt.</p>
   *
   * @param scan a {@link org.apache.hadoop.hbase.client.Scan} object.
   * @return a {@link com.google.bigtable.v2.ReadRowsRequest} object.
   */
  public ReadRowsRequest adapt(Scan scan) {
    ReadHooks readHooks = new DefaultReadHooks();
    ReadRowsRequest.Builder builder = Adapters.SCAN_ADAPTER.adapt(scan, readHooks);
    builder.setTableName(getTableNameString());
    return readHooks.applyPreSendHook(builder.build());
  }

  /**
   * <p>adapt.</p>
   *
   * @param append a {@link org.apache.hadoop.hbase.client.Append} object.
   * @return a {@link com.google.bigtable.v2.ReadModifyWriteRowRequest} object.
   */
  public ReadModifyWriteRowRequest adapt(Append append) {
    ReadModifyWriteRowRequest.Builder builder = Adapters.APPEND_ADAPTER.adapt(append);
    builder.setTableName(getTableNameString());
    return builder.build();
  }

  /**
   * <p>adapt.</p>
   *
   * @param increment a {@link org.apache.hadoop.hbase.client.Increment} object.
   * @return a {@link com.google.bigtable.v2.ReadModifyWriteRowRequest} object.
   */
  public ReadModifyWriteRowRequest adapt(Increment increment) {
    ReadModifyWriteRowRequest.Builder builder = Adapters.INCREMENT_ADAPTER.adapt(increment);
    builder.setTableName(getTableNameString());
    return builder.build();
  }

  /**
   * <p>adapt.</p>
   *
   * @param put a {@link org.apache.hadoop.hbase.client.Put} object.
   * @return a {@link com.google.bigtable.v2.MutateRowRequest} object.
   */
  public MutateRowRequest adapt(Put put) {
    MutateRowRequest.Builder builder = mutationAdapters.putAdapter.adapt(put);
    builder.setTableName(getTableNameString());
    return builder.build();
  }


  /**
   * <p>adaptEntry.</p>
   *
   * @param put a {@link org.apache.hadoop.hbase.client.Put} object.
   * @return a {@link com.google.bigtable.v2.MutateRowsRequest.Entry} object.
   */
  public MutateRowsRequest.Entry adaptEntry(Put put) {
    return mutationAdapters.putAdapter.toEntry(put);
  }

  /**
   * <p>adapt.</p>
   *
   * @param mutations a {@link org.apache.hadoop.hbase.client.RowMutations} object.
   * @return a {@link com.google.bigtable.v2.MutateRowRequest} object.
   */
  public MutateRowRequest adapt(RowMutations mutations) {
    MutateRowRequest.Builder builder = mutationAdapters.rowMutationsAdapter.adapt(mutations);
    builder.setTableName(getTableNameString());
    return builder.build();
  }

  /**
   * <p>adaptEntry.</p>
   *
   * @param mutations a {@link org.apache.hadoop.hbase.client.RowMutations} object.
   * @return a {@link com.google.bigtable.v2.MutateRowsRequest.Entry} object.
   */
  public MutateRowsRequest.Entry adaptEntry(RowMutations mutations) {
    return mutationAdapters.rowMutationsAdapter.toEntry(mutations);
  }

  /**
   * <p>adapt.</p>
   *
   * @param mutation a {@link org.apache.hadoop.hbase.client.Mutation} object.
   * @return a {@link com.google.bigtable.v2.MutateRowRequest} object.
   */
  public MutateRowRequest adapt(org.apache.hadoop.hbase.client.Mutation mutation) {
    MutateRowRequest.Builder builder = mutationAdapters.hbaseMutationAdapter.adapt(mutation);
    builder.setTableName(getTableNameString());
    return builder.build();
  }

  /**
   * <p>Getter for the field <code>bigtableTableName</code>.</p>
   *
   * @return a {@link com.google.cloud.bigtable.grpc.BigtableTableName} object.
   */
  public BigtableTableName getBigtableTableName() {
    return bigtableTableName;
  }
  
  /**
   * <p>Getter for the field <code>tableName</code>.</p>
   *
   * @return a {@link org.apache.hadoop.hbase.TableName} object.
   */
  public TableName getTableName() {
    return tableName;
  }

  /**
   * <p>getTableNameString.</p>
   *
   * @return a {@link java.lang.String} object.
   */
  protected String getTableNameString() {
    return getBigtableTableName().toString();
  }

}
