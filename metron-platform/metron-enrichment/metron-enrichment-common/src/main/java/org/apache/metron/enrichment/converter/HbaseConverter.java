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

package org.apache.metron.enrichment.converter;

import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.metron.enrichment.lookup.EnrichmentResult;
import org.apache.metron.enrichment.lookup.LookupKey;
import org.apache.metron.enrichment.lookup.LookupValue;

import java.io.Closeable;
import java.io.IOException;

public interface HbaseConverter<KEY_T extends LookupKey, VALUE_T extends LookupValue> extends Closeable {

    Put toPut(String columnFamily, KEY_T key, VALUE_T values) throws IOException;

    void put(String columnFamily, KEY_T key, VALUE_T values) throws IOException;

    EnrichmentResult fromPut(Put put, String columnFamily) throws IOException;

    Result toResult(String columnFamily, KEY_T key, VALUE_T values) throws IOException;

    EnrichmentResult fromResult(Result result, String columnFamily) throws IOException;

    Get toGet(String columnFamily, KEY_T key);
}
