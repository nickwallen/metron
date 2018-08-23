/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.metron.profiler.integration;

import org.adrianwalker.multilinestring.Multiline;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hbase.client.Put;
import org.apache.metron.common.Constants;
import org.apache.metron.hbase.mock.MockHBaseTableProvider;
import org.apache.metron.hbase.mock.MockHTable;
import org.apache.metron.integration.BaseIntegrationTest;
import org.apache.metron.integration.ComponentRunner;
import org.apache.metron.integration.UnableToStartException;
import org.apache.metron.integration.components.FluxTopologyComponent;
import org.apache.metron.integration.components.KafkaComponent;
import org.apache.metron.integration.components.ZKServerComponent;
import org.apache.metron.profiler.ProfileMeasurement;
import org.apache.metron.profiler.client.stellar.FixedLookback;
import org.apache.metron.profiler.client.stellar.GetProfile;
import org.apache.metron.profiler.client.stellar.WindowLookback;
import org.apache.metron.profiler.hbase.RowKeyBuilder;
import org.apache.metron.profiler.hbase.SaltyRowKeyBuilder;
import org.apache.metron.stellar.common.DefaultStellarStatefulExecutor;
import org.apache.metron.stellar.common.StellarStatefulExecutor;
import org.apache.metron.stellar.dsl.Context;
import org.apache.metron.stellar.dsl.functions.resolver.SimpleFunctionResolver;
import org.json.simple.JSONObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static com.google.code.tempusfugit.temporal.Duration.seconds;
import static com.google.code.tempusfugit.temporal.Timeout.timeout;
import static com.google.code.tempusfugit.temporal.WaitFor.waitOrTimeout;
import static org.apache.metron.profiler.client.stellar.ProfilerClientConfig.PROFILER_COLUMN_FAMILY;
import static org.apache.metron.profiler.client.stellar.ProfilerClientConfig.PROFILER_HBASE_TABLE;
import static org.apache.metron.profiler.client.stellar.ProfilerClientConfig.PROFILER_HBASE_TABLE_PROVIDER;
import static org.apache.metron.profiler.client.stellar.ProfilerClientConfig.PROFILER_PERIOD;
import static org.apache.metron.profiler.client.stellar.ProfilerClientConfig.PROFILER_PERIOD_UNITS;
import static org.apache.metron.profiler.client.stellar.ProfilerClientConfig.PROFILER_SALT_DIVISOR;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An integration test of the Profiler topology.
 */
public class ProfilerIntegrationTest extends BaseIntegrationTest {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final String TEST_RESOURCES = "../../metron-analytics/metron-profiler/src/test";
  private static final String FLUX_PATH = "../metron-profiler/src/main/flux/profiler/remote.yaml";

  public static final long startAt = 10;
  public static final String entity = "10.0.0.1";

  private static final String tableName = "profiler";
  private static final String columnFamily = "P";
  private static final String inputTopic = Constants.INDEXING_TOPIC;
  private static final String outputTopic = "profiles";
  private static final int saltDivisor = 10;

  private static final long periodDurationMillis = TimeUnit.SECONDS.toMillis(20);
  private static final long windowLagMillis = TimeUnit.SECONDS.toMillis(10);
  private static final long windowDurationMillis = TimeUnit.SECONDS.toMillis(10);
  private static final long profileTimeToLiveMillis = TimeUnit.SECONDS.toMillis(20);

  private static final long maxRoutesPerBolt = 100000;

  private static ZKServerComponent zkComponent;
  private static FluxTopologyComponent fluxComponent;
  private static KafkaComponent kafkaComponent;
  private static ConfigUploadComponent configUploadComponent;
  private static ComponentRunner runner;
  private static MockHTable profilerTable;

  private static String message1;
  private static String message2;
  private static String message3;

  private StellarStatefulExecutor executor;

  /**
   * [
   *    org.apache.metron.profiler.ProfileMeasurement,
   *    org.apache.metron.profiler.ProfilePeriod,
   *    org.apache.metron.common.configuration.profiler.ProfileResult,
   *    org.apache.metron.common.configuration.profiler.ProfileResultExpressions,
   *    org.apache.metron.common.configuration.profiler.ProfileTriageExpressions,
   *    org.apache.metron.common.configuration.profiler.ProfilerConfig,
   *    org.apache.metron.common.configuration.profiler.ProfileConfig,
   *    org.json.simple.JSONObject,
   *    org.json.simple.JSONArray,
   *    java.util.LinkedHashMap,
   *    org.apache.metron.statistics.OnlineStatisticsProvider
   *  ]
   */
  @Multiline
  private static String kryoSerializers;

  /**
   * The Profiler can generate profiles based on processing time.  With processing time,
   * the Profiler builds profiles based on when the telemetry is processed.
   *
   * <p>Not defining a 'timestampField' within the Profiler configuration tells the Profiler
   * to use processing time.
   *
   * <p>There are two mechanisms that will cause a profile to flush.
   *
   * (1) As new messages arrive, time is advanced. The splitter bolt attaches a timestamp to each
   * message (which can be either event or system time.)  This advances time and leads to profile
   * measurements being flushed.
   *
   * (2) If no messages arrive to advance time, then the "time-to-live" mechanism will flush a profile
   * after a period of time.
   *
   * <p>This test specifically tests the *first* mechanism where time is advanced by incoming messages.
   */
  @Test
  public void testProcessingTime() throws Exception {

    // upload the config to zookeeper
    uploadConfig(TEST_RESOURCES + "/config/zookeeper/processing-time-test");

    // start the topology and write test messages to kafka
    fluxComponent.submitTopology();

    // the messages that will be applied to the profile
    kafkaComponent.writeMessages(inputTopic, message1);
    kafkaComponent.writeMessages(inputTopic, message2);
    kafkaComponent.writeMessages(inputTopic, message3);

    // retrieve the profile measurement using PROFILE_GET
    String profileGetExpression = "PROFILE_GET('processing-time-test', '10.0.0.1', PROFILE_FIXED('5', 'MINUTES'))";
    List<Integer> actuals = execute(profileGetExpression, List.class);
    LOG.debug("{} = {}", profileGetExpression, actuals);

    // storm needs at least one message to close its event window
    int attempt = 0;
    while(actuals.size() == 0 && attempt++ < 10) {

      // wait for the profiler to flush
      long sleep = windowDurationMillis;
      LOG.debug("Waiting {} millis for profiler to flush", sleep);
      Thread.sleep(sleep);

      // write another message to advance time.  this ensures that we are testing the 'normal' flush mechanism.
      // if we do not send additional messages to advance time, then it is the profile TTL mechanism which
      // will ultimately flush the profile
      kafkaComponent.writeMessages(inputTopic, message2);

      // retrieve the profile measurement using PROFILE_GET
      actuals = execute(profileGetExpression, List.class);
      LOG.debug("{} = {}", profileGetExpression, actuals);
    }

    // the profile should count at least 3 messages
    assertTrue(actuals.size() > 0);
    assertTrue(actuals.get(0) >= 3);
  }

  /**
   * The Profiler can generate profiles based on processing time.  With processing time,
   * the Profiler builds profiles based on when the telemetry is processed.
   *
   * <p>Not defining a 'timestampField' within the Profiler configuration tells the Profiler
   * to use processing time.
   *
   * <p>There are two mechanisms that will cause a profile to flush.
   *
   * (1) As new messages arrive, time is advanced. The splitter bolt attaches a timestamp to each
   * message (which can be either event or system time.)  This advances time and leads to profile
   * measurements being flushed.
   *
   * (2) If no messages arrive to advance time, then the "time to live" mechanism will flush a profile
   * after a period of time.
   *
   * <p>This test specifically tests the *second* mechanism when a profile is flushed by the
   * "time to live" mechanism.
   */
  @Test
  public void testProcessingTimeWithTimeToLiveFlush() throws Exception {

    // upload the config to zookeeper
    uploadConfig(TEST_RESOURCES + "/config/zookeeper/processing-time-test");

    // start the topology and write test messages to kafka
    fluxComponent.submitTopology();

    // the messages that will be applied to the profile
    kafkaComponent.writeMessages(inputTopic, message1);
    kafkaComponent.writeMessages(inputTopic, message2);
    kafkaComponent.writeMessages(inputTopic, message3);

    // wait a bit beyond the window lag before writing another message.  this allows storm's window manager to close
    // the event window, which then lets the profiler processes the previous messages.
    Thread.sleep(windowLagMillis * 2);
    kafkaComponent.writeMessages(inputTopic, message3);

    // retrieve the profile measurement using PROFILE_GET
    String profileGetExpression = "PROFILE_GET('processing-time-test', '10.0.0.1', PROFILE_FIXED('5', 'MINUTES'))";
    List<Integer> actuals = execute(profileGetExpression, List.class);
    LOG.debug("{} = {}", profileGetExpression, actuals);

    // storm needs at least one message to close its event window
    int attempt = 0;
    while(actuals.size() == 0 && attempt++ < 10) {

      // wait for the profiler to flush
      long sleep = windowDurationMillis;
      LOG.debug("Waiting {} millis for profiler to flush", sleep);
      Thread.sleep(sleep);

      // do not write additional messages to advance time. this ensures that we are testing the "time to live"
      // flush mechanism. the TTL setting defines when the profile will be flushed

      // retrieve the profile measurement using PROFILE_GET
      actuals = execute(profileGetExpression, List.class);
      LOG.debug("{} = {}", profileGetExpression, actuals);
    }

    // the profile should count 3 messages
    assertTrue(actuals.size() > 0);
    assertEquals(3, actuals.get(0).intValue());
  }

  /**
   * The Profiler can generate profiles using event time.  With event time processing,
   * the Profiler uses timestamps contained in the source telemetry.
   *
   * <p>Defining a 'timestampField' within the Profiler configuration tells the Profiler
   * from which field the timestamp should be extracted.
   */
  @Test
  public void testEventTime() throws Exception {

    // upload the profiler config to zookeeper
    uploadConfig(TEST_RESOURCES + "/config/zookeeper/event-time-test");

    // start the topology and write test messages to kafka
    fluxComponent.submitTopology();

    List<String> messages = FileUtils.readLines(new File("src/test/resources/telemetry.json"));
    kafkaComponent.writeMessages(inputTopic, messages);

    // wait until the profile is flushed
    waitOrTimeout(() -> profilerTable.getPutLog().size() >= 3, timeout(seconds(90)));

    // validate the measurements written by the batch profiler using `PROFILE_GET`
    // the 'window' looks up to 5 hours before the last timestamp contained in the telemetry
    assign("lastTimestamp", "1530978728982L");
    assign("window", "PROFILE_WINDOW('from 5 hours ago', lastTimestamp)");

    // validate the first profile period; the next has likely not been flushed yet
    {
      // there are 14 messages where ip_src_addr = 192.168.66.1
      List results = execute("PROFILE_GET('count-by-ip', '192.168.66.1', window)", List.class);
      assertEquals(14, results.get(0));
    }
    {
      // there are 36 messages where ip_src_addr = 192.168.138.158
      List results = execute("PROFILE_GET('count-by-ip', '192.168.138.158', window)", List.class);
      assertEquals(36, results.get(0));
    }
    {
      // there are 50 messages in all
      List results = execute("PROFILE_GET('total-count', 'total', window)", List.class);
      assertEquals(50, results.get(0));
    }
  }


  /**
   * The result produced by a Profile has to be serializable within Storm. If the result is not
   * serializable the topology will crash and burn.
   *
   * This test ensures that if a profile returns a STATS object created using the STATS_INIT and
   * STATS_ADD functions, that it can be correctly serialized and persisted.
   */
  @Test
  public void testProfileWithStatsObject() throws Exception {

    // upload the profiler config to zookeeper
    uploadConfig(TEST_RESOURCES + "/config/zookeeper/profile-with-stats");

    // start the topology and write test messages to kafka
    fluxComponent.submitTopology();
    kafkaComponent.writeMessages(inputTopic, message1);
    kafkaComponent.writeMessages(inputTopic, message2);
    kafkaComponent.writeMessages(inputTopic, message3);

    // wait until the profile is flushed
    waitOrTimeout(() -> profilerTable.getPutLog().size() > 0, timeout(seconds(90)));

    // ensure that a value was persisted in HBase
    List<Put> puts = profilerTable.getPutLog();
    assertEquals(1, puts.size());

    // generate the expected row key. only the profile name, entity, and period are used to generate the row key
    ProfileMeasurement measurement = new ProfileMeasurement()
            .withProfileName("profile-with-stats")
            .withEntity("global")
            .withPeriod(startAt, periodDurationMillis, TimeUnit.MILLISECONDS);
    RowKeyBuilder rowKeyBuilder = new SaltyRowKeyBuilder(saltDivisor, periodDurationMillis, TimeUnit.MILLISECONDS);
    byte[] expectedRowKey = rowKeyBuilder.rowKey(measurement);

    // ensure the correct row key was generated
    byte[] actualRowKey = puts.get(0).getRow();
    assertArrayEquals(failMessage(expectedRowKey, actualRowKey), expectedRowKey, actualRowKey);
  }

  /**
   * Generates an error message for if the byte comparison fails.
   *
   * @param expected The expected value.
   * @param actual The actual value.
   * @return
   * @throws UnsupportedEncodingException
   */
  private String failMessage(byte[] expected, byte[] actual) throws UnsupportedEncodingException {
    return String.format("expected '%s', got '%s'",
              new String(expected, "UTF-8"),
              new String(actual, "UTF-8"));
  }

  private static String getMessage(String ipSource, long timestamp) {
    return new MessageBuilder()
            .withField("ip_src_addr", ipSource)
            .withField("timestamp", timestamp)
            .build()
            .toJSONString();
  }

  @BeforeClass
  public static void setupBeforeClass() throws UnableToStartException {

    // create some messages that contain a timestamp - a really old timestamp; close to 1970
    message1 = getMessage(entity, startAt);
    message2 = getMessage(entity, startAt + 100);
    message3 = getMessage(entity, startAt + (windowDurationMillis * 2));

    // storm topology properties
    final Properties topologyProperties = new Properties() {{

      // storm settings
      setProperty("profiler.workers", "1");
      setProperty("profiler.executors", "0");
      setProperty("storm.auto.credentials", "[]");
      setProperty("topology.auto-credentials", "[]");
      setProperty("topology.message.timeout.secs", "180");
      setProperty("topology.max.spout.pending", "100000");

      // ensure tuples are serialized during the test, otherwise serialization problems
      // will not be found until the topology is run on a cluster with multiple workers
      setProperty("topology.testing.always.try.serialize", "true");
      setProperty("topology.fall.back.on.java.serialization", "false");
      setProperty("topology.kryo.register", kryoSerializers);

      // kafka settings
      setProperty("profiler.input.topic", inputTopic);
      setProperty("profiler.output.topic", outputTopic);
      setProperty("kafka.start", "EARLIEST");
      setProperty("kafka.security.protocol", "PLAINTEXT");

      // hbase settings
      setProperty("profiler.hbase.salt.divisor", Integer.toString(saltDivisor));
      setProperty("profiler.hbase.table", tableName);
      setProperty("profiler.hbase.column.family", columnFamily);
      setProperty("profiler.hbase.batch", "10");
      setProperty("profiler.hbase.flush.interval.seconds", "1");
      setProperty("hbase.provider.impl", "" + MockHBaseTableProvider.class.getName());

      // profile settings
      setProperty("profiler.period.duration", Long.toString(periodDurationMillis));
      setProperty("profiler.period.duration.units", "MILLISECONDS");
      setProperty("profiler.ttl", Long.toString(profileTimeToLiveMillis));
      setProperty("profiler.ttl.units", "MILLISECONDS");
      setProperty("profiler.window.duration", Long.toString(windowDurationMillis));
      setProperty("profiler.window.duration.units", "MILLISECONDS");
      setProperty("profiler.window.lag", Long.toString(windowLagMillis));
      setProperty("profiler.window.lag.units", "MILLISECONDS");
      setProperty("profiler.max.routes.per.bolt", Long.toString(maxRoutesPerBolt));
    }};

    // create the mock table
    profilerTable = (MockHTable) MockHBaseTableProvider.addToCache(tableName, columnFamily);

    zkComponent = getZKServerComponent(topologyProperties);

    // create the input and output topics
    kafkaComponent = getKafkaComponent(topologyProperties, Arrays.asList(
            new KafkaComponent.Topic(inputTopic, 1),
            new KafkaComponent.Topic(outputTopic, 1)));

    // upload profiler configuration to zookeeper
    configUploadComponent = new ConfigUploadComponent()
            .withTopologyProperties(topologyProperties);

    // load flux definition for the profiler topology
    fluxComponent = new FluxTopologyComponent.Builder()
            .withTopologyLocation(new File(FLUX_PATH))
            .withTopologyName("profiler")
            .withTopologyProperties(topologyProperties)
            .build();

    // start all components
    runner = new ComponentRunner.Builder()
            .withComponent("zk",zkComponent)
            .withComponent("kafka", kafkaComponent)
            .withComponent("config", configUploadComponent)
            .withComponent("storm", fluxComponent)
            .withMillisecondsBetweenAttempts(15000)
            .withNumRetries(10)
            .withCustomShutdownOrder(new String[] {"storm","config","kafka","zk"})
            .build();
    runner.start();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    MockHBaseTableProvider.clear();
    if (runner != null) {
      runner.stop();
    }
  }

  @Before
  public void setup() {
    // create the mock table
    profilerTable = (MockHTable) MockHBaseTableProvider.addToCache(tableName, columnFamily);

    // global properties
    Map<String, Object> global = new HashMap<String, Object>() {{
      put(PROFILER_HBASE_TABLE.getKey(), tableName);
      put(PROFILER_COLUMN_FAMILY.getKey(), columnFamily);
      put(PROFILER_HBASE_TABLE_PROVIDER.getKey(), MockHBaseTableProvider.class.getName());

      // client needs to use the same period duration
      put(PROFILER_PERIOD.getKey(), Long.toString(periodDurationMillis));
      put(PROFILER_PERIOD_UNITS.getKey(), "MILLISECONDS");

      // client needs to use the same salt divisor
      put(PROFILER_SALT_DIVISOR.getKey(), saltDivisor);
    }};

    // create the stellar execution environment
    executor = new DefaultStellarStatefulExecutor(
            new SimpleFunctionResolver()
                    .withClass(GetProfile.class)
                    .withClass(FixedLookback.class)
                    .withClass(WindowLookback.class),
            new Context.Builder()
                    .with(Context.Capabilities.GLOBAL_CONFIG, () -> global)
                    .build());
  }

  @After
  public void tearDown() throws Exception {
    MockHBaseTableProvider.clear();
    profilerTable.clear();
    if (runner != null) {
      runner.reset();
    }
  }

  /**
   * Uploads config values to Zookeeper.
   * @param path The path on the local filesystem to the config values.
   * @throws Exception
   */
  public void uploadConfig(String path) throws Exception {
    configUploadComponent
            .withGlobalConfiguration(path)
            .withProfilerConfiguration(path)
            .update();
  }

  /**
   * Assign a value to the result of an expression.
   *
   * @param var The variable to assign.
   * @param expression The expression to execute.
   */
  private void assign(String var, String expression) {
    executor.assign(var, expression, Collections.emptyMap());
  }

  /**
   * Execute a Stellar expression.
   *
   * @param expression The Stellar expression to execute.
   * @param clazz
   * @param <T>
   * @return The result of executing the Stellar expression.
   */
  private <T> T execute(String expression, Class<T> clazz) {
    return executor.execute(expression, Collections.emptyMap(), clazz);
  }
}
