name := "scrappy"
version := "0.0.1-SNAPSHOT"

scalaVersion := "2.12.7"

resolvers += Resolver.sonatypeRepo("releases")

scalacOptions ++= Seq(
  "-encoding", "UTF-8", // source files are in UTF-8
  "-deprecation", // warn about use of deprecated APIs
  "-unchecked", // warn about unchecked type parameters
  "-feature", // warn about misused language features
  "-language:higherKinds", // allow higher kinded types without `import scala.language.higherKinds`
  "-Xlint", // enable handy linter warnings
  "-Xfatal-warnings", // turn compiler warnings into errors
  "-Ypartial-unification", // allow the compiler to unify type constructors of different arities,
  "-Yrangepos" // Use range positions for syntax trees.
)

// Versions
val seleniumVersion = "3.141.59"
val log4CatsVersion = "1.0.1"
val logbackVersion = "1.2.3"
val pureConfigVersion = "0.12.2"
val catsVersion = "2.0.0"
val catsEffectVersion = "2.0.0"
val fs2Version = "2.1.0"
val nScalaVersion = "2.22.0"
val circeVersion = "0.12.3"
val http4sVersion = "0.21.0-M6"
// Test library version
val spec2Version = "4.6.0"
// Special cases
val circeGenericExtrasVersion = "0.12.1"
// Compiler Plugins
val kindProjector = "0.9.3"
val paradise = "2.1.1"

libraryDependencies ++= Seq(
  "org.seleniumhq.selenium" % "selenium-java" % seleniumVersion,
  "org.seleniumhq.selenium" % "selenium-firefox-driver" % seleniumVersion,
  "org.seleniumhq.selenium" % "selenium-chrome-driver" % seleniumVersion,

  "io.chrisdavenport" %% "log4cats-core" % log4CatsVersion,
  "io.chrisdavenport" %% "log4cats-slf4j" % log4CatsVersion,
  "ch.qos.logback" % "logback-classic" % logbackVersion,

  "com.github.pureconfig" %% "pureconfig" % pureConfigVersion,
  "com.github.pureconfig" %% "pureconfig-cats-effect" % pureConfigVersion,
  "com.github.pureconfig" %% "pureconfig-generic" % pureConfigVersion,

  "org.typelevel" %% "cats-core" % catsVersion,

  "org.typelevel" %% "cats-effect" % catsEffectVersion,

  "co.fs2" %% "fs2-core" % fs2Version,
  "co.fs2" %% "fs2-io" % fs2Version,

  "com.github.nscala-time" %% "nscala-time" % nScalaVersion,

  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "io.circe" % "circe-generic-extras_2.12" % circeGenericExtrasVersion,

  "org.http4s" % "http4s-core_2.12" % http4sVersion,
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
  "org.http4s" %% "http4s-client" % http4sVersion,

  "org.specs2" %% "specs2-core" % spec2Version % "test"
)

addCompilerPlugin("org.spire-math" %% "kind-projector" % kindProjector)
addCompilerPlugin(
  "org.scalamacros" % "paradise" % paradise cross CrossVersion.full
)

assemblyMergeStrategy in assembly := {
  case "application.conf" => MergeStrategy.singleOrError
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
