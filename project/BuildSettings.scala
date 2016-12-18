import sbt.Keys._
import sbtbuildinfo._
import sbtbuildinfo.BuildInfoKeys._

object BuildSettings {
  import Dependencies._

  def commonSettings(appName: String) = Seq(
      organization := s"io.orkestra.$appName",
      homepage := None,
      buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoPackage := appName,
      resolvers ++= Seq(Resolvers.sonatypeSnapshots, 
                        Resolvers.typesafe, 
                        Resolvers.sonatype)
    ) ++ FormatSettings.settings

}
