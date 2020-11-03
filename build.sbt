import sbt.Keys.mainClass

// -----------------

lazy val _version = "2.17.0"

lazy val commonSettings = Seq(
  version := _version,
  scalaVersion := "2.12.10",
  organization := "org.constellation"
)

lazy val versions = new {
  val spongyCastle = "1.58.0.0"
  val micrometer = "1.5.5"
  val prometheus = "0.9.0"
  val cats = "2.2.0"
  val mockito = "1.15.0"
  val twitterChill = "0.9.3"
  val http4s = "0.21.7"
  val circe = "0.13.0"
  val circeEnumeratum = "1.6.1"
  val circeGenericExtras = "0.13.0"
  val fs2 = "2.4.4"
  val httpSigner = "0.3.3"
  val scaffeine = "4.0.1"
  val betterFiles = "3.9.1"
  val pureconfig = "0.13.0"
}

// -----------------

envVars in Test := Map("CL_STOREPASS" -> "storepass", "CL_KEYPASS" -> "keypass")
enablePlugins(JavaAgent, JavaAppPackaging)
addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
addCompilerPlugin(("org.scalamacros" % "paradise" % "2.1.1").cross(CrossVersion.full))

scalacOptions :=
  Seq(
    "-Ypartial-unification",
    "-unchecked",
    "-deprecation",
    "-feature",
    "-language:postfixOps",
    "-language:implicitConversions",
    "-language:higherKinds"
  )
javaAgents += "org.aspectj" % "aspectjweaver" % "1.9.4" % "runtime"

lazy val coreSettings = Seq(
  parallelExecution in Test := false,
  resolvers += "Artima Maven Repository".at("https://repo.artima.com/releases"),
  resolvers += "Typesafe Releases".at("https://repo.typesafe.com/typesafe/maven-releases/"),
  resolvers += "jitpack".at("https://jitpack.io"),
  resolvers += Resolver.sonatypeRepo("releases"),
  resolvers += Resolver.bintrayRepo("abankowski", "maven")
)

// -----------------

lazy val http4sDependencies = Seq(
  "org.http4s" %% "http4s-blaze-server",
  "org.http4s" %% "http4s-blaze-client",
  "org.http4s" %% "http4s-circe",
  "org.http4s" %% "http4s-dsl",
  "org.http4s" %% "http4s-prometheus-metrics",
  "org.http4s" %% "http4s-okhttp-client"
).map(_ % versions.http4s)

lazy val circeDependencies = Seq(
  "io.circe" %% "circe-core" % versions.circe,
  "io.circe" %% "circe-generic" % versions.circe,
  "io.circe" %% "circe-generic-extras" % versions.circeGenericExtras,
  "io.circe" %% "circe-parser" % versions.circe,
  "com.beachape" %% "enumeratum-circe" % versions.circeEnumeratum
)

lazy val fs2Dependencies = Seq(
  "co.fs2" %% "fs2-core",
  "co.fs2" %% "fs2-io",
  "co.fs2" %% "fs2-reactive-streams"
).map(_ % versions.fs2)

lazy val loggingDependencies = Seq(
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "io.chrisdavenport" %% "log4cats-slf4j" % "1.1.1"
)

lazy val catsDependencies = Seq(
  ("org.typelevel" %% "cats-core" % versions.cats).withSources().withJavadoc(),
  ("org.typelevel" %% "cats-effect" % versions.cats).withSources().withJavadoc()
)

lazy val spongyCastleDependencies = Seq(
  "com.madgag.spongycastle" % "core" % versions.spongyCastle,
  "com.madgag.spongycastle" % "prov" % versions.spongyCastle,
  "com.madgag.spongycastle" % "bcpkix-jdk15on" % versions.spongyCastle,
  "com.madgag.spongycastle" % "bcpg-jdk15on" % versions.spongyCastle,
  "com.madgag.spongycastle" % "bctls-jdk15on" % versions.spongyCastle,
  "org.bouncycastle" % "bcprov-jdk15on" % "1.65"
)

lazy val prometheusDependencies = Seq(
  "io.micrometer" % "micrometer-registry-prometheus" % versions.micrometer,
  "io.prometheus" % "simpleclient" % versions.prometheus,
  "io.prometheus" % "simpleclient_common" % versions.prometheus,
  "io.prometheus" % "simpleclient_caffeine" % versions.prometheus,
  "io.prometheus" % "simpleclient_logback" % versions.prometheus
)

// -----------------

lazy val sharedDependencies = Seq(
  "com.github.scopt" %% "scopt" % "4.0.0-RC2",
  "joda-time" % "joda-time" % "1.6"
) ++ circeDependencies ++ catsDependencies ++ loggingDependencies

lazy val keyToolSharedDependencies = Seq(
  "com.google.cloud" % "google-cloud-storage" % "1.91.0"
) ++ spongyCastleDependencies ++ sharedDependencies

lazy val walletSharedDependencies = Seq(
  "com.twitter" %% "chill" % versions.twitterChill,
  "com.twitter" %% "algebird-core" % "0.13.5"
) ++ sharedDependencies

lazy val schemaSharedDependencies = keyToolSharedDependencies ++ walletSharedDependencies

lazy val coreDependencies = Seq(
  ("com.github.pathikrit" %% "better-files" % versions.betterFiles).withSources().withJavadoc(),
  "com.github.japgolly.scalacss" %% "ext-scalatags" % "0.6.1",
  "com.github.djelenc" % "alpha-testbed" % "1.0.3", // eigen trust
  ("com.github.blemale" %% "scaffeine" % versions.scaffeine).withSources().withJavadoc(),
  "net.logstash.logback" % "logstash-logback-encoder" % "6.4",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.863",
  "pl.abankowski" %% "http-request-signer-core" % versions.httpSigner,
  "pl.abankowski" %% "http4s-request-signer" % versions.httpSigner,
  "com.github.pureconfig" %% "pureconfig" % versions.pureconfig,
  "io.chrisdavenport" %% "fuuid" % "0.4.0"
) ++ prometheusDependencies ++ http4sDependencies ++ schemaSharedDependencies

//Test dependencies
lazy val testDependencies = Seq(
  "org.scalacheck" %% "scalacheck" % "1.14.3",
  "org.scalatest" %% "scalatest" % "3.2.2",
  "org.scalactic" %% "scalactic" % "3.2.2",
  "org.scalamock" %% "scalamock" % "5.0.0",
  "org.mockito" %% "mockito-scala" % versions.mockito,
  "org.mockito" %% "mockito-scala-cats" % versions.mockito
).map(_ % "it,test")

// -----------------

testOptions in Test += Tests.Setup(() => System.setProperty("macmemo.disable", "true"))
testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-u", "target/test-results/scalatest")

test in assembly := {}

Test / fork := true // <-- comment out to attach debugger
Test / logBuffered := false

assemblyMergeStrategy in assembly := {
  case "logback.xml"                                       => MergeStrategy.first
  case x if x.contains("io.netty.versions.properties")     => MergeStrategy.discard
  case PathList(xs @ _*) if xs.last == "module-info.class" => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

// -----------------

lazy val keytool = (project in file("keytool"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    commonSettings,
    name := "keytool",
    buildInfoKeys := Seq[BuildInfoKey](
      version
    ),
    buildInfoPackage := "org.constellation.keytool",
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    mainClass := Some("org.constellation.keytool.KeyTool"),
    libraryDependencies ++= keyToolSharedDependencies
  )

lazy val schema = (project in file("schema"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(keytool)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](
      version
    ),
    buildInfoPackage := "org.constellation.schema",
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    libraryDependencies ++= schemaSharedDependencies
  )

lazy val wallet = (project in file("wallet"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(schema)
  .settings(
    commonSettings,
    name := "wallet",
    buildInfoKeys := Seq[BuildInfoKey](
      version
    ),
    buildInfoPackage := "org.constellation.wallet",
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    mainClass := Some("org.constellation.wallet.Wallet"),
    libraryDependencies ++= walletSharedDependencies
  )

lazy val root = (project in file("."))
  .dependsOn(schema)
  .disablePlugins(plugins.JUnitXmlReportPlugin)
  .configs(IntegrationTest)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      "gitBranch" -> git.gitCurrentBranch.value,
      "gitCommit" -> git.gitHeadCommit.value.getOrElse("commit N/A")
    ),
    buildInfoPackage := "org.constellation",
    buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
    commonSettings,
    name := "constellation",
    coreSettings,
    Defaults.itSettings,
    libraryDependencies ++= (coreDependencies ++ testDependencies),
    mainClass := Some("org.constellation.ConstellationNode$")
    // other settings here
  )
