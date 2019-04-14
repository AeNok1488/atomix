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
package io.atomix.protocols.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;
import io.atomix.cluster.MemberId;
import io.atomix.cluster.TestClusterMembershipService;
import io.atomix.primitive.PrimitiveBuilder;
import io.atomix.primitive.PrimitiveManagementService;
import io.atomix.primitive.PrimitiveType;
import io.atomix.primitive.Replication;
import io.atomix.primitive.config.PrimitiveConfig;
import io.atomix.primitive.event.EventType;
import io.atomix.primitive.operation.OperationMetadata;
import io.atomix.primitive.operation.OperationType;
import io.atomix.primitive.operation.PrimitiveOperation;
import io.atomix.primitive.operation.impl.DefaultOperationId;
import io.atomix.primitive.partition.MemberGroupStrategy;
import io.atomix.primitive.partition.PartitionId;
import io.atomix.primitive.partition.PrimaryElection;
import io.atomix.primitive.partition.TestPrimaryElection;
import io.atomix.primitive.service.AbstractPrimitiveService;
import io.atomix.primitive.service.PrimitiveService;
import io.atomix.primitive.service.ServiceExecutor;
import io.atomix.primitive.session.Session;
import io.atomix.primitive.session.SessionClient;
import io.atomix.primitive.session.SessionId;
import io.atomix.protocols.backup.PrimaryBackupServer.Role;
import io.atomix.protocols.backup.protocol.EventRequest;
import io.atomix.protocols.backup.protocol.EventResponse;
import io.atomix.protocols.backup.protocol.OnCloseRequest;
import io.atomix.protocols.backup.protocol.OnCloseResponse;
import io.atomix.protocols.backup.protocol.OnExpireRequest;
import io.atomix.protocols.backup.protocol.OnExpireResponse;
import io.atomix.protocols.backup.protocol.ReadRequest;
import io.atomix.protocols.backup.protocol.ReadResponse;
import io.atomix.protocols.backup.protocol.TestEvent;
import io.atomix.protocols.backup.protocol.TestPrimaryBackupProtocolFactory;
import io.atomix.protocols.backup.protocol.WriteRequest;
import io.atomix.protocols.backup.protocol.WriteResponse;
import io.atomix.utils.concurrent.Futures;
import io.atomix.utils.serializer.Serializer;
import io.atomix.utils.serializer.serializers.DefaultSerializers;
import net.jodah.concurrentunit.ConcurrentTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Raft test.
 */
public class PrimaryBackupTest extends ConcurrentTestCase {
  private static final Serializer SERIALIZER = DefaultSerializers.BASIC;
  private volatile int memberId;
  private volatile int sessionId;
  private PrimaryElection election;
  protected volatile List<MemberId> nodes;
  protected volatile List<PrimaryBackupClient> clients = new ArrayList<>();
  protected volatile List<PrimaryBackupServer> servers = new ArrayList<>();
  protected volatile TestPrimaryBackupProtocolFactory protocolFactory;

  @Test
  public void testOneNodeCommand() throws Throwable {
    testSubmitCommand(1, 0, Replication.SYNCHRONOUS);
  }

  @Test
  public void testSynchronousCommand() throws Throwable {
    testSubmitCommand(3, 2, Replication.SYNCHRONOUS);
  }

  @Test
  public void testAsynchronousCommand() throws Throwable {
    testSubmitCommand(3, 2, Replication.ASYNCHRONOUS);
  }

  /**
   * Tests submitting a command to a partial cluster.
   */
  private void testSubmitCommand(int nodes, int backups, Replication replication) throws Throwable {
    createServers(nodes);

    PrimaryBackupClient client = createClient();
    SessionClient session = createProxy(client, backups, replication);
    session.execute(PrimitiveOperation.newBuilder()
        .setId(WRITE)
        .build()).thenRun(this::resume);

    await(5000);
  }

  @Test
  public void testOneNodeQuery() throws Throwable {
    testSubmitQuery(1, 0, Replication.SYNCHRONOUS);
  }

  @Test
  public void testSynchronousQuery() throws Throwable {
    testSubmitQuery(3, 2, Replication.SYNCHRONOUS);
  }

  @Test
  public void testAsynchronousQuery() throws Throwable {
    testSubmitQuery(3, 2, Replication.ASYNCHRONOUS);
  }

  /**
   * Tests submitting a query with a configured consistency level.
   */
  private void testSubmitQuery(int nodes, int backups, Replication replication) throws Throwable {
    createServers(nodes);

    PrimaryBackupClient client = createClient();
    SessionClient session = createProxy(client, backups, replication);
    session.execute(PrimitiveOperation.newBuilder()
        .setId(READ)
        .build()).thenRun(this::resume);

    await(5000);
  }

  @Test
  public void testOneNodeEvent() throws Throwable {
    testSequentialEvent(1, 0, Replication.SYNCHRONOUS);
  }

  @Test
  public void testSynchronousEvent() throws Throwable {
    testSequentialEvent(3, 2, Replication.SYNCHRONOUS);
  }

  @Test
  public void testAsynchronousEvent() throws Throwable {
    testSequentialEvent(3, 2, Replication.ASYNCHRONOUS);
  }

  /**
   * Tests submitting a sequential event.
   */
  private void testSequentialEvent(int nodes, int backups, Replication replication) throws Throwable {
    createServers(nodes);

    AtomicLong count = new AtomicLong();
    AtomicLong index = new AtomicLong();

    PrimaryBackupClient client = createClient();
    SessionClient session = createProxy(client, backups, replication);
    session.<Long>addEventListener(CHANGE_EVENT, event -> {
      threadAssertEquals(count.incrementAndGet(), 2L);
      threadAssertEquals(index.get(), SERIALIZER.decode(event.value()));
      resume();
    });

    session.execute(PrimitiveOperation.newBuilder()
        .setId(EVENT)
        .setValue(ByteString.copyFrom(SERIALIZER.encode(true)))
        .build())
        .<Long>thenApply(SERIALIZER::decode)
        .thenAccept(result -> {
          threadAssertNotNull(result);
          threadAssertEquals(count.incrementAndGet(), 1L);
          index.set(result);
          resume();
        });

    await(5000, 2);
  }

  @Test
  public void testOneNodeEvents() throws Throwable {
    testEvents(1, 0, Replication.SYNCHRONOUS);
  }

  @Test
  public void testSynchronousEvents() throws Throwable {
    testEvents(3, 2, Replication.SYNCHRONOUS);
  }

  @Test
  public void testAsynchronousEvents() throws Throwable {
    testEvents(3, 2, Replication.ASYNCHRONOUS);
  }

  /**
   * Tests submitting sequential events to all sessions.
   */
  private void testEvents(int nodes, int backups, Replication replication) throws Throwable {
    createServers(nodes);

    PrimaryBackupClient client1 = createClient();
    SessionClient session1 = createProxy(client1, backups, replication);
    session1.addEventListener(CHANGE_EVENT, event -> {
      threadAssertNotNull(event);
      resume();
    });

    PrimaryBackupClient client2 = createClient();
    SessionClient session2 = createProxy(client2, backups, replication);
    session2.addEventListener(CHANGE_EVENT, event -> {
      threadAssertNotNull(event);
      resume();
    });

    session1.execute(PrimitiveOperation.newBuilder()
        .setId(READ)
        .build()).thenRun(this::resume);
    session2.execute(PrimitiveOperation.newBuilder()
        .setId(READ)
        .build()).thenRun(this::resume);
    await(5000, 2);

    session1.execute(PrimitiveOperation.newBuilder()
        .setId(EVENT)
        .setValue(ByteString.copyFrom(SERIALIZER.encode(false)))
        .build()).thenRun(this::resume);
    await(5000, 3);
  }

  @Test
  public void testOneNodeManyEvents() throws Throwable {
    testManyEvents(1, 0, Replication.SYNCHRONOUS);
  }

  @Test
  public void testManySynchronousEvents() throws Throwable {
    testManyEvents(3, 2, Replication.SYNCHRONOUS);
  }

  @Test
  public void testManyAsynchronousEvents() throws Throwable {
    testManyEvents(3, 2, Replication.ASYNCHRONOUS);
  }

  /**
   * Tests submitting a linearizable event that publishes to all sessions.
   */
  private void testManyEvents(int nodes, int backups, Replication replication) throws Throwable {
    createServers(nodes);

    PrimaryBackupClient client = createClient();
    SessionClient session = createProxy(client, backups, replication);
    session.addEventListener(CHANGE_EVENT, message -> {
      threadAssertNotNull(message);
      resume();
    });

    for (int i = 0; i < 10; i++) {
      session.execute(PrimitiveOperation.newBuilder()
          .setId(EVENT)
          .setValue(ByteString.copyFrom(SERIALIZER.encode(true)))
          .build()).thenRun(this::resume);

      await(5000, 2);
    }
  }

  @Test
  @Ignore
  public void testSynchronousEventsAfterPrimaryShutdown() throws Throwable {
    testManyEventsAfterPrimaryShutdown(Replication.SYNCHRONOUS);
  }

  @Test
  @Ignore
  public void testAsynchronousEventsAfterPrimaryShutdown() throws Throwable {
    testManyEventsAfterPrimaryShutdown(Replication.ASYNCHRONOUS);
  }

  /**
   * Tests events after a primary shutdown.
   */
  private void testManyEventsAfterPrimaryShutdown(Replication replication) throws Throwable {
    List<PrimaryBackupServer> servers = createServers(5);

    PrimaryBackupClient client = createClient();
    SessionClient session = createProxy(client, 3, replication);
    session.addEventListener(CHANGE_EVENT, event -> {
      threadAssertNotNull(event);
      resume();
    });

    for (int i = 0; i < 10; i++) {
      session.execute(PrimitiveOperation.newBuilder()
          .setId(EVENT)
          .setValue(ByteString.copyFrom(SERIALIZER.encode(true)))
          .build()).thenRun(this::resume);

      await(5000, 2);
    }

    PrimaryBackupServer leader = servers.stream().filter(s -> s.getRole() == Role.PRIMARY).findFirst().get();
    leader.stop().get(10, TimeUnit.SECONDS);

    for (int i = 0; i < 10; i++) {
      session.execute(PrimitiveOperation.newBuilder()
          .setId(EVENT)
          .setValue(ByteString.copyFrom(SERIALIZER.encode(true)))
          .build()).thenRun(this::resume);

      await(5000, 2);
    }
  }

  /**
   * Tests submitting linearizable events.
   */
  @Test
  public void testManySynchronousSessionsManyEvents() throws Throwable {
    testManySessionsManyEvents(Replication.SYNCHRONOUS);
  }

  /**
   * Tests submitting linearizable events.
   */
  @Test
  public void testManyAsynchronousSessionsManyEvents() throws Throwable {
    testManySessionsManyEvents(Replication.ASYNCHRONOUS);
  }

  /**
   * Tests submitting a linearizable event that publishes to all sessions.
   */
  private void testManySessionsManyEvents(Replication replication) throws Throwable {
    createServers(3);

    PrimaryBackupClient client = createClient();
    SessionClient session = createProxy(client, 2, replication);
    session.addEventListener(CHANGE_EVENT, event -> {
      threadAssertNotNull(event);
      resume();
    });

    SessionClient session1 = createProxy(createClient(), 2, replication);
    session1.execute(PrimitiveOperation.newBuilder()
        .setId(READ)
        .build()).thenRun(this::resume);
    session1.addEventListener(CHANGE_EVENT, event -> {
      threadAssertNotNull(event);
      resume();
    });

    SessionClient session2 = createProxy(createClient(), 2, replication);
    session2.execute(PrimitiveOperation.newBuilder()
        .setId(READ)
        .build()).thenRun(this::resume);
    session2.addEventListener(CHANGE_EVENT, event -> {
      threadAssertNotNull(event);
      resume();
    });

    await(5000, 2);

    for (int i = 0; i < 10; i++) {
      session.execute(PrimitiveOperation.newBuilder()
          .setId(EVENT)
          .setValue(ByteString.copyFrom(SERIALIZER.encode(false)))
          .build()).thenRun(this::resume);

      await(10000, 4);
    }
  }

  @Test
  public void testSynchronousCloseEvent() throws Throwable {
    testSessionClose(Replication.SYNCHRONOUS);
  }

  @Test
  public void testAsynchronousCloseEvent() throws Throwable {
    testSessionClose(Replication.ASYNCHRONOUS);
  }

  /**
   * Tests a session closing.
   */
  private void testSessionClose(Replication replication) throws Throwable {
    createServers(3);

    PrimaryBackupClient client1 = createClient();
    SessionClient session1 = createProxy(client1, 2, replication);
    PrimaryBackupClient client2 = createClient();
    session1.execute(PrimitiveOperation.newBuilder()
        .setId(CLOSE)
        .build()).thenRun(this::resume);
    await(Duration.ofSeconds(10).toMillis(), 1);
    session1.addEventListener(CLOSE_EVENT, e -> resume());
    SessionClient session2 = createProxy(client2, 2, replication);
    session2.execute(PrimitiveOperation.newBuilder()
        .setId(READ)
        .build()).thenRun(this::resume);
    await(5000);
    session2.close().thenRun(this::resume);
    await(Duration.ofSeconds(10).toMillis(), 2);
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
  private List<PrimaryBackupServer> createServers(int count) throws Throwable {
    List<PrimaryBackupServer> servers = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      nodes.add(nextMemberId());
      PrimaryBackupServer server = createServer(nodes.get(i));
      server.start().thenRun(this::resume);
      servers.add(server);
    }

    await(5000, count);

    return servers;
  }

  /**
   * Creates a Raft server.
   */
  private PrimaryBackupServer createServer(MemberId memberId) {
    PrimaryBackupServer server = PrimaryBackupServer.builder()
        .withServerName("test")
        .withProtocol(protocolFactory.newServerProtocol(memberId))
        .withMembershipService(new TestClusterMembershipService(memberId, nodes))
        .withMemberGroupProvider(MemberGroupStrategy.NODE_AWARE)
        .withPrimaryElection(election)
        .build();
    servers.add(server);
    return server;
  }

  /**
   * Creates a Raft client.
   */
  private PrimaryBackupClient createClient() throws Throwable {
    MemberId memberId = nextMemberId();
    PrimaryBackupClient client = PrimaryBackupClient.builder()
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
   * Creates a new primary-backup proxy.
   */
  private SessionClient createProxy(PrimaryBackupClient client, int backups, Replication replication) {
    try {
      return client.sessionBuilder("primary-backup-test", TestPrimitiveType.INSTANCE)
          .withNumBackups(backups)
          .withReplication(replication)
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
    nodes = new ArrayList<>();
    memberId = 0;
    sessionId = 0;
    clients = new ArrayList<>();
    servers = new ArrayList<>();
    protocolFactory = new TestPrimaryBackupProtocolFactory();
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

    public TestPrimitiveService() {
      super(TestPrimitiveType.INSTANCE);
    }

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
