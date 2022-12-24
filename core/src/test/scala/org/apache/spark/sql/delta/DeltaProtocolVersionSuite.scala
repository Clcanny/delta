/*
 * Copyright (2021) The Delta Lake Project Authors.
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

package org.apache.spark.sql.delta

// scalastyle:off import.ordering.noEmptyLine
import java.io.File
import java.util.Locale

import org.apache.spark.sql.delta.DeltaOperations.ManualUpdate
import org.apache.spark.sql.delta.actions._
import org.apache.spark.sql.delta.actions.TableFeatureProtocolUtils._
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.test.DeltaSQLCommandTest
import org.apache.spark.sql.delta.util.FileNames.deltaFile

import org.apache.spark.SparkConf
import org.apache.spark.sql.{AnalysisException, QueryTest, SaveMode}
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StructType

trait DeltaProtocolVersionSuiteBase extends QueryTest
  with SharedSparkSession  with DeltaSQLCommandTest {

  // `.schema` generates NOT NULL columns which requires writer protocol 2. We convert all to
  // NULLable to avoid silent writer protocol version bump.
  private lazy val testTableSchema = spark.range(1).schema.asNullable

  // This is solely a test hook. Users cannot create new Delta tables with protocol lower than
  // that of their current version.
  protected def createTableWithProtocol(
      protocol: Protocol,
      path: File,
      schema: StructType = testTableSchema): DeltaLog = {
    val log = DeltaLog.forTable(spark, path)
    log.ensureLogDirectoryExist()
    log.store.write(
      deltaFile(log.logPath, 0),
      Iterator(Metadata(schemaString = schema.json).json, protocol.json),
      overwrite = false,
      log.newDeltaHadoopConf())
    log.update()
    log
  }

  test("upgrade to current version") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(1, 1), path)
      assert(log.snapshot.protocol === Protocol(1, 1))
      log.upgradeProtocol(Action.supportedProtocolVersion())
      assert(log.snapshot.protocol === Action.supportedProtocolVersion())
    }
  }

  test("upgrade to a version with DeltaTable API") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(0, 0), path)
      assert(log.snapshot.protocol === Protocol(0, 0))
      val table = io.delta.tables.DeltaTable.forPath(spark, path.getCanonicalPath)
      table.upgradeTableProtocol(1, 1)
      assert(log.snapshot.protocol === Protocol(1, 1))
      table.upgradeTableProtocol(1, 2)
      assert(log.snapshot.protocol === Protocol(1, 2))
      table.upgradeTableProtocol(1, 3)
      assert(log.snapshot.protocol === Protocol(1, 3))
      intercept[DeltaTableFeatureException] {
        table.upgradeTableProtocol(
          TABLE_FEATURES_MIN_READER_VERSION,
          TABLE_FEATURES_MIN_WRITER_VERSION - 1)
      }
    }
  }

  test("upgrade to support table features - no feature") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(1, 1), path)
      assert(log.snapshot.protocol === Protocol(1, 1))
      val table = io.delta.tables.DeltaTable.forPath(spark, path.getCanonicalPath)
      table.upgradeTableProtocol(1, TABLE_FEATURES_MIN_WRITER_VERSION)
      assert(
        log.snapshot.protocol === Protocol(
          minReaderVersion = 1,
          minWriterVersion = TABLE_FEATURES_MIN_WRITER_VERSION,
          readerFeatures = None,
          writerFeatures = Some(Set())))
      table.upgradeTableProtocol(
        TABLE_FEATURES_MIN_READER_VERSION,
        TABLE_FEATURES_MIN_WRITER_VERSION)
      assert(
        log.snapshot.protocol === Protocol(
          minReaderVersion = TABLE_FEATURES_MIN_READER_VERSION,
          minWriterVersion = TABLE_FEATURES_MIN_WRITER_VERSION,
          readerFeatures = Some(Set()),
          writerFeatures = Some(Set())))
    }
  }

  test("upgrade to support table features - writer-only feature") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(1, 2), path)
      assert(log.snapshot.protocol === Protocol(1, 2))
      val table = io.delta.tables.DeltaTable.forPath(spark, path.getCanonicalPath)
      table.upgradeTableProtocol(1, TABLE_FEATURES_MIN_WRITER_VERSION)
      assert(
        log.snapshot.protocol === Protocol(
          minReaderVersion = 1,
          minWriterVersion = TABLE_FEATURES_MIN_WRITER_VERSION,
          readerFeatures = None,
          writerFeatures = Some(Set(AppendOnlyTableFeature.toDescriptor))))
      table.upgradeTableProtocol(
        TABLE_FEATURES_MIN_READER_VERSION,
        TABLE_FEATURES_MIN_WRITER_VERSION)
      assert(
        log.snapshot.protocol === Protocol(
          minReaderVersion = TABLE_FEATURES_MIN_READER_VERSION,
          minWriterVersion = TABLE_FEATURES_MIN_WRITER_VERSION,
          readerFeatures = Some(Set()),
          writerFeatures = Some(Set(AppendOnlyTableFeature.toDescriptor))))
    }
  }

  test("upgrade to support table features - many features") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(2, 6), path)
      assert(log.snapshot.protocol === Protocol(2, 6))
      val table = io.delta.tables.DeltaTable.forPath(spark, path.getCanonicalPath)
      table.upgradeTableProtocol(2, TABLE_FEATURES_MIN_WRITER_VERSION)
      assert(
        log.snapshot.protocol === Protocol(
          minReaderVersion = 2,
          minWriterVersion = TABLE_FEATURES_MIN_WRITER_VERSION,
          readerFeatures = None,
          writerFeatures = Some(
            Set(AppendOnlyTableFeature, TestLegacyWriterFeature, TestLegacyReaderWriterFeature)
              .map(_.toDescriptor))))
      spark.sql(
        s"ALTER TABLE delta.`${path.getPath}` SET TBLPROPERTIES (" +
          s"  delta.feature.${TestWriterFeature.name}='enabled'" +
          s")")
      table.upgradeTableProtocol(
        TABLE_FEATURES_MIN_READER_VERSION,
        TABLE_FEATURES_MIN_WRITER_VERSION)
      assert(
        log.snapshot.protocol === Protocol(
          minReaderVersion = TABLE_FEATURES_MIN_READER_VERSION,
          minWriterVersion = TABLE_FEATURES_MIN_WRITER_VERSION,
          readerFeatures = Some(Set()),
          writerFeatures = Some(
            Set(
              AppendOnlyTableFeature,
              TestLegacyWriterFeature,
              TestLegacyReaderWriterFeature,
              TestWriterFeature)
              .map(_.toDescriptor))))
    }
  }

  test("protocol upgrade using SQL API") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(1, 2), path)

      assert(log.snapshot.protocol === Protocol(1, 2))
      sql(
        s"ALTER TABLE delta.`${path.getCanonicalPath}` " +
          "SET TBLPROPERTIES (delta.minWriterVersion = 3)")
      assert(log.snapshot.protocol === Protocol(1, 3))
      assertPropertiesAndShowTblProperties(log)
      sql(s"ALTER TABLE delta.`${path.getCanonicalPath}` " +
        s"SET TBLPROPERTIES (delta.minWriterVersion=${TABLE_FEATURES_MIN_WRITER_VERSION})")
      assert(
        log.snapshot.protocol === Protocol(
          minReaderVersion = 1,
          minWriterVersion = TABLE_FEATURES_MIN_WRITER_VERSION,
          readerFeatures = None,
          writerFeatures = Some(Set(AppendOnlyTableFeature.toDescriptor))))
      assertPropertiesAndShowTblProperties(log, tableHasFeatures = true)
      sql(s"ALTER TABLE delta.`${path.getCanonicalPath}` " +
        s"SET TBLPROPERTIES (delta.minReaderVersion=${TABLE_FEATURES_MIN_READER_VERSION})")
      assert(
        log.snapshot.protocol === Protocol(
          minReaderVersion = TABLE_FEATURES_MIN_READER_VERSION,
          minWriterVersion = TABLE_FEATURES_MIN_WRITER_VERSION,
          readerFeatures = Some(Set()),
          writerFeatures = Some(Set(AppendOnlyTableFeature.toDescriptor))))
      assertPropertiesAndShowTblProperties(log, tableHasFeatures = true)
    }
  }

  test("overwrite keeps the same protocol version") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(0, 0), path)
      spark.range(1)
        .write
        .format("delta")
        .mode("overwrite")
        .save(path.getCanonicalPath)
      log.update()
      assert(log.snapshot.protocol === Protocol(0, 0))
    }
  }

  test("overwrite keeps the same table properties") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(0, 0), path)
      spark.sql(
        s"ALTER TABLE delta.`${path.getCanonicalPath}` SET TBLPROPERTIES ('myProp'='true')")
      spark
        .range(1)
        .write
        .format("delta")
        .option("anotherProp", "true")
        .mode("overwrite")
        .save(path.getCanonicalPath)
      log.update()
      assert(log.snapshot.metadata.configuration.size === 1)
      assert(log.snapshot.metadata.configuration("myProp") === "true")
    }
  }

  test("overwrite keeps the same protocol version and features") {
    withTempDir { path =>
      val protocol = Protocol(0, TABLE_FEATURES_MIN_WRITER_VERSION)
        .withFeature(AppendOnlyTableFeature)
      val log = createTableWithProtocol(protocol, path)
      spark
        .range(1)
        .write
        .format("delta")
        .mode("overwrite")
        .save(path.getCanonicalPath)
      log.update()
      assert(log.snapshot.protocol === protocol)
    }
  }

  test("overwrite with additional configs keeps the same protocol version and features") {
    withTempDir { path =>
      val protocol = Protocol(1, TABLE_FEATURES_MIN_WRITER_VERSION)
        .withFeature(AppendOnlyTableFeature)
      val log = createTableWithProtocol(protocol, path)
      spark
        .range(1)
        .write
        .format("delta")
        .option("delta.feature.testWriter", "enabled")
        .option("delta.feature.testReaderWriter", "enabled")
        .mode("overwrite")
        .save(path.getCanonicalPath)
      log.update()
      assert(log.snapshot.protocol === protocol)
    }
  }

  test("overwrite with additional session defaults keeps the same protocol version and features") {
    withTempDir { path =>
      val protocol = Protocol(1, TABLE_FEATURES_MIN_WRITER_VERSION)
        .withFeature(AppendOnlyTableFeature)
      val log = createTableWithProtocol(protocol, path)
      withSQLConf(
        s"$DEFAULT_FEATURE_PROP_PREFIX${TestLegacyWriterFeature.name}" -> "enabled") {
        spark
          .range(1)
          .write
          .format("delta")
          .option("delta.feature.testWriter", "enabled")
          .option("delta.feature.testReaderWriter", "enabled")
          .mode("overwrite")
          .save(path.getCanonicalPath)
      }
      log.update()
      assert(log.snapshot.protocol === protocol)
    }
  }

  test("access with protocol too high") {
    withTempDir { path =>
      val log = DeltaLog.forTable(spark, path)
      log.ensureLogDirectoryExist()
      log.store.write(
        deltaFile(log.logPath, 0),
        Iterator(Metadata().json, Protocol(Integer.MAX_VALUE, Integer.MAX_VALUE).json),
        overwrite = false,
        log.newDeltaHadoopConf())
      intercept[InvalidProtocolVersionException] {
        spark.range(1).write.format("delta").save(path.getCanonicalPath)
      }
    }
  }

  test("can't downgrade") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(1, 3), path)
      assert(log.snapshot.protocol === Protocol(1, 3))
      val e1 = intercept[ProtocolDowngradeException] {
        log.upgradeProtocol(Protocol(1, 2))
      }
      val e2 = intercept[ProtocolDowngradeException] {
        val table = io.delta.tables.DeltaTable.forPath(spark, path.getCanonicalPath)
        table.upgradeTableProtocol(1, 2)
      }
      val e3 = intercept[ProtocolDowngradeException] {
        sql(s"ALTER TABLE delta.`${path.getCanonicalPath}` " +
          "SET TBLPROPERTIES (delta.minWriterVersion = 2)")
      }
      assert(e1.getMessage === e2.getMessage)
      assert(e1.getMessage === e3.getMessage)
      assert(e1.getMessage.contains("cannot be downgraded from (1,3) to (1,2)"))
    }
  }

  test("concurrent upgrade") {
    withTempDir { path =>
      val newProtocol = Protocol()
      val log = createTableWithProtocol(Protocol(0, 0), path)

      // We have to copy out the internals of upgradeProtocol to induce the concurrency.
      val txn = log.startTransaction()
      log.upgradeProtocol(newProtocol)
      intercept[ProtocolChangedException] {
        txn.commit(Seq(newProtocol), DeltaOperations.UpgradeProtocol(newProtocol))
      }
    }
  }

  test("incompatible protocol change during the transaction") {
    for (incompatibleProtocol <- Seq(
      Protocol(minReaderVersion = Int.MaxValue),
      Protocol(minWriterVersion = Int.MaxValue),
      Protocol(minReaderVersion = Int.MaxValue, minWriterVersion = Int.MaxValue)
    )) {
      withTempDir { path =>
        spark.range(0).write.format("delta").save(path.getCanonicalPath)
        val deltaLog = DeltaLog.forTable(spark, path)
        val hadoopConf = deltaLog.newDeltaHadoopConf()
        val txn = deltaLog.startTransaction()
        val currentVersion = txn.snapshot.version
        deltaLog.store.write(
          deltaFile(deltaLog.logPath, currentVersion + 1),
          Iterator(incompatibleProtocol.json),
          overwrite = false,
          hadoopConf)

        // Should detect the above incompatible protocol change and fail
        intercept[InvalidProtocolVersionException] {
          txn.commit(AddFile("test", Map.empty, 1, 1, dataChange = true) :: Nil, ManualUpdate)
        }
        // Make sure we didn't commit anything
        val p = deltaFile(deltaLog.logPath, currentVersion + 2)
        assert(
          !p.getFileSystem(hadoopConf).exists(p),
          s"$p should not be committed")
      }
    }
  }

  import testImplicits._
  /** Creates a Delta table and checks the expected protocol version */
  private def testCreation(tableName: String, writerVersion: Int)(fn: String => Unit): Unit = {
    withTempDir { dir =>
      withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> "1") {
        withTable(tableName) {
          fn(dir.getCanonicalPath)

          val deltaLog = DeltaLog.forTable(spark, dir)
          assert(deltaLog.snapshot.version === 0, "did not create a Delta table")
          assert(deltaLog.snapshot.protocol.minWriterVersion === writerVersion)
          assert(deltaLog.snapshot.protocol.minReaderVersion === 1)
        }
      }
    }
  }

  test("can create table using the latest protocol with conf") {
    val readerVersion = Action.supportedProtocolVersion().minReaderVersion
    val writerVersion = Action.supportedProtocolVersion().minWriterVersion
    withTempDir { dir =>
      withSQLConf(
        DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> writerVersion.toString,
        DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_READER_VERSION.key -> readerVersion.toString) {
        sql(s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta")
        val deltaLog = DeltaLog.forTable(spark, dir)
        assert(deltaLog.snapshot.protocol ===
               Action.supportedProtocolVersion(withAllFeatures = false))
      }
    }
  }

  test("can create table using features configured in session") {
    val readerVersion = Action.supportedProtocolVersion().minReaderVersion
    val writerVersion = Action.supportedProtocolVersion().minWriterVersion
    withTempDir { dir =>
      withSQLConf(
        DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> writerVersion.toString,
        DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_READER_VERSION.key -> readerVersion.toString,
        s"$DEFAULT_FEATURE_PROP_PREFIX${AppendOnlyTableFeature.name}" -> "enabled",
        s"$DEFAULT_FEATURE_PROP_PREFIX${TestReaderWriterFeature.name}" -> "enabled") {
        sql(s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta")
        val deltaLog = DeltaLog.forTable(spark, dir)
        assert(
          deltaLog.snapshot.protocol ===
            Action
              .supportedProtocolVersion(withAllFeatures = false)
              .withFeatures(Set(AppendOnlyTableFeature, TestReaderWriterFeature)))
      }
    }
  }

  test("can create table using features configured in table properties and session") {
    withTempDir { dir =>
      withSQLConf(
        s"$DEFAULT_FEATURE_PROP_PREFIX${TestWriterFeature.name}" -> "enabled") {
        sql(
          s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
            "TBLPROPERTIES (" +
            s"  delta.feature.${AppendOnlyTableFeature.name}='enabled'," +
            s"  delta.feature.${TestLegacyReaderWriterFeature.name}='enabled'" +
            s")")
        val deltaLog = DeltaLog.forTable(spark, dir)
        assert(
          deltaLog.snapshot.protocol.minReaderVersion ===
            TestLegacyReaderWriterFeature.minReaderVersion,
          "reader protocol version should not support table features because we use " +
            "only a legacy reader-writer feature.")
        assert(
          deltaLog.snapshot.protocol.minWriterVersion ===
            TABLE_FEATURES_MIN_WRITER_VERSION,
          "writer protocol version must support table features because a native feature" +
            "is enabled.")
        assert(
          deltaLog.snapshot.protocol.readerAndWriterFeatureDescriptors === Set(
            AppendOnlyTableFeature,
            TestLegacyReaderWriterFeature,
            TestWriterFeature).map(_.toDescriptor))
      }
    }
  }

  test("creating a new table with default protocol") {
    val tableName = "delta_test"

    def testTableCreation(fn: String => Unit): Unit = {
      testCreation(tableName, 1) { dir =>
        fn(dir)
      }
    }

    testTableCreation { dir => spark.range(10).write.format("delta").save(dir) }
    testTableCreation { dir =>
      spark.range(10).write.format("delta").option("path", dir).saveAsTable(tableName)
    }
    testTableCreation { dir =>
      spark.range(10).writeTo(tableName).using("delta").tableProperty("location", dir).create()
    }
    testTableCreation { dir =>
      sql(s"CREATE TABLE $tableName (id bigint) USING delta LOCATION '$dir'")
    }
    testTableCreation { dir =>
      sql(s"CREATE TABLE $tableName USING delta LOCATION '$dir' AS SELECT * FROM range(10)")
    }
    testTableCreation { dir =>
      val stream = MemoryStream[Int]
      stream.addData(1 to 10)
      val q = stream.toDF().writeStream.format("delta")
        .option("checkpointLocation", new File(dir, "_checkpoint").getCanonicalPath)
        .start(dir)
      q.processAllAvailable()
      q.stop()
    }

    testTableCreation { dir =>
      spark.range(10).write.mode("append").parquet(dir)
      sql(s"CONVERT TO DELTA parquet.`$dir`")
    }
  }

  test(
    "creating a new table with default protocol - requiring more recent protocol version") {
    val tableName = "delta_test"
    def testTableCreation(fn: String => Unit): Unit = testCreation(tableName, 2)(fn)

    testTableCreation { dir =>
      spark.range(10).writeTo(tableName).using("delta")
        .tableProperty("location", dir)
        .tableProperty("delta.appendOnly", "true")
        .create()
    }
    testTableCreation { dir =>
      sql(s"CREATE TABLE $tableName (id bigint) USING delta LOCATION '$dir' " +
        s"TBLPROPERTIES (delta.appendOnly = 'true')")
    }
    testTableCreation { dir =>
      sql(s"CREATE TABLE $tableName USING delta TBLPROPERTIES (delta.appendOnly = 'true') " +
        s"LOCATION '$dir' AS SELECT * FROM range(10)")
    }
    testTableCreation { dir =>
      sql(s"CREATE TABLE $tableName (id bigint NOT NULL) USING delta LOCATION '$dir'")
    }

    for (key <- List("spark.delta.properties.defaults.appendOnly",
                     "spark.databricks.delta.properties.defaults.appendOnly")) {
      withSQLConf(key -> "true") {
        testTableCreation { dir => spark.range(10).write.format("delta").save(dir) }
        testTableCreation { dir =>
          spark.range(10).write.format("delta").option("path", dir).saveAsTable(tableName)
        }
        testTableCreation { dir =>
          spark.range(10).writeTo(tableName).using("delta").tableProperty("location", dir).create()
        }
        testTableCreation { dir =>
          sql(s"CREATE TABLE $tableName (id bigint) USING delta LOCATION '$dir'")
        }
        testTableCreation { dir =>
          sql(s"CREATE TABLE $tableName USING delta LOCATION '$dir' AS SELECT * FROM range(10)")
        }
        testTableCreation { dir =>
          val stream = MemoryStream[Int]
          stream.addData(1 to 10)
          val q = stream.toDF().writeStream.format("delta")
            .option("checkpointLocation", new File(dir, "_checkpoint").getCanonicalPath)
            .start(dir)
          q.processAllAvailable()
          q.stop()
        }

        testTableCreation { dir =>
          spark.range(10).write.mode("append").parquet(dir)
          sql(s"CONVERT TO DELTA parquet.`$dir`")
        }
      }
    }
  }

  test("replacing a new table with default protocol") {
    withTempDir { dir =>
      // In this test we go back and forth through protocol versions, testing the various syntaxes
      // of replacing tables
      val tbl = "delta_test"
      withTable(tbl) {
        withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> "1") {
          sql(s"CREATE TABLE $tbl (id bigint) USING delta LOCATION '${dir.getCanonicalPath}'")
        }
        val deltaLog = DeltaLog.forTable(spark, dir)
        assert(deltaLog.snapshot.protocol.minWriterVersion === 1,
          "Should've picked up the protocol from the configuration")

        // Replace the table and make sure the config is picked up
        withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> "2") {
          spark.range(10).writeTo(tbl).using("delta")
            .tableProperty("location", dir.getCanonicalPath).replace()
        }
        assert(deltaLog.snapshot.protocol.minWriterVersion === 2,
          "Should've picked up the protocol from the configuration")

        // Replace with the old writer again
        withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> "1") {
          sql(s"REPLACE TABLE $tbl (id bigint) USING delta LOCATION '${dir.getCanonicalPath}'")
          assert(deltaLog.snapshot.protocol.minWriterVersion === 1,
            "Should've created a new protocol")

          sql(s"CREATE OR REPLACE TABLE $tbl (id bigint NOT NULL) USING delta " +
            s"LOCATION '${dir.getCanonicalPath}'")
          assert(deltaLog.snapshot.protocol.minWriterVersion === 2,
            "Invariant should require the higher protocol")

          // Go back to version 1
          sql(s"REPLACE TABLE $tbl (id bigint) USING delta LOCATION '${dir.getCanonicalPath}'")
          assert(deltaLog.snapshot.protocol.minWriterVersion === 1,
            "Should've created a new protocol")

          // Check table properties with different syntax
          spark.range(10).writeTo(tbl).tableProperty("location", dir.getCanonicalPath)
            .tableProperty("delta.appendOnly", "true").using("delta").createOrReplace()
          assert(deltaLog.snapshot.protocol.minWriterVersion === 2,
            "appendOnly should require the higher protocol")
        }
      }
    }
  }

  test("create a table with no protocol") {
    withTempDir { path =>
      val log = DeltaLog.forTable(spark, path)
      log.ensureLogDirectoryExist()
      log.store.write(
        deltaFile(log.logPath, 0),
        Iterator(Metadata().json),
        overwrite = false,
        log.newDeltaHadoopConf())

      assert(intercept[DeltaIllegalStateException] {
        log.update()
      }.getErrorClass == "DELTA_STATE_RECOVER_ERROR")
      assert(intercept[DeltaIllegalStateException] {
        spark.read.format("delta").load(path.getCanonicalPath)
      }.getErrorClass == "DELTA_STATE_RECOVER_ERROR")
      assert(intercept[DeltaIllegalStateException] {
        spark.range(1).write.format("delta").mode(SaveMode.Overwrite).save(path.getCanonicalPath)
      }.getErrorClass == "DELTA_STATE_RECOVER_ERROR")
    }
  }

  test("bad inputs for default protocol versions") {
    val readerVersion = Action.supportedProtocolVersion().minReaderVersion
    val writerVersion = Action.supportedProtocolVersion().minWriterVersion
    withTempDir { path =>
      val dir = path.getCanonicalPath
      Seq("abc", "", "0", (readerVersion + 1).toString).foreach { conf =>
        val e = intercept[IllegalArgumentException] {
          withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_READER_VERSION.key -> conf) {
            spark.range(10).write.format("delta").save(dir)
          }
        }
      }
      Seq("abc", "", "0", (writerVersion + 1).toString).foreach { conf =>
        intercept[IllegalArgumentException] {
          withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> conf) {
            spark.range(10).write.format("delta").save(dir)
          }
        }
      }
    }
  }

  test("table creation with protocol as table property") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> "1") {
        sql(s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (delta.minWriterVersion=3)")

        assert(deltaLog.snapshot.protocol.minReaderVersion === 1)
        assert(deltaLog.snapshot.protocol.minWriterVersion === 3)
        assertPropertiesAndShowTblProperties(deltaLog)

        // Can downgrade using REPLACE
        sql(s"REPLACE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (delta.MINWRITERVERSION=1)")
        assert(deltaLog.snapshot.protocol.minReaderVersion === 1)
        assert(deltaLog.snapshot.protocol.minWriterVersion === 1)
        assertPropertiesAndShowTblProperties(deltaLog)
      }
    }
  }

  test("table creation with writer-only features as table property") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      sql(
        s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (" +
          "  DeLtA.fEaTurE.APPendONly='eNAbled'," +
          "  delta.feature.testWriter='enabled'" +
          ")")

      assert(deltaLog.snapshot.protocol.minReaderVersion === 1)
      assert(
        deltaLog.snapshot.protocol.minWriterVersion === TABLE_FEATURES_MIN_WRITER_VERSION)
      assert(
        deltaLog.snapshot.protocol.readerAndWriterFeatureDescriptors === Set(
          AppendOnlyTableFeature, TestWriterFeature).map(_.toDescriptor))
      assertPropertiesAndShowTblProperties(deltaLog, tableHasFeatures = true)
    }
  }

  test("table creation with legacy reader-writer features as table property") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      sql(
        s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (DeLtA.fEaTurE.testLEGACYReaderWritER='eNAbled')")

      assert(
        deltaLog.snapshot.protocol.minReaderVersion ===
          TestLegacyReaderWriterFeature.minReaderVersion)
      assert(
        deltaLog.snapshot.protocol.minWriterVersion ===
          TestLegacyReaderWriterFeature.minWriterVersion)
      assertPropertiesAndShowTblProperties(deltaLog)
    }
  }

  test("table creation with native writer-only features as table property") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      sql(
        s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (DeLtA.fEaTurE.testWritER='eNAbled')")

      assert(
        deltaLog.snapshot.protocol.minReaderVersion === 1)
      assert(
        deltaLog.snapshot.protocol.minWriterVersion ===
          TABLE_FEATURES_MIN_WRITER_VERSION)
      assert(
        deltaLog.snapshot.protocol.readerAndWriterFeatureDescriptors === Set(
          TestWriterFeature.toDescriptor))
      assertPropertiesAndShowTblProperties(deltaLog, tableHasFeatures = true)
    }
  }

  test("table creation with reader-writer features as table property") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      sql(
        s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (" +
          "  DeLtA.fEaTurE.testLEGACYReaderWritER='eNAbled'," +
          "  DeLtA.fEaTurE.testReaderWritER='enabled'" +
          ")")

      assert(
        deltaLog.snapshot.protocol.minReaderVersion === TABLE_FEATURES_MIN_READER_VERSION)
      assert(
        deltaLog.snapshot.protocol.minWriterVersion === TABLE_FEATURES_MIN_WRITER_VERSION)
      assert(
        deltaLog.snapshot.protocol.readerAndWriterFeatureDescriptors === Set(
          TestLegacyReaderWriterFeature, TestReaderWriterFeature).map(_.toDescriptor))
      assertPropertiesAndShowTblProperties(deltaLog, tableHasFeatures = true)
    }
  }

  test("table creation with feature as table property and supported protocol version") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      sql(
        s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (" +
          s"  DEltA.MINREADERversion='${TABLE_FEATURES_MIN_READER_VERSION}'," +
          s"  DEltA.MINWRITERversion='${TABLE_FEATURES_MIN_WRITER_VERSION}'," +
          "  DeLtA.fEaTurE.testLEGACYReaderWriter='eNAbled'" +
          ")")

      assert(
        deltaLog.snapshot.protocol === Protocol(
          minReaderVersion = TABLE_FEATURES_MIN_READER_VERSION,
          minWriterVersion = TABLE_FEATURES_MIN_WRITER_VERSION,
          readerFeatures =
            Some(Set(TestLegacyReaderWriterFeature.toDescriptor)),
          writerFeatures =
            Some(Set(TestLegacyReaderWriterFeature.toDescriptor))))
      assertPropertiesAndShowTblProperties(deltaLog, tableHasFeatures = true)
    }
  }

  test("table creation with feature as table property and supported writer protocol version") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      sql(
        s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          s"TBLPROPERTIES (" +
          s"  delta.minWriterVersion='${TABLE_FEATURES_MIN_WRITER_VERSION}'," +
          s"  delta.feature.testLegacyReaderWriter='enabled'" +
          s")")

      assert(
        deltaLog.snapshot.protocol === Protocol(
          minReaderVersion = 2,
          minWriterVersion = TABLE_FEATURES_MIN_WRITER_VERSION,
          readerFeatures = None,
          writerFeatures =
            Some(Set(TestLegacyReaderWriterFeature.toDescriptor))))
      assertPropertiesAndShowTblProperties(deltaLog, tableHasFeatures = true)
    }
  }

  test("table creation with automatically-enabled feature and unsupported protocol") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      sql(
        s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta TBLPROPERTIES (" +
          "  delta.minReaderVersion='1'," +
          "  delta.minWriterVersion='2'," +
          "  delta.enableChangeDataFeed='true'" +
          ")")
      assert(deltaLog.snapshot.protocol.minReaderVersion === 1)
      assert(deltaLog.snapshot.protocol.minWriterVersion === 4)
    }
  }

  test("table creation with feature as table property and unsupported protocol version") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      intercept[Exception] {
        sql(s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
            "TBLPROPERTIES (delta.minWriterVersion='2',delta.feature.testWriter='enabled')")
      }.getMessage.contains("Writer Table Features must be supported to add a writer feature")
    }
  }

  test("table creation with protocol as table property - property wins over conf") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> "3") {
        sql(s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (delta.MINwriterVERsion=2)")

        assert(deltaLog.snapshot.protocol.minWriterVersion === 2)
        assertPropertiesAndShowTblProperties(deltaLog)
      }
    }
  }

  test("table creation with protocol as table property - feature requirements win SQL") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> "1") {
        sql(s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (delta.minWriterVersion=1, delta.appendOnly=true)")

        assert(deltaLog.snapshot.protocol.minWriterVersion === 2)
        assertPropertiesAndShowTblProperties(deltaLog)

        sql(s"REPLACE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (delta.minWriterVersion=1)")

        assert(deltaLog.snapshot.protocol.minWriterVersion === 1)
        assertPropertiesAndShowTblProperties(deltaLog)

        // Works with REPLACE too
        sql(s"REPLACE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (delta.minWriterVersion=1, delta.appendOnly=true)")
        assert(deltaLog.snapshot.protocol.minWriterVersion === 2)
        assertPropertiesAndShowTblProperties(deltaLog)
      }
    }
  }

  test("table creation with protocol as table property - feature requirements win DF") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> "1") {
        spark.range(10).writeTo(s"delta.`${dir.getCanonicalPath}`")
          .tableProperty("delta.minWriterVersion", "1")
          .tableProperty("delta.appendOnly", "true")
          .using("delta")
          .create()

        assert(deltaLog.snapshot.protocol.minWriterVersion === 2)
        assertPropertiesAndShowTblProperties(deltaLog)
      }
    }
  }

  test("table creation with protocol as table property - default table properties") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      withSQLConf((DeltaConfigs.sqlConfPrefix + "minWriterVersion") -> "3") {
        spark.range(10).writeTo(s"delta.`${dir.getCanonicalPath}`")
          .using("delta")
          .create()

        assert(deltaLog.snapshot.protocol.minWriterVersion === 3)
        assertPropertiesAndShowTblProperties(deltaLog)
      }
    }
  }

  test("table creation with protocol as table property - explicit wins over conf") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      withSQLConf((DeltaConfigs.sqlConfPrefix + "minWriterVersion") -> "3") {
        spark.range(10).writeTo(s"delta.`${dir.getCanonicalPath}`")
          .tableProperty("delta.minWriterVersion", "2")
          .using("delta")
          .create()

        assert(deltaLog.snapshot.protocol.minWriterVersion === 2)
        assertPropertiesAndShowTblProperties(deltaLog)
      }
    }
  }

  test("table creation with protocol as table property - bad input") {
    withTempDir { dir =>
      val e = intercept[IllegalArgumentException] {
        sql(s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (delta.minWriterVersion='delta rulz')")
      }
      assert(e.getMessage.contains("integer"))

      val e2 = intercept[AnalysisException] {
        sql(s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (delta.minWr1terVersion=2)") // Typo in minWriterVersion
      }
      assert(e2.getMessage.contains("Unknown configuration"))

      val e3 = intercept[IllegalArgumentException] {
        sql(s"CREATE TABLE delta.`${dir.getCanonicalPath}` (id bigint) USING delta " +
          "TBLPROPERTIES (delta.minWriterVersion=0)")
      }
      assert(e3.getMessage.contains("integer"))
    }
  }

  test("protocol as table property - desc table") {
    withTempDir { dir =>
      val deltaLog = DeltaLog.forTable(spark, dir)
      withSQLConf(DeltaSQLConf.DELTA_PROTOCOL_DEFAULT_WRITER_VERSION.key -> "2") {
        spark.range(10).writeTo(s"delta.`${dir.getCanonicalPath}`")
          .using("delta")
          .tableProperty("delta.minWriterVersion", "3")
          .createOrReplace()
      }
      assert(deltaLog.snapshot.protocol.minWriterVersion === 3)

      val output = spark.sql(s"DESC EXTENDED delta.`${dir.getCanonicalPath}`").collect()
      assert(output.exists(_.toString.contains("delta.minWriterVersion")),
        s"minWriterVersion not found in: ${output.mkString("\n")}")
      assert(output.exists(_.toString.contains("delta.minReaderVersion")),
        s"minReaderVersion not found in: ${output.mkString("\n")}")
    }
  }

  test("auto upgrade protocol version - version 2") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(1, 1), path)
      spark.sql(s"""
                   |ALTER TABLE delta.`${log.dataPath.toString}`
                   |SET TBLPROPERTIES ('delta.appendOnly' = 'true')
                 """.stripMargin)
      assert(log.snapshot.protocol.minWriterVersion === 2)
    }
  }

  test("auto upgrade protocol version - version 3") {
    withTempDir { path =>
      val log = DeltaLog.forTable(spark, path)
      sql(s"CREATE TABLE delta.`${path.getCanonicalPath}` (id bigint) USING delta " +
        "TBLPROPERTIES (delta.minWriterVersion=2)")
      assert(log.update().protocol.minWriterVersion === 2)
      spark.sql(s"""
                   |ALTER TABLE delta.`${path.getCanonicalPath}`
                   |ADD CONSTRAINT test CHECK (id < 5)
                 """.stripMargin)
      assert(log.update().protocol.minWriterVersion === 3)
    }
  }

  test("auto upgrade protocol version even with explicit protocol version configs") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(1, 1), path)
      spark.sql(s"""
                   |ALTER TABLE delta.`${log.dataPath.toString}` SET TBLPROPERTIES (
                   |  'delta.minWriterVersion' = '2',
                   |  'delta.enableChangeDataFeed' = 'true'
                   |)""".stripMargin)
      assert(log.snapshot.protocol.minWriterVersion === 4)

    }
  }

  test("feature can be implicit listed during alter table") {
    withTempDir { path =>
      val log = createTableWithProtocol(Protocol(2, TABLE_FEATURES_MIN_WRITER_VERSION), path)
      spark.sql(s"""
                   |ALTER TABLE delta.`${log.dataPath.toString}` SET TBLPROPERTIES (
                   |  'delta.feature.testLegacyReaderWriter' = 'enabled'
                   |)""".stripMargin)
      assert(log.snapshot.protocol.minReaderVersion === 2)
      assert(!log.snapshot.protocol.readerFeatures.isDefined)
      assert(
        log.snapshot.protocol.writerFeatures === Some(
          Set(TestLegacyReaderWriterFeature.toDescriptor)))

    }
  }

  private def assertPropertiesAndShowTblProperties(
      deltaLog: DeltaLog,
      tableHasFeatures: Boolean = false): Unit = {
    val configs = deltaLog.snapshot.metadata.configuration.map { case (k, v) =>
      k.toLowerCase(Locale.ROOT) -> v
    }
    assert(!configs.contains(Protocol.MIN_READER_VERSION_PROP))
    assert(!configs.contains(Protocol.MIN_WRITER_VERSION_PROP))
    assert(!configs.exists(_._1.startsWith(FEATURE_PROP_PREFIX)))

    val tblProperties =
      sql(s"SHOW TBLPROPERTIES delta.`${deltaLog.dataPath.toString}`").collect()

    assert(
      tblProperties.exists(row => row.getAs[String]("key") == Protocol.MIN_READER_VERSION_PROP))
    assert(
      tblProperties.exists(row => row.getAs[String]("key") == Protocol.MIN_WRITER_VERSION_PROP))

    assert(tableHasFeatures === tblProperties.exists(row =>
      row.getAs[String]("key").startsWith(FEATURE_PROP_PREFIX)))
    val rows =
      tblProperties.filter(row =>
        row.getAs[String]("key").startsWith(FEATURE_PROP_PREFIX))
    for (row <- rows) {
      val name = row.getAs[String]("key").substring(FEATURE_PROP_PREFIX.length)
      val status = row.getAs[String]("value")
      assert(TableFeature.allSupportedFeaturesMap.contains(name.toLowerCase(Locale.ROOT)))
      assert(status == "enabled")
    }
  }
}

class DeltaProtocolVersionSuite extends DeltaProtocolVersionSuiteBase
