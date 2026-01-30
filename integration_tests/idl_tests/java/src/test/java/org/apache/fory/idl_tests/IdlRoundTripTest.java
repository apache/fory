/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.idl_tests;

import addressbook.AddressBook;
import addressbook.AddressbookForyRegistration;
import addressbook.Animal;
import addressbook.Cat;
import addressbook.Dog;
import addressbook.Person;
import addressbook.Person.PhoneNumber;
import addressbook.Person.PhoneType;
import complex_pb.ComplexPbForyRegistration;
import complex_pb.PrimitiveTypes;
import any_example.AnyExampleForyRegistration;
import any_example.AnyHolder;
import any_example.AnyInner;
import any_example.AnyUnion;
import complex_fbs.ComplexFbsForyRegistration;
import complex_fbs.Container;
import complex_fbs.Metric;
import complex_fbs.Note;
import complex_fbs.Payload;
import complex_fbs.ScalarPack;
import complex_fbs.Status;
import graph.Edge;
import graph.Graph;
import graph.GraphForyRegistration;
import graph.Node;
import root.MultiHolder;
import optional_types.AllOptionalTypes;
import optional_types.OptionalHolder;
import optional_types.OptionalTypesForyRegistration;
import optional_types.OptionalUnion;
import tree.TreeForyRegistration;
import tree.TreeNode;
import monster.Color;
import monster.Monster;
import monster.MonsterForyRegistration;
import monster.Vec3;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

public class IdlRoundTripTest {

  @Test
  public void testAddressBookRoundTrip() throws Exception {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
    AddressbookForyRegistration.register(fory);

    AddressBook book = buildAddressBook();
    byte[] bytes = fory.serialize(book);
    Object decoded = fory.deserialize(bytes);

    Assert.assertTrue(decoded instanceof AddressBook);
    Assert.assertEquals(decoded, book);

    for (String peer : resolvePeers()) {
      Path dataFile = Files.createTempFile("idl-" + peer + "-", ".bin");
      dataFile.toFile().deleteOnExit();
      Files.write(dataFile, bytes);

      Map<String, String> env = new HashMap<>();
      env.put("DATA_FILE", dataFile.toAbsolutePath().toString());
      PeerCommand command = buildPeerCommand(peer, env);
      runPeer(command, peer);

      byte[] peerBytes = Files.readAllBytes(dataFile);
      Object roundTrip = fory.deserialize(peerBytes);
      Assert.assertTrue(roundTrip instanceof AddressBook);
      Assert.assertEquals(roundTrip, book);
    }
  }

  @Test
  public void testToBytesFromBytes() {
    AddressBook book = buildAddressBook();
    byte[] bookBytes = book.toBytes();
    AddressBook decodedBook = AddressBook.fromBytes(bookBytes);
    Assert.assertEquals(decodedBook, book);

    Dog dog = new Dog();
    dog.setName("Rex");
    dog.setBarkVolume(5);
    Animal animal = Animal.ofDog(dog);
    byte[] animalBytes = animal.toBytes();
    Animal decodedAnimal = Animal.fromBytes(animalBytes);
    Assert.assertEquals(decodedAnimal, animal);

    Person owner = new Person();
    owner.setName("Alice");
    owner.setId(123);
    owner.setEmail("");
    owner.setTags(Collections.emptyList());
    owner.setScores(new HashMap<>());
    owner.setSalary(0.0);
    owner.setPhones(Collections.emptyList());
    Dog rootDog = new Dog();
    rootDog.setName("Rex");
    rootDog.setBarkVolume(5);
    owner.setPet(Animal.ofDog(rootDog));

    AddressBook multiBook = new AddressBook();
    multiBook.setPeople(Arrays.asList(owner));
    Map<String, Person> peopleByName = new HashMap<>();
    peopleByName.put(owner.getName(), owner);
    multiBook.setPeopleByName(peopleByName);

    TreeNode rootNode = new TreeNode();
    rootNode.setId("root");
    rootNode.setName("root");
    rootNode.setChildren(Collections.emptyList());

    MultiHolder multi = new MultiHolder();
    multi.setBook(multiBook);
    multi.setRoot(rootNode);
    multi.setOwner(owner);

    byte[] multiBytes = multi.toBytes();
    MultiHolder decodedMulti = MultiHolder.fromBytes(multiBytes);
    Assert.assertEquals(decodedMulti, multi);
  }

  @Test
  public void testPrimitiveTypesRoundTrip() throws Exception {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
    AddressbookForyRegistration.register(fory);
    ComplexPbForyRegistration.register(fory);

    PrimitiveTypes types = buildPrimitiveTypes();
    byte[] bytes = fory.serialize(types);
    Object decoded = fory.deserialize(bytes);

    Assert.assertTrue(decoded instanceof PrimitiveTypes);
    Assert.assertEquals(decoded, types);

    for (String peer : resolvePeers()) {
      Path dataFile = Files.createTempFile("idl-primitive-" + peer + "-", ".bin");
      dataFile.toFile().deleteOnExit();
      Files.write(dataFile, bytes);

      Map<String, String> env = new HashMap<>();
      env.put("DATA_FILE_PRIMITIVES", dataFile.toAbsolutePath().toString());
      PeerCommand command = buildPeerCommand(peer, env);
      runPeer(command, peer);

      byte[] peerBytes = Files.readAllBytes(dataFile);
      Object roundTrip = fory.deserialize(peerBytes);
      Assert.assertTrue(roundTrip instanceof PrimitiveTypes);
      Assert.assertEquals(roundTrip, types);
    }
  }

  @Test
  public void testOptionalTypesRoundTrip() throws Exception {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
    OptionalTypesForyRegistration.register(fory);

    OptionalHolder holder = buildOptionalHolder();
    byte[] bytes = fory.serialize(holder);
    Object decoded = fory.deserialize(bytes);

    Assert.assertTrue(decoded instanceof OptionalHolder);
    Assert.assertEquals(decoded, holder);

    for (String peer : resolvePeers()) {
      Path dataFile = Files.createTempFile("idl-optional-" + peer + "-", ".bin");
      dataFile.toFile().deleteOnExit();
      Files.write(dataFile, bytes);

      Map<String, String> env = new HashMap<>();
      env.put("DATA_FILE_OPTIONAL_TYPES", dataFile.toAbsolutePath().toString());
      PeerCommand command = buildPeerCommand(peer, env);
      runPeer(command, peer);

      byte[] peerBytes = Files.readAllBytes(dataFile);
      Object roundTrip = fory.deserialize(peerBytes);
      Assert.assertTrue(roundTrip instanceof OptionalHolder);
      Assert.assertEquals(roundTrip, holder);
    }
  }

  @Test
  public void testAnyRoundTrip() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
    AnyExampleForyRegistration.register(fory);

    AnyHolder holder = buildAnyHolder();
    byte[] bytes = fory.serialize(holder);
    Object decoded = fory.deserialize(bytes);

    Assert.assertTrue(decoded instanceof AnyHolder);
    Assert.assertEquals(decoded, holder);
  }

  @Test
  public void testTreeRoundTrip() throws Exception {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withRefTracking(true).build();
    TreeForyRegistration.register(fory);

    TreeNode tree = buildTree();
    byte[] bytes = fory.serialize(tree);
    Object decoded = fory.deserialize(bytes);

    Assert.assertTrue(decoded instanceof TreeNode);
    TreeNode roundTrip = (TreeNode) decoded;
    assertTree(roundTrip);

    for (String peer : resolvePeers()) {
      Path dataFile = Files.createTempFile("idl-tree-" + peer + "-", ".bin");
      dataFile.toFile().deleteOnExit();
      Files.write(dataFile, bytes);

      Map<String, String> env = new HashMap<>();
      env.put("DATA_FILE_TREE", dataFile.toAbsolutePath().toString());
      PeerCommand command = buildPeerCommand(peer, env);
      runPeer(command, peer);

      byte[] peerBytes = Files.readAllBytes(dataFile);
      Object peerRoundTrip = fory.deserialize(peerBytes);
      Assert.assertTrue(peerRoundTrip instanceof TreeNode);
      assertTree((TreeNode) peerRoundTrip);
    }
  }

  @Test
  public void testGraphRoundTrip() throws Exception {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withRefTracking(true).build();
    GraphForyRegistration.register(fory);

    Graph graph = buildGraph();
    byte[] bytes = fory.serialize(graph);
    Object decoded = fory.deserialize(bytes);

    Assert.assertTrue(decoded instanceof Graph);
    Graph roundTrip = (Graph) decoded;
    assertGraph(roundTrip);

    for (String peer : resolvePeers()) {
      Path dataFile = Files.createTempFile("idl-graph-" + peer + "-", ".bin");
      dataFile.toFile().deleteOnExit();
      Files.write(dataFile, bytes);

      Map<String, String> env = new HashMap<>();
      env.put("DATA_FILE_GRAPH", dataFile.toAbsolutePath().toString());
      PeerCommand command = buildPeerCommand(peer, env);
      runPeer(command, peer);

      byte[] peerBytes = Files.readAllBytes(dataFile);
      Object peerRoundTrip = fory.deserialize(peerBytes);
      Assert.assertTrue(peerRoundTrip instanceof Graph);
      assertGraph((Graph) peerRoundTrip);
    }
  }

  @Test
  public void testFlatbuffersRoundTrip() throws Exception {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
    MonsterForyRegistration.register(fory);
    ComplexFbsForyRegistration.register(fory);

    Monster monster = buildMonster();
    byte[] monsterBytes = fory.serialize(monster);
    Object monsterDecoded = fory.deserialize(monsterBytes);
    Assert.assertTrue(monsterDecoded instanceof Monster);
    Assert.assertEquals(monsterDecoded, monster);

    Container container = buildContainer();
    byte[] containerBytes = fory.serialize(container);
    Object containerDecoded = fory.deserialize(containerBytes);
    Assert.assertTrue(containerDecoded instanceof Container);
    Assert.assertEquals(containerDecoded, container);

    for (String peer : resolvePeers()) {
      Path monsterFile =
          Files.createTempFile("idl-flatbuffers-monster-" + peer + "-", ".bin");
      monsterFile.toFile().deleteOnExit();
      Files.write(monsterFile, monsterBytes);

      Path containerFile =
          Files.createTempFile("idl-flatbuffers-test2-" + peer + "-", ".bin");
      containerFile.toFile().deleteOnExit();
      Files.write(containerFile, containerBytes);

      Map<String, String> env = new HashMap<>();
      env.put("DATA_FILE_FLATBUFFERS_MONSTER", monsterFile.toAbsolutePath().toString());
      env.put("DATA_FILE_FLATBUFFERS_TEST2", containerFile.toAbsolutePath().toString());
      PeerCommand command = buildPeerCommand(peer, env);
      runPeer(command, peer);

      byte[] peerMonsterBytes = Files.readAllBytes(monsterFile);
      Object monsterRoundTrip = fory.deserialize(peerMonsterBytes);
      Assert.assertTrue(monsterRoundTrip instanceof Monster);
      Assert.assertEquals(monsterRoundTrip, monster);

      byte[] peerContainerBytes = Files.readAllBytes(containerFile);
      Object containerRoundTrip = fory.deserialize(peerContainerBytes);
      Assert.assertTrue(containerRoundTrip instanceof Container);
      Assert.assertEquals(containerRoundTrip, container);
    }
  }

  private List<String> resolvePeers() {
    String peerEnv = System.getenv("IDL_PEER_LANG");
    if (peerEnv == null || peerEnv.trim().isEmpty()) {
      return Collections.emptyList();
    }
    List<String> peers =
        Arrays.stream(peerEnv.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toList());
    if (peers.contains("all")) {
      return Arrays.asList("python", "go", "rust", "cpp");
    }
    return peers;
  }

  private PeerCommand buildPeerCommand(String peer, Map<String, String> environment) {
    Path repoRoot = repoRoot();
    Path idlRoot = repoRoot.resolve("integration_tests").resolve("idl_tests");
    Path workDir = idlRoot;
    List<String> command;
    PeerCommand peerCommand = new PeerCommand();
    peerCommand.environment.putAll(environment);

    switch (peer) {
      case "python":
        command = Arrays.asList("python", "-m", "idl_tests.roundtrip");
        String pythonPath =
            idlRoot.resolve("python").resolve("src")
                + File.pathSeparator
                + repoRoot.resolve("python");
        String existingPythonPath = System.getenv("PYTHONPATH");
        if (existingPythonPath != null && !existingPythonPath.isEmpty()) {
          pythonPath = pythonPath + File.pathSeparator + existingPythonPath;
        }
        peerCommand.environment.put("PYTHONPATH", pythonPath);
        peerCommand.environment.put("ENABLE_FORY_CYTHON_SERIALIZATION", "0");
        break;
      case "go":
        workDir = idlRoot.resolve("go");
        command = Arrays.asList("go", "test", "-run", "TestAddressBookRoundTrip", "-v");
        break;
      case "rust":
        workDir = idlRoot.resolve("rust");
        command = Arrays.asList("cargo", "test", "--test", "idl_roundtrip");
        break;
      case "cpp":
        command = Collections.singletonList("./cpp/run.sh");
        break;
      default:
        throw new IllegalArgumentException("Unknown peer language: " + peer);
    }

    peerCommand.command = command;
    peerCommand.workDir = workDir;
    return peerCommand;
  }

  private void runPeer(PeerCommand command, String peer) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command.command);
    builder.directory(command.workDir.toFile());
    builder.environment().putAll(command.environment);

    Process process = builder.start();
    boolean finished = process.waitFor(180, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      Assert.fail("Peer process timed out for " + peer);
    }

    int exitCode = process.exitValue();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    if (exitCode != 0) {
      Assert.fail(
          "Peer process failed for "
              + peer
              + " with exit code "
              + exitCode
              + "\nstdout:\n"
              + stdout
              + "\nstderr:\n"
              + stderr);
    }
  }

  private Path repoRoot() {
    Path moduleDir = java.nio.file.Paths.get("").toAbsolutePath();
    return moduleDir.getParent().getParent().getParent();
  }

  private AddressBook buildAddressBook() {
    PhoneNumber mobile = new PhoneNumber();
    mobile.setNumber("555-0100");
    mobile.setPhoneType(PhoneType.MOBILE);

    PhoneNumber work = new PhoneNumber();
    work.setNumber("555-0111");
    work.setPhoneType(PhoneType.WORK);

    List<PhoneNumber> phones = new ArrayList<>();
    phones.add(mobile);
    phones.add(work);

    List<String> tags = Arrays.asList("friend", "colleague");

    Map<String, Integer> scores = new HashMap<>();
    scores.put("math", 100);
    scores.put("science", 98);

    Person person = new Person();
    person.setName("Alice");
    person.setId(123);
    person.setEmail("alice@example.com");
    person.setTags(tags);
    person.setScores(scores);
    person.setSalary(120000.5);
    person.setPhones(phones);
    Dog dog = new Dog();
    dog.setName("Rex");
    dog.setBarkVolume(5);
    Animal pet = Animal.ofDog(dog);
    Cat cat = new Cat();
    cat.setName("Mimi");
    cat.setLives(9);
    pet.setCat(cat);
    person.setPet(pet);

    AddressBook book = new AddressBook();
    List<Person> people = new ArrayList<>();
    people.add(person);
    book.setPeople(people);

    Map<String, Person> peopleByName = new HashMap<>();
    peopleByName.put(person.getName(), person);
    book.setPeopleByName(peopleByName);

    return book;
  }

  private PrimitiveTypes buildPrimitiveTypes() {
    PrimitiveTypes types = new PrimitiveTypes();
    types.setBoolValue(true);
    types.setInt8Value((byte) 12);
    types.setInt16Value((short) 1234);
    types.setInt32Value(-123456);
    types.setVarint32Value(-12345);
    types.setInt64Value(-123456789L);
    types.setVarint64Value(-987654321L);
    types.setTaggedInt64Value(123456789L);
    types.setUint8Value((byte) 200);
    types.setUint16Value((short) 60000);
    types.setUint32Value(1234567890);
    types.setVarUint32Value(1234567890);
    types.setUint64Value(9876543210L);
    types.setVarUint64Value(12345678901L);
    types.setTaggedUint64Value(2222222222L);
    types.setFloat32Value(2.5f);
    types.setFloat64Value(3.5d);
    PrimitiveTypes.Contact contact = PrimitiveTypes.Contact.ofEmail("alice@example.com");
    contact.setPhone(12345);
    types.setContact(contact);
    return types;
  }

  private Monster buildMonster() {
    Vec3 pos = new Vec3();
    pos.setX(1.0f);
    pos.setY(2.0f);
    pos.setZ(3.0f);

    Monster monster = new Monster();
    monster.setPos(pos);
    monster.setMana((short) 200);
    monster.setHp((short) 80);
    monster.setName("Orc");
    monster.setFriendly(true);
    monster.setInventory(new byte[] {(byte) 1, (byte) 2, (byte) 3});
    monster.setColor(Color.Blue);
    return monster;
  }

  private OptionalHolder buildOptionalHolder() {
    AllOptionalTypes allTypes = new AllOptionalTypes();
    allTypes.setBoolValue(true);
    allTypes.setInt8Value((byte) 12);
    allTypes.setInt16Value((short) 1234);
    allTypes.setInt32Value(-123456);
    allTypes.setFixedInt32Value(-123456);
    allTypes.setVarint32Value(-12345);
    allTypes.setInt64Value(-123456789L);
    allTypes.setFixedInt64Value(-123456789L);
    allTypes.setVarint64Value(-987654321L);
    allTypes.setTaggedInt64Value(123456789L);
    allTypes.setUint8Value((byte) 200);
    allTypes.setUint16Value((short) 60000);
    allTypes.setUint32Value(1234567890);
    allTypes.setFixedUint32Value(1234567890);
    allTypes.setVarUint32Value(1234567890);
    allTypes.setUint64Value(9876543210L);
    allTypes.setFixedUint64Value(9876543210L);
    allTypes.setVarUint64Value(12345678901L);
    allTypes.setTaggedUint64Value(2222222222L);
    allTypes.setFloat32Value(2.5f);
    allTypes.setFloat64Value(3.5);
    allTypes.setStringValue("optional");
    allTypes.setBytesValue(new byte[] {1, 2, 3});
    allTypes.setDateValue(LocalDate.of(2024, 1, 2));
    allTypes.setTimestampValue(Instant.parse("2024-01-02T03:04:05Z"));
    allTypes.setInt32List(new int[] {1, 2, 3});
    allTypes.setStringList(Arrays.asList("alpha", "beta"));
    Map<String, Long> int64Map = new HashMap<>();
    int64Map.put("alpha", 10L);
    int64Map.put("beta", 20L);
    allTypes.setInt64Map(int64Map);

    OptionalHolder holder = new OptionalHolder();
    holder.setAllTypes(allTypes);
    holder.setChoice(OptionalUnion.ofNote("optional"));
    return holder;
  }

  private AnyHolder buildAnyHolder() {
    AnyInner inner = new AnyInner();
    inner.setName("inner");

    AnyHolder holder = new AnyHolder();
    holder.setBoolValue(Boolean.TRUE);
    holder.setStringValue("hello");
    holder.setDateValue(LocalDate.of(2024, 1, 2));
    holder.setTimestampValue(Instant.ofEpochSecond(1704164645L));
    holder.setMessageValue(inner);
    holder.setUnionValue(AnyUnion.ofText("union"));
    holder.setListValue(Arrays.asList("alpha", "beta"));
    holder.setMapValue(new HashMap<>(Map.of("k1", "v1", "k2", "v2")));
    return holder;
  }

  private TreeNode buildTree() {
    TreeNode childA = new TreeNode();
    childA.setId("child-a");
    childA.setName("child-a");
    childA.setChildren(Collections.emptyList());

    TreeNode childB = new TreeNode();
    childB.setId("child-b");
    childB.setName("child-b");
    childB.setChildren(Collections.emptyList());

    childA.setParent(childB);
    childB.setParent(childA);

    TreeNode root = new TreeNode();
    root.setId("root");
    root.setName("root");
    root.setChildren(Arrays.asList(childA, childA, childB));
    return root;
  }

  private void assertTree(TreeNode root) {
    List<TreeNode> children = root.getChildren();
    Assert.assertNotNull(children);
    Assert.assertEquals(children.size(), 3);
    Assert.assertSame(children.get(0), children.get(1));
    Assert.assertNotSame(children.get(0), children.get(2));
    Assert.assertSame(children.get(0).getParent(), children.get(2));
    Assert.assertSame(children.get(2).getParent(), children.get(0));
  }

  private Graph buildGraph() {
    Node nodeA = new Node();
    nodeA.setId("node-a");
    Node nodeB = new Node();
    nodeB.setId("node-b");

    Edge edge = new Edge();
    edge.setId("edge-1");
    edge.setWeight(1.5f);
    edge.setFrom(nodeA);
    edge.setTo(nodeB);

    nodeA.setOutEdges(Collections.singletonList(edge));
    nodeA.setInEdges(Collections.singletonList(edge));
    nodeB.setInEdges(Collections.singletonList(edge));
    nodeB.setOutEdges(Collections.emptyList());

    Graph graph = new Graph();
    graph.setNodes(Arrays.asList(nodeA, nodeB));
    graph.setEdges(Collections.singletonList(edge));
    return graph;
  }

  private void assertGraph(Graph graph) {
    Assert.assertNotNull(graph.getNodes());
    Assert.assertNotNull(graph.getEdges());
    Assert.assertEquals(graph.getNodes().size(), 2);
    Assert.assertEquals(graph.getEdges().size(), 1);
    Node nodeA = graph.getNodes().get(0);
    Node nodeB = graph.getNodes().get(1);
    Edge edge = graph.getEdges().get(0);
    Assert.assertSame(edge, nodeA.getOutEdges().get(0));
    Assert.assertSame(edge, nodeA.getInEdges().get(0));
    Assert.assertSame(edge.getFrom(), nodeA);
    Assert.assertSame(edge.getTo(), nodeB);
  }

  private Container buildContainer() {
    ScalarPack pack = new ScalarPack();
    pack.setB((byte) -8);
    pack.setUb((byte) 200);
    pack.setS((short) -1234);
    pack.setUs((short) 40000);
    pack.setI(-123456);
    pack.setUi(123456);
    pack.setL(-123456789L);
    pack.setUl(987654321L);
    pack.setF(1.5f);
    pack.setD(2.5d);
    pack.setOk(true);

    Container container = new Container();
    container.setId(9876543210L);
    container.setStatus(Status.STARTED);
    container.setBytes(new byte[] {(byte) 1, (byte) 2, (byte) 3});
    container.setNumbers(new int[] {10, 20, 30});
    container.setScalars(pack);
    container.setNames(Arrays.asList("alpha", "beta"));
    container.setFlags(new boolean[] {true, false});
    Note note = new Note();
    note.setText("alpha");
    Payload payload = Payload.ofNote(note);
    Metric metric = new Metric();
    metric.setValue(42.0d);
    payload.setMetric(metric);
    container.setPayload(payload);
    return container;
  }

  private static final class PeerCommand {
    private List<String> command;
    private Path workDir;
    private final Map<String, String> environment = new HashMap<>();
  }
}
