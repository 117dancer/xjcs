
import sbt._

import Keys._

//import sbtassembly.AssemblyPlugin.autoImport._

name := "test"

version := "1.0"

scalaVersion := "2.11.8"

// sbtPlugin := true

ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) }

libraryDependencies += "org.apache.spark" % "spark-core_2.11" % "2.0.1" % "provided"

libraryDependencies += "org.apache.spark" % "spark-streaming_2.11" % "2.0.1" % "provided"

libraryDependencies += "org.apache.spark" % "spark-mllib_2.11" % "2.0.1"

libraryDependencies += "org.apache.spark" % "spark-streaming-kafka-0-10_2.11" % "2.0.1"

// libraryDependencies += "org.apache.spark" %% "spark-streaming-kafka-0-8" % "2.0.1"

libraryDependencies += "org.apache.kafka" % "kafka_2.11" % "0.10.2.0"

 // libraryDependencies += "org.apache.spark" %% "spark-sql" % "2.0.1"

libraryDependencies += "org.elasticsearch" % "elasticsearch-spark-20_2.11" % "5.3.0"

libraryDependencies += "com.typesafe.play" % "play-json_2.11" % "2.4.8"

libraryDependencies += "io.circe" % "circe-core_2.11" % "0.7.0"

libraryDependencies += "redis.clients" % "jedis" % "2.9.0"

libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.5"

dependencyOverrides ++= Set(
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.6.5"
)

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-compiler" % "2.11.8",
  "org.scala-lang" % "scala-library" % "2.11.8",
  "org.scala-lang" % "scala-reflect" % "2.11.8"
)


assemblyMergeStrategy in assembly := {
  case PathList("javax", "servlet", xs @ _*) => MergeStrategy.last
  case PathList("javax", "inject", xs @ _*) => MergeStrategy.last
  case PathList("javax", "activation", xs @ _*) => MergeStrategy.last
  case PathList("org", "apache", xs @ _*) => MergeStrategy.last
  case PathList("org", "aopalliance", xs @ _*) => MergeStrategy.last
  case PathList("com", "google", xs @ _*) => MergeStrategy.last
  case PathList("com", "esotericsoftware", xs @ _*) => MergeStrategy.last
  case PathList("com", "codahale", xs @ _*) => MergeStrategy.last
  case PathList("com", "yammer", xs @ _*) => MergeStrategy.last
  case "about.html" => MergeStrategy.rename
  case "overview.html" => MergeStrategy.rename
  case "META-INF/ECLIPSEF.RSA" => MergeStrategy.last
  case "META-INF/mailcap" => MergeStrategy.last
  case "META-INF/mimetypes.default" => MergeStrategy.last
  case "plugin.properties" => MergeStrategy.last
  case "log4j.properties" => MergeStrategy.last
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

