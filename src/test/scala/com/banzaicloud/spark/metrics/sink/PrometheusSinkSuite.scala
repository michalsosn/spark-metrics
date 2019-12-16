package com.banzaicloud.spark.metrics.sink

import java.io.IOException
import java.util
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

import com.banzaicloud.spark.metrics.sink.PrometheusSink.SinkConfig
import com.codahale.metrics.MetricRegistry
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.PushGateway
import org.apache.spark.banzaicloud.metrics.sink.{PrometheusSink => SparkPrometheusSink}
import org.junit.{Assert, Test}

import scala.collection.JavaConverters._
import scala.util.Try

class PrometheusSinkSuite {
  case class TestSinkConfig(metricsNamespace: Option[String],
                            sparkAppId: Option[String],
                            sparkAppName: Option[String],
                            executorId: Option[String]) extends SinkConfig

  private val properties = new Properties
  properties.setProperty("enable-jmx-collector", "true")
  properties.setProperty("labels", "a=1,b=22")
  properties.setProperty("period", "1")
  properties.setProperty("group-key", "key1=AA,key2=BB")
  properties.setProperty("jmx-collector-config", "/dev/null")

  trait Fixture {
    def sinkConfig = TestSinkConfig(Some("test-job-name"), Some("test-app-id"), Some("test-app-name"), None)
    lazy val pgMock = new PushGatewayMock
    lazy val registry = new MetricRegistry

    def withSink[T](fn: (SparkPrometheusSink) => T): Unit = {
      // Given
      val sink = new SparkPrometheusSink(properties, registry, null, sinkConfig, _ => pgMock)
      try {
      //When
        sink.start()
        sink.report()
      } finally {
        Try(sink.stop()) // We call stop to avoid duplicated metrics across different tests
      }
    }
  }

  @Test
  def testSinkForDriver(): Unit = new Fixture {
    //Given
    override val sinkConfig = super.sinkConfig.copy(executorId = Some("driver"))

    registry.counter("test-counter").inc(3)
    withSink { sink =>
      //Then
      Assert.assertTrue(pgMock.requests.size == 1)
      val request = pgMock.requests.head

      Assert.assertTrue(request.job == "test-job-name")
      Assert.assertTrue(request.groupingKey.asScala == Map("role" -> "driver", "key1" -> "AA", "key2" -> "BB"))
      Assert.assertTrue(
        request.registry.metricFamilySamples().asScala.exists(_.name == "jmx_config_reload_success_total")
      )

    }
  }

  @Test
  def testSinkForExecutor(): Unit = new Fixture {
    //Given
    override val sinkConfig = super.sinkConfig.copy(executorId = Some("2"))

    registry.counter("test-counter").inc(3)

    withSink { sink =>
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
        families.exists(_.name == "jmx_config_reload_success_total")
      )
    }
  }

  class PushGatewayMock extends PushGateway("anything") {
    val requests = new CopyOnWriteArrayList[Request]().asScala
    case class Request(registry: CollectorRegistry, job: String, groupingKey: util.Map[String, String], method: String)

    @throws[IOException]
    override def push(registry: CollectorRegistry, job: String) {
      logRequest(registry, job, null, "PUT")
    }

    @throws[IOException]
    override def push(registry: CollectorRegistry, job: String, groupingKey: util.Map[String, String]): Unit = {
      logRequest(registry, job, groupingKey, "PUT")
    }

    @throws[IOException]
    override def pushAdd(registry: CollectorRegistry, job: String) {
      logRequest(registry, job, null, "POST")
    }

    @throws[IOException]
    override def pushAdd(registry: CollectorRegistry, job: String, groupingKey: util.Map[String, String]): Unit = {
      logRequest(registry, job, groupingKey, "POST")
    }

    @throws[IOException]
    override def delete(job: String) {
      logRequest(null, job, null, "DELETE")
    }

    @throws[IOException]
    override def delete(job: String, groupingKey: util.Map[String, String]) {
      logRequest(null, job, groupingKey, "DELETE")
    }

    private def logRequest(registry: CollectorRegistry, job: String, groupingKey: util.Map[String, String], method: String) {
      requests += Request(registry, job, groupingKey, method)
    }
  }
}
