package io.orkestra.cluster.routing

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.{MemberStatus, Member, Cluster}
import com.typesafe.config.ConfigFactory
import io.orkestra.cluster.protocol.Response.Failure.RouterNotFound
import io.orkestra.cluster.protocol.Response.Success.{Routers, Router}
import play.api.libs.json.{Json, Format}
import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.pattern.ask
import io.orkestra.cluster.protocol.{Register, RegisterInternal}

import io.orkestra.rorschach.Rorschach

class ClusterListener(serviceName: String) extends Actor with ActorLogging {
  import RouterRR._
  import ClusterListener._

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val cluster = Cluster(system)

  val config = ConfigFactory.load
  val downingTime = config.getInt("clustering.unreachable-down-after")

  // downing tasks for unreachable nodes
  private var downingSchedules: Map[Member, Cancellable] = Map.empty[Member, Cancellable]

  var routers = Map[String, ActorRef]()
  var internalActors = Map[String, ActorRef]()

  val rorschach = Rorschach(self, cluster)

  def roleToId(role: String) = role + "-handler"

  override def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, classOf[ClusterDomainEvent])
  }

  override def postStop(): Unit = {
    super.postStop()
    downingSchedules.foreach(_._2.cancel)
    cluster.unsubscribe(self)
  }

  def receive = clusterPassiveHandler orElse internalActiveHandler orElse subActorHandler orElse routersManagement

  /**
   * responsible for registering the internal handler actor in the router
   */
  def subActorHandler: Receive = {
    case RegisterInternal(internalRef, role) =>
      log.debug(s"Registered Internal Actor with $role, $internalRef")
      internalActors = internalActors + (role -> internalRef)
  }

  /**
   * responsible for getting routee from a specific router
   */
  def internalActiveHandler: Receive = {
    case msg @ GetRoutee(role) if routers.contains(role) =>
      log.info(s"Forwarding to routee ${routers(role)} of role $role request")
      routers(role) forward msg

    case msg @ GetRoutee(role) =>
      log.info(s"Empty router of role $role, responding with empty routee")
      sender ! Routee(None)
  }

  /**
   * responsible for listening to cluster events
   */
  def clusterPassiveHandler: Receive = {

    case MemberJoined(member) =>
      log.info(s"Joining Member $member")

    case MemberUp(member) =>
      log.info(s"$member is up. registering it and sending a register probe")
      registerMember(member)
      registerToMember(member)

    //TODO schedule shut down of the current node(self) in case it's not in the majority part of the cluster
    case UnreachableMember(member) =>
      log.info(s"[Unreachable] $member")
      rorschach.reportUnreachable(member)
      val state = cluster.state
      if (isMajority(state.members.size, state.unreachable.size)) {
        if (member.status == MemberStatus.up ||
          member.status == MemberStatus.joining ||
          member.status == MemberStatus.weaklyUp)
          scheduletakeDown(member)
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

  /**
   * responsible for managing the routers through the http layer
   */
  def routersManagement: Receive = {
    case GetRouters =>
      log.debug(s"getting routers")
      val requester = sender()
      val res = routers.map { r =>
        (r._2 ? GetRoutees)(3.seconds).map {
          case routees: List[ActorRef] =>
            Json.toJson(Router(r._1, routees.map(_.path.toString)))
        }
      }
      Future.sequence(res).map { x =>
        requester ! Routers(x)
      }
    case GetRouter(role) =>
      log.debug(s"getting router $role")
      val requester = sender()
      if (routers.contains(role)) {
        (routers(role) ? GetRoutees)(3.seconds).map {
          case routees: List[ActorRef] =>
            requester ! Router(role, routees.map(_.path.toString))
        }
      } else {
        sender ! RouterNotFound(role)
      }

    case msg: DeleteRoutee =>
      val role = msg.role
      if (routers.contains(role)) {
        routers(role) forward msg
      } else {
        sender ! RouterNotFound(role)
      }
  }

  /**
   * send a Register message to the newly discovered member
   * @param member of type akka cluster member
   */
  private def registerToMember(member: Member): Unit =
    if (member.roles != cluster.selfRoles) {
      val path = RootActorPath(member.address) / "user" / "cluster-socket"
      internalActors foreach { (pair: (String, ActorRef)) =>
        log.info(s"Registering ${pair._1} on ${pair._1} to dependent actor $path")
        context.actorSelection(path) ! Register(pair._2, pair._1)
      }
    }

  /**
   * register the newly discovered member in the internal router
   * @param member of type akka cluster member
   */
  private def registerMember(member: Member): Unit =
    member.roles.foreach { role =>
      if (!routers.contains(role)) addRouter(role)
      log.info(s"Registering $member to router with role $role")
      val path = RootActorPath(member.address) / "user" / roleToId(role)
      routers(role) ! RegisterRoutee(path)
    }

  /**
   * remove this member from the internal router
   * @param member member of type akka cluster member
   */
  private def removeMember(member: Member): Unit =
    member.roles.foreach { role =>
      if (routers.contains(role)) {
        log.info(s"Removing $member from router with role $role")
        val path = RootActorPath(member.address) / "user" / roleToId(role)
        routers(role) ! RemoveRoutee(path)
      }
    }

  /**
   * a router is creatd for each role of the member so if a memebr has more than one role,
   * a router will be created for each role
   * @param role the role of the new member
   */
  private def addRouter(role: String): Unit = {
    val router = system.actorOf(Props(new RouterRR(roleToId(role), cluster)), (role + "-router"))
    context.watch(router)
    log.info(s"Adding router with role $role")
    routers = routers + (role -> router)
  }

  /**
   * recover a member from quarantine and cancel its downing schedule
   * @param member member of type akka cluster member
   */
  private def recoverMember(member: Member): Unit =
    member.roles.foreach { role =>
      if (routers.contains(role)) {
        log.info(s"recovering member ${member} from router with role: ${role}")
        val path = RootActorPath(member.address) / "user" / roleToId(role)
        routers(role) ! RecoverRoutee(path)
      }
      if (downingSchedules.contains(member)) {
        log.info(s"cancelling downing schedule for member ${member}")
        downingSchedules(member).cancel
        downingSchedules = downingSchedules - member
      }
    }

  private def majority(n: Int): Int = (n + 1) / 2 + (n + 1) % 2

  private def isMajority(total: Int, dead: Int): Boolean = {
    require(total > 0)
    require(dead >= 0)
    (total - dead) >= majority(total)
  }

  /**
   * quarantine an unreachable member and schedule its downing after a specified configurable time
   * this only happens if the current node is in the majority part of the network partition based on the last known membership information
   * this won't happen if the unreachable node is the seed node(lighthouse role)
   * @param member member of type akka cluster member
   */
  private def scheduletakeDown(member: Member) = {
    member.roles.foreach { role =>
      if (routers.contains(role)) {
        log.info(s"Quaranting member ${member} from router with role: ${role}")
        val path = RootActorPath(member.address) / "user" / roleToId(role)
        routers(role) ! QuarantineRoutee(path)
        if (!member.roles.contains("lighthouse")) {
          log.info(s"scheduling take down of unreachable member: $member in $downingTime seconds")
          downingSchedules = downingSchedules + (member -> context.system.scheduler.scheduleOnce(downingTime.seconds, self, DownManual(member)))
        }
      }
    }
  }
}

object ClusterListener {

  case class DownManual(member: Member)

  sealed trait ManagementReguest
  case object GetRouters extends ManagementReguest
  case class GetRouter(role: String) extends ManagementReguest
  case class DeleteRoutee(role: String, path: String) extends ManagementReguest

  def props(serviceName: String) =
    Props(new ClusterListener(serviceName))
}
