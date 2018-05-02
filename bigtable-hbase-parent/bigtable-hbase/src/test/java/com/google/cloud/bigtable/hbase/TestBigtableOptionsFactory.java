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
package com.google.cloud.bigtable.hbase;


import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;

import com.google.cloud.bigtable.config.BigtableOptions;
import com.google.cloud.bigtable.config.RetryOptions;

@RunWith(JUnit4.class)
public class TestBigtableOptionsFactory {

  public static final String TEST_HOST = "localhost";
  public static final int TEST_PORT = 80;
  public static final String TEST_PROJECT_ID = "project-foo";
  public static final String TEST_INSTANCE_ID = "test-instance";

  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  private Configuration configuration;

  @Before
  public void setup() {
    configuration = new Configuration(false);
    configuration.set(BigtableOptionsFactory.BIGTABLE_HOST_KEY, TEST_HOST);
    configuration.set(BigtableOptionsFactory.PROJECT_ID_KEY, TEST_PROJECT_ID);
    configuration.set(BigtableOptionsFactory.INSTANCE_ID_KEY, TEST_INSTANCE_ID);
  }

  @Test
  public void testProjectIdIsRequired() throws IOException {
    Configuration configuration = new Configuration(false);
    configuration.unset(BigtableOptionsFactory.PROJECT_ID_KEY);

    expectedException.expect(IllegalArgumentException.class);
    BigtableOptionsFactory.fromConfiguration(configuration);
  }

  @Test
  public void testHostIsRequired() throws IOException {
    Configuration configuration = new Configuration(false);
    configuration.unset(BigtableOptionsFactory.BIGTABLE_HOST_KEY);

    expectedException.expect(IllegalArgumentException.class);
    BigtableOptionsFactory.fromConfiguration(configuration);
  }

  @Test
  public void testInstanceIsRequired() throws IOException {
    Configuration configuration = new Configuration(false);
    configuration.unset(BigtableOptionsFactory.INSTANCE_ID_KEY);

    expectedException.expect(IllegalArgumentException.class);
    BigtableOptionsFactory.fromConfiguration(configuration);
  }

  @Test
  public void testAdminHostKeyIsUsed() throws IOException {
    configuration.set(BigtableOptionsFactory.BIGTABLE_HOST_KEY, TEST_HOST);
    configuration.set(BigtableOptionsFactory.BIGTABLE_ADMIN_HOST_KEY, TEST_HOST);
    configuration.setBoolean(BigtableOptionsFactory.BIGTABLE_USE_SERVICE_ACCOUNTS_KEY, false);
    configuration.setBoolean(BigtableOptionsFactory.BIGTABLE_NULL_CREDENTIAL_ENABLE_KEY, true);
    BigtableOptions.Builder options = BigtableOptionsFactory.fromConfiguration(configuration);
    Assert.assertEquals(TEST_HOST, options.build().dataHost());
  }

  @Test
  public void testOptionsAreConstructedWithValidInput() throws IOException {
    configuration.set(BigtableOptionsFactory.BIGTABLE_HOST_KEY, TEST_HOST);
    configuration.setBoolean(BigtableOptionsFactory.BIGTABLE_USE_SERVICE_ACCOUNTS_KEY, false);
    configuration.setBoolean(BigtableOptionsFactory.BIGTABLE_NULL_CREDENTIAL_ENABLE_KEY, true);
    BigtableOptions.Builder options = BigtableOptionsFactory.fromConfiguration(configuration);
    Assert.assertEquals(TEST_HOST, options.build().dataHost());
    Assert.assertEquals(TEST_PROJECT_ID, options.build().projectId());
    Assert.assertEquals(TEST_INSTANCE_ID, options.build().instanceId());
  }

  @Test
  public void testDefaultRetryOptions() throws IOException {
    RetryOptions retryOptions =
        BigtableOptionsFactory.fromConfiguration(configuration).build().retryOptions();
    assertEquals(
      RetryOptions.DEFAULT_ENABLE_GRPC_RETRIES,
      retryOptions.enableRetries());
    assertEquals(
        RetryOptions.DEFAULT_MAX_ELAPSED_BACKOFF_MILLIS,
        retryOptions.getMaxElapsedBackoffMillis());
    assertEquals(
        RetryOptions.DEFAULT_READ_PARTIAL_ROW_TIMEOUT_MS,
        retryOptions.getReadPartialRowTimeoutMillis());
    assertEquals(
        RetryOptions.DEFAULT_MAX_SCAN_TIMEOUT_RETRIES,
        retryOptions.getMaxScanTimeoutRetries());
  }

  @Test
  public void testSettingRetryOptions() throws IOException {
    configuration.set(BigtableOptionsFactory.ENABLE_GRPC_RETRIES_KEY, "false");
    configuration.set(BigtableOptionsFactory.ENABLE_GRPC_RETRY_DEADLINEEXCEEDED_KEY, "false");
    configuration.set(BigtableOptionsFactory.MAX_ELAPSED_BACKOFF_MILLIS_KEY, "111");
    configuration.set(BigtableOptionsFactory.READ_PARTIAL_ROW_TIMEOUT_MS, "123");
    RetryOptions retryOptions =
        BigtableOptionsFactory.fromConfiguration(configuration).build().retryOptions();
    assertEquals(false, retryOptions.enableRetries());
    assertEquals(false, retryOptions.retryOnDeadlineExceeded());
    assertEquals(111, retryOptions.getMaxElapsedBackoffMillis());
    assertEquals(123, retryOptions.getReadPartialRowTimeoutMillis());
  }
}
