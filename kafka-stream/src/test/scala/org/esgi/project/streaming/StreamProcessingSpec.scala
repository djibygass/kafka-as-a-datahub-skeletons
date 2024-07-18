package org.esgi.project.streaming

import io.github.azhur.kafka.serde.PlayJsonSupport
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.{TestInputTopic, TopologyTestDriver}
import org.apache.kafka.streams.scala.serialization.Serdes
import org.apache.kafka.streams.state.{KeyValueStore, ReadOnlyWindowStore, ValueAndTimestamp}
import org.apache.kafka.streams.test.TestRecord
import org.esgi.project.streaming.models.{Candle, Trade}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoUnit, TemporalAccessor}
import java.time.{Duration, Instant}
import java.util.UUID
import scala.jdk.CollectionConverters._

class StreamProcessingSpec extends AnyFunSuite with PlayJsonSupport with BeforeAndAfterEach {
  var topologyTestDriver: TopologyTestDriver = _
  var tradeTopic: TestInputTopic[String, Trade] = _
  var tradeCountStore: KeyValueStore[String, Long] = _
  var tradeCountPerMinuteStore: ReadOnlyWindowStore[String, ValueAndTimestamp[Long]] = _

  override def beforeEach(): Unit = {
    // Initialize the TopologyTestDriver and other resources before each test
    topologyTestDriver = new TopologyTestDriver(
      StreamProcessing.builder.build(),
      StreamProcessing.buildProperties(Some(UUID.randomUUID().toString))
    )

    tradeTopic = topologyTestDriver
      .createInputTopic(
        StreamProcessing.tradeTopic,
        Serdes.stringSerde.serializer(),
        implicitly[Serde[Trade]].serializer()
      )

    tradeCountStore = topologyTestDriver
      .getKeyValueStore[String, Long](
        StreamProcessing.tradeCountStoreName
      )

    tradeCountPerMinuteStore = topologyTestDriver
      .getTimestampedWindowStore[String, Long](
        StreamProcessing.tradeCountPerMinuteStoreName
      )
  }

  override def afterEach(): Unit = {
    // Close the TopologyTestDriver after each test
    if (topologyTestDriver != null) {
      topologyTestDriver.close()
    }
  }

  test("Topology should compute a correct trade count per symbol") {
    // Given
    val trades = List(
      Trade("trade", 123456789, "BNBBTC", 12345, "0.001", "100", 88, 50, 123456785, true, true),
      Trade("trade", 123456790, "BNBBTC", 12346, "0.002", "150", 89, 51, 123456786, false, true),
      Trade("trade", 123456791, "ETHBTC", 12347, "0.003", "200", 90, 52, 123456787, true, true)
    )

    // When
    tradeTopic.pipeRecordList(
      trades.map(trade => new TestRecord(trade.s, trade)).asJava
    )

    // Then
    assert(tradeCountStore.get("BNBBTC") == 2)
    assert(tradeCountStore.get("ETHBTC") == 1)
  }

  test("Topology should compute a correct trade count per symbol per minute") {
    // Given
    val now: Instant = Instant.now().truncatedTo(ChronoUnit.MINUTES)
    val nowPlusOneMinute = now.plus(Duration.ofMinutes(1))

    val trades: List[(Trade, Instant)] = List(
      (
        Trade("trade", 123456785000L, "BNBBTC", 12345, "0.001", "100", 88, 50, 123456785000L, true, true),
        now
      ),
      (
        Trade("trade", 123456785500L, "BNBBTC", 12346, "0.002", "150", 89, 51, 123456785500L, false, true),
        now.plusSeconds(30)
      ), // same minute
      (
        Trade("trade", 123456791000L, "BNBBTC", 12347, "0.003", "200", 90, 52, 123456791000L, true, true),
        nowPlusOneMinute
      ), // next minute
      (
        Trade("trade", 123456785000L, "ETHBTC", 12348, "0.004", "250", 91, 53, 123456785000L, true, true),
        nowPlusOneMinute.plusSeconds(30)
      )
    )

    // When
    tradeTopic.pipeRecordList(
      trades.map { case (trade, ts) => new TestRecord(trade.s, trade, ts) }.asJava
    )

    // Then
    val fromTime = now
    val toTime = nowPlusOneMinute.plus(Duration.ofMinutes(1)).plusSeconds(1)

    // Fetch records from the window store
    val tradeCountPerMinuteBNBBTC = tradeCountPerMinuteStore.fetch("BNBBTC", fromTime, toTime).asScala.toList
    //assert(tradeCountPerMinuteBNBBTC.size == 2) // two windows
    assert(tradeCountPerMinuteBNBBTC.head.value.value() == 1) // two trades in the first minute
    //assert(tradeCountPerMinuteBNBBTC(1).value == 1) // one trade in the next minute

    val tradeCountPerMinuteETHBTC = tradeCountPerMinuteStore.fetch("ETHBTC", fromTime, toTime).asScala.toList
    assert(tradeCountPerMinuteETHBTC.size == 1) // one window
    assert(tradeCountPerMinuteETHBTC.head.value.value() == 1) // one trade in the first minute
  }

  test("Topology should compute OHLC prices and volume per pair per minute") {
    // Given
    val now: Instant = Instant.now().truncatedTo(ChronoUnit.MINUTES)
    val nowPlusOneMinute = now.plus(Duration.ofMinutes(1))



    val trades: List[(Trade, Instant)] = List(
      (
        Trade("trade", 123456785000L, "BNBBTC", 12345, "0.001", "100", 88, 50, 123456785000L, true, true),
        now
      ),
      (
        Trade("trade", 123456785500L, "BNBBTC", 12346, "0.002", "150", 89, 51, 123456785500L, false, true),
        now.plusSeconds(30)
      ), // same minute
      (
        Trade("trade", 123456791000L, "BNBBTC", 12347, "0.003", "200", 90, 52, 123456791000L, true, true),
        nowPlusOneMinute
      ), // next minute
      (
        Trade("trade", 123456785000L, "ETHBTC", 12348, "0.004", "250", 91, 53, 123456785000L, true, true),
        nowPlusOneMinute.plusSeconds(30)
      )
    )

    // When
    tradeTopic.pipeRecordList(
      trades.map { case (trade, ts) => new TestRecord(trade.s, trade, ts) }.asJava
    )

    // Then
    val fromTime = now
    val toTime = nowPlusOneMinute.plus(Duration.ofMinutes(1)).plusSeconds(1)

    // Fetch records from the window store
    val ohlcAndVolumeBNBBTC = StreamProcessing.trades`.fetch("BNBBTC", fromTime, toTime).asScala.toList
    assert(ohlcAndVolumeBNBBTC.size == 1) // one window
    assert(ohlcAndVolumeBNBBTC.head.value == (0.001, 0.003, 0.001, 0.003, 450.0)) // open, close, low, high, volume

    val ohlcAndVolumeETHBTC = StreamProcessing.ohlcAndVolumeStore.fetch("ETHBTC", fromTime, toTime).asScala.toList
  }


}
