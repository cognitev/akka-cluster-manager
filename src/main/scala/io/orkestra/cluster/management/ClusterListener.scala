package io.orkestra.cluster.management

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.{MemberStatus, Member, Cluster}
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConversions._

import io.orkestra.cluster.protocol.{Register, RegisterInternal}

import io.orkestra.rorschach.Rorschach

class ClusterListener(serviceName: String) extends Actor with ActorLogging {
  import RouterRR._

  implicit val system = context.system
  val cluster = Cluster(system)

  val config = ConfigFactory.load
  val dependencies = config.getStringList(s"$serviceName.dependencies").toSet
  val sharedRoles = config.getStringList(s"$serviceName.sharedRoles").toSet

  var routers = Map[String, ActorRef]()
  var internalActors = Map[String, ActorRef]()

  val rorschach = Rorschach(self, cluster)

  def roleToId(role: String) = role + "-backend"

  def isDependency(member: Member) =
    member.roles.intersect(dependencies).nonEmpty

  def isDependent(member: Member) =
    member.roles.intersect(sharedRoles).nonEmpty

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
      if (isWatchdog)
        cleanCluster(member)

    case MemberUp(member) =>
      if (isDependency(member)) {
        log.info(s"Registering dependency $member")
        registerMember(member)
      }
      if (isDependent(member)) {
        log.info(s"Found dependent service $member, sending a register probe")
        registerToDependent(member)
      }

    case UnreachableMember(member) if isDependency(member) =>
      log.info(s"Removing [Unreachable] $member")
      rorschach.reportUnreachable(member)
      quarantineMember(member)

    case ReachableMember(member) if isDependency(member) =>
      log.info(s"Reachable member: $member")
      rorschach.reportReachable(member)
      recoverMember(member)

    case MemberRemoved(member, MemberStatus.Up) if isDependency(member) =>
      log.info(s"Removing [Removed] $member")
      rorschach.reportDown(member)
      removeMember(member)

    case Register(memberRef, role) if dependencies.contains(role) =>
      if (!routers.contains(role))
        addRouter(role)
      log.info(s"Registering member ${memberRef.path}")
      routers(role) ! RegisterRoutee(memberRef.path)

    case Terminated(routerRef) =>
      log.warning(s"Removing router $routerRef")
      routers = routers.filter((entry: (String, ActorRef)) => entry._2 != routerRef)
      SupervisorStrategy

    case (event: ClusterDomainEvent) =>
      log.debug(s"Cluster Wide Event: $event")

  }
  def receive = clusterPassiveHandler orElse internalActiveHandler orElse subActorHandler

  def registerToDependent(member: Member): Unit =
    if (member.roles != cluster.selfRoles) {
      val path = RootActorPath(member.address) / "user" / "cluster-socket"
      internalActors map { (pair: (String, ActorRef)) =>
        log.info(s"Registering ${pair._1} on ${pair._1} to dependent actor $path")
        context.actorSelection(path) ! Register(pair._2, pair._1)
      }
    }

  def registerMember(member: Member): Unit =
    member.roles.intersect(dependencies).map { role =>
      if (!routers.contains(role))
        addRouter(role)
      log.info(s"Registering $member to router with role $role")
      val path = RootActorPath(member.address) / "user" / roleToId(role)
      routers(role) ! RegisterRoutee(path)
    }

  def removeMember(member: Member): Unit =
    member.roles.intersect(dependencies).map { role =>
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

  def quarantineMember(member: Member): Unit =
    member.roles.intersect(dependencies).map { role =>
      if (routers.contains(role)) {
        log.info(s"Quaranting member ${member} from router with role: ${role}")
        val path = RootActorPath(member.address) / "user" / roleToId(role)
        routers(role) ! QuarantineRoutee(path)
      }
    }

  def recoverMember(member: Member): Unit =
    member.roles.intersect(dependencies).map { role =>
      if (routers.contains(role)) {
        log.info(s"Quaranting member ${member} from router with role: ${role}")
        val path = RootActorPath(member.address) / "user" / roleToId(role)
        routers(role) ! RecoverRoutee(path)
      }
    }

  def cleanCluster(member: Member) = {
    log.debug(s"Cleaning cluster $routers")
    routers.map { kv =>
      log.debug(s"Cleaning Router $kv")
      member.roles.intersect(dependencies).map { role =>
        // For each role
        // only the valid role will not be downed
        val path = RootActorPath(member.address) / "user" / roleToId(role)
        kv._2 ! CleanQuarantine(path)
      }
    }
  }

  def isWatchdog: Boolean =
    cluster.selfRoles.map(_.toLowerCase).contains("watchdog")

}

object ClusterListener {

  def props(serviceName: String) =
    Props(new ClusterListener(serviceName))
}

