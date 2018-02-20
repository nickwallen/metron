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

package org.apache.metron.profiler;

import org.apache.metron.stellar.dsl.Context;
import org.json.simple.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Distributes a telemetry message along a {@link MessageRoute}. A {@link MessageRoute} will lead to a
 * {@link ProfileBuilder} that is responsible for building and maintaining a profile.
 *
 * <p>A {@link ProfileBuilder} is responsible for maintaining the state of a single (profile, entity)
 * pairing.  There will be one {@link ProfileBuilder} for each (profile, entity) pair.
 *
 * <p>A {@link MessageDistributor} ensures that each {@link ProfileBuilder} receives the telemetry
 * messages that it needs.
 *
 * @see MessageRoute
 * @see ProfileMeasurement
 */
public interface MessageDistributor {

  /**
   * Distribute a message along a MessageRoute.
   *
   * @param message The message that needs distributed.
   * @param timestamp The timestamp of the message.
   * @param route The message route.
   * @param context The Stellar execution context.
   * @throws ExecutionException
   */
  void distribute(JSONObject message, long timestamp, MessageRoute route, Context context);

  /**
   * Flushes all profiles.
   *
   * <p>Flushes all ProfileBuilders that this distributor is responsible for.
   *
   * @return The profile measurements; one for each (profile, entity) pair.
   */
  List<ProfileMeasurement> flush();
}
