/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.log;

import io.atomix.primitive.protocol.LogProtocol;
import io.atomix.primitive.protocol.PrimitiveProtocol;
import io.atomix.utils.component.Component;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Distributed log protocol.
 */
public class DistributedLogProtocol implements LogProtocol<DistributedLog> {
  public static final Type TYPE = new Type();

  /**
   * Returns an instance of the log protocol with the default configuration.
   *
   * @return an instance of the log protocol with the default configuration
   */
  public static DistributedLogProtocol instance() {
    return new DistributedLogProtocol(new DistributedLogProtocolConfig());
  }

  /**
   * Returns a new log protocol builder.
   *
   * @return a new log protocol builder
   */
  public static DistributedLogProtocolBuilder builder() {
    return new DistributedLogProtocolBuilder(new DistributedLogProtocolConfig());
  }

  /**
   * Returns a new log protocol builder.
   *
   * @param group the partition group
   * @return the log protocol builder
   */
  public static DistributedLogProtocolBuilder builder(String group) {
    return new DistributedLogProtocolBuilder(new DistributedLogProtocolConfig().setGroup(group));
  }

  /**
   * Log protocol type.
   */
  @Component
  public static final class Type implements PrimitiveProtocol.Type<DistributedLogProtocolConfig> {
    private static final String NAME = "multi-log";

    @Override
    public String name() {
      return NAME;
    }

    @Override
    public DistributedLogProtocolConfig newConfig() {
      return new DistributedLogProtocolConfig();
    }

    @Override
    public PrimitiveProtocol newProtocol(DistributedLogProtocolConfig config) {
      return new DistributedLogProtocol(config);
    }
  }

  private final DistributedLogProtocolConfig config;

  protected DistributedLogProtocol(DistributedLogProtocolConfig config) {
    this.config = checkNotNull(config, "config cannot be null");
  }

  @Override
  public PrimitiveProtocol.Type type() {
    return TYPE;
  }

  @Override
  public String group() {
    return config.getGroup();
  }

  /**
   * Returns the protocol configuration.
   *
   * @return the protocol configuration
   */
  public DistributedLogProtocolConfig config() {
    return config;
  }

  @Override
  public DistributedLog toProto() {
    return DistributedLog.newBuilder()
        .setGroup(group())
        .setPartitions(config.getPartitions())
        .setReplicationFactor(config.getReplicationFactor())
        .setReplicationStrategy(config.getReplicationStrategy())
        .build();
  }
}
