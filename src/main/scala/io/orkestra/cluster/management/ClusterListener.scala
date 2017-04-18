package io.orkestra.cluster.management

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.http.management.ClusterHttpManagement
import akka.cluster.{MemberStatus, Member, Cluster}
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConversions._
import scala.concurrent.duration._

import io.orkestra.cluster.protocol.{Register, RegisterInternal}

import io.orkestra.rorschach.Rorschach

class ClusterListener(serviceName: String) extends Actor with ActorLogging {
  import RouterRR._
  import ClusterListener._

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val cluster = Cluster(system)

  val httpClusterManagement = ClusterHttpManagement(cluster).start()

  val config = ConfigFactory.load
  val downingTime = config.getInt("clustering.unreachable-down-after")

  var routers = Map[String, ActorRef]()
  var internalActors = Map[String, ActorRef]()

  val rorschach = Rorschach(self, cluster)

  def roleToId(role: String) = role + "-backend"

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[ClusterDomainEvent])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  def subActorHandler: Receive = {
    case RegisterInternal(internalRef, role) =>
      log.debug(s"Registered Internal Actor with $role, $internalRef")
      internalActors = internalActors + (role -> internalRef)
  }

  def internalActiveHandler: Receive = {
    case msg @ GetRoutee(role) if routers.contains(role) =>
      log.info(s"Forwarding to routee ${routers(role)} of role $role request")
      routers(role) forward msg

    case msg @ GetRoutee(role) =>
      log.info(s"Empty router of role $role, responding with empty routee")
      sender ! Routee(None)
  }

  def clusterPassiveHandler: Receive = {

    case MemberJoined(member) =>
      log.info(s"Joining Member $member")

    case MemberUp(member) =>
      log.info(s"$member is up. registering it and sending a register probe")
      registerMember(member)
      registerToMember(member)

    case UnreachableMember(member) =>
      log.info(s"[Unreachable] $member")
      rorschach.reportUnreachable(member)
      val state = cluster.state
      if (isMajority(state.members.size, state.unreachable.size)) {
        if (member.status == MemberStatus.up) scheduletakeDown(member)
        else removeMember(member)
      }

    case ReachableMember(member) =>
      log.info(s"Reachable member: $member")
      rorschach.reportReachable(member)
      recoverMember(member)

    case MemberRemoved(member, MemberStatus.Up) =>
      log.info(s"Removing [Removed] $member")
      rorschach.reportDown(member)
      removeMember(member)

    case Register(memberRef, role) =>
      if (!routers.contains(role))
        addRouter(role)
      log.info(s"Registering member ${memberRef.path}")
      routers(role) ! RegisterRoutee(memberRef.path)

    case Terminated(routerRef) =>
      log.warning(s"Removing router $routerRef")
      routers = routers.filter((entry: (String, ActorRef)) => entry._2 != routerRef)
      SupervisorStrategy

    case DownManual(member) =>
      log.info(s"downing member: $member")
      member.roles.map { role =>
        val path = RootActorPath(member.address) / "user" / roleToId(role)
        routers(role) ! CleanQuarantine(path)
      }
  }
  def receive = clusterPassiveHandler orElse internalActiveHandler orElse subActorHandler

  def registerToMember(member: Member): Unit =
    if (member.roles != cluster.selfRoles) {
      val path = RootActorPath(member.address) / "user" / "cluster-socket"
      internalActors foreach { (pair: (String, ActorRef)) =>
        log.info(s"Registering ${pair._1} on ${pair._1} to dependent actor $path")
        context.actorSelection(path) ! Register(pair._2, pair._1)
      }
    }

  def registerMember(member: Member): Unit =
    member.roles.foreach { role =>
      if (!routers.contains(role)) addRouter(role)
      log.info(s"Registering $member to router with role $role")
      val path = RootActorPath(member.address) / "user" / roleToId(role)
      routers(role) ! RegisterRoutee(path)
    }

  def removeMember(member: Member): Unit =
    member.roles.foreach { role =>
      if (routers.contains(role)) {
        log.info(s"Removing $member from router with role $role")
        val path = RootActorPath(member.address) / "user" / roleToId(role)
        routers(role) ! RemoveRoutee(path)
      }
    }

  def addRouter(role: String): Unit = {
    val router = system.actorOf(Props(new RouterRR(roleToId(role), cluster)), (role + "-router"))
    context.watch(router)
    log.info(s"Adding router with role $role")
    routers = routers + (role -> router)
  }

  def recoverMember(member: Member): Unit =
    member.roles.foreach { role =>
      if (routers.contains(role)) {
        log.info(s"recovering member ${member} from router with role: ${role}")
        val path = RootActorPath(member.address) / "user" / roleToId(role)
        routers(role) ! RecoverRoutee(path)
      }
    }

  private def majority(n: Int): Int = (n + 1) / 2 + (n + 1) % 2

  private def isMajority(total: Int, dead: Int): Boolean = {
    require(total > 0)
    require(dead >= 0)
    (total - dead) >= majority(total)
  }

  private def scheduletakeDown(member: Member) = {
    member.roles.map { role =>
      if (routers.contains(role)) {
        log.info(s"Quaranting member ${member} from router with role: ${role}")
        val path = RootActorPath(member.address) / "user" / roleToId(role)
        routers(role) ! QuarantineRoutee(path)
        log.info(s"scheduling take down of unreachable member: $member in $downingTime seconds")
        context.system.scheduler.scheduleOnce(downingTime seconds, self, DownManual(member))
      }
    }
  }

}

object ClusterListener {

  case class DownManual(member: Member)

  def props(serviceName: String) =
    Props(new ClusterListener(serviceName))
}
