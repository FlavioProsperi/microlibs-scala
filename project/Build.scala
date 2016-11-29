import sbt._
import Keys._
import org.scalajs.sbtplugin.ScalaJSPlugin
import ScalaJSPlugin.autoImport._
import pl.project13.scala.sbt.JmhPlugin
import com.timushev.sbt.updates.UpdatesKeys._
import Lib._

object Microlibs {

  private val ghProject = "microlibs-scala"

  private val publicationSettings =
    Lib.publicationSettings(ghProject)

  object Ver {
    final val JAMM          = "0.3.1"
    final val KindProjector = "0.9.3"
    final val MacroParadise = "2.1.0"
    final val Monocle       = "1.3.2"
    final val MTest         = "0.4.4"
    final val Scala211      = "2.11.8"
    final val Scala212      = "2.12.0"
    final val Scalaz        = "7.2.7"
    final val UnivEq        = "1.0.2"
  }

  def scalacFlags = Seq(
    "-deprecation",
    "-unchecked",
    "-Ywarn-dead-code",
    "-Ywarn-unused",
    "-Ywarn-value-discard",
    "-feature",
    "-language:postfixOps",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-language:existentials")

  val commonSettings = ConfigureBoth(
    _.settings(
      organization                := "com.github.japgolly.microlibs",
      homepage                    := Some(url("https://github.com/japgolly/" + ghProject)),
      licenses                    += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0")),
      scalaVersion                := Ver.Scala211,
      crossScalaVersions          := Seq(Ver.Scala211, Ver.Scala212),
      scalacOptions              ++= scalacFlags,
      scalacOptions in Test      --= Seq("-Ywarn-dead-code"),
      shellPrompt in ThisBuild    := ((s: State) => Project.extract(s).currentRef.project + "> "),
      triggeredMessage            := Watched.clearWhenTriggered,
      incOptions                  := incOptions.value.withNameHashing(true),
      updateOptions               := updateOptions.value.withCachedResolution(true),
      dependencyUpdatesExclusions := moduleFilter(organization = "org.scala-lang")
                                   | moduleFilter(organization = "org.eclipse.jetty"),
      addCompilerPlugin("org.spire-math" %% "kind-projector" % Ver.KindProjector))
    .configure(
      addCommandAliases(
        "/"   -> "project root",
        "L"   -> "root/publishLocal",
        "C"   -> "root/clean",
        "T"   -> ";root/clean;root/test",
        "TL"  -> ";T;L",
        "c"   -> "compile",
        "tc"  -> "test:compile",
        "t"   -> "test",
        "to"  -> "test-only",
        "tq"  -> "test-quick",
        "cc"  -> ";clean;compile",
        "ctc" -> ";clean;test:compile",
        "ct"  -> ";clean;test")))

  def definesMacros = ConfigureBoth(
    _.settings(
      scalacOptions += "-language:experimental.macros",
      libraryDependencies ++= Seq(
        "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided")))

  def macroParadisePlugin =
    compilerPlugin("org.scalamacros" % "paradise" % Ver.MacroParadise cross CrossVersion.full)

  def utestSettings = ConfigureBoth(
    _.settings(
      libraryDependencies += "com.lihaoyi" %%% "utest" % Ver.MTest % "test",
      testFrameworks      += new TestFramework("utest.runner.Framework")))
    .jsConfigure(
      // Not mandatory; just faster.
      _.settings(jsEnv in Test := PhantomJSEnv().value))

  // ===================================================================================================================

  lazy val root =
    Project("root", file("."))
      .configure(commonSettings.jvm, preventPublication)
      .aggregate(rootJVM, rootJS, bench)

  lazy val rootJVM =
    Project("JVM", file(".rootJVM"))
      .configure(commonSettings.jvm, preventPublication)
      .aggregate(
        adtMacrosJVM, configJVM, macroUtilsJVM, nameFnJVM, nonemptyJVM, recursionJVM, scalazExtJVM, stdlibExtJVM, testUtilJVM)

  lazy val rootJS =
    Project("JS", file(".rootJS"))
      .configure(commonSettings.jvm, preventPublication)
      .aggregate(
        adtMacrosJS, configJS, macroUtilsJS, nameFnJS, nonemptyJS, recursionJS, scalazExtJS, stdlibExtJS, testUtilJS)

  // ===================================================================================================================

  lazy val adtMacrosJVM = adtMacros.jvm
  lazy val adtMacrosJS  = adtMacros.js
  lazy val adtMacros = crossProject
    .in(file("adt-macros"))
    .configureCross(commonSettings, publicationSettings, definesMacros, utestSettings)
    .dependsOn(macroUtils, nonempty)
    .settings(moduleName := "adt-macros")

  lazy val configJVM = config.jvm
  lazy val configJS  = config.js
  lazy val config = crossProject
    .configureCross(commonSettings, publicationSettings, utestSettings)
    .dependsOn(stdlibExt, testUtil % "test->compile")
    .settings(
      libraryDependencies ++= Seq(
        "org.scalaz"                 %%% "scalaz-core"   % Ver.Scalaz,
        "com.github.japgolly.univeq" %%% "univeq-scalaz" % Ver.UnivEq))

  lazy val macroUtilsJVM = macroUtils.jvm
  lazy val macroUtilsJS  = macroUtils.js
  lazy val macroUtils = crossProject
    .in(file("macro-utils"))
    .configureCross(commonSettings, publicationSettings, definesMacros, utestSettings)
    .settings(moduleName := "macro-utils")

  lazy val nameFnJVM = nameFn.jvm
  lazy val nameFnJS  = nameFn.js
  lazy val nameFn = crossProject
    .in(file("name-fn"))
    .configureCross(commonSettings, publicationSettings, definesMacros, utestSettings)
    .settings(moduleName := "name-fn")

  lazy val nonemptyJVM = nonempty.jvm
  lazy val nonemptyJS  = nonempty.js
  lazy val nonempty = crossProject
    .configureCross(commonSettings, publicationSettings, utestSettings)
    .settings(
      libraryDependencies ++= Seq(
        "org.scalaz"                 %%% "scalaz-core"   % Ver.Scalaz,
        "com.github.japgolly.univeq" %%% "univeq-scalaz" % Ver.UnivEq))

  lazy val recursionJVM = recursion.jvm
  lazy val recursionJS  = recursion.js
  lazy val recursion = crossProject
    .configureCross(commonSettings, publicationSettings, utestSettings)
    .settings(libraryDependencies += "org.scalaz" %%% "scalaz-core" % Ver.Scalaz)

  lazy val scalazExtJVM = scalazExt.jvm
  lazy val scalazExtJS  = scalazExt.js
  lazy val scalazExt = crossProject
    .in(file("scalaz-ext"))
    .configureCross(commonSettings, publicationSettings, definesMacros, utestSettings)
    .dependsOn(macroUtils)
    .settings(
      moduleName := "scalaz-ext",
      libraryDependencies += "org.scalaz" %%% "scalaz-core" % Ver.Scalaz)

  lazy val stdlibExtJVM = stdlibExt.jvm
  lazy val stdlibExtJS  = stdlibExt.js
  lazy val stdlibExt = crossProject
    .in(file("stdlib-ext"))
    .configureCross(commonSettings, publicationSettings, utestSettings)
    .settings(moduleName := "stdlib-ext")

  lazy val testUtilJVM = testUtil.jvm
  lazy val testUtilJS  = testUtil.js
  lazy val testUtil = crossProject
    .in(file("test-util"))
    .configureCross(commonSettings, publicationSettings)
    .settings(
      moduleName := "test-util",
      libraryDependencies += "org.scalaz" %%% "scalaz-core" % Ver.Scalaz)

  // ===================================================================================================================

  lazy val bench = project.in(file("bench"))
    .dependsOn(recursionJVM % "compile->test")
    .enablePlugins(JmhPlugin)
    .configure(commonSettings.jvm, preventPublication)
    .settings(
      name := "bench",
      libraryDependencies += "com.github.jbellis" % "jamm" % Ver.JAMM,
      fork := true,
      javaOptions ++= Seq("-server", "-Xss8M"),

      // Add the JAMM jar as an agent
      javaOptions in run := {
        val classPath = (dependencyClasspath in Compile).value
        val jammJar = classPath.collectFirst {
          case sbt.Attributed(f) if f.getName.matches("jamm-[0-9.]+\\.jar") => f.getAbsolutePath
        }.get
        val oldOptions = (javaOptions in run).value
        val newOptions = oldOptions :+ s"-javaagent:$jammJar"
        newOptions
      }
    )
}
