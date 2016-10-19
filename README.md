# Sonic Akka [![Build Status](https://travis-ci.org/xarxa6/sonic-akka.svg)](https://travis-ci.org/xarxa6/sonic-akka)

Akka Streams API for the Sonic protocol

# Installation
Add to your `plugins.sbt`:
```
addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0")
```

Add to your `build.sbt` or `Build.scala`:
```
   resolvers += Resolver.bintrayRepo("ernestrc", "maven"),

   libraryDependencies ++= {
     Seq("build.unstable" %% "sonic-core" % "0.6.4")
   },

```

# Usage
Check the [./examples/src/main](./examples/src/main) directory for examples in both Java and Scala.

# Contribute
If you would like to contribute to the project, please fork the project, include your changes and submit a pull request back to the main repository.

# License
MIT License 
