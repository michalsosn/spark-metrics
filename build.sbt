lazy val scala212 = "2.12.17"
lazy val supportedScalaVersions = List(scala212)
lazy val ioPrometheusVersion = "0.16.0"

lazy val root = (project in file("."))
  .settings(
    name := "spark-metrics",
    organization := "com.banzaicloud",
    organizationHomepage := Some(url("https://banzaicloud.com")),
    homepage := Some(url("https://github.com/banzaicloud/spark-metrics")),
    developers := List(
      Developer("stoader", "Sebastian Toader", "st0ad3r@gmail.com", url("https://github.com/stoader")),
      Developer("sancyx", "Sandor Magyari", "sancyx@gmail.com", url("https://github.com/sancyx")),
      Developer("baluchicken", "Balint Molnar", "balintmolnar91@gmail.com", url("https://github.com/baluchicken"))
    ),
    scmInfo := Some(ScmInfo(url("https://github.com/banzaicloud/spark-metrics"), "git@github.com:banzaicloud/spark-metrics.git")),
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalaVersion := scala212,
    crossScalaVersions := supportedScalaVersions,
    version      := "3.3-1.0.0",
    libraryDependencies ++= Seq(
      "io.prometheus" % "simpleclient" % ioPrometheusVersion,
      "io.prometheus" % "simpleclient_dropwizard" % ioPrometheusVersion,
      "io.prometheus" % "simpleclient_common" % ioPrometheusVersion,
      "io.prometheus" % "simpleclient_pushgateway" % ioPrometheusVersion,
      "io.dropwizard.metrics" % "metrics-core" % "4.2.18" % Provided,
      "io.prometheus.jmx" % "collector" % "0.18.0",
      "org.apache.spark" %% "spark-core" % "3.3.0" % Provided,
      "com.novocode" % "junit-interface" % "0.11" % Test,
      ),
    testOptions in Test := Seq(Tests.Argument(TestFrameworks.JUnit, "-a"))
  )


publishMavenStyle := true
useGpg := true

// Add sonatype repository settings
publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)

