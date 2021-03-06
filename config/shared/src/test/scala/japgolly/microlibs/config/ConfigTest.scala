package japgolly.microlibs.config

import japgolly.microlibs.testutil.TestUtil._
import scalaz.{-\/, \/-}
import scalaz.Scalaz.Id
import scalaz.std.AllInstances._
import scalaz.syntax.applicative._
import utest._
import ConfigParser.Implicits.Defaults._
import Helpers._

object ConfigTest extends TestSuite {

  override def tests = TestSuite {

    'findFirst -
      assertEq(Config.need[String]("s").run(srcs), ConfigResult.Success("hey"))

    'findSecond -
      assertEq(Config.need[Int]("i2").run(srcs), ConfigResult.Success(22))

    'notFound -
      assertEq(Config.get[Int]("notfound").run(srcs), ConfigResult.Success(Option.empty[Int]))

    'missing1 -
      assertEq(Config.need[Int]("missing").run(srcs), ConfigResult.QueryFailure(Map(Key("missing") -> None), Set.empty, srcNames))

    'missing2 -
      assertEq(
        (Config.need[Int]("no1") tuple Config.need[Int]("no2")).run(srcs),
        ConfigResult.QueryFailure(Map(Key("no1") -> None, Key("no2") -> None), Set.empty, srcNames))

    'valueFail1 -
      assertEq(
        Config.need[Int]("s").run(srcs),
        ConfigResult.QueryFailure(Map(Key("s") -> Some((src1.name, ConfigValue.Error("Int expected.", Some("hey"))))), Set.empty, srcNames))

    'valueFail2 -
      assertEq(
        Config.need[Int]("s2").run(srcs),
        ConfigResult.QueryFailure(Map(Key("s2") -> Some((src2.name, ConfigValue.Error("Int expected.", Some("ah"))))), Set.empty, srcNames))

    'errorMsg {
      'notFound - assertEq(Config.need[Int]("QQ").run(srcs).toDisjunction, -\/(
        """
          |1 error:
          |  - No value for key [QQ]
          |
          |2 sources (highest to lowest priority):
          |  - S1
          |  - S2
        """.stripMargin.trim))

      'notFound2 - {
        val c = Config.need[Int]("QQ") tuple Config.get[Int]("X") tuple Config.need[Int]("i") tuple Config.need[Int]("M")
        assertEq(c.run(srcs).toDisjunction, -\/(
          """
            |2 errors:
            |  - No value for key [M]
            |  - No value for key [QQ]
            |
            |2 sources (highest to lowest priority):
            |  - S1
            |  - S2
          """.stripMargin.trim))
      }

      'errors2 - {
        val c = Config.need[Int]("s") tuple Config.get[Int]("X")
        assertEq(c.run(srcs > srcE).toDisjunction, -\/(
          """
            |2 errors:
            |  - Error reading key [X] from source [SE]: This source is fake!
            |  - Error reading key [s] from source [S1] with value [hey]: Int expected.
            |
            |3 sources (highest to lowest priority):
            |  - S1
            |  - S2
            |  - SE
          """.stripMargin.trim))
      }

      'unkeyedErrors {
        val c1 = Config.need[Int]("in").map(_ + 1000).ensure(_ > 1150, "Must be > 1150.")
        val c2 = 7.point[Config].ensure(_ > 10, "Must be > 10.")
        val c3 = (Config.need[Int]("in") |@| Config.need[Int]("i2"))(_ + _).ensure(_ > 150, "Must be > 150.")
        val c = c1 tuple c2 tuple c3
        assertEq(c.run(srcs > srcE).toDisjunction, -\/(
          """
            |3 errors:
            |  - Error using <function>, key [i2], key [in]: Must be > 150.
            |  - Error using <function>, key [in]: Must be > 1150.
            |  - Error using runtime value [7]: Must be > 10.
            |
            |3 sources (highest to lowest priority):
            |  - S1
            |  - S2
            |  - SE
          """.stripMargin.trim))
      }
    }

    'ensure {
      'read1 {
        val c = Config.need[Int]("in")
        'ok - assertEq(
          c.ensure(_ < 150, "Must be < 150.").run(srcs),
          ConfigResult.Success(100))
        'ko - assertEq(
          c.ensure(_ > 150, "Must be > 150.").run(srcs),
          ConfigResult.QueryFailure(Map(Key("in") -> Some((src1.name, ConfigValue.Error("Must be > 150.", Some("100"))))), Set.empty, srcNames))
      }

      'readMap1 {
        val c = Config.need[Int]("in").map(_ + 1000)
        'ok - assertEq(
          c.ensure(_ > 1050, "Must be > 1050.").run(srcs),
          ConfigResult.Success(1100))
        'ko - assertEq(
          c.ensure(_ > 1150, "Must be > 1150.").run(srcs),
          ConfigResult.QueryFailure(Map.empty, Set("Must be > 1150." -> Set(\/-(Key("in")), -\/("<function>"))), srcNames))
      }

      'read2 {
        val c = (Config.need[Int]("in") |@| Config.need[Int]("i2"))(_ + _)
        'ok - assertEq(
          c.ensure(_ < 150, "Must be < 150.").run(srcs),
          ConfigResult.Success(122))
        'ko - assertEq(
          c.ensure(_ > 150, "Must be > 150.").run(srcs),
          ConfigResult.QueryFailure(Map.empty, Set("Must be > 150." -> Set(\/-(Key("in")), \/-(Key("i2")), -\/("<function>"))), srcNames))
      }
    }

    'keyMod {
      'prefix {
        val s = Source.manual[Id]("S")(
          "a.b.1" -> "AB-1", "a.1" -> "A!", "b.1" -> "B!", "1" -> "I",
          "a.b.2" -> "AB-2", "a.2" -> "A@", "b.2" -> "B@", "2" -> "II")
        val one = Config.need[String]("1")
        val two = Config.need[String]("2")

        * - assertEq((one tuple two.withPrefix("b.")).run(s).get_!, ("I", "B@"))
        * - assertEq((one.withPrefix("b.") tuple two).run(s).get_!, ("B!", "II"))
        * - assertEq((one tuple two).withPrefix("b.").run(s).get_!, ("B!", "B@"))
        * - assertEq((one tuple two.withPrefix("b.")).withPrefix("a.").run(s).get_!, ("A!", "AB-2"))

        'missing - assertEq(
          one.withPrefix("omg.").run(s),
          ConfigResult.QueryFailure(Map(Key("omg.1") -> None), Set.empty, Vector(s.name)))
      }

      'caseInsensitive {
        val c = Config.get[String]("S2")
        assertEq((c tuple c.withCaseInsensitiveKeys).run(srcs).get_!, (None, Some("ah")))
      }
    }

    'consumerFn {
      class Mutable {
        var a = 1
        var b = 2
        var name = ""
        def setA(x: Int): Unit = a = x
        def setB(x: Int): Unit = b = x
        def setName(x: String): Unit = name = x
      }
      val c = Config.consumerFn[Mutable](
        _.get("in", _.setA),
        _.get("nope", _.setB),
        _.need("s", _.setName)
      ).map{ fn =>
        val m = new Mutable
        fn(m)
        m
      }
      val m = c.run(srcs).get_!
      assertEq((m.a, m.b, m.name), (100, 2, "hey"))
    }

    'consumerFnC {
      class Mutable {
        var a = 1
        var b = 2
        var name = ""
      }
      val c = Config.consumerFn[Mutable](
        _.getC[Int]("in", _.a = _),
        _.getC[Int]("nope", _.b = _),
        _.getC[String]("s", _.name = _)
      ).map{ fn =>
        val m = new Mutable
        fn(m)
        m
      }
      val m = c.run(srcs).get_!
      assertEq((m.a, m.b, m.name), (100, 2, "hey"))
    }

    'mapKeyQueries {
      val s = Source.manual[Id]("S")(
        "both.1" -> "YAY", "both_1" -> "NOPE",
        "db_port" -> "1234")
        .mapKeyQueries(k => List(k, k.replace('.', '_')))

      'alternate - assertEq(Config.need[Int]("db.port").run(s).get_!, 1234)
      'priority - assertEq(Config.need[String]("both.1").run(s).get_!, "YAY")
    }

    'choice {
      val ci = Config.need[Int]("C").ensure(1.to(2).contains, "Choose 1 or 2")
      val c1 = Config.need[String]("C1")
      val c2 = Config.need[String]("C2")
      val cc = ci.choose(i => if (i == 1) c1 else c2)
      val v1 = "see one"
      val v2 = "sea too"
      val s1 = Source.manual[Id]("S1")("C" -> "1", "C1" -> v1)
      val s2 = Source.manual[Id]("S2")("C" -> "2", "C2" -> v2)

      'c1 {
        assertEq(cc.run(s1).get_!, v1)
        assertEq(cc.run(s1 > s2).get_!, v1)
      }
      'c2 {
        assertEq(cc.run(s2).get_!, v2)
        assertEq(cc.run(s2 > s1).get_!, v2)
      }
    }

  }
}
