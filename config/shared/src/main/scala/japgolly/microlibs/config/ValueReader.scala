package japgolly.microlibs.config

import japgolly.microlibs.stdlib_ext._
import java.util.regex.Pattern
import scalaz.{-\/, \/, \/-}

final class ValueReader[A](val read: ConfigValue.Found => String \/ A) extends AnyVal {
  def map[B](f: A => B): ValueReader[B] =
    new ValueReader(read(_) map f)

  def mapAttempt[B](f: A => String \/ B): ValueReader[B] =
    new ValueReader(read(_) flatMap f)

  def flatMap[B](f: A => ValueReader[B]): ValueReader[B] =
    new ValueReader(v => read(v).flatMap(f(_) read v))

  def ensure(test: A => Boolean, errorMsg: A => String): ValueReader[A] =
    new ValueReader(read(_).flatMap(a => if (test(a)) \/-(a) else -\/(errorMsg(a))))
}

object ValueReader {

  @inline def apply[A](implicit r: ValueReader[A]) = r

  object Implicits {

    private val RegexTrue = Pattern.compile("^(?:t(?:rue)?|y(?:es)?|1|on|enabled?)$", Pattern.CASE_INSENSITIVE)
    private val RegexFalse = Pattern.compile("^(?:f(?:alse)?|n(?:o)?|0|off|disabled?)$", Pattern.CASE_INSENSITIVE)

    object StringAsIs extends StringAsIs
    trait StringAsIs {
      implicit val readString: ValueReader[String] =
        new ValueReader(v => \/-(v.value))
    }

    object StringDefault extends StringDefault
    trait StringDefault {
      implicit val readString: ValueReader[String] =
        StringAsIs.readString.map(_.trim.replaceFirst("\\s*#.*$", ""))
    }

    trait Primitives {
      implicit def readInt(implicit s: ValueReader[String]): ValueReader[Int] =
        s.mapAttempt {
          case ParseInt(i) => \/-(i)
          case _ => -\/("Int expected.")
        }

      implicit def readLong(s: ValueReader[String]): ValueReader[Long] =
        s.mapAttempt {
          case ParseLong(l) => \/-(l)
          case _ => -\/("Long expected.")
        }

      implicit def readBoolean(s: ValueReader[String]): ValueReader[Boolean] =
        s.mapAttempt(s =>
          if (RegexTrue.matcher(s).matches)
            \/-(true)
          else if (RegexFalse.matcher(s).matches)
            \/-(false)
          else
            -\/("Boolean expected.")
        )
    }

    object Defaults extends Defaults
    trait Defaults extends Primitives with StringDefault
  }
}

