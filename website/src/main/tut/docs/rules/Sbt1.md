---
layout: docs
title: Sbt1
---

# Sbt1

**⚠️ Experimental**

Upgrade deprecated sbt 0.13 syntax to sbt 1.0 compatible syntax.
Note. This rule is only intended to be used with *.scala files for sbt 0.13.
This rule does not work for *.sbt files and there is no
need to run this rule if you are already using sbt 1.0.

To fix the sbt build sources of your build use __sbtfix___:

- Install {% doc_ref Installation, semanticdb-sbt %}

- Start a new sbt shell session or inside an active shell run `> reload`

- `> sbtfix Sbt1`

- Note that the command is __sbtfix__ to run on your sbt build sources.

To fix sources of an sbt 0.13 plugin use __scalafix__:

- Install the `semanticdb-sbt` compiler plugin to your sbt 0.13 plugin:

    ```scala
    // build.sbt
    lazy val my210project = project.settings(
      scalaVersion := "2.10.6", // semanticdb-sbt only supports 2.10.6
      addCompilerPlugin(
        "org.scalameta" % "semanticdb-sbt" % "{{ site.semanticdbSbt }}" cross CrossVersion.full
      )
    )
    ```

- Run `my210project/scalafix Sbt1`

- Note that the command is __scalafix__ to run on regular project sources.

```scala
// before
x <+= (y in Compile)
// after
x += (y in Compile).value
```

| Change          | Status |
|:---------------:|:------:|
| `<+=`           |  Done  |
| `<++=`          |  Done  |
| `<<=`           |  Done  |
| `extends Build` | Not handled, refer to the [migration guide](http://www.scala-sbt.org/1.x/docs/Migrating-from-sbt-013x.html#Migrating+from+the+Build+trait) |
| `(task1, task2).map` | Not handled, refer to the [migration guide](http://www.scala-sbt.org/1.x/docs/Migrating-from-sbt-013x.html#Migrating+from+the+tuple+enrichments) |
