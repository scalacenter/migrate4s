package scalafix

import scala.collection.IterableLike
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.meta._
import scala.meta.semantic.v1.Completed
import scala.meta.semantic.v1.Signature
import scala.meta.semantic.v1.Symbol
import scala.meta.tokens.Token
import scala.util.Success
import scala.util.Try

import org.scalameta.logger

package object syntax {

  implicit class XtensionIterableLike[A, Repr](xs: IterableLike[A, Repr]) {
    def distinctBy[B, That](f: A => B)(
        implicit cbf: CanBuildFrom[Repr, A, That]): That = {
      val builder = cbf(xs.repr)
      val set = mutable.HashSet.empty[B]
      xs.foreach { o =>
        val b = f(o)
        if (!set(b)) {
          set += b
          builder += o
        }
      }
      builder.result
    }
  }

  implicit class XtensionTryToEither[A](e: Try[A]) {
    def asEither: Either[Throwable, A] = e match {
      case Success(x) => Right(x)
      case scala.util.Failure(x) => Left(x)
    }
  }

  implicit class XtensionEither[A, B](either: Either[A, B]) {
    def map[C](f: B => C): Either[A, C] = either.right.map(f)
    def flatMap[C >: A, D](f: B => Either[C, D]): Either[C, D] =
      either.right.flatMap(f)
  }

  implicit class XtensionEitherThrowable[A](either: Either[Throwable, A]) {
    def leftAsString: Either[String, A] = either.left.map(_.getMessage)
    def get: A = either match {
      case Right(b) => b
      case Left(e) => throw e
    }
  }

  implicit class XtensionEitherString[B](either: Either[String, B]) {
    def get: B = either match {
      case Right(b) => b
      case Left(e) => throw new Exception(e)
    }
  }

  implicit class XtensionTermRef(ref: Term.Ref) {
    def toTypeRef: Type.Ref = ref match {
      case Term.Name(name) => Type.Name(name)
      case Term.Select(qual: Term.Ref, Term.Name(name)) =>
        Type.Select(qual, Type.Name(name))
      case _ => ref.syntax.parse[Type].get.asInstanceOf[Type.Ref]
    }
  }

  implicit class XtensionToken(token: Token) {
    def posTuple: (Int, Int) = token.start -> token.end
  }

  implicit class XtensionCompleted[T](completed: Completed[T]) {
    // TODO(olafur) contribute these upstream
    def toOption: Option[T] = completed match {
      case Completed.Success(e) => Some(e)
      case _ => None
    }
    def toEither: Either[Exception, T] = completed match {
      case Completed.Success(e) => Right(e)
      case Completed.Error(e) => Left(e)
    }
    def toTry: Try[T] = Try(completed.get)
  }
  implicit class XtensionSymbol(symbol: Symbol) {

    /** Returns simplified version of this Symbol.
      *
      * - No Symbol.Multi
      * - No Signature.{Type,Method}
      */
    def normalized: Symbol = symbol match {
      case Symbol.Multi(sym +: _) => sym.normalized
      case Symbol.Global(sym, Signature.Type(name)) =>
        Symbol.Global(sym, Signature.Term(name))
      case Symbol.Global(Symbol.Global(sym, Signature.Term(name)),
                         Signature.Method("apply", _)) =>
        Symbol.Global(sym, Signature.Term(name))
      case Symbol.Global(sym, Signature.Method(name, _)) =>
        Symbol.Global(sym.normalized, Signature.Term(name))
      case x => x
    }

    /** Returns corresponding scala.meta.Tree for symbol
      * Caveat: not thoroughly tested, may crash
      * */
    def to[T <: Tree]: Try[T] = Try {
      val tree = symbol match {
        case Symbol.Global(Symbol.None, Signature.Term(name)) =>
          Term.Name(name)
        case Symbol.Global(sym, Signature.Type(name)) =>
          Type.Select(sym.to[Term.Ref].get, Type.Name(name))
        case Symbol.Global(sym, Signature.Term(name)) =>
          Term.Select(sym.to[Term].get, Term.Name(name))
      }
      tree.asInstanceOf[T] // should crash
    }
  }
  implicit class XtensionString(str: String) {
    def revealWhiteSpace: String = logger.revealWhitespace(str)
  }
}
