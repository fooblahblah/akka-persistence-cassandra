package akka.persistence.cassandra.snapshot

import java.lang.{ Long => JLong }
import java.nio.ByteBuffer

import scala.collection.JavaConverters._

import akka.persistence._
import akka.persistence.SnapshotProtocol._
import akka.persistence.cassandra.CassandraCleanup
import akka.persistence.snapshot.SnapshotStoreSpec
import akka.testkit.TestProbe

import com.datastax.driver.core.Cluster
import com.typesafe.config.ConfigFactory

class CassandraSnapshotStoreSpec extends SnapshotStoreSpec with CassandraStatements with CassandraCleanup {
  lazy val config = ConfigFactory.parseString(
    """
      |akka.persistence.journal.plugin = "cassandra-journal"
      |akka.persistence.snapshot-store.plugin = "cassandra-snapshot-store"
      |cassandra-snapshot-store.max-metadata-result-size = 2
    """.stripMargin)

  val storeConfig = system.settings.config.getConfig("cassandra-snapshot-store")

  val keyspace = storeConfig.getString("keyspace")
  val table = storeConfig.getString("table")

  val cluster = Cluster.builder.addContactPoints(storeConfig.getStringList("contact-points").asScala: _*).build
  val session = cluster.connect()

  override def afterAll(): Unit = {
    session.close()
    cluster.close()
    super.afterAll()
  }

  "A Cassandra snapshot store" must {
    "make up to 3 snapshot loading attempts" in {
      val probe = TestProbe()

      // load most recent snapshot
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria.Latest, Long.MaxValue), probe.ref)

      // get most recent snapshot
      val expected = probe.expectMsgPF() { case LoadSnapshotResult(Some(snapshot), _) => snapshot }

      // write two more snapshots that cannot be de-serialized.
      session.execute(writeSnapshot, pid, 17: JLong, 123: JLong, ByteBuffer.wrap("fail-1".getBytes("UTF-8")))
      session.execute(writeSnapshot, pid, 18: JLong, 124: JLong, ByteBuffer.wrap("fail-2".getBytes("UTF-8")))

      // load most recent snapshot, first two attempts will fail ...
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria.Latest, Long.MaxValue), probe.ref)

      // third attempt succeeds
      probe.expectMsg(LoadSnapshotResult(Some(expected), Long.MaxValue))
    }
    "give up after 3 snapshot loading attempts" in {
      val probe = TestProbe()

      // load most recent snapshot
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria.Latest, Long.MaxValue), probe.ref)

      // wait for most recent snapshot
      probe.expectMsgPF() { case LoadSnapshotResult(Some(snapshot), _) => snapshot }

      // write three more snapshots that cannot be de-serialized.
      session.execute(writeSnapshot, pid, 17: JLong, 123: JLong, ByteBuffer.wrap("fail-1".getBytes("UTF-8")))
      session.execute(writeSnapshot, pid, 18: JLong, 124: JLong, ByteBuffer.wrap("fail-2".getBytes("UTF-8")))
      session.execute(writeSnapshot, pid, 19: JLong, 125: JLong, ByteBuffer.wrap("fail-3".getBytes("UTF-8")))

      // load most recent snapshot, first three attempts will fail ...
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria.Latest, Long.MaxValue), probe.ref)

      // no 4th attempt has been made
      probe.expectMsg(LoadSnapshotResult(None, Long.MaxValue))
    }
  }
}
