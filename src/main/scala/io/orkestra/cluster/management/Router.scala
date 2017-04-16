package io.orkestra.cluster.management

import scala.collection.immutable.Queue
import akka.actor._
import akka.cluster.Cluster

class RouterRR(memberId: String, cluster: Cluster)
    extends Actor
    with ActorLogging {

  import RouterRR._

  var members: Queue[ActorRef] = Queue()

  var quarantineMembers: List[ActorRef] = List.empty[ActorRef]

  def receive = {

    case GetRoutee(role) =>
      sender ! Routee(getMember)

    case RegisterRoutee(path) =>
      if (isQuarantine(path))
        recoverMember(path)
      else
        probeRoutee(path)

    case RemoveRoutee(path) =>
      removeMember(path)

    case QuarantineRoutee(path) =>
      quarantineMember(path)

    case RecoverRoutee(path) =>
      recoverMember(path)

    case CleanQuarantine(path) =>
      quarantineCleaner(path)

    case ActorIdentity(`memberId`, Some(routeeRef)) =>
      registerMember(routeeRef)

    case ActorIdentity(`memberId`, None) =>
      log.warning(s"member with id $memberId not found")

    case Terminated(memberRef) =>
      log.info(s"Member ${memberRef.path} was Terminated")
      removeMember(memberRef.path)
      SupervisorStrategy

  }

  def probeRoutee(path: ActorPath) = {
    context.actorSelection(path) ! Identify(memberId)
  }

  def registerMember(memberRef: ActorRef) = {
    context.watch(memberRef)
    members = memberRef +: members
  }

  def removeMember(path: ActorPath) = {
    log.info(s"removing member: $path from router and downing it")
    cluster.down(path.address)
    members = members.filter(_.path != path)
  }

  def getMember: Option[ActorRef] =
    if (members.size != 0) {
      val (member, memberstmp) = members.dequeue
      members = memberstmp.enqueue(member)
      Some(member)
    } else
      None

  def quarantineMember(path: ActorPath) = {
    val (unhealthy, healthy): (Queue[ActorRef], Queue[ActorRef]) = members.partition(_.path == path)
    members = healthy
    quarantineMembers ++= unhealthy
  }

  def recoverMember(path: ActorPath) = {
    val (healthy, unhealthy): (List[ActorRef], List[ActorRef]) = quarantineMembers.partition(_.path == path)
    members ++= healthy
    quarantineMembers = unhealthy
  }

  def isQuarantine(path: ActorPath) =
    quarantineMembers.filter(_.path == path).nonEmpty

  def quarantineCleaner(path: ActorPath) = {
    log.debug(s"Quarantine is being cleaned of $path...")
    quarantineMembers.filter(_.path == path).map { m =>
      log.warning(s"Removing quarantined member ${m.path.address}")
      cluster.down(m.path.address)
    }
  }
}

object RouterRR {
  case class RegisterRoutee(x: ActorPath)
  case class RemoveRoutee(x: ActorPath)
  case class QuarantineRoutee(x: ActorPath)
  case class RecoverRoutee(x: ActorPath)
  case class GetRoutee(role: String)
  case class Routee(ref: Option[ActorRef])
  case class CleanQuarantine(path: ActorPath)
}
