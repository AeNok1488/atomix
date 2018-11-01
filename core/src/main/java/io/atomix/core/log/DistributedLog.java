/*
 * Copyright 2018-present Open Networking Foundation
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
package io.atomix.core.log;

import java.util.function.Consumer;

import io.atomix.primitive.SyncPrimitive;

/**
 * Distributed log primitive.
 */
public interface DistributedLog<E> extends SyncPrimitive {

  /**
   * Appends an entry to the distributed log.
   *
   * @param entry the entry to append
   */
  void produce(E entry);

  /**
   * Adds a consumer to the log.
   *
   * @param consumer the log consumer
   */
  default void consume(Consumer<Record<E>> consumer) {
    consume(1, consumer);
  }

  /**
   * Adds a consumer to the log.
   *
   * @param offset the offset from which to begin consuming the log
   * @param consumer the log consumer
   */
  void consume(long offset, Consumer<Record<E>> consumer);

  @Override
  AsyncDistributedLog<E> async();

}