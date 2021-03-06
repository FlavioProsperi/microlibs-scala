package japgolly.microlibs.config

import japgolly.microlibs.testutil.TestUtil._
import scalaz.std.AllInstances._
import scalaz.{-\/, Equal, \/-}
import utest._
import Helpers._

object ConfigParserTest extends TestSuite {

  val k = Key("k")

  def testOk[A: ConfigParser: Equal](origValue: String, expect: A): Unit =
    assertEq(ConfigParser[A].parse(ConfigValue.Found(k, origValue)), \/-(expect))

  def testBad[A: ConfigParser : Equal](origValue: String, errorFrag: String = ""): Unit =
    ConfigParser[A].parse(ConfigValue.Found(k, origValue)) match {
      case -\/(e) => assertContainsCI(e, errorFrag)
      case \/-(a) => fail(s"Error expected containing '$errorFrag', instead it passed with $a.")
    }

  override def tests = TestSuite {

    'defaults {
      import ConfigParser.Implicits.Defaults._

      'string {
        testOk("qWe", "qWe")
      }

      'double {
        testOk[Double]("123", 123)
        testOk[Double]("-3", -3.0)
        testOk[Double]("1.2", 1.2)
        testOk[Double]("0.2", 0.2)
        testOk[Double](".2", 0.2)
        testBad[Double]("X")
        testBad[Double]("123X")
        testBad[Double]("X123")
        testBad[Double]("")
      }

      'int {
        testOk("123", 123)
        testOk("-3", -3)
        testBad[Int]("X")
        testBad[Int]("123X")
        testBad[Int]("X123")
        testBad[Int]("3.4")
        testBad[Int]("")
      }

      'long {
        testOk("123", 123L)
        testOk("-3", -3L)
        testBad[Long]("X")
      }

      'boolean {
        testOk("1", true)
        testOk("true", true)
        testOk("TRUE", true)
        testOk("t", true)
        testOk("T", true)
        testOk("enabled", true)

        testOk("0", false)
        testOk("false", false)
        testOk("False", false)
        testOk("f", false)
        testOk("F", false)
        testOk("disabled", false)

        testBad[Boolean]("what")
        testBad[Boolean]("")
      }

      'whitespace {
        testOk(" a b c  ", "a b c")
        testOk("   124  ", 124)
      }

      'comments {
        testOk("x # y # z", "x")
        testOk(" # y # z", "")
        testOk("# y # z", "")
        testOk("36 # hehe what?! 1", 36)
        testOk("136 #", 136)
        testOk("blah!@#!@#", "blah!@#!@#")
        testOk("blah!@#!@# ", "blah!@#!@#")
        testOk("blah!@#!@# # a password", "blah!@#!@#")
      }
    }

    'ensure {
      import ConfigParser.Implicits.Defaults._

      'ok - assertEq(
        Config.need("in")(ConfigParser[Int].ensure(_ < 150, "Must be < 150.")).run(srcs),
        ConfigResult.Success(100))

      'ko - assertEq(
        Config.need("in")(ConfigParser[Int].ensure(_ > 150, "Must be > 150.")).run(srcs),
        ConfigResult.QueryFailure(Map(Key("in") -> Some((src1.name, ConfigValue.Error("Must be > 150.", Some("100"))))), Set.empty, srcNames))
    }

  }
}
