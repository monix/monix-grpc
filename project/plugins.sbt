resolvers += Resolver.sonatypeRepo("staging")
resolvers += Resolver.sonatypeRepo("snapshots")
resolvers += Resolver.bintrayIvyRepo("sbt", "sbt-plugin-releases")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.1")
addSbtPlugin("com.thesamet" % "sbt-protoc-gen-project" % "0.1.6")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.10.11"

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")

addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.8.1")
addSbtPlugin("ch.epfl.scala" % "sbt-release-early" % "2.1.1+10-c6ef3f60")
addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % "0.7.0")
