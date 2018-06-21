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
package org.apache.metron.profiler.hbase;

import org.apache.hadoop.hbase.util.Bytes;
import org.apache.metron.common.configuration.profiler.ProfileConfig;
import org.apache.metron.hbase.bolt.mapper.ColumnList;
import org.apache.metron.profiler.ProfileMeasurement;
import org.apache.metron.profiler.ProfilePeriod;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.apache.metron.common.utils.SerDeUtils.toBytes;
import static org.junit.Assert.assertTrue;

public class AllFieldsColumnBuilderTest {

  private static final long periodDuration = 15;
  private static final TimeUnit periodUnits = TimeUnit.MINUTES;
  private static final long AUG2016 = 1472131630748L;

  private AllFieldsColumnBuilder columnBuilder;
  private ProfileMeasurement measurement;
  private ProfilePeriod profilePeriod;
  private ProfileConfig profileDefinition;

  @Before
  public void setup() {
    columnBuilder = new AllFieldsColumnBuilder();
    profileDefinition = new ProfileConfig()
            .withProfile("profile");
    profilePeriod = new ProfilePeriod(AUG2016, periodDuration, periodUnits);
    measurement = new ProfileMeasurement()
            .withProfileName("profile")
            .withEntity("entity")
            .withPeriod(profilePeriod)
            .withProfileValue(22)
            .withDefinition(profileDefinition);
  }

  @Test
  public void testGetColumnQualifier() {
    assertTrue(Arrays.equals(Bytes.toBytes("value"), columnBuilder.getColumnQualifier("value")));
    assertTrue(Arrays.equals(Bytes.toBytes("name"), columnBuilder.getColumnQualifier("name")));
    assertTrue(Arrays.equals(Bytes.toBytes("entity"), columnBuilder.getColumnQualifier("entity")));
    assertTrue(Arrays.equals(Bytes.toBytes("definition"), columnBuilder.getColumnQualifier("definition")));
    assertTrue(Arrays.equals(Bytes.toBytes("start"), columnBuilder.getColumnQualifier("start")));
    assertTrue(Arrays.equals(Bytes.toBytes("end"), columnBuilder.getColumnQualifier("end")));
    assertTrue(Arrays.equals(Bytes.toBytes("period"), columnBuilder.getColumnQualifier("period")));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetInvalidColumnQualifier() {
    columnBuilder.getColumnQualifier("invalid");
  }

  @Test
  public void testColumns() {
    // the columns that are expected
    byte[] cf = columnBuilder.getColumnFamilyBytes();
    ColumnList expected = new ColumnList();
    expected.addColumn(cf, columnBuilder.getColumnQualifier("value"), toBytes(measurement.getProfileValue()));
    expected.addColumn(cf, columnBuilder.getColumnQualifier("name"), toBytes(measurement.getProfileName()));
    expected.addColumn(cf, columnBuilder.getColumnQualifier("entity"), toBytes(measurement.getEntity()));
    expected.addColumn(cf, columnBuilder.getColumnQualifier("definition"), toBytes(measurement.getDefinition()));
    expected.addColumn(cf, columnBuilder.getColumnQualifier("start"), toBytes(measurement.getPeriod().getStartTimeMillis()));
    expected.addColumn(cf, columnBuilder.getColumnQualifier("end"), toBytes(measurement.getPeriod().getEndTimeMillis()));
    expected.addColumn(cf, columnBuilder.getColumnQualifier("period"), toBytes(measurement.getPeriod().getPeriod()));

    // retrieve the columns
    ColumnList columnList = columnBuilder.columns(measurement);
    List<ColumnList.Column> actual = columnList.getColumns();

    for(ColumnList.Column col: actual) {
      assertTrue(expected.getColumns().contains(col));
    }
  }

}