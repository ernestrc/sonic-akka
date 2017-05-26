import sbt.Keys._
import sbt._
import sbtassembly.AssemblyKeys._
import sbtassembly.MergeStrategy
import spray.revolver.RevolverPlugin._

object Build extends sbt.Build {

  val scalaV = "2.11.8"
  val akkaV = "2.4.14"
  val akkaHttpV = "10.0.0"
  val sonicV = "0.6.11"

  val commonSettings = Seq(
    organization := "build.unstable",
    version := sonicV,
    scalaVersion := scalaV,
    licenses += ("MIT", url("https://opensource.org/licenses/MIT")),
    resolvers += Resolver.bintrayRepo("ernestrc", "maven"),
    publishArtifact in (Compile, packageDoc) := false,
    scalacOptions := Seq(
      "-unchecked",
      "-Xlog-free-terms",
      "-deprecation",
      "-feature",
      "-Xlint",
      "-Ywarn-dead-code",
      "-Ywarn-unused",
      "-encoding", "UTF-8",
      "-target:jvm-1.8"
    )
  )

  val meta = """META.INF(.)*""".r

  val assemblyStrategy = assemblyMergeStrategy in assembly := {
    case "reference.conf" => MergeStrategy.concat
    case "application.conf" => MergeStrategy.discard
    case meta(_) => MergeStrategy.discard
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  }

  val core: Project = Project("sonic-core", file("."))
    .settings(commonSettings: _*)
    .settings(
      libraryDependencies ++= {
        Seq(
          "build.unstable" %% "tylog" % "0.3.0",
          "io.spray" %% "spray-json" % "1.3.2",
          "com.typesafe.akka" %% "akka-actor" % akkaV,
          "com.typesafe.akka" %% "akka-slf4j" % akkaV,
          "com.typesafe.akka" %% "akka-stream" % akkaV,
          "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV,
          "ch.megard" %% "akka-http-cors" % "0.1.10",
          "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % "test",
          "com.auth0" % "java-jwt" % "2.1.0" % "test",
          "org.scalatest" %% "scalatest" % "2.2.5" % "test"
        )
      }
    )

  val examples = Project("examples", file("./examples"))
    .settings(Revolver.settings: _*)
    .settings(commonSettings: _*)
    .settings(
      libraryDependencies ++= {
        Seq(
          "build.unstable" %% "tylog" % "0.3.0",
          "net.logstash.logback" % "logstash-logback-encoder" % "4.7",
          "ch.qos.logback" % "logback-classic" % "1.1.7"
        )
      }
    ).dependsOn(core)
}
