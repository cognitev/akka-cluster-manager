import _root_.de.johoop.jacoco4sbt.JacocoPlugin
import _root_.de.johoop.jacoco4sbt.JacocoPlugin.jacoco
import sbt._
import Keys._
import de.johoop.jacoco4sbt._
import JacocoPlugin._

object ClusterManagement {
  import Dependencies._

  val appName = "ClusterManagement"

  val defaultVersion = "1.0"

  val appDependencies = Seq(
    Logging.slf4j,
    Akka.actor,
    Akka.cluster,
  Orkestra.rorschach)

  val settings = BuildSettings.commonSettings(appName) ++
    jacoco.settings ++
    Seq(parallelExecution in jacoco.Config := false) ++
    Seq(name := appName,
      version := defaultVersion,
      libraryDependencies ++= appDependencies)
}
