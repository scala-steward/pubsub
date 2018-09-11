package com.evolutiongaming.cluster.pubsub

import akka.actor.ActorSystem
import com.evolutiongaming.cluster.pubsub.PubSub.{OnMsg, Unsubscribe}
import com.evolutiongaming.concurrent.AvailableProcessors
import com.evolutiongaming.concurrent.sequentially.{MapDirective, SequentialMap, Sequentially}
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.safeakka.actor.ActorLog

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

trait OptimiseSubscribe {
  def apply[T: Topic](onMsg: OnMsg[T])(subscribe: OnMsg[T] => Unsubscribe): Unsubscribe
}

object OptimiseSubscribe {

  val Empty: OptimiseSubscribe = new OptimiseSubscribe {
    def apply[T: Topic](onMsg: OnMsg[T])(subscribe: OnMsg[T] => Unsubscribe) = subscribe(onMsg)
  }

  type Listener = OnMsg[Any]

  def apply(system: ActorSystem): OptimiseSubscribe = {
    val sequentially = Sequentially(system, None, AvailableProcessors())
    val log = ActorLog(system, classOf[OptimiseSubscribe])
    apply(sequentially, log)(system.dispatcher)
  }

  def apply(sequentially: Sequentially[String], log: ActorLog)(implicit ec: ExecutionContext): OptimiseSubscribe = {
    val map = SequentialMap[String, Subscription](sequentially)
    apply(map, log)
  }

  def apply(map: SequentialMap[String, Subscription], log: ActorLog)
    (implicit ec: ExecutionContext): OptimiseSubscribe = new OptimiseSubscribe {

    def apply[T](onMsg: OnMsg[T])(subscribe: OnMsg[T] => Unsubscribe)(implicit topic: Topic[T]): Unsubscribe = {
      val listener = onMsg.asInstanceOf[Listener]

      val result = map.updateUnit(topic.name) { subscription =>
        val updated = subscription match {
          case Some(subscription) => subscription + listener
          case None               =>
            val unsubscribe = subscribe { (msg: T, sender) =>
              for {
                subscription <- map.getNow(topic.name)
                listener <- subscription.listeners
              } try {
                listener(msg, sender)
              } catch {
                case NonFatal(failure) => log.error(s"onMsg failed for $topic: $failure", failure)
              }
            }
            Subscription(unsubscribe, listener :: Nel)
        }
        MapDirective.update(updated)
      }

      result.failed.foreach { failure =>
        log.error(s"failed to subscribe to $topic, $failure", failure)
      }

      () => {
        val unsubscribe = for {
          _ <- result
          _ <- map.updateUnit(topic.name) {
            case None               => MapDirective.ignore
            case Some(subscription) => subscription - listener match {
              case Some(subscription) => MapDirective.update(subscription)
              case None               =>
                subscription.unsubscribe()
                MapDirective.remove
            }
          }
        } yield {}

        unsubscribe.failed.foreach { failure =>
          log.error(s"failed to unsubscribe from $topic, $failure", failure)
        }
      }
    }
  }


  final case class Subscription(unsubscribe: Unsubscribe, listeners: Nel[Listener]) { self =>

    def +(listener: Listener): Subscription = copy(listeners = listener :: listeners)

    def -(listener: Listener): Option[Subscription] = {
      val listeners = self.listeners.filter(_ != listener)
      Nel.opt(listeners).map(listeners => copy(listeners = listeners))
    }
  }
}