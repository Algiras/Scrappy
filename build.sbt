name := "scrappy"
version := "0.0.1-SNAPSHOT"

scalaVersion := "2.12.7"

resolvers += Resolver.sonatypeRepo("releases")

scalacOptions ++= Seq(
  "-encoding", "UTF-8",    // source files are in UTF-8
  "-deprecation",          // warn about use of deprecated APIs
  "-unchecked",            // warn about unchecked type parameters
  "-feature",              // warn about misused language features
  "-language:higherKinds", // allow higher kinded types without `import scala.language.higherKinds`
  "-Xlint",                // enable handy linter warnings
  "-Xfatal-warnings",      // turn compiler warnings into errors
  "-Ypartial-unification", // allow the compiler to unify type constructors of different arities,
  "-Yrangepos"             // Use range positions for syntax trees.
)

libraryDependencies ++= Seq(
  "org.seleniumhq.selenium" % "selenium-java" % "3.141.59",
  "org.seleniumhq.selenium" % "selenium-firefox-driver" % "3.141.59",
  "ch.qos.logback"  %  "logback-classic"  % "1.2.3",

  "io.chrisdavenport" %% "log4cats-core" % "1.0.1",
  "io.chrisdavenport" %% "log4cats-slf4j" % "1.0.1",

"com.github.pureconfig" %% "pureconfig"             % "0.12.2",
  "com.github.pureconfig" %% "pureconfig-cats-effect" % "0.12.2",
  "com.github.pureconfig" %% "pureconfig-generic"     % "0.12.2",

  "org.typelevel" %% "cats-core" % "2.0.0",
  "org.typelevel" %% "cats-effect" % "2.0.0",
  "co.fs2" %% "fs2-core" % "2.1.0",
  "io.chrisdavenport" % "mules_2.12" % "0.3.0",

  "com.github.nscala-time" %% "nscala-time" % "2.22.0",

  "com.pepegar" %% "hammock-core" % "0.10.0",
  "com.pepegar" %% "hammock-circe" % "0.10.0",
  "com.pepegar" %% "hammock-apache-http" % "0.10.0",

  "io.circe" %% "circe-core" % "0.12.2",
  "io.circe" %% "circe-generic" % "0.12.2",
  "io.circe" %% "circe-parser" % "0.12.2",
  "io.circe" % "circe-generic-extras_2.12" % "0.12.2",

  "org.http4s" %% "http4s-dsl" % "0.20.8",
  "org.http4s" %% "http4s-circe" % "0.20.8",
  "org.http4s" %% "http4s-blaze-server" % "0.20.8",

  "org.specs2" %% "specs2-core" % "4.6.0" % "test",
  "org.mockito" % "mockito-core" % "3.2.4" % "test"
)

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")
addCompilerPlugin(
  "org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full
)

