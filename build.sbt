import de.johoop.jacoco4sbt._
import JacocoPlugin._
import sbtbuildinfo._

enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)

// Global Settings
scalaVersion in Global := "2.11.8"

organization in Global := "io.orkestra"

name in Global := "Cluster Management"

scalacOptions in Global ++= Seq("-unchecked", "-deprecation", "-feature")

logBuffered in Test in Global:= false

parallelExecution in jacoco.Config := false

publishTo in ThisBuild := Some("menacommere" at "https://api.bintray.com/maven/menacommere")

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

val buildNumber = Option(System.getenv().get("BUILD_NUMBER"))

version in Global := "1.0"

version in Docker := "build-" + buildNumber.getOrElse("1.0")

lazy val root = project.in(file(".")).
  enablePlugins(BuildInfoPlugin).
  settings(ClusterManagement.settings: _*)

dockerRepository := Some("menacommere-docker-registry.bintray.io")

dockerUpdateLatest := true

