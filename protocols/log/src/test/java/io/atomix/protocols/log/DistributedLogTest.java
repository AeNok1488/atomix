/*
 * Copyright 2018-present Open Networking Foundation
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
package io.atomix.protocols.log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.atomix.cluster.MemberId;
import io.atomix.cluster.TestClusterMembershipService;
import io.atomix.primitive.PrimitiveBuilder;
import io.atomix.primitive.PrimitiveManagementService;
import io.atomix.primitive.PrimitiveType;
import io.atomix.primitive.config.PrimitiveConfig;
import io.atomix.primitive.event.EventType;
import io.atomix.primitive.log.LogSession;
import io.atomix.primitive.operation.OperationMetadata;
import io.atomix.primitive.operation.OperationType;
import io.atomix.primitive.operation.impl.DefaultOperationId;
import io.atomix.primitive.partition.MemberGroupStrategy;
import io.atomix.primitive.partition.PartitionId;
import io.atomix.primitive.partition.PrimaryElection;
import io.atomix.primitive.partition.TestPrimaryElection;
import io.atomix.primitive.service.AbstractPrimitiveService;
import io.atomix.primitive.service.PrimitiveService;
import io.atomix.primitive.service.ServiceExecutor;
import io.atomix.primitive.session.Session;
import io.atomix.primitive.session.SessionId;
import io.atomix.protocols.log.protocol.EventRequest;
import io.atomix.protocols.log.protocol.EventResponse;
import io.atomix.protocols.log.protocol.OnCloseRequest;
import io.atomix.protocols.log.protocol.OnCloseResponse;
import io.atomix.protocols.log.protocol.OnExpireRequest;
import io.atomix.protocols.log.protocol.OnExpireResponse;
import io.atomix.protocols.log.protocol.ReadRequest;
import io.atomix.protocols.log.protocol.ReadResponse;
import io.atomix.protocols.log.protocol.TestEvent;
import io.atomix.protocols.log.protocol.TestLogProtocolFactory;
import io.atomix.protocols.log.protocol.WriteRequest;
import io.atomix.protocols.log.protocol.WriteResponse;
import io.atomix.utils.concurrent.Futures;
import io.atomix.utils.serializer.Serializer;
import io.atomix.utils.serializer.serializers.DefaultSerializers;
import net.jodah.concurrentunit.ConcurrentTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Raft test.
 */
public class DistributedLogTest extends ConcurrentTestCase {
  private static final Serializer SERIALIZER = DefaultSerializers.BASIC;
  private volatile int memberId;
  private volatile int sessionId;
  private PrimaryElection election;
  protected volatile List<MemberId> nodes;
  protected volatile List<DistributedLogSessionClient> clients = new ArrayList<>();
  protected volatile List<DistributedLogServer> servers = new ArrayList<>();
  protected volatile TestLogProtocolFactory protocolFactory;

  @Test
  public void testProducerConsumer() throws Throwable {
    createServers(3);
    DistributedLogSessionClient client1 = createClient();
    LogSession session1 = createSession(client1);
    DistributedLogSessionClient client2 = createClient();
    LogSession session2 = createSession(client2);

    session1.consumer().consume(1, record -> {
      threadAssertTrue(Arrays.equals("Hello world!".getBytes(), record.getValue().toByteArray()));
      resume();
    });
    session2.producer().append("Hello world!".getBytes());
    await(5000);
  }

  @Test
  public void testConsumeIndex() throws Throwable {
    createServers(3);
    DistributedLogSessionClient client1 = createClient();
    LogSession session1 = createSession(client1);
    DistributedLogSessionClient client2 = createClient();
    LogSession session2 = createSession(client2);

    for (int i = 1; i <= 10; i++) {
      session2.producer().append(String.valueOf(i).getBytes()).join();
    }

    session1.consumer().consume(10, record -> {
      threadAssertTrue(record.getIndex() == 10);
      threadAssertTrue(Arrays.equals("10".getBytes(), record.getValue().toByteArray()));
      resume();
    });

    await(5000);
  }

  @Test
  public void testConsumeAfterSizeCompact() throws Throwable {
    List<DistributedLogServer> servers = createServers(3);
    DistributedLogSessionClient client1 = createClient();
    LogSession session1 = createSession(client1);
    DistributedLogSessionClient client2 = createClient();
    LogSession session2 = createSession(client2);

    Predicate<List<DistributedLogServer>> predicate = s ->
        s.stream().map(sr -> sr.context.journal().segments().size() > 2).reduce(Boolean::logicalOr).orElse(false);
    while (!predicate.test(servers)) {
      session1.producer().append(UUID.randomUUID().toString().getBytes());
    }
    servers.forEach(server -> server.context.compact());

    session2.consumer().consume(1, record -> {
      threadAssertTrue(record.getIndex() > 1);
      resume();
    });
    await(5000);
  }

  @Test
  public void testConsumeAfterAgeCompact() throws Throwable {
    List<DistributedLogServer> servers = createServers(3);
    DistributedLogSessionClient client1 = createClient();
    LogSession session1 = createSession(client1);
    DistributedLogSessionClient client2 = createClient();
    LogSession session2 = createSession(client2);

    Predicate<List<DistributedLogServer>> predicate = s ->
        s.stream().map(sr -> sr.context.journal().segments().size() > 1).reduce(Boolean::logicalOr).orElse(false);
    while (!predicate.test(servers)) {
      session1.producer().append(UUID.randomUUID().toString().getBytes());
    }
    Thread.sleep(1000);
    servers.forEach(server -> server.context.compact());

    session2.consumer().consume(1, record -> {
      threadAssertTrue(record.getIndex() > 1);
      resume();
    });
    await(5000);
  }

  /**
   * Returns the next unique member identifier.
   *
   * @return The next unique member identifier.
   */
  private MemberId nextMemberId() {
    return MemberId.from(String.valueOf(++memberId));
  }

  /**
   * Returns the next unique session identifier.
   */
  private SessionId nextSessionId() {
    return SessionId.from(++sessionId);
  }

  /**
   * Creates a set of Raft servers.
   */
  private List<DistributedLogServer> createServers(int count) throws Throwable {
    List<DistributedLogServer> servers = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      nodes.add(nextMemberId());
      DistributedLogServer server = createServer(nodes.get(i));
      server.start().thenRun(this::resume);
      servers.add(server);
    }

    await(5000, count);

    return servers;
  }

  /**
   * Creates a Raft server.
   */
  private DistributedLogServer createServer(MemberId memberId) {
    DistributedLogServer server = DistributedLogServer.builder()
        .withServerName("test")
        .withProtocol(protocolFactory.newServerProtocol(memberId))
        .withMembershipService(new TestClusterMembershipService(memberId, nodes))
        .withMemberGroupProvider(MemberGroupStrategy.NODE_AWARE)
        .withPrimaryElection(election)
        .withDirectory(new File("target/test-logs", memberId.id()))
        .withMaxSegmentSize(1024 * 8)
        .withMaxLogSize(1024)
        .withMaxLogAge(Duration.ofMillis(10))
        .build();
    servers.add(server);
    return server;
  }

  /**
   * Creates a Raft client.
   */
  private DistributedLogSessionClient createClient() throws Throwable {
    MemberId memberId = nextMemberId();
    DistributedLogSessionClient client = DistributedLogSessionClient.builder()
        .withClientName("test")
        .withPartitionId(PartitionId.newBuilder()
            .setGroup("test")
            .setPartition(1)
            .build())
        .withMembershipService(new TestClusterMembershipService(memberId, nodes))
        .withSessionIdProvider(() -> CompletableFuture.completedFuture(nextSessionId()))
        .withPrimaryElection(election)
        .withProtocol(protocolFactory.newClientProtocol(memberId))
        .build();
    clients.add(client);
    return client;
  }

  /**
   * Creates a new log session.
   */
  private LogSession createSession(DistributedLogSessionClient client) {
    try {
      return client.sessionBuilder()
          .build()
          .connect()
          .get(30, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new RuntimeException(e);
    }
  }

  @Before
  @After
  public void clearTests() throws Exception {
    Futures.allOf(servers.stream()
        .map(s -> s.stop().exceptionally(v -> null))
        .collect(Collectors.toList()))
        .get(30, TimeUnit.SECONDS);
    Futures.allOf(clients.stream()
        .map(c -> c.close().exceptionally(v -> null))
        .collect(Collectors.toList()))
        .get(30, TimeUnit.SECONDS);

    Path directory = Paths.get("target/test-logs/");
    if (Files.exists(directory)) {
      Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    }

    nodes = new ArrayList<>();
    memberId = 0;
    sessionId = 0;
    clients = new ArrayList<>();
    servers = new ArrayList<>();
    protocolFactory = new TestLogProtocolFactory();
    election = new TestPrimaryElection(PartitionId.newBuilder()
        .setGroup("test")
        .setPartition(1)
        .build());
  }

  private static final OperationMetadata WRITE = OperationMetadata.newBuilder()
      .setType(OperationType.COMMAND)
      .setName("write")
      .build();
  private static final OperationMetadata EVENT = OperationMetadata.newBuilder()
      .setType(OperationType.COMMAND)
      .setName("event")
      .build();
  private static final OperationMetadata EXPIRE = OperationMetadata.newBuilder()
      .setType(OperationType.COMMAND)
      .setName("expire")
      .build();
  private static final OperationMetadata CLOSE = OperationMetadata.newBuilder()
      .setType(OperationType.COMMAND)
      .setName("close")
      .build();

  private static final OperationMetadata READ = OperationMetadata.newBuilder()
      .setType(OperationType.QUERY)
      .setName("read")
      .build();

  private static final EventType CHANGE_EVENT = EventType.from("change");
  private static final EventType EXPIRE_EVENT = EventType.from("expire");
  private static final EventType CLOSE_EVENT = EventType.from("close");

  public static class TestPrimitiveType implements PrimitiveType {
    private static final TestPrimitiveType INSTANCE = new TestPrimitiveType();

    @Override
    public String name() {
      return "primary-backup-test";
    }

    @Override
    public PrimitiveConfig newConfig() {
      throw new UnsupportedOperationException();
    }

    @Override
    public PrimitiveBuilder newBuilder(String primitiveName, PrimitiveConfig config, PrimitiveManagementService managementService) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PrimitiveService newService() {
      return new TestPrimitiveService();
    }
  }

  /**
   * Test state machine.
   */
  public static class TestPrimitiveService extends AbstractPrimitiveService {
    private SessionId expire;
    private SessionId close;

    @Override
    protected void configure(ServiceExecutor executor) {
      executor.register(new DefaultOperationId(WRITE.getName(), WRITE.getType()), this::write, WriteRequest::parseFrom, WriteResponse::toByteArray);
      executor.register(new DefaultOperationId(READ.getName(), READ.getType()), this::read, ReadRequest::parseFrom, ReadResponse::toByteArray);
      executor.register(new DefaultOperationId(EVENT.getName(), EVENT.getType()), this::event, EventRequest::parseFrom, EventResponse::toByteArray);
      executor.register(new DefaultOperationId(CLOSE.getName(), CLOSE.getType()), this::close, OnCloseRequest::parseFrom, OnCloseResponse::toByteArray);
      executor.register(new DefaultOperationId(EXPIRE.getName(), EXPIRE.getType()), this::expire, OnExpireRequest::parseFrom, OnExpireResponse::toByteArray);
    }

    @Override
    public void onExpire(Session session) {
      if (expire != null) {
        getSession(expire).publish(EXPIRE_EVENT);
      }
    }

    @Override
    public void onClose(Session session) {
      if (close != null && !session.sessionId().equals(close)) {
        getSession(close).publish(CLOSE_EVENT);
      }
    }

    @Override
    public void backup(OutputStream output) throws IOException {
      output.write(new byte[]{1});
    }

    @Override
    public void restore(InputStream input) throws IOException {
      assertEquals(1, input.read());
    }

    protected WriteResponse write(WriteRequest request) {
      return WriteResponse.newBuilder()
          .setIndex(getCurrentIndex())
          .build();
    }

    protected ReadResponse read(ReadRequest request) {
      return ReadResponse.newBuilder()
          .setIndex(getCurrentIndex())
          .build();
    }

    protected EventResponse event(EventRequest request) {
      if (request.getSender()) {
        getCurrentSession().publish(CHANGE_EVENT, TestEvent.newBuilder().setIndex(getCurrentIndex()).build(), TestEvent::toByteArray);
      } else {
        for (Session session : getSessions()) {
          session.publish(CHANGE_EVENT, TestEvent.newBuilder().setIndex(getCurrentIndex()).build(), TestEvent::toByteArray);
        }
      }
      return EventResponse.newBuilder()
          .setIndex(getCurrentIndex())
          .build();
    }

    public OnCloseResponse close(OnCloseRequest request) {
      close = getCurrentSession().sessionId();
      return OnCloseResponse.newBuilder().build();
    }

    public OnExpireResponse expire(OnExpireRequest request) {
      expire = getCurrentSession().sessionId();
      return OnExpireResponse.newBuilder().build();
    }
  }

}
