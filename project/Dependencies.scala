import sbt._

object Dependencies {

  def provided(module: ModuleID): ModuleID = module % "provided"
  def test(module: ModuleID): ModuleID = module % "test"

  object Resolvers {
    val typesafe = "typesafe.com" at "http://repo.typesafe.com/typesafe/releases/"
    val sonatype = "sonatype" at "http://oss.sonatype.org/content/repositories/releases"
    val sonatypeSnapshots = "sonatype-snapshots" at "http://oss.sonatype.org/content/repositories/snapshots"
    val local = Resolver.file("Local repo", file(System.getProperty("user.home") + "/.ivy2/local"))(Resolver.ivyStylePatterns)
    val mc = Resolver.bintrayRepo("menacommere", "maven")
    val commons = Seq(typesafe, sonatype, local)
  }

  object scalaLang {
    val scalaVersion = "2.11.8"
    val reflection = "org.scala-lang" % "scala-reflect" % scalaVersion
    val compiler = "org.scala-lang" % "scala-compiler" % scalaVersion
    val testKit = "org.scalatest"  %% "scalatest" % "2.2.6"
  }
  object Logging {
    val slf4j = "org.slf4j" % "slf4j-api" % "1.7.5"
  }

  object Play {
    val json = "com.typesafe.play" %% "play-json" % "2.3.4"
  }

  object Orkestra {
    val rorschach = "io.orkestra.rorschach" % "rorschach_2.11" % "0.1.0"
  }

  object Akka {
    val version = "2.4.14"
    val httpVersion = "10.0.0"
    val actor = "com.typesafe.akka" %% "akka-actor" % version
    val http = "com.typesafe.akka" %% "akka-http" % httpVersion
    val cluster = "com.typesafe.akka" %% "akka-cluster" % version
    val httpMgt = "com.lightbend.akka" %% "akka-management-cluster-http" % "0.1-RC1"
  }

}
