package com.banzaicloud.spark.metrics.sink

import java.io.IOException
import java.util
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import com.banzaicloud.spark.metrics.sink.PrometheusSink.SinkConfig
import com.codahale.metrics.{Metric, MetricFilter, MetricRegistry}
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.PushGateway
import org.apache.spark.banzaicloud.metrics.sink.{PrometheusSink => SparkPrometheusSink}
import org.junit.{Assert, Test}

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.matching.Regex

class PrometheusSinkSuite {
  case class TestSinkConfig(
    metricsNamespace: Option[String],
    sparkAppId: Option[String],
    sparkAppName: Option[String],
    executorId: Option[String]
  ) extends SinkConfig


  val basicProperties: Properties = {
    val properties = new Properties
    properties.setProperty("enable-jmx-collector", "true")
    properties.setProperty("labels", "a=1,b=22")
    properties.setProperty("period", "1")
    properties.setProperty("group-key", "key1=AA,key2=BB")
    properties.setProperty("jmx-collector-config", "/dev/null")
    properties
  }

  trait Fixture {
    def sinkConfig: TestSinkConfig = TestSinkConfig(Some("test-job-name"), Some("test-app-id"), Some("test-app-name"), None)
    lazy val pgMock = new PushGatewayMock
    lazy val registry = new MetricRegistry

    def withSink[T](properties: Properties = basicProperties)(fn: SparkPrometheusSink => T): Unit = {
      // Given
      val sink: SparkPrometheusSink = new SparkPrometheusSink(properties, registry, sinkConfig, _ => pgMock)
      try {
      //When
        sink.start()
        sink.report()
        fn(sink)
      } finally {
        Try(sink.stop()) // We call stop to avoid duplicated metrics across different tests
      }
    }
  }

  @Test
  def testSinkForDriver(): Unit = new Fixture {
    //Given
    override val sinkConfig: TestSinkConfig = super.sinkConfig.copy(executorId = Some("driver"))

    registry.counter("test-counter").inc(3)
    withSink() { sink =>
      //Then
      Assert.assertTrue(pgMock.requests.size == 1)
      val request = pgMock.requests.head
      val families = request.registry.metricFamilySamples().asScala.toList

      Assert.assertTrue(request.job == "test-job-name")
      Assert.assertTrue(request.groupingKey.asScala == Map("role" -> "driver", "key1" -> "AA", "key2" -> "BB"))
      Assert.assertTrue(
          families.exists(_.name == "java_lang_CodeHeap_non_nmethods_PeakUsage_used")
      )
      Assert.assertTrue(
        sink.metricsFilter == MetricFilter.ALL
      )
    }
  }

  @Test
  def testSinkForExecutor(): Unit = new Fixture {
    //Given
    override val sinkConfig: TestSinkConfig = super.sinkConfig.copy(executorId = Some("2"))

    registry.counter("test-counter").inc(3)

    withSink() { sink =>
      //Then
      Assert.assertTrue(pgMock.requests.size == 1)
      val request = pgMock.requests.head

      Assert.assertTrue(request.job == "test-job-name")
      Assert.assertTrue(request.groupingKey.asScala == Map("role" -> "executor", "number" -> "2", "key1" -> "AA", "key2" -> "BB"))
      val families = request.registry.metricFamilySamples().asScala.toList

      Assert.assertTrue {
        val counterFamily = families.find(_.name == "test_counter").get
        val sample = counterFamily.samples.asScala.head
        val labels = sample.labelNames.asScala.zip(sample.labelValues.asScala)
        labels == List("a" -> "1", "b" -> "22")
      }
      Assert.assertTrue(
          families.exists(_.name == "java_lang_CodeHeap_non_nmethods_PeakUsage_used")
      )
      Assert.assertTrue(
        sink.metricsFilter == MetricFilter.ALL
      )
    }
  }

  val testMetricsNameCaptureRegexPropertyProperties: Properties = {
    val properties = new Properties
    properties.setProperty("metrics-name-capture-regex-0", "[a-z]")
    properties.setProperty("metrics-name-replacement-0", "x")
    properties.setProperty("metrics-name-capture-regex-1", "[A-Z]")
    properties.setProperty("metrics-name-replacement-1", "X")
    properties
  }

  @Test
  def testMetricsNameCaptureRegexProperty(): Unit = new Fixture {
    //Given
    withSink(properties = testMetricsNameCaptureRegexPropertyProperties) { sink =>
      //Then
      Assert.assertEquals(sink.metricsNameCaptureRegex, Option.empty[Regex])
      Assert.assertEquals(sink.metricsNameReplacement, Option.empty[String])
      Assert.assertEquals(sink.metricsNameCaptureRegexSeq.map(_.regex), List("[a-z]", "[A-Z]"))
      Assert.assertEquals(sink.metricsNameReplacementSeq, List("x", "X"))
    }
  }

  class PushGatewayMock extends PushGateway("anything") {
    val requests = new CopyOnWriteArrayList[Request]().asScala
    case class Request(registry: CollectorRegistry, job: String, groupingKey: util.Map[String, String], method: String)

    @throws[IOException]
    override def push(registry: CollectorRegistry, job: String): Unit = {
      logRequest(registry, job, null, "PUT")
    }

    @throws[IOException]
    override def push(registry: CollectorRegistry, job: String, groupingKey: util.Map[String, String]): Unit = {
      logRequest(registry, job, groupingKey, "PUT")
    }

    @throws[IOException]
    override def pushAdd(registry: CollectorRegistry, job: String): Unit = {
      logRequest(registry, job, null, "POST")
    }

    @throws[IOException]
    override def pushAdd(registry: CollectorRegistry, job: String, groupingKey: util.Map[String, String]): Unit = {
      logRequest(registry, job, groupingKey, "POST")
    }

    @throws[IOException]
    override def delete(job: String): Unit = {
      logRequest(null, job, null, "DELETE")
    }

    @throws[IOException]
    override def delete(job: String, groupingKey: util.Map[String, String]): Unit = {
      logRequest(null, job, groupingKey, "DELETE")
    }

    private def logRequest(registry: CollectorRegistry, job: String, groupingKey: util.Map[String, String], method: String): Unit = {
      requests += Request(registry, job, groupingKey, method)
    }
  }
}

object PrometheusSinkSuite {
  trait NoOpMetricFilter extends MetricFilter {
    override def matches(name: String, metric: Metric): Boolean = false
  }

  class DefaultConstr extends NoOpMetricFilter

  class PropertiesConstr(val props: Properties) extends NoOpMetricFilter

  class JavaMapConstr(val props: util.Map[String, String]) extends NoOpMetricFilter

  class ScalaMapConstr(val props: Map[String, String]) extends NoOpMetricFilter
}
