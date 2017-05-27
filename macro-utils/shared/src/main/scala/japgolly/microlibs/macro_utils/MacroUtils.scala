package japgolly.microlibs.macro_utils

import scala.annotation.tailrec

object MacroUtils {
  sealed trait FindSubClasses
  case object DirectOnly extends FindSubClasses
  case object LeavesOnly extends FindSubClasses
  case object Everything extends FindSubClasses
}

abstract class MacroUtils {
  val c: scala.reflect.macros.blackbox.Context
  import c.universe._
  import MacroUtils.FindSubClasses

  sealed trait TypeOrTree
  case class GotType(t: Type) extends TypeOrTree
  case class GotTree(t: Tree) extends TypeOrTree
  implicit def autoTypeOrTree1(t: Type): TypeOrTree = GotType(t)
  implicit def autoTypeOrTree2(t: Tree): TypeOrTree = GotTree(t)

  @inline final def DirectOnly = MacroUtils.DirectOnly
  @inline final def LeavesOnly = MacroUtils.LeavesOnly
  @inline final def Everything = MacroUtils.Everything

  final def sep = ("_" * 120) + "\n"

  final def fail(msg: String): Nothing =
    c.abort(c.enclosingPosition, msg)

  final def warn(msg: String): Unit =
    c.warning(c.enclosingPosition, msg)

  final def concreteWeakTypeOf[T: c.WeakTypeTag]: Type = {
    val t = weakTypeOf[T]
    ensureConcrete(t)
    t
  }

  final def ensureConcrete(t: Type): Unit = {
    val sym = t.typeSymbol.asClass
    if (sym.isAbstract)
      fail(s"${sym.name} is abstract which is not allowed.")
    if (sym.isTrait)
      fail(s"${sym.name} is a trait which is not allowed.")
    if (sym.isSynthetic)
      fail(s"${sym.name} is synthetic which is not allowed.")
  }

  final def caseClassType[T: c.WeakTypeTag]: Type = {
    val t = concreteWeakTypeOf[T]
    ensureCaseClass(t)
    t
  }

  final def ensureCaseClass(t: Type): Unit = {
    val sym = t.typeSymbol.asClass
    if (!sym.isCaseClass)
      fail(s"${sym.name} is not a case class.")
  }

  final def primaryConstructorParams(t: Type): List[Symbol] =
    t.decls
      .collectFirst { case m: MethodSymbol if m.isPrimaryConstructor => m }
      .getOrElse(fail("Unable to discern primary constructor."))
      .paramLists
      .headOption
      .getOrElse(fail("Primary constructor missing paramList."))

  final def primaryConstructorParams_require1(t: Type): Symbol =
    primaryConstructorParams(t) match {
      case p :: Nil => p
      case x        => fail(s"One field expected. ${t.typeSymbol.name} has: $x")
    }

  final def primaryConstructorParams_require2(t: Type): (Symbol, Symbol) =
    primaryConstructorParams(t) match {
      case a :: b :: Nil => (a, b)
      case x             => fail(s"Two fields expected. ${t.typeSymbol.name} has: $x")
    }

  final type NameAndType = (TermName, Type)

  final def nameAndType(T: Type, s: Symbol): NameAndType = {
    def paramType(name: TermName): Type =
      T.decl(name).typeSignatureIn(T) match {
        case NullaryMethodType(t) => t
        case t                    => t
      }

    val a = s.asTerm.name
    val A = paramType(a)
    (a, A)
  }

  final def ensureValidAdtBase(tpe: Type): ClassSymbol = {
    tpe.typeConstructor // https://issues.scala-lang.org/browse/SI-7755
    val sym = tpe.typeSymbol.asClass

    if (!sym.isSealed)
      fail(s"${sym.name} must be sealed.")

    if (!(sym.isAbstract || sym.isTrait))
      fail(s"${sym.name} must be abstract or a trait.")

    if (sym.knownDirectSubclasses.isEmpty)
      fail(s"${sym.name} does not have any sub-classes. This may happen due to a limitation of scalac (SI-7046). 95% fixed in Scala 2.11.11 & 2.12.1.")

    sym
  }

  final def crawlADT[A](tpe: Type, f: ClassSymbol => Option[A], giveUp: ClassSymbol => Vector[A]): Vector[A] = {
    def go(t: Type, as: Vector[A]): Vector[A] = {
      val tb = ensureValidAdtBase(t)
      tb.knownDirectSubclasses.foldLeft(as) { (q, sub) =>
        val subClass = sub.asClass
        val subType = sub.asType.toType

        f(subClass) match {
          case Some(a) =>
            q :+ a
          case None =>
            if (subClass.isAbstract || subClass.isTrait)
              go(subType, q)
            else
              q ++ giveUp(subClass)
        }
      }
    }
    go(tpe, Vector.empty)
  }

  /**
   * Constraints:
   * - Type must be sealed.
   * - Type must be abstract or a trait.
   */
  final def findConcreteTypes(tpe: Type, f: FindSubClasses): Set[ClassSymbol] = {
     val sym = ensureValidAdtBase(tpe)

    def findSubClasses(p: ClassSymbol): Set[ClassSymbol] = {
      p.knownDirectSubclasses.flatMap { sub =>
        val subClass = sub.asClass
        if (subClass.isTrait)
          findSubClasses(subClass)
        else f match {
          case MacroUtils.DirectOnly => Set(subClass)
          case MacroUtils.Everything => Set(subClass) ++ findSubClasses(subClass)
          case MacroUtils.LeavesOnly =>
            val s = findSubClasses(subClass)
            if (s.isEmpty)
              Set(subClass)
            else
              s
        }
      }
    }

    findSubClasses(sym)
  }

  final def findConcreteTypesNE(tpe: Type, f: FindSubClasses): Set[ClassSymbol] = {
    val r = findConcreteTypes(tpe, f)
    if (r.isEmpty)
      fail(s"Unable to find concrete types for ${tpe.typeSymbol.name}.")
    r
  }

  final def findConcreteAdtTypes(tpe: Type, f: FindSubClasses): Set[Type] =
    findConcreteTypes(tpe, f) map (determineAdtType(tpe, _))

  final def findConcreteAdtTypesNE(tpe: Type, f: FindSubClasses): Set[Type] =
    findConcreteTypesNE(tpe, f) map (determineAdtType(tpe, _))

  /**
   * findConcreteTypes will spit out type constructors. This will turn them into types.
   *
   * @param T The ADT base trait.
   * @param t The subclass.
   */
  final def determineAdtType(T: Type, t: ClassSymbol): Type = {
    val t2 =
      if (t.typeParams.isEmpty)
        t.toType
      else if (t.isCaseClass)
        caseClassTypeCtorToType(T, t)
      else
        t.toType
    require(t2 <:< T, s"$t2 is not a subtype of $T")
    t2
  }

  /**
   * Turns a case class type constructor into a type.
   *
   * Eg. caseClassTypeCtorToType(Option[Int], Some[_]) → Some[Int]
   *
   * Actually this doesn't work with type variance :(
   */
  private def caseClassTypeCtorToType(baseTrait: Type, caseclass: ClassSymbol): Type = {
    val companion = caseclass.companion
    val apply = companion.typeSignature.member(TermName("apply"))
    if (apply == NoSymbol)
      fail(s"Don't know how to turn $caseclass into a real type of $baseTrait; it's generic and its companion has no `apply` method.")

    val matchArgs = apply.asMethod.paramLists.flatten.map { arg => pq"_" }
    val name = TermName(c.freshName("x"))
    c.typecheck(q"""(??? : $baseTrait) match {case $name@$companion(..$matchArgs) => $name }""").tpe
  }

  final def flattenBlocks(trees: List[Tree]): Vector[Tree] = {
    @tailrec def go(acc: Vector[Tree], ts: List[Tree]): Vector[Tree] =
      ts match {
        case                 Nil => acc
        case Block(a, b) :: tail => go(acc, a ::: b :: tail)
        case h           :: tail => go(acc :+ h, tail)
      }
    go(Vector.empty, trees)
  }
//  final def flattenBlocks(trees: GenTraversable[Tree]): Vector[Tree] = {
//    import _
//    @tailrec final def go(acc: Vector[Tree], ts: GenTraversable[Tree]): Vector[Tree] =
//      ts.headOption match {
//        case None              => acc
//        case Some(Block(a, b)) => go(acc, (a :+ b) ++ ts.tail)
//        case Some(h)           => go(acc :+ h, ts.tail)
//      }
//    go(Vector.empty, trees)
//  }

  final def modStringHead(s: String, f: Char => Char): String =
    if (s.isEmpty)
      ""
    else {
      val h = f(s.head).toString
      if (s.length == 1)
        h
      else
        h + s.tail
    }

  final def lowerCaseHead(s: String): String =
    modStringHead(s, _.toLower)

  final def readMacroArg_boolean(e: c.Expr[Boolean]): Boolean =
    e match {
      case Expr(Literal(Constant(b: Boolean))) => b
      case _ => fail(s"Expected a literal boolean, got: ${showRaw(e)}")
    }

  final def readMacroArg_string(e: c.Expr[String]): String =
    e match {
      case Expr(Literal(Constant(s: String))) => s
      case _ => fail(s"Expected a literal string, got: ${showRaw(e)}")
    }

  final def readMacroArg_symbol(e: c.Expr[scala.Symbol]): String =
    e match {
      case Expr(Apply(_, List(Literal(Constant(n: String))))) => n
      case _ => fail(s"Expected a symbol, got: ${showRaw(e)}")
    }

  final def readMacroArg_stringString(e: c.Expr[(String, String)]): (String, Literal) =
    e match {
      // "k" -> "v"
      case Expr(Apply(TypeApply(Select(Apply(_, List(Literal(Constant(k: String)))), mg), _), List(v@Literal(Constant(_: String))))) =>
        (k, v)
      case x =>
        fail(s"""Expected "k" -> "v", got: $x\n${showRaw(x)}""")
    }

  final def readMacroArg_symbolString(e: c.Expr[(scala.Symbol, String)]): (String, Literal) =
    e match {
      // 'k -> "v"
      case Expr(Apply(TypeApply(Select(Apply(_, List(Apply(_, List(Literal(Constant(k: String)))))), mg), _), List(v@Literal(Constant(_: String))))) =>
        (k, v)
      case x =>
        fail(s"""Expected 'k -> "v", got: $x\n${showRaw(x)}""")
    }

  final def readMacroArg_tToLitFn[T, V: scala.reflect.Manifest](e: c.Expr[T => V]): List[(Either[Select, Type], Literal)] =
    readMacroArg_tToTree(e).map(x => (x._1, x._2 match {
      case lit @ Literal(Constant(_: V)) => lit
      case x => fail(s"Expecting a literal value, got: ${showRaw(x)}")
    }))

  final def readMacroArg_tToTree[T, V](e: c.Expr[T => V]): List[(Either[Select, Type], Tree)] =
    e match {
      case Expr(Function(_, Match(_, caseDefs))) =>
        caseDefs map {

          // case _: Class => "k"
          case CaseDef(Typed(_, t: TypeTree), _, tree) =>
            (Right(t.tpe), tree)

          // case Object => "k"
          case CaseDef(s@ Select(_, _), _, tree) =>
            (Left(s), tree)

          case x =>
            fail(s"Expecting a case like: {case _: Type => ?}\n    Got: ${showRaw(x)}")
        }
      case _ =>
        fail(s"Expecting a function like: {case _: Type => ?}\n    Got: ${showRaw(e)}")
    }

  /**
   * Create code for a function that will call .apply() on a given type's type companion object.
   */
  final def tcApplyFn(t: Type): Select = {
    val sym = t.typeSymbol
    val tc  = sym.companion
    if (tc == NoSymbol)
      fail(s"Companion object not found for $sym")
    val pre = t match {
      case TypeRef(p, _, _) => p
      case x                => fail(s"Don't know how to extract `pre` from ${showRaw(x)}")
    }

    pre match {
      // Path dependent, eg. `t.Literal`
      case SingleType(NoPrefix, path) =>
        Select(Ident(path), tc.asTerm.name)

      // Assume type companion .apply exists
      case _ =>
        Select(Ident(tc), TermName("apply"))
    }
  }

  final def selectFQN(s: String, lastIsType: Boolean): RefTree = {
    val terms = s.split('.').map(TermName(_): Name)
    val l = terms.length - 1
    // Bad hack
    if (lastIsType)
      terms(l) = terms(l).toTypeName
    val h = Ident(terms.head): RefTree
    if (l == 0)
      h
    else
      terms.tail.foldLeft(h)(Select(_, _))
  }

  final def toSelectFQN(t: TypeSymbol): RefTree = {
    // Do this properly later
    selectFQN(t.fullName, !t.isModuleClass)
  }

  /**
   * Sometimes using a type directly in a clause like "case _: $t => ...", causes spurious exhaustiveness warnings.
   * I definitively know why, problably something about compiler-phase order.
   * This fixes it consistently so far.
   */
  final def fixAdtTypeForCaseDef(t: Type): Tree = {
    if (t.typeSymbol.isModuleClass)
      TypeTree(t)
    else {
      // Take the FQN and re-evaluate. Why? I don't know.
      // But without this there'll be spurious exhaustiveness warnings
      val fqn = toSelectFQN(t.typeSymbol.asType)
      if (t.typeArgs.isEmpty)
        fqn
      else
        AppliedTypeTree(fqn, t.typeArgs.map(TypeTree(_)))
    }
  }

  final def tryInferImplicit(t: Type): Option[Tree] =
    c.inferImplicitValue(t, silent = true) match {
      case EmptyTree => None
      case i         => Some(i)
    }

  final def needInferImplicit(t: Type): Tree =
    tryInferImplicit(t) getOrElse fail(s"Implicit not found: $t")

  implicit val liftInit = Liftable[Init](i => q"..${i.stmts}")
  class Init {
    var seen = Map.empty[String, TermName]
    var stmts: Vector[Tree] = Vector.empty

    def +=(t: Tree): Unit =
      stmts :+= t

    def valImp(tot: TypeOrTree): TermName = tot match {
      case GotType(t) => valDef(needInferImplicit(t))
      case GotTree(t) => valDef(q"implicitly[$t]")
    }

    def valDef(value: Tree): TermName = {
      val k = value.toString()
      seen.get(k) match {
        case None =>
          val v = TermName(c.freshName())
          this += q"val $v = $value"
          seen = seen.updated(k, v)
          v
        case Some(v) => v
      }
    }

    def wrap(body: Tree): Tree =
      q"..$this; $body"
  }

  def Init(stmts: Tree*): Init = {
    val i = new Init
    stmts foreach (i += _)
    i
  }

  def LitNil = Ident(c.mirror staticModule "scala.collection.immutable.Nil")

  def identityExpr[T: c.WeakTypeTag]: c.Expr[T => T] = {
    val T = weakTypeOf[T]
    c.Expr[T => T](q"(t: $T) => t")
  }

  def deterministicOrderT(ts: TraversableOnce[Type]): Vector[Type] =
    ts.toVector.sortBy(_.typeSymbol.fullName)

  def deterministicOrderC(ts: TraversableOnce[ClassSymbol]): Vector[ClassSymbol] =
    ts.toVector.sortBy(_.fullName)


  final def replaceMacroMethod(newMethod: String) =
    c.macroApplication match {
      case TypeApply(Select(r, _), _) => Select(r, TermName(newMethod))
      case Select(r, _)               => Select(r, TermName(newMethod))
      case x => fail(s"Don't know how to parse macroApplication: ${showRaw(x)}")
    }

  final def excludeNamedParams(exclusions: Seq[String], data: List[(TermName, Type)]): List[(TermName, Type)] =
    if (exclusions.isEmpty)
      data
    else {
      var blacklist = Set.empty[String]
      var bsize = 0
      for (s <- exclusions) {
        if (blacklist contains s)
          fail(s"Duplicate found: $s")
        blacklist += s
        bsize += 1
      }

      def name(x: (TermName, Type)): String =
        x._1.decodedName.toString

      val b = List.newBuilder[(TermName, Type)]
      var excluded = 0
      for (x <- data)
        if (blacklist contains name(x))
          excluded += 1
        else
          b += x

      if (bsize != excluded) {
        val x = blacklist -- data.map(name)
        fail(s"Not found: ${x mkString ", "}")
      }

      b.result()
    }

  final def primaryConstructorParamsExcluding(t: Type, exclusions: Seq[c.Expr[scala.Symbol]]): List[(TermName, Type)] =
    excludeNamedParams(
      exclusions.map(readMacroArg_symbol),
      primaryConstructorParams(t).map(nameAndType(t, _)))

  def showUnorderedTypes(ts: Set[Type]): String =
    ts.toList.map(_.toString).sorted.mkString(", ")

}
