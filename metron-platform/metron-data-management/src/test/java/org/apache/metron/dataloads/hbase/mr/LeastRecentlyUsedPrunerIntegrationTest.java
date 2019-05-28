/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.metron.dataloads.hbase.mr;

import com.google.common.collect.Iterables;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.PosixParser;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.metron.dataloads.bulk.LeastRecentlyUsedPruner;
import org.apache.metron.enrichment.converter.EnrichmentConverter;
import org.apache.metron.enrichment.converter.EnrichmentKey;
import org.apache.metron.enrichment.converter.EnrichmentValue;
import org.apache.metron.enrichment.lookup.HBaseEnrichmentLookup;
import org.apache.metron.enrichment.lookup.LookupKey;
import org.apache.metron.enrichment.lookup.TrackedEnrichmentLookup;
import org.apache.metron.enrichment.lookup.accesstracker.BloomAccessTracker;
import org.apache.metron.enrichment.lookup.accesstracker.PersistentAccessTracker;
import org.apache.metron.hbase.client.HBaseConnectionFactory;
import org.apache.metron.test.utils.UnitTestHelper;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class LeastRecentlyUsedPrunerIntegrationTest {
    /** The test util. */
    private static HBaseTestingUtility testUtil;

    /** The test table. */
    private static Table testTable;
    private static Table accessTrackerTable;
    private static final String tableName = "malicious_domains";
    private static final String columnFamily = "cf";
    private static final String accessTrackerTableName = "access_trackers";
    private static final String accessTrackerColumnFamily = "cf";
    private static final String beginTime = "04/14/2016 12:00:00";
    private static final String timeFormat = "georgia";
    private static Configuration config = null;

    @BeforeClass
    public static void setup() throws Exception {
        UnitTestHelper.setJavaLoggingLevel(Level.SEVERE);
        Map.Entry<HBaseTestingUtility, Configuration> kv = HBaseUtil.INSTANCE.create(true);
        config = kv.getValue();
        testUtil = kv.getKey();
        testTable = testUtil.createTable(TableName.valueOf(tableName), columnFamily);
        accessTrackerTable = testUtil.createTable(TableName.valueOf(accessTrackerTableName), accessTrackerColumnFamily);
    }

    @AfterClass
    public static void teardown() throws Exception {
        HBaseUtil.INSTANCE.teardown(testUtil);
    }

    public List<LookupKey> getKeys(int start, int end) {
        List<LookupKey> keys = new ArrayList<>();
        for(int i = start;i < end;++i) {
            keys.add(new EnrichmentKey("type", "key-" + i));
        }
        return keys;
    }

    @Test
    public void testCommandLine() throws Exception {
        Configuration conf = HBaseConfiguration.create();

        String[] argv = {"-a 04/14/2016 12:00:00", "-f cf", "-t malicious_domains", "-u access_trackers",  "-v georgia", "-z cf"};
        String[] otherArgs = new GenericOptionsParser(conf, argv).getRemainingArgs();

        CommandLine cli = LeastRecentlyUsedPruner.BulkLoadOptions.parse(new PosixParser(), otherArgs);
        Assert.assertEquals(columnFamily, LeastRecentlyUsedPruner.BulkLoadOptions.COLUMN_FAMILY.get(cli).trim());
        Assert.assertEquals(tableName, LeastRecentlyUsedPruner.BulkLoadOptions.TABLE.get(cli).trim());
        Assert.assertEquals(accessTrackerTableName, LeastRecentlyUsedPruner.BulkLoadOptions.ACCESS_TABLE.get(cli).trim());
        Assert.assertEquals(accessTrackerColumnFamily, LeastRecentlyUsedPruner.BulkLoadOptions.ACCESS_COLUMN_FAMILY.get(cli).trim());
        Assert.assertEquals(beginTime, LeastRecentlyUsedPruner.BulkLoadOptions.AS_OF_TIME.get(cli).trim());
        Assert.assertEquals(timeFormat, LeastRecentlyUsedPruner.BulkLoadOptions.AS_OF_TIME_FORMAT.get(cli).trim());
    }

    @Test
    public void test() throws Exception {
        HBaseConnectionFactory connFactory = new HBaseConnectionFactory();
        Configuration config = HBaseConfiguration.create();
        config.set("hbase.rpc.timeout", "1000");
        long ts = System.currentTimeMillis();
        PersistentAccessTracker accessTracker = new PersistentAccessTracker(tableName,
                "0",
                accessTrackerTableName,
                accessTrackerColumnFamily,
                new BloomAccessTracker("tracker1", 100, 0.03),
                0L,
                connFactory,
                config);
        HBaseEnrichmentLookup hbaseLookup = new HBaseEnrichmentLookup(connFactory, tableName, columnFamily);
        TrackedEnrichmentLookup lookup = new TrackedEnrichmentLookup(hbaseLookup, accessTracker);

        List<LookupKey> goodKeysHalf = getKeys(0, 5);
        List<LookupKey> goodKeysOtherHalf = getKeys(5, 10);
        Iterable<LookupKey> goodKeys = Iterables.concat(goodKeysHalf, goodKeysOtherHalf);
        List<LookupKey> badKey = getKeys(10, 11);

        EnrichmentConverter converter = new EnrichmentConverter(tableName);
        for(LookupKey k : goodKeysHalf) {
            testTable.put(converter.toPut(columnFamily,
                    (EnrichmentKey) k,
                    new EnrichmentValue().withValue("k", "dummy")));
            Assert.assertTrue(lookup.exists((EnrichmentKey) k));
        }
        accessTracker.persist(true);
        for(LookupKey k : goodKeysOtherHalf) {
            testTable.put(converter.toPut(columnFamily,
                    (EnrichmentKey) k,
                    new EnrichmentValue().withValue("k", "dummy")));
            Assert.assertTrue(lookup.exists((EnrichmentKey)k));
        }
        testUtil.flush();
        Assert.assertFalse(lookup.getAccessTracker().hasSeen(goodKeysHalf.get(0)));
        for(LookupKey k : goodKeysOtherHalf) {
            Assert.assertTrue(lookup.getAccessTracker().hasSeen(k));
        }
        accessTracker.persist(true);
        {
            testTable.put(converter.toPut(columnFamily,
                    (EnrichmentKey) badKey.get(0),
                    new EnrichmentValue().withValue("k", "dummy")));
        }
        testUtil.flush();
        Assert.assertFalse(lookup.getAccessTracker().hasSeen(badKey.get(0)));

        Job job = LeastRecentlyUsedPruner.createJob(config, tableName, columnFamily, accessTrackerTableName, accessTrackerColumnFamily, ts);
        Assert.assertTrue(job.waitForCompletion(true));
        for(LookupKey k : goodKeys) {
            Assert.assertTrue(lookup.exists((EnrichmentKey)k));
        }
        for(LookupKey k : badKey) {
            Assert.assertFalse(lookup.exists((EnrichmentKey)k));
        }
    }
}
