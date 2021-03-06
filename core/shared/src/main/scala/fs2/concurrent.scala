package fs2

import cats.{ Eq, Traverse }
import cats.implicits.{ catsSyntaxEither => _, _ }
import cats.effect.{ Effect, IO }

import fs2.internal.{Actor,LinkedMap}
import fs2.util.Attempt
import fs2.util.ExecutionContexts._
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}

import scala.concurrent.ExecutionContext

/** Provides concurrency support for effect types. */
object concurrent {

  private final class MsgId
  private sealed abstract class Msg[A]
  private object Msg {
    final case class Read[A](cb: Attempt[(A, Long)] => Unit, id: MsgId) extends Msg[A]
    final case class Nevermind[A](id: MsgId, cb: Attempt[Boolean] => Unit) extends Msg[A]
    final case class Set[A](r: Attempt[A], cb: () => Unit) extends Msg[A]
    final case class TrySet[A](id: Long, r: Attempt[A], cb: Attempt[Boolean] => Unit) extends Msg[A]
  }

  /**
   * The result of a `Ref` modification. `previous` is the value before modification
   * (the value passed to modify function, `f` in the call to `modify(f)`. `now`
   * is the new value computed by `f`.
   */
  final case class Change[+A](previous: A, now: A) {
    def modified[AA >: A](implicit eq: Eq[AA]): Boolean = eq.neqv(previous, now)
    def map[B](f: A => B): Change[B] = Change(f(previous), f(now))
  }

  /** An asynchronous, concurrent mutable reference. */
  final class Ref[F[_],A](implicit F: Effect[F], ec: ExecutionContext) { self =>

    private var result: Attempt[A] = null
    // any waiting calls to `access` before first `set`
    private var waiting: LinkedMap[MsgId, Attempt[(A, Long)] => Unit] = LinkedMap.empty
    // id which increases with each `set` or successful `modify`
    private var nonce: Long = 0

    private val actor: Actor[Msg[A]] = Actor.actor[Msg[A]] {
      case Msg.Read(cb, idf) =>
        if (result eq null) {
          waiting = waiting.updated(idf, cb)
        } else {
          val r = result
          val id = nonce
          ec.executeThunk { cb((r: Either[Throwable, A]).map((_,id))) }
        }

      case Msg.Set(r, cb) =>
        nonce += 1L
        if (result eq null) {
          val id = nonce
          waiting.values.foreach { cb =>
            ec.executeThunk { cb((r: Either[Throwable, A]).map((_,id))) }
          }
          waiting = LinkedMap.empty
        }
        result = r
        cb()

      case Msg.TrySet(id, r, cb) =>
        if (id == nonce) {
          nonce += 1L; val id2 = nonce
          waiting.values.foreach { cb =>
            ec.executeThunk { cb((r: Either[Throwable, A]).map((_,id2))) }
          }
          waiting = LinkedMap.empty
          result = r
          cb(Right(true))
        }
        else cb(Right(false))

      case Msg.Nevermind(id, cb) =>
        val interrupted = waiting.get(id).isDefined
        waiting = waiting - id
        ec.executeThunk { cb(Right(interrupted)) }
    }

    /**
     * Obtains a snapshot of the current value of the `Ref`, and a setter
     * for updating the value. The setter may noop (in which case `false`
     * is returned) if another concurrent call to `access` uses its
     * setter first. Once it has noop'd or been used once, a setter
     * never succeeds again.
     */
    def access: F[(A, Attempt[A] => F[Boolean])] =
      F.flatMap(F.delay(new MsgId)) { mid =>
        F.map(getStamped(mid)) { case (a, id) =>
          val set = (a: Attempt[A]) =>
            F.async[Boolean] { cb => actor ! Msg.TrySet(id, a, cb) }
          (a, set)
        }
      }

    /** Obtains the value of the `Ref`, or wait until it has been `set`. */
    def get: F[A] = F.flatMap(F.delay(new MsgId)) { mid => F.map(getStamped(mid))(_._1) }

    /** Like `get`, but returns an `F[Unit]` that can be used cancel the subscription. */
    def cancellableGet: F[(F[A], F[Unit])] = F.delay {
      val id = new MsgId
      val get = F.map(getStamped(id))(_._1)
      val cancel = F.async[Unit] {
        cb => actor ! Msg.Nevermind(id, r => cb((r: Either[Throwable, Boolean]).map(_ => ())))
      }
      (get, cancel)
    }

    /**
     * Tries modifying the reference once, returning `None` if another
     * concurrent `set` or `modify` completes between the time
     * the variable is read and the time it is set.
     */
    def tryModify(f: A => A): F[Option[Change[A]]] =
      access.flatMap { case (previous,set) =>
        val now = f(previous)
        set(Right(now)).map { b =>
          if (b) Some(Change(previous, now))
          else None
        }
      }

    /** Like `tryModify` but allows to return `B` along with change. **/
    def tryModify2[B](f: A => (A,B)): F[Option[(Change[A], B)]] =
      access.flatMap { case (previous,set) =>
        val (now,b0) = f(previous)
        set(Right(now)).map { b =>
          if (b) Some(Change(previous, now) -> b0)
          else None
        }
    }

    /** Repeatedly invokes `[[tryModify]](f)` until it succeeds. */
    def modify(f: A => A): F[Change[A]] =
      tryModify(f).flatMap {
        case None => modify(f)
        case Some(change) => F.pure(change)
      }

    /** Like modify, but allows to extra `b` in single step. **/
    def modify2[B](f: A => (A,B)): F[(Change[A], B)] =
      tryModify2(f).flatMap {
        case None => modify2(f)
        case Some(changeAndB) => F.pure(changeAndB)
      }

    /**
     * *Asynchronously* sets a reference. After the returned `F[Unit]` is bound,
     * the task is running in the background. Multiple tasks may be added to a
     * `Ref[A]`.
     *
     * Satisfies: `r.setAsync(fa) flatMap { _ => r.get } == fa`
     */
    def setAsync(fa: F[A]): F[Unit] =
      F.liftIO(F.runAsync(F.shift(fa)(ec)) { r => IO(actor ! Msg.Set(r, () => ())) })

    /**
     * *Asynchronously* sets a reference to a pure value.
     *
     * Satisfies: `r.setAsyncPure(a) flatMap { _ => r.get(a) } == pure(a)`
     */
    def setAsyncPure(a: A): F[Unit] = setAsync(F.pure(a))

    /**
     * *Synchronously* sets a reference. The returned value completes evaluating after the reference has been successfully set.
     */
    def setSync(fa: F[A]): F[Unit] =
      F.liftIO(F.runAsync(F.shift(fa)(ec)) { r => IO.async { cb => actor ! Msg.Set(r, () => cb(Right(()))) } })

    /**
     * *Synchronously* sets a reference to a pure value.
     */
    def setSyncPure(a: A): F[Unit] = setSync(F.pure(a))

    /**
     * Runs `f1` and `f2` simultaneously, but only the winner gets to
     * `set` to this `ref`. The loser continues running but its reference
     * to this ref is severed, allowing this ref to be garbage collected
     * if it is no longer referenced by anyone other than the loser.
     */
    def race(f1: F[A], f2: F[A]): F[Unit] = F.delay {
      val ref = new AtomicReference(actor)
      val won = new AtomicBoolean(false)
      val win = (res: Attempt[A]) => {
        // important for GC: we don't reference this ref
        // or the actor directly, and the winner destroys any
        // references behind it!
        if (won.compareAndSet(false, true)) {
          val actor = ref.get
          ref.set(null)
          actor ! Msg.Set(res, () => ())
        }
      }
      unsafeRunAsync(f1)(res => IO(win(res)))
      unsafeRunAsync(f2)(res => IO(win(res)))
    }

    private def getStamped(msg: MsgId): F[(A,Long)] =
      F.async[(A,Long)] { cb => actor ! Msg.Read(cb, msg) }
  }

  /** Creates an asynchronous, concurrent mutable reference. */
  def ref[F[_], A](implicit F: Effect[F], ec: ExecutionContext): F[Ref[F,A]] =
    F.delay(new Ref[F, A])

  /** Creates an asynchronous, concurrent mutable reference, initialized to `a`. */
  def refOf[F[_]: Effect, A](a: A)(implicit ec: ExecutionContext): F[Ref[F,A]] = ref[F, A].flatMap(r => r.setAsyncPure(a).as(r))

  /** Like `traverse` but each `G[B]` computed from an `A` is evaluated in parallel. */
  def parallelTraverse[F[_], G[_], A, B](fa: F[A])(f: A => G[B])(implicit F: Traverse[F], G: Effect[G], ec: ExecutionContext): G[F[B]] =
    F.traverse(fa)(f andThen start[G, B]).flatMap(G.sequence(_))

  /** Like `sequence` but each `G[A]` is evaluated in parallel. */
  def parallelSequence[F[_], G[_], A](fga: F[G[A]])(implicit F: Traverse[F], G: Effect[G], ec: ExecutionContext): G[F[A]] =
    parallelTraverse(fga)(identity)

  /**
   * Begins asynchronous evaluation of `f` when the returned `F[F[A]]` is
   * bound. The inner `F[A]` will block until the result is available.
   */
  def start[F[_]: Effect, A](f: F[A])(implicit ec: ExecutionContext): F[F[A]] =
    ref[F, A].flatMap { ref => ref.setAsync(f).as(ref.get) }

  /**
    * Returns an effect that, when run, races evaluation of `fa` and `fb`,
    * and returns the result of whichever completes first. The losing effect
    * continues to execute in the background though its result will be sent
    * nowhere.
   */
  def race[F[_]: Effect, A, B](fa: F[A], fb: F[B])(implicit ec: ExecutionContext): F[Either[A, B]] =
    ref[F, Either[A,B]].flatMap { ref =>
      ref.race(fa.map(Left.apply), fb.map(Right.apply)) >> ref.get
    }

  /**
   * Like `unsafeRunSync` but execution is shifted to the supplied execution context.
   * This method returns immediately after submitting execution to the execution context.
   */
  def unsafeRunAsync[F[_], A](fa: F[A])(f: Either[Throwable, A] => IO[Unit])(implicit F: Effect[F], ec: ExecutionContext): Unit =
    F.runAsync(F.shift(fa)(ec))(f).unsafeRunSync

  /** Deprecated alias for [[Stream.join]]. */
  @deprecated("Use Stream.join instead", "1.0")
  def join[F[_],O](maxOpen: Int)(outer: Stream[F,Stream[F,O]])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
    Stream.join(maxOpen)(outer)
}
